package com.anbar.wms.identity.service;

import com.anbar.wms.common.entity.BaseEntity;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.identity.repository.AppUserRepository;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.security.AuthenticatedUserResolver;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticatedUserLookupService implements AuthenticatedUserResolver {

    private final AppUserRepository users;

    public AuthenticatedUserLookupService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> resolve(long userId) {
        return users.findWithRolesAndWarehousesById(userId)
                .filter(AppUser::isActive)
                .map(user -> new AuthenticatedUser(
                        user.getId(),
                        user.getUsername(),
                        user.roleCodes(),
                        user.getWarehouses().stream().map(BaseEntity::getId).collect(Collectors.toSet())));
    }
}
