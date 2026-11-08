package com.anbar.wms.importing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of a CSV bulk import. Rows are validated independently: valid rows are imported together (one stock
 * document per reference number), invalid rows are reported here and skipped, so {@code successfulRows} and
 * {@code failedRows} always sum to {@code totalRows}.
 *
 * @param replayed true when this is the stored result of an earlier upload with the same Idempotency-Key
 */
public record ImportResult(
        long importId,
        boolean replayed,
        int totalRows,
        int successfulRows,
        int failedRows,
        BigDecimal importedQuantity,
        @JsonInclude(JsonInclude.Include.ALWAYS) List<RowError> errors) {

    /** A single rejected row. {@code field} is the CSV column at fault (or "row" for whole-row problems). */
    public record RowError(int rowNumber, String field, String errorCode, String message) {
    }
}
