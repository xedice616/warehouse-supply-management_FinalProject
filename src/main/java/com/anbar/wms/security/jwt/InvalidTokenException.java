package com.anbar.wms.security.jwt;

import com.anbar.wms.common.exception.ErrorCode;

/** A presented token was rejected: {@link ErrorCode#TOKEN_EXPIRED} or {@link ErrorCode#INVALID_TOKEN}. */
public class InvalidTokenException extends RuntimeException {

    private final ErrorCode code;

    InvalidTokenException(ErrorCode code, Throwable cause) {
        super(code.defaultMessage(), cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
