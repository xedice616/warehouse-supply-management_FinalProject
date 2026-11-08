package com.anbar.wms.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.identity.dto.LoginRequest;
import com.anbar.wms.identity.dto.RegisterRequest;
import com.anbar.wms.identity.dto.TokenResponse;
import com.anbar.wms.identity.dto.UserResponse;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.identity.entity.Role;
import com.anbar.wms.identity.entity.UserStatus;
import com.anbar.wms.identity.mapper.UserMapperImpl;
import com.anbar.wms.identity.repository.AppUserRepository;
import com.anbar.wms.identity.repository.RoleRepository;
import com.anbar.wms.security.config.WmsSecurityProperties;
import com.anbar.wms.security.jwt.JwtService;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final String PASSWORD = "Correct-horse-42";

    @Mock
    private AppUserRepository users;

    @Mock
    private RoleRepository roles;

    private final PasswordEncoder passwordEncoder =
            new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(4)));

    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        WmsSecurityProperties properties = new WmsSecurityProperties(
                new WmsSecurityProperties.Jwt(
                        Base64.getEncoder().encodeToString("auth-service-test-secret-long-enough-for-hs256"
                                .getBytes(StandardCharsets.UTF_8)),
                        "wms", "wms-api", Duration.ofMinutes(15), Duration.ofSeconds(30)),
                4,
                new WmsSecurityProperties.Lockout(5, Duration.ofMinutes(15)),
                new WmsSecurityProperties.BootstrapAdmin(null, null, null));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        jwtService = new JwtService(properties, clock);
        authService = new AuthService(users, roles, passwordEncoder, jwtService, new UserMapperImpl(), properties, clock);
    }

    // ------------------------------------------------------------------ login

    @Test
    void unknownUserGetsGenericInvalidCredentials() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertFails(() -> authService.login(new LoginRequest("Ghost", PASSWORD)), ErrorCode.INVALID_CREDENTIALS);
        verify(users, never()).recordFailedLogin(anyLong(), anyInt(), any());
    }

    @Test
    void wrongPasswordIsCountedAndRefused() {
        AppUser user = user(7L);
        when(users.findByUsername("aysel")).thenReturn(Optional.of(user));

        assertFails(() -> authService.login(new LoginRequest("aysel", "Wrong-password-1")), ErrorCode.INVALID_CREDENTIALS);
        verify(users).recordFailedLogin(7L, 5, NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void lockedAccountIsRefusedWithoutCheckingThePassword() {
        AppUser user = user(7L);
        ReflectionTestUtils.setField(user, "lockedUntil", NOW.plusSeconds(60));
        when(users.findByUsername("aysel")).thenReturn(Optional.of(user));

        assertFails(() -> authService.login(new LoginRequest("aysel", PASSWORD)), ErrorCode.ACCOUNT_LOCKED);
        verify(users, never()).recordFailedLogin(anyLong(), anyInt(), any());
        verify(users, never()).recordSuccessfulLogin(anyLong(), any());
    }

    @Test
    void expiredLockNoLongerBlocks() {
        AppUser user = user(7L);
        ReflectionTestUtils.setField(user, "lockedUntil", NOW.minusSeconds(1));
        when(users.findByUsername("aysel")).thenReturn(Optional.of(user));

        assertThat(authService.login(new LoginRequest("aysel", PASSWORD)).accessToken()).isNotBlank();
    }

    @Test
    void disabledAccountIsRevealedOnlyWithTheRightPassword() {
        AppUser user = user(7L);
        ReflectionTestUtils.setField(user, "status", UserStatus.DISABLED);
        when(users.findByUsername("aysel")).thenReturn(Optional.of(user));

        assertFails(() -> authService.login(new LoginRequest("aysel", "Wrong-password-1")), ErrorCode.INVALID_CREDENTIALS);
        assertFails(() -> authService.login(new LoginRequest("aysel", PASSWORD)), ErrorCode.ACCOUNT_DISABLED);
    }

    @Test
    void successfulLoginByEmailIssuesTokenForTheUser() {
        AppUser user = user(7L);
        when(users.findByEmail("aysel@example.com")).thenReturn(Optional.of(user));

        TokenResponse response = authService.login(new LoginRequest(" Aysel@Example.com ", PASSWORD));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(jwtService.parse(response.accessToken()).userId()).isEqualTo(7L);
        verify(users).recordSuccessfulLogin(7L, NOW);
    }

    // ------------------------------------------------------------------ registration

    @Test
    void registrationHashesPasswordAndGrantsOnlyUser() {
        when(roles.findByCode("USER")).thenReturn(Optional.of(role("USER")));

        UserResponse response = authService.register(
                new RegisterRequest("New.User", "New@Example.com", PASSWORD, "New User"));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).startsWith("{bcrypt}$2a$").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
        assertThat(response.username()).isEqualTo("new.user");
        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.roles()).containsExactly("USER");
    }

    @Test
    void duplicateEmailIsRejectedBeforeSaving() {
        when(users.existsByEmail("taken@example.com")).thenReturn(true);

        assertFails(() -> authService.register(new RegisterRequest("someone", "Taken@Example.com", PASSWORD, "X")),
                ErrorCode.EMAIL_ALREADY_REGISTERED);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateCaughtByTheDatabaseIsStillAConflict() {
        when(roles.findByCode("USER")).thenReturn(Optional.of(role("USER")));
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("insert failed",
                new SQLException("duplicate key value violates unique constraint \"uq_app_user_email\"")));

        assertFails(() -> authService.register(new RegisterRequest("racer", "racer@example.com", PASSWORD, "X")),
                ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    // ------------------------------------------------------------------ helpers

    private AppUser user(long id) {
        AppUser user = new AppUser("aysel", "aysel@example.com", passwordEncoder.encode(PASSWORD), "Aysel");
        ReflectionTestUtils.setField(user, "id", id);
        user.replaceRoles(List.of(role("USER")));
        return user;
    }

    private static Role role(String code) {
        Role role = BeanUtils.instantiateClass(Role.class);
        ReflectionTestUtils.setField(role, "code", code);
        return role;
    }

    private static void assertFails(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(expected));
    }
}
