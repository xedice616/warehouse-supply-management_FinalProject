package com.anbar.wms.identity.mapper;

import com.anbar.wms.identity.dto.UserResponse;
import com.anbar.wms.identity.dto.UserSummary;
import com.anbar.wms.identity.dto.WarehouseSummary;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.warehouse.entity.Warehouse;
import java.util.Comparator;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Entity → API response. Generated at compile time; an unmapped response field fails the build. */
@Mapper
public interface UserMapper {

    @Mapping(target = "roles", expression = "java(sortedRoles(user))")
    @Mapping(target = "warehouses", expression = "java(sortedWarehouses(user))")
    UserResponse toResponse(AppUser user);

    WarehouseSummary toSummary(Warehouse warehouse);

    UserSummary toUserSummary(AppUser user);

    default List<String> sortedRoles(AppUser user) {
        return user.roleCodes().stream().sorted().toList();
    }

    default List<WarehouseSummary> sortedWarehouses(AppUser user) {
        return user.getWarehouses().stream()
                .sorted(Comparator.comparing(Warehouse::getCode))
                .map(this::toSummary)
                .toList();
    }
}
