package com.anbar.wms.purchasing.repository;

import java.math.BigDecimal;

public record PurchaseSummaryRow(String key, String label, long orderCount, BigDecimal totalAmount) {
}
