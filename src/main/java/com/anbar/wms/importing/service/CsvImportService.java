package com.anbar.wms.importing.service;

import com.anbar.wms.catalog.dto.CatalogResponses.ProductResponse;
import com.anbar.wms.common.config.WmsProperties;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ConstraintViolations;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.catalog.service.ProductService;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.importing.dto.ImportResult;
import com.anbar.wms.importing.dto.ImportResult.RowError;
import com.anbar.wms.importing.entity.StockImport;
import com.anbar.wms.importing.repository.StockImportRepository;
import com.anbar.wms.importing.service.CsvReader.Csv;
import com.anbar.wms.inventory.service.StockMovementService;
import com.anbar.wms.inventory.service.StockMovementService.ReceiptLine;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.warehouse.dto.WarehouseResponse;
import com.anbar.wms.warehouse.service.WarehouseService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * Bulk inbound stock import from a CSV file. Rows are validated independently; valid rows are imported together
 * through {@link StockMovementService} (one INBOUND document per reference number), so the import never bypasses
 * stock-movement business logic nor touches balances directly. Invalid rows are reported, never silently dropped.
 *
 * <p>Idempotency: the caller sends an {@code Idempotency-Key}. A repeated upload with the same key returns the
 * stored result instead of importing again. Everything (the stock documents and the import record) commits in
 * one transaction, so a retry can never double-post.
 */
@Service
public class CsvImportService {

    static final List<String> HEADER = List.of(
            "warehouseCode", "sku", "quantity", "unitCost", "batchNumber", "expirationDate", "referenceNumber");
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_REFERENCE_LENGTH = 64;
    private static final String KEY_CONSTRAINT = "uq_stock_import_idempotency_key";

    private final ProductService products;
    private final WarehouseService warehouses;
    private final StockMovementService stockMovements;
    private final StockImportRepository imports;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final WmsProperties properties;
    private final Clock clock;

    public CsvImportService(ProductService products, WarehouseService warehouses, StockMovementService stockMovements,
                            StockImportRepository imports, EntityManager entityManager, ObjectMapper objectMapper,
                            WmsProperties properties, Clock clock) {
        this.products = products;
        this.warehouses = warehouses;
        this.stockMovements = stockMovements;
        this.imports = imports;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ImportResult importStockInbound(MultipartFile file, String idempotencyKey, AuthenticatedUser actor) {
        String key = validKey(idempotencyKey);
        Optional<StockImport> earlier = imports.findByIdempotencyKey(key);
        if (earlier.isPresent()) {
            return replay(earlier.get());
        }

        Csv csv = readCsv(file);
        List<List<String>> rows = csv.rows();

        List<RowError> errors = new ArrayList<>();
        Map<Group, List<ReceiptLine>> groups = new LinkedHashMap<>();
        BigDecimal importedQuantity = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            int rowNumber = i + 1;
            try {
                Parsed parsed = parseRow(rows.get(i), key, actor);
                groups.computeIfAbsent(new Group(parsed.warehouseId(), parsed.referenceNumber()),
                        g -> new ArrayList<>()).add(parsed.line());
                importedQuantity = importedQuantity.add(parsed.line().quantity());
            } catch (RowRejected rejected) {
                errors.add(new RowError(rowNumber, rejected.field, rejected.code, rejected.getMessage()));
            }
        }

        groups.forEach((group, lines) ->
                stockMovements.importInbound(group.warehouseId(), group.referenceNumber(), lines, actor));

        int failed = errors.size();
        int successful = rows.size() - failed;
        ImportResult body = new ImportResult(0, false, rows.size(), successful, failed, importedQuantity, errors);
        StockImport saved = persist(key, body, actor);
        return new ImportResult(saved.getId(), false, body.totalRows(), body.successfulRows(), body.failedRows(),
                body.importedQuantity(), body.errors());
    }

