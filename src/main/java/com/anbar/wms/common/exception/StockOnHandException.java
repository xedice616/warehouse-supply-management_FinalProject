package com.anbar.wms.common.exception;

import java.util.Map;

public class StockOnHandException extends BusinessException {

    public StockOnHandException(String resource, String message) {
        super(ErrorCode.STOCK_ON_HAND, message, Map.of("resource", resource));
    }
}
