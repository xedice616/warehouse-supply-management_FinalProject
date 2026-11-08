package com.anbar.wms.identity.service;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;

/**
 * A login attempt was refused. The login transaction does <em>not</em> roll back for it, so the failed-attempt
 * counter it recorded is kept.
 */
public class AuthenticationFailedException extends BusinessException {

    public AuthenticationFailedException(ErrorCode code) {
        super(code);
    }
}