    private Parsed parseRow(List<String> cells, String key, AuthenticatedUser actor) {
        if (cells.size() != HEADER.size()) {
            throw new RowRejected("row", "MALFORMED_ROW",
                    "Expected %d columns but found %d".formatted(HEADER.size(), cells.size()));
        }
        String warehouseCode = trimmed(cells.get(0));
        String sku = trimmed(cells.get(1));
        BigDecimal quantity = requiredPositive(cells.get(2), "quantity");
        require(warehouseCode, "warehouseCode");
        require(sku, "sku");

        ProductResponse product = products.lookupBySku(sku)
                .orElseThrow(() -> new RowRejected("sku", "MISSING_PRODUCT", "No product with SKU '%s'".formatted(sku)));
        if (!product.active()) {
            throw new RowRejected("sku", "INACTIVE_PRODUCT", "Product '%s' is inactive".formatted(sku));
        }
        if (quantity.stripTrailingZeros().scale() > product.unit().decimalScale()) {
            throw new RowRejected("quantity", "INVALID_QUANTITY_SCALE",
                    "Quantities of %s allow at most %d decimal places"
                            .formatted(product.unit().code(), product.unit().decimalScale()));
        }

        WarehouseResponse warehouse = warehouses.lookupByCode(warehouseCode)
                .filter(w -> actor.warehouseScope().allows(w.id()))
                .orElseThrow(() -> new RowRejected("warehouseCode", "MISSING_WAREHOUSE",
                        "No accessible warehouse with code '%s'".formatted(warehouseCode)));
        if (!warehouse.active()) {
            throw new RowRejected("warehouseCode", "INACTIVE_WAREHOUSE",
                    "Warehouse '%s' is inactive".formatted(warehouseCode));
        }

        BigDecimal unitCost = optionalUnitCost(cells.get(3));
        String batchNumber = batchNumber(trimmed(cells.get(4)), product);
        LocalDate expiration = expiration(trimmed(cells.get(5)), batchNumber, product);
        String reference = reference(trimmed(cells.get(6)), key);

        return new Parsed(warehouse.id(), reference,
                new ReceiptLine(product.id(), quantity, unitCost, batchNumber, expiration, null, null));
    }

    private String batchNumber(String batchNumber, ProductResponse product) {
        boolean given = !batchNumber.isBlank();
        if (product.batchTracked() && !given) {
            throw new RowRejected("batchNumber", "BATCH_REQUIRED",
                    "Product '%s' is batch-tracked; a batch number is required".formatted(product.sku()));
        }
        if (!product.batchTracked() && given) {
            throw new RowRejected("batchNumber", "BATCH_NOT_ALLOWED",
                    "Product '%s' is not batch-tracked; omit the batch number".formatted(product.sku()));
        }
        return given ? batchNumber : null;
    }

    private LocalDate expiration(String value, String batchNumber, ProductResponse product) {
        LocalDate expiration = null;
        if (!value.isBlank()) {
            try {
                expiration = LocalDate.parse(value);
            } catch (DateTimeParseException ex) {
                throw new RowRejected("expirationDate", "INVALID_EXPIRATION_DATE",
                        "'%s' is not an ISO date (yyyy-MM-dd)".formatted(value));
            }
            if (expiration.isBefore(today())) {
                throw new RowRejected("expirationDate", "BATCH_EXPIRED",
                        "The batch expired on %s and cannot be received".formatted(expiration));
            }
        }
        if (product.expiryTracked() && batchNumber != null && expiration == null) {
            throw new RowRejected("expirationDate", "EXPIRY_DATE_REQUIRED",
                    "Product '%s' is expiry-tracked; an expiration date is required".formatted(product.sku()));
        }
        return expiration;
    }

    private static BigDecimal optionalUnitCost(String raw) {
        String value = trimmed(raw);
        if (value.isBlank()) {
            return null;
        }
        BigDecimal unitCost = number(value, "unitCost");
        if (unitCost.signum() < 0) {
            throw new RowRejected("unitCost", "INVALID_UNIT_COST", "The unit cost cannot be negative");
        }
        return unitCost;
    }

