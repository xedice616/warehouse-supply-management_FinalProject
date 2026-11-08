package com.anbar.wms.importing.controller;

import com.anbar.wms.importing.dto.ImportResult;
import com.anbar.wms.importing.service.CsvImportService;
import com.anbar.wms.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Imports", description = "Bulk data import from CSV files. Imports go through the normal stock-movement "
        + "logic; they never edit balances directly.")
@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    private final CsvImportService service;

    public ImportController(CsvImportService service) {
        this.service = service;
    }

    @Operation(summary = "Bulk inbound stock from a CSV file",
            description = """
                    Uploads a CSV with the header `warehouseCode,sku,quantity,unitCost,batchNumber,expirationDate,\
                    referenceNumber`. Each row is validated independently: valid rows are received as INBOUND stock \
                    (one document per reference number), invalid rows are returned in `errors` and skipped. \
                    unitCost, batchNumber and expirationDate may be blank where the product allows it; referenceNumber \
                    defaults to the Idempotency-Key.

                    Send a unique `Idempotency-Key` per upload: repeating a request with the same key returns the \
                    original result (200, `replayed=true`) without importing again. Requires ADMIN or WAREHOUSE_MANAGER, \
                    and rows may only target warehouses the caller can access.""")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    @PostMapping(value = "/stock-inbound", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> importStockInbound(
            @Parameter(description = "CSV file", content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Unique per upload, e.g. a UUID")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        ImportResult result = service.importStockInbound(file, idempotencyKey, user);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
}
