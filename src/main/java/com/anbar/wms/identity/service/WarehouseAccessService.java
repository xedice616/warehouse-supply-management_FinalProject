package com.anbar.wms.identity.service;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.InactiveResourceException;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import com.anbar.wms.identity.dto.UserResponse;
import com.anbar.wms.identity.dto.UserSummary;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.identity.mapper.UserMapper;
import com.anbar.wms.identity.repository.AppUserRepository;
import com.anbar.wms.security.RoleCode;
import com.anbar.wms.warehouse.entity.Warehouse;
import com.anbar.wms.warehouse.service.WarehouseService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who may see and operate which warehouse (the {@code user_warehouse_access} table). */
@Service
public class WarehouseAccessService {

    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("username", "username", "fullName", "fullName"), Sort.by("username"));

    private final AppUserRepository users;
    private final WarehouseService warehouses;
    private final UserMapper mapper;

    public WarehouseAccessService(AppUserRepository users, WarehouseService warehouses, UserMapper mapper) {
        this.users = users;
        this.warehouses = warehouses;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserSummary> listManagers(long warehouseId, Pageable pageable) {
        warehouses.require(warehouseId);
        return PageResponse.from(
                users.findByWarehouseAndRole(warehouseId, RoleCode.WAREHOUSE_MANAGER.name(), SORT.apply(pageable)),
                mapper::toUserSummary);
    }

    @Transactional
    public UserSummary assignManager(long warehouseId, long userId) {
        Warehouse warehouse = warehouses.requireActive(warehouseId);
        AppUser user = requireUser(userId);
        if (!user.isActive()) {
            throw new InactiveResourceException("User", userId, user.getUsername());
        }
        if (!user.hasRole(RoleCode.WAREHOUSE_MANAGER)) {
            throw new BusinessException(ErrorCode.NOT_A_WAREHOUSE_MANAGER,
                    "User %s does not have the WAREHOUSE_MANAGER role".formatted(user.getUsername()),
                    Map.of("userId", userId));
        }
        user.grantWarehouse(warehouse);
        return mapper.toUserSummary(user);
    }

    @Transactional
    public void removeManager(long warehouseId, long userId) {
        Warehouse warehouse = warehouses.require(warehouseId);
        requireUser(userId).revokeWarehouse(warehouse);
    }

    @Transactional
    public UserResponse replaceUserWarehouses(long userId, Set<Long> warehouseIds) {
        AppUser user = requireUser(userId);
        List<Warehouse> granted = warehouseIds.stream().sorted().map(warehouses::require).toList();
        user.replaceWarehouses(granted);
        return mapper.toResponse(user);
    }

    private AppUser requireUser(long userId) {
        return users.findWithRolesAndWarehousesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}
