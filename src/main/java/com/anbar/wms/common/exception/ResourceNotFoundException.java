package com.anbar.wms.common.exception;

import java.util.Map;

/**
 * The requested resource does not exist, or exists but is outside the caller's data scope
 * (both cases look identical to the client so existence is not revealed).
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND,
                "%s with id '%s' was not found".formatted(resource, id),
                Map.of("resource", resource, "id", String.valueOf(id)));
    }
}
