package com.anbar.wms.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.identity.entity.Role;
import com.anbar.wms.identity.entity.UserStatus;
import com.anbar.wms.identity.mapper.UserMapperImpl;
import com.anbar.wms.identity.repository.AppUserRepository;
import com.anbar.wms.identity.repository.RoleRepository;
import com.anbar.wms.security.RoleCode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock
    private AppUserRepository users;

    @Mock
    private RoleRepository roles;

    private UserAdminService service;
    private final Role admin = role("ADMIN");
    private final Role user = role("USER");

    @BeforeEach
    void setUp() {
        service = new UserAdminService(users, roles, new UserMapperImpl(), new BCryptPasswordEncoder(4));
        when(roles.lockByCode("ADMIN")).thenReturn(Optional.of(admin));
    }

    @Test
    void lastActiveAdministratorCannotLoseAdmin() {
        when(users.findWithRolesAndWarehousesById(1L)).thenReturn(Optional.of(adminUser()));
        when(users.countByRoleAndStatus("ADMIN", UserStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.replaceRoles(1L, Set.of(RoleCode.USER), "root"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.LAST_ADMIN_REQUIRED));
    }

    @Test
    void administratorCanBeDemotedWhileAnotherRemains() {
        when(users.findWithRolesAndWarehousesById(1L)).thenReturn(Optional.of(adminUser()));
        when(users.countByRoleAndStatus("ADMIN", UserStatus.ACTIVE)).thenReturn(2L);
        when(roles.findByCodeIn(List.of("USER"))).thenReturn(List.of(user));

        assertThat(service.replaceRoles(1L, Set.of(RoleCode.USER), "root").roles()).containsExactly("USER");
    }

    @Test
    void lastActiveAdministratorCannotBeDisabled() {
        when(users.findWithRolesAndWarehousesById(1L)).thenReturn(Optional.of(adminUser()));
        when(users.countByRoleAndStatus("ADMIN", UserStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.setActive(1L, false, "root"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.LAST_ADMIN_REQUIRED));
    }

    @Test
    void anAdministratorCanBeDisabledWhileAnotherRemains() {
        AppUser account = adminUser();
        when(users.findWithRolesAndWarehousesById(1L)).thenReturn(Optional.of(account));
        when(users.countByRoleAndStatus("ADMIN", UserStatus.ACTIVE)).thenReturn(2L);

        assertThat(service.setActive(1L, false, "root").status()).isEqualTo("DISABLED");
        assertThat(account.isActive()).isFalse();
    }

    private AppUser adminUser() {
        AppUser account = new AppUser("boss", "boss@example.test", "{bcrypt}x", "Boss");
        ReflectionTestUtils.setField(account, "id", 1L);
        account.replaceRoles(List.of(admin));
        return account;
    }

    private static Role role(String code) {
        Role role = BeanUtils.instantiateClass(Role.class);
        ReflectionTestUtils.setField(role, "code", code);
        return role;
    }
}
