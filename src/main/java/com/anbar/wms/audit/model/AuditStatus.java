package com.anbar.wms.audit.model;

/**
 * {@code DRAFT → IN_PROGRESS → PENDING_APPROVAL → APPROVED → COMPLETED}. Rejection sends a pending audit back to
 * IN_PROGRESS for recounting. Cancelling is possible before approval. Completion posts the adjustments, once.
 */
public enum AuditStatus {
    DRAFT,
    IN_PROGRESS,
    PENDING_APPROVAL,
    APPROVED,
    COMPLETED,
    CANCELLED
}
