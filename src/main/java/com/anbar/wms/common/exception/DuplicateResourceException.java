package com.anbar.wms.common.exception;

import java.util.Map;

public class DuplicateResourceException extends BusinessException {

    public DuplicateResourceException(String field) {
        super(ErrorCode.DUPLICATE_RESOURCE, "A resource with the same %s already exists".formatted(field),
                Map.of("field", field));
    }
}
