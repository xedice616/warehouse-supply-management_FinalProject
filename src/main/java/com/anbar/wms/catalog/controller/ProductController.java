package com.anbar.wms.catalog.controller;

import com.anbar.wms.catalog.dto.CatalogResponses.ProductResponse;
import com.anbar.wms.catalog.dto.ProductRequests;
import com.anbar.wms.catalog.dto.ProductSearchResponses.ProductSummary;
import com.anbar.wms.catalog.service.ProductService;
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

@Tag(name = "Products", description = "Product master data. Stock is never edited here; see the stock endpoints.")
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @Operation(summary = "List products",
            description = "q matches a SKU or name prefix. Sortable by sku, name, createdAt, purchasePrice.")
    @GetMapping
    public PageResponse<ProductResponse> list(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) Long categoryId,
                                              @RequestParam(required = false) Boolean active,
                                              @ParameterObject Pageable pageable) {
        return service.list(q, categoryId, active, pageable);
    }

    @Operation(summary = "Search products",
            description = "Lightweight search. sku and barcode are exact (case-insensitive SKU); q is a name prefix; "
                    + "categoryId and active narrow further. All optional, combined with AND. Sortable by sku, name.")
    @GetMapping("/search")
    public PageResponse<ProductSummary> search(@RequestParam(required = false) String sku,
                                               @RequestParam(required = false) String barcode,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) Long categoryId,
                                               @RequestParam(required = false) Boolean active,
                                               @ParameterObject Pageable pageable) {
        return service.search(sku, barcode, q, categoryId, active, pageable);
    }

    @Operation(summary = "Look up a product by exact SKU")
    @GetMapping("/sku/{sku}")
    public ProductResponse bySku(@PathVariable String sku) {
        return service.getBySku(sku);
    }

    @Operation(summary = "Look up a product by exact barcode")
    @GetMapping("/barcode/{barcode}")
    public ProductResponse byBarcode(@PathVariable String barcode) {
        return service.getByBarcode(barcode);
    }

    @GetMapping("/{id:\\d+}")
    public ProductResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequests.Create request) {
        ProductResponse created = service.create(request);
        return ResponseEntity.created(Locations.ofCreated(created.id())).body(created);
    }

    @Operation(summary = "Update a product",
            description = "The SKU never changes. Unit and batch/expiry tracking are fixed once stock has moved.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable long id, @Valid @RequestBody ProductRequests.Update request) {
        return service.update(id, request);
    }

    @Operation(summary = "Activate or deactivate",
            description = "Inactive products can't be used in new stock operations. Deactivation needs zero stock.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/status")
    public ProductResponse setStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        return service.setActive(id, request.active());
    }

    @Operation(summary = "Delete a product that was never used",
            description = "409 RESOURCE_IN_USE if it has stock history or appears on any document; deactivate it instead.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
