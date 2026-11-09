package com.anbar.wms.identity.service;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.validation.PasswordPolicy;
import com.anbar.wms.identity.dto.LoginRequest;
import com.anbar.wms.identity.dto.RegisterRequest;
import com.anbar.wms.identity.dto.TokenResponse;
import com.anbar.wms.identity.dto.UserResponse;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.identity.entity.Role;
import com.anbar.wms.identity.mapper.UserMapper;
import com.anbar.wms.identity.repository.AppUserRepository;
import com.anbar.wms.identity.repository.RoleRepository;
import com.anbar.wms.security.RoleCode;
import com.anbar.wms.security.config.WmsSecurityProperties;
import com.anbar.wms.security.jwt.AccessToken;
import com.anbar.wms.security.jwt.JwtService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login and the current-user view.
 *
 * <p>Login defends against the usual attacks:
 * <ul>
 *   <li>One answer ({@code INVALID_CREDENTIALS}) for "no such user" and "wrong password", and a dummy BCrypt
 *       comparison for unknown users so response times don't reveal which accounts exist.</li>
 *   <li>Brute force: after {@code maxFailedAttempts} wrong passwords the account locks for {@code duration};
 *       while locked, the password isn't even checked.</li>
 *   <li>Disabled accounts are only revealed to someone who knows the correct password.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final WmsSecurityProperties.Lockout lockout;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(AppUserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                       JwtService jwtService, UserMapper userMapper, WmsSecurityProperties properties, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.lockout = properties.lockout();
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String username = AppUser.normalizeUsername(request.username());
        String email = AppUser.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        if (users.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USERNAME_ALREADY_TAKEN);
        }

        AppUser user = new AppUser(username, email, passwordEncoder.encode(request.password()), request.fullName());
        user.replaceRoles(List.of(requireRole(RoleCode.USER)));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Lost a race with a concurrent registration for the same email or username.
            throw translateDuplicate(ex);
        }
        log.info("Registered user id={} username={}", user.getId(), user.getUsername());
        return userMapper.toResponse(user);
    }

    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public TokenResponse login(LoginRequest request) {
        Instant now = clock.instant();
        Optional<AppUser> found = findByUsernameOrEmail(request.usernameOrEmail());
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyPasswordHash); // equalize timing
            throw new AuthenticationFailedException(ErrorCode.INVALID_CREDENTIALS);
        }

        AppUser user = found.get();
        if (user.isLockedAt(now)) {
            throw new AuthenticationFailedException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!passwordMatches(request.password(), user.getPasswordHash())) {
            users.recordFailedLogin(user.getId(), lockout.maxFailedAttempts(), now.plus(lockout.duration()));
            log.info("Failed login for user id={}", user.getId());
            throw new AuthenticationFailedException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isActive()) {
            throw new AuthenticationFailedException(ErrorCode.ACCOUNT_DISABLED);
        }

        users.recordSuccessfulLogin(user.getId(), now);
        AccessToken token = jwtService.issue(user.getId(), user.getUsername(), user.roleCodes());
        return new TokenResponse(token.value(), TokenResponse.BEARER, jwtService.accessTokenTtlSeconds(), token.expiresAt());
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(long userId) {
        AppUser user = users.findWithRolesAndWarehousesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return userMapper.toResponse(user);
    }

    /** Changes the authenticated user's own password after re-checking the current one. */
    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!passwordMatches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "The current password is incorrect");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        log.info("User id={} changed their own password", userId);
    }

    private Optional<AppUser> findByUsernameOrEmail(String usernameOrEmail) {
        String value = usernameOrEmail.trim();
        return value.contains("@")
                ? users.findByEmail(AppUser.normalizeEmail(value))
                : users.findByUsername(AppUser.normalizeUsername(value));
    }

    private boolean passwordMatches(String rawPassword, String hash) {
        // BCrypt can't verify passwords over 72 bytes; no stored password can be that long anyway.
        return PasswordPolicy.fitsBcrypt(rawPassword) && passwordEncoder.matches(rawPassword, hash);
    }

    private Role requireRole(RoleCode code) {
        return roles.findByCode(code.name())
                .orElseThrow(() -> new IllegalStateException("Role " + code + " is missing; check migrations"));
    }

    private static RuntimeException translateDuplicate(DataIntegrityViolationException ex) {
        String message = String.valueOf(NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
        if (message.contains("uq_app_user_email")) {
            return new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        if (message.contains("uq_app_user_username")) {
            return new BusinessException(ErrorCode.USERNAME_ALREADY_TAKEN);
        }
        return ex;
    }
}
