package com.anbar.wms.catalog.controller;

import com.anbar.wms.catalog.dto.CatalogResponses.UnitResponse;
import com.anbar.wms.catalog.dto.UnitRequests;
import com.anbar.wms.catalog.service.UnitOfMeasureService;
import com.anbar.wms.common.web.Locations;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.StatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Units of measure")
@RestController
@RequestMapping("/api/v1/units")
public class UnitOfMeasureController {

    private final UnitOfMeasureService service;

    public UnitOfMeasureController(UnitOfMeasureService service) {
        this.service = service;
    }

    @Operation(summary = "List units", description = "Sortable by code, name, createdAt.")
    @GetMapping
    public PageResponse<UnitResponse> list(@RequestParam(required = false) Boolean active,
                                           @ParameterObject Pageable pageable) {
        return service.list(active, pageable);
    }

    @GetMapping("/{id}")
    public UnitResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<UnitResponse> create(@Valid @RequestBody UnitRequests.Create request) {
        UnitResponse created = service.create(request);
        return ResponseEntity.created(Locations.ofCreated(created.id())).body(created);
    }

    @Operation(summary = "Update a unit", description = "The decimal scale is fixed once products use the unit.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public UnitResponse update(@PathVariable long id, @Valid @RequestBody UnitRequests.Update request) {
        return service.update(id, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public UnitResponse setStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return service.setActive(id, request.active());
    }

    @Operation(summary = "Delete an unused unit", description = "409 RESOURCE_IN_USE if any product uses it.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