    private static String reference(String value, String key) {
        if (value.isBlank()) {
            return key.length() > MAX_REFERENCE_LENGTH ? key.substring(0, MAX_REFERENCE_LENGTH) : key;
        }
        if (value.length() > MAX_REFERENCE_LENGTH) {
            throw new RowRejected("referenceNumber", "INVALID_VALUE",
                    "The reference number may be at most %d characters".formatted(MAX_REFERENCE_LENGTH));
        }
        return value;
    }

    private static BigDecimal requiredPositive(String raw, String field) {
        BigDecimal value = number(require(trimmed(raw), field), field);
        if (value.signum() <= 0) {
            throw new RowRejected(field, "INVALID_QUANTITY", "The quantity must be greater than zero");
        }
        return value;
    }

    private static BigDecimal number(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new RowRejected(field, "INVALID_NUMBER", "'%s' is not a number".formatted(value));
        }
    }

    private static String require(String value, String field) {
        if (value.isBlank()) {
            throw new RowRejected(field, "MISSING_VALUE", "%s is required".formatted(field));
        }
        return value;
    }

    private Csv readCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.CSV_IMPORT_FAILED, "The uploaded file is empty");
        }
        if (!looksLikeCsv(file)) {
            throw new BusinessException(ErrorCode.CSV_IMPORT_FAILED,
                    "Upload a CSV file (text/csv or a .csv filename)");
        }
        String text;
        try {
            text = stripBom(new String(file.getBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            throw new BusinessException(ErrorCode.CSV_IMPORT_FAILED, "The uploaded file could not be read");
        }
        Csv csv = CsvReader.parse(text);
        if (!headerMatches(csv.header())) {
            throw new BusinessException(ErrorCode.CSV_IMPORT_FAILED,
                    "The header must be exactly: " + String.join(",", HEADER));
        }
        if (csv.rows().isEmpty()) {
            throw new BusinessException(ErrorCode.CSV_IMPORT_FAILED, "The file has a header but no data rows");
        }
        if (csv.rows().size() > MAX_ROWS) {
            throw new BusinessException(ErrorCode.CSV_IMPORT_FAILED,
                    "The file has more than %d data rows".formatted(MAX_ROWS));
        }
        return csv;
    }

    private static boolean looksLikeCsv(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return true;
        }
        String type = file.getContentType();
        return type == null || type.startsWith("text/") || type.equals("application/vnd.ms-excel")
                || type.equals("application/octet-stream");
    }

    private static boolean headerMatches(List<String> header) {
        if (header.size() != HEADER.size()) {
            return false;
        }
        for (int i = 0; i < HEADER.size(); i++) {
            if (!HEADER.get(i).equalsIgnoreCase(header.get(i).trim())) {
                return false;
            }
        }
        return true;
    }

    private StockImport persist(String key, ImportResult body, AuthenticatedUser actor) {
        StockImport record = new StockImport(key, body.totalRows(), body.successfulRows(), body.failedRows(),
                body.importedQuantity(), objectMapper.writeValueAsString(body),
                entityManager.getReference(AppUser.class, actor.id()), Instant.now(clock));
        try {
            return imports.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.violates(ex, KEY_CONSTRAINT)) {
                throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                        "This import is already being processed; retry to get its result");
            }
            throw ex;
        }
    }

    private ImportResult replay(StockImport record) {
        ImportResult body = objectMapper.readValue(record.getResultJson(), ImportResult.class);
        return new ImportResult(record.getId(), true, body.totalRows(), body.successfulRows(), body.failedRows(),
                body.importedQuantity(), body.errors());
    }

    private static String validKey(String idempotencyKey) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        return key;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessTimezone()));
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private record Group(long warehouseId, String referenceNumber) {
    }

    private record Parsed(long warehouseId, String referenceNumber, ReceiptLine line) {
    }

    private static final class RowRejected extends RuntimeException {
        private final String field;
        private final String code;

        RowRejected(String field, String code, String message) {
            super(message);
            this.field = field;
            this.code = code;
        }
    }
}
