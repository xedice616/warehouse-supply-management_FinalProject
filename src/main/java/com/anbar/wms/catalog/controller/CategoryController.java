package com.anbar.wms.catalog.controller;

import com.anbar.wms.catalog.dto.CatalogResponses.CategoryResponse;
import com.anbar.wms.catalog.dto.CategoryRequests;
import com.anbar.wms.catalog.service.CategoryService;
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

@Tag(name = "Categories")
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @Operation(summary = "List categories",
            description = "Filter by name prefix (q), parentId and active. Sortable by name, createdAt.")
    @GetMapping
    public PageResponse<CategoryResponse> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) Long parentId,
                                               @RequestParam(required = false) Boolean active,
                                               @ParameterObject Pageable pageable) {
        return service.list(q, parentId, active, pageable);
    }

    @GetMapping("/{id}")
    public CategoryResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequests.Create request) {
        CategoryResponse created = service.create(request);
        return ResponseEntity.created(Locations.ofCreated(created.id())).body(created);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public CategoryResponse update(@PathVariable long id, @Valid @RequestBody CategoryRequests.Update request) {
        return service.update(id, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public CategoryResponse setStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return service.setActive(id, request.active());
    }

    @Operation(summary = "Delete an unused category",
            description = "409 RESOURCE_IN_USE if it has products or subcategories.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
