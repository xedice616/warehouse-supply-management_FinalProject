package com.anbar.wms.identity.controller;

import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.identity.dto.UpdateRolesRequest;
import com.anbar.wms.identity.dto.UpdateWarehousesRequest;
import com.anbar.wms.identity.dto.UserResponse;
import com.anbar.wms.identity.service.UserAdminService;
import com.anbar.wms.identity.service.WarehouseAccessService;
import com.anbar.wms.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users", description = "User administration (ADMIN only)")
@ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN or TOKEN_EXPIRED")
@ApiResponse(responseCode = "403", description = "ACCESS_DENIED: the caller is not an ADMIN")
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private final UserAdminService userAdminService;
    private final WarehouseAccessService warehouseAccessService;

    public UserAdminController(UserAdminService userAdminService, WarehouseAccessService warehouseAccessService) {
        this.userAdminService = userAdminService;
        this.warehouseAccessService = warehouseAccessService;
    }

    @Operation(summary = "List users",
            description = "Sortable by username, email, createdAt, lastLoginAt. Page size is capped at 100.")
    @GetMapping
    public PageResponse<UserResponse> list(@ParameterObject Pageable pageable) {
        return userAdminService.list(pageable);
    }

    @Operation(summary = "Replace the warehouses a user may see and operate on",
            description = "Non-admins only see stock and details of their assigned warehouses.")
    @PutMapping("/{id}/warehouses")
    public UserResponse replaceWarehouses(@PathVariable long id, @Valid @RequestBody UpdateWarehousesRequest request) {
        return warehouseAccessService.replaceUserWarehouses(id, request.warehouseIds());
    }

    @Operation(summary = "Replace a user's roles")
    @ApiResponse(responseCode = "200", description = "Roles replaced")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "LAST_ADMIN_REQUIRED")
    @PutMapping("/{id}/roles")
    public UserResponse replaceRoles(@PathVariable long id,
                                     @Valid @RequestBody UpdateRolesRequest request,
                                     @AuthenticationPrincipal AuthenticatedUser admin) {
        return userAdminService.replaceRoles(id, request.roles(), admin.username());
    }
}
