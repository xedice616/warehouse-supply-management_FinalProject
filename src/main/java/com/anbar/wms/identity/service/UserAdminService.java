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

    public UserAdminService(AppUserRepository users, RoleRepository roles, UserMapper userMapper) {
        this.users = users;
        this.roles = roles;
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(Pageable pageable) {
        return PageResponse.from(users.findAll(SORT.apply(pageable)), userMapper::toResponse);
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
