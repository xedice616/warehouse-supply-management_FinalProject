package com.anbar.wms.common.exception;

import java.math.BigDecimal;
import java.util.Map;

public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(long warehouseId, long productId, BigDecimal available, BigDecimal requested) {
        super(ErrorCode.INSUFFICIENT_STOCK,
                "Only %s available, %s requested".formatted(available.stripTrailingZeros().toPlainString(),
                        requested.stripTrailingZeros().toPlainString()),
                Map.of("warehouseId", warehouseId, "productId", productId,
                        "available", available, "requested", requested));
    }
}
