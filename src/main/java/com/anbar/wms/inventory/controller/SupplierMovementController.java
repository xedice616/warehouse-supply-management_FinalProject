package com.anbar.wms.inventory.controller;

import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.inventory.dto.MovementSearch;
import com.anbar.wms.inventory.dto.StockMovementResponse;
import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.inventory.service.StockMovementQueryService;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.supplier.service.SupplierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Suppliers")
@RestController
@RequestMapping("/api/v1/suppliers/{supplierId}")
@PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
public class SupplierMovementController {

    private final StockMovementQueryService history;
    private final SupplierService suppliers;

    public SupplierMovementController(StockMovementQueryService history, SupplierService suppliers) {
        this.history = history;
        this.suppliers = suppliers;
    }

    @Operation(summary = "Goods received from a supplier", description = "Inbound movements linked to the supplier.")
    @GetMapping("/stock-movements")
    public PageResponse<StockMovementResponse> deliveries(@PathVariable long supplierId,
                                                          @ParameterObject Pageable pageable,
                                                          @AuthenticationPrincipal AuthenticatedUser user) {
        suppliers.require(supplierId);
        return history.search(new MovementSearch(null, null, null, MovementType.INBOUND, supplierId, null, null, null),
                user.warehouseScope(), pageable);
    }
}
