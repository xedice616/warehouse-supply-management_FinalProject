package com.anbar.wms.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An expected failure of a use case, translated into an RFC 9457 problem response carrying {@link #getCode()}.
 *
 * <p>The message is shown to API clients, so it must never contain internal details (SQL, stack traces, secrets).
 * Extra machine-readable context goes into {@link #getProperties()}, e.g. {@code available} and {@code requested}
 * for an insufficient-stock error.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;

    public BusinessException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public BusinessException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public BusinessException(ErrorCode code, String message, Map<String, ?> properties) {
        super(message);
        this.code = code;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public ErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
