package com.anbar.wms.identity.controller;

import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.identity.dto.UserSummary;
import com.anbar.wms.identity.service.WarehouseAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Warehouse managers", description = "ADMIN only")
@RestController
@RequestMapping("/api/v1/warehouses/{warehouseId}/managers")
@PreAuthorize("hasRole('ADMIN')")
public class WarehouseManagerController {

    private final WarehouseAccessService service;

    public WarehouseManagerController(WarehouseAccessService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<UserSummary> list(@PathVariable long warehouseId, @ParameterObject Pageable pageable) {
        return service.listManagers(warehouseId, pageable);
    }

    @Operation(summary = "Assign a manager",
            description = "The user must be active and have the WAREHOUSE_MANAGER role; the warehouse must be active. Idempotent.")
    @PutMapping("/{userId}")
    public UserSummary assign(@PathVariable long warehouseId, @PathVariable long userId) {
        return service.assignManager(warehouseId, userId);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@PathVariable long warehouseId, @PathVariable long userId) {
        service.removeManager(warehouseId, userId);
        return ResponseEntity.noContent().build();
    }
}
