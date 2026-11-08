package com.anbar.wms.importing.entity;

import com.anbar.wms.common.entity.BaseEntity;
import com.anbar.wms.identity.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One processed CSV bulk-import request. The idempotency key makes a repeated upload return the stored result
 * instead of importing again; {@code resultJson} holds the full result (per-row errors included) so a replay is
 * faithful without reprocessing the file.
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockImport extends BaseEntity {

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "total_rows", nullable = false, updatable = false)
    private int totalRows;

    @Column(name = "successful_rows", nullable = false, updatable = false)
    private int successfulRows;

    @Column(name = "failed_rows", nullable = false, updatable = false)
    private int failedRows;

    @Column(name = "imported_quantity", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal importedQuantity;

    @Column(name = "result_json", nullable = false, updatable = false)
    private String resultJson;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "imported_by", nullable = false, updatable = false)
    private AppUser importedBy;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    public StockImport(String idempotencyKey, int totalRows, int successfulRows, int failedRows,
                       BigDecimal importedQuantity, String resultJson, AppUser importedBy, Instant importedAt) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.totalRows = totalRows;
        this.successfulRows = successfulRows;
        this.failedRows = failedRows;
        this.importedQuantity = Objects.requireNonNull(importedQuantity, "importedQuantity");
        this.resultJson = Objects.requireNonNull(resultJson, "resultJson");
        this.importedBy = Objects.requireNonNull(importedBy, "importedBy");
        this.importedAt = Objects.requireNonNull(importedAt, "importedAt");
    }
}
