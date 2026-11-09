package com.anbar.wms.identity.service;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import com.anbar.wms.identity.dto.UserResponse;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.identity.entity.Role;
import com.anbar.wms.identity.entity.UserStatus;
import com.anbar.wms.identity.mapper.UserMapper;
import com.anbar.wms.identity.repository.AppUserRepository;
import com.anbar.wms.identity.repository.RoleRepository;
import com.anbar.wms.security.RoleCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User administration for ADMINs. Authorization is enforced at the controller ({@code hasRole('ADMIN')}). */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("username", "username", "email", "email", "createdAt", "createdAt", "lastLoginAt", "lastLoginAt"),
            Sort.by("username"));

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserAdminService(AppUserRepository users, RoleRepository roles, UserMapper userMapper,
                            PasswordEncoder passwordEncoder) {
        this.users = users;
        this.roles = roles;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(Pageable pageable) {
        return PageResponse.from(users.findAll(SORT.apply(pageable)), userMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse get(long userId) {
        return userMapper.toResponse(require(userId));
    }

    /**
     * Enables or disables a user. Disabling takes effect immediately: the account can no longer log in and its
     * existing tokens are rejected on the next request. The last active administrator cannot be disabled, so the
     * system can always be administered.
     */
    @Transactional
    public UserResponse setActive(long userId, boolean active, String actorUsername) {
        // Serializes concurrent status/role changes so the last admin can't be disabled by two requests at once.
        roles.lockByCode(RoleCode.ADMIN.name()).orElseThrow();
        AppUser user = require(userId);
        if (!active && user.isActive() && user.hasRole(RoleCode.ADMIN)
                && users.countByRoleAndStatus(RoleCode.ADMIN.name(), UserStatus.ACTIVE) <= 1) {
            throw new BusinessException(ErrorCode.LAST_ADMIN_REQUIRED);
        }
        if (active) {
            user.enable();
        } else {
            user.disable();
        }
        log.info("User {} {} user id={}", actorUsername, active ? "enabled" : "disabled", userId);
        return userMapper.toResponse(user);
    }

    /** Clears a temporary lockout and the failed-login counter so the user can log in again. */
    @Transactional
    public UserResponse unlock(long userId, String actorUsername) {
        AppUser user = require(userId);
        user.unlock();
        log.info("User {} unlocked user id={}", actorUsername, userId);
        return userMapper.toResponse(user);
    }

    /** Sets a new password for a user and unlocks the account. The password is validated before it reaches here. */
    @Transactional
    public UserResponse resetPassword(long userId, String newPassword, String actorUsername) {
        AppUser user = require(userId);
        user.changePassword(passwordEncoder.encode(newPassword));
        user.unlock();
        log.info("User {} reset the password of user id={}", actorUsername, userId);
        return userMapper.toResponse(user);
    }

    private AppUser require(long userId) {
        return users.findWithRolesAndWarehousesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    /**
     * Replaces the user's roles. The last active administrator can't lose ADMIN, otherwise nobody could administer
     * the system. Changes take effect on the user's next request (tokens don't carry authority).
     */
    @Transactional
    public UserResponse replaceRoles(long userId, Set<RoleCode> newRoleCodes, String actorUsername) {
        // Serializes concurrent role changes so two admins can't demote each other at once and leave none.
        roles.lockByCode(RoleCode.ADMIN.name()).orElseThrow();

        AppUser user = users.findWithRolesAndWarehousesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        boolean losesAdmin = user.hasRole(RoleCode.ADMIN) && !newRoleCodes.contains(RoleCode.ADMIN);
        if (losesAdmin && user.isActive()
                && users.countByRoleAndStatus(RoleCode.ADMIN.name(), UserStatus.ACTIVE) <= 1) {
            throw new BusinessException(ErrorCode.LAST_ADMIN_REQUIRED);
        }

        List<Role> newRoles = roles.findByCodeIn(newRoleCodes.stream().map(Enum::name).toList());
        user.replaceRoles(newRoles);
        log.info("User {} set roles of user id={} to {}", actorUsername, userId, newRoleCodes);
        return userMapper.toResponse(user);
    }
}
