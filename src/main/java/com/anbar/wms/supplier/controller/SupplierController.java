package com.anbar.wms.supplier.controller;

import com.anbar.wms.common.web.Locations;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.StatusRequest;
import com.anbar.wms.supplier.dto.SupplierRequests;
import com.anbar.wms.supplier.dto.SupplierResponse;
import com.anbar.wms.supplier.service.SupplierService;
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

@Tag(name = "Suppliers", description = "Read: ADMIN, WAREHOUSE_MANAGER. Write: ADMIN.")
@RestController
@RequestMapping("/api/v1/suppliers")
@PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @Operation(summary = "List and search suppliers",
            description = "q matches a company-name, tax-id or code prefix. Sortable by companyName, code, createdAt.")
    @GetMapping
    public PageResponse<SupplierResponse> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) Boolean active,
                                               @ParameterObject Pageable pageable) {
        return service.list(q, active, pageable);
    }

    @GetMapping("/{id}")
    public SupplierResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @Operation(summary = "Create a supplier", description = "The code (SUP-000123) is assigned automatically.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequests.Create request) {
        SupplierResponse created = service.create(request);
        return ResponseEntity.created(Locations.ofCreated(created.id())).body(created);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public SupplierResponse update(@PathVariable long id, @Valid @RequestBody SupplierRequests.Update request) {
        return service.update(id, request);
    }

    @Operation(summary = "Activate or deactivate", description = "Inactive suppliers can't be used on new inbound movements.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public SupplierResponse setStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return service.setActive(id, request.active());
    }

    @Operation(summary = "Delete a supplier nothing refers to",
            description = "409 RESOURCE_IN_USE once it has deliveries, batches or orders; deactivate it instead.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
