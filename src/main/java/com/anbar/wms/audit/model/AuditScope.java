package com.anbar.wms.audit.model;

/** FULL audits start with every stocked item of the warehouse; PARTIAL audits contain only the items added. */
public enum AuditScope {
    FULL,
    PARTIAL
}
