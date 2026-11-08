package com.anbar.wms.warehouse.controller;

import com.anbar.wms.common.web.Locations;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.StatusRequest;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.warehouse.dto.WarehouseRequests;
import com.anbar.wms.warehouse.dto.WarehouseResponse;
import com.anbar.wms.warehouse.service.WarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Warehouses", description = "Non-admins only see the warehouses assigned to them.")
@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @Operation(summary = "List warehouses", description = "q matches a code or name prefix. Sortable by code, name, createdAt.")
    @GetMapping
    public PageResponse<WarehouseResponse> list(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) Boolean active,
                                                @ParameterObject Pageable pageable,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.warehouseScope(), q, active, pageable);
    }

    @GetMapping("/{id}")
    public WarehouseResponse get(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return service.get(id, user.warehouseScope());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@Valid @RequestBody WarehouseRequests.Create request) {
        WarehouseResponse created = service.create(request);
        return ResponseEntity.created(Locations.ofCreated(created.id())).body(created);
    }

    @Operation(summary = "Update a warehouse", description = "The code never changes.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public WarehouseResponse update(@PathVariable long id, @Valid @RequestBody WarehouseRequests.Update request) {
        return service.update(id, request);
    }

    @Operation(summary = "Activate or deactivate",
            description = "Inactive warehouses can't take part in new stock operations. Deactivation needs zero stock.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public WarehouseResponse setStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return service.setActive(id, request.active());
    }
}
