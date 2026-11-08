package com.anbar.wms.common.exception;

import java.util.Map;

public class InactiveResourceException extends BusinessException {

    public InactiveResourceException(String resource, long id, String label) {
        super(ErrorCode.INACTIVE_RESOURCE, "%s %s is inactive".formatted(resource, label),
                Map.of("resource", resource, "id", id));
    }
}
