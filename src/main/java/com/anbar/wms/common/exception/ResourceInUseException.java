package com.anbar.wms.common.exception;

import java.util.Map;

public class ResourceInUseException extends BusinessException {

    public ResourceInUseException(String resource) {
        this(resource, "%s is referenced by other data and cannot be deleted; deactivate it instead".formatted(resource));
    }

    public ResourceInUseException(String resource, String message) {
        super(ErrorCode.RESOURCE_IN_USE, message, Map.of("resource", resource));
    }
}
