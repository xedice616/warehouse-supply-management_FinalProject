package com.anbar.wms.common.exception;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned in the {@code code} field of every error response.
 * Clients branch on these codes, never on the human-readable message, so existing codes must not be renamed.
 * Feature modules add their own codes here as they are built (e.g. {@code INSUFFICIENT_STOCK} in stage 4).
 */
public enum ErrorCode {

    // 400
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body is malformed or contains unknown properties"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "A request parameter is missing or invalid"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "An Idempotency-Key header (1-100 characters) is required"),
    INVALID_SORT_PROPERTY(HttpStatus.BAD_REQUEST, "Sorting by this property is not supported"),
    INVALID_QUANTITY_SCALE(HttpStatus.BAD_REQUEST, "The quantity has more decimal places than its unit allows"),
    CSV_IMPORT_FAILED(HttpStatus.BAD_REQUEST, "The CSV file could not be read; check its type, header and contents"),

    // 401: who are you?
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "The access token is invalid"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "The access token has expired"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username, email or password"),
    ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "The account is temporarily locked after too many failed logins"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "The account is disabled"),

    // 403: you may not do this
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You are not allowed to perform this action"),

    // 404 / 405 / 406 / 415
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "No endpoint matches this request"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Requested response media type is not supported"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Request media type is not supported"),

    // 413
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded file is larger than the allowed limit"),

    // 409
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The resource was modified by someone else; reload and retry"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "A resource with the same unique value already exists"),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "The request conflicts with existing data"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "The resource is not in a state that allows this action"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "An account with this email already exists"),
    USERNAME_ALREADY_TAKEN(HttpStatus.CONFLICT, "This username is already taken"),
    LAST_ADMIN_REQUIRED(HttpStatus.CONFLICT, "The last active administrator cannot lose the ADMIN role"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "This Idempotency-Key was already used for a different request"),
    RESOURCE_IN_USE(HttpStatus.CONFLICT, "The resource is referenced by other data and cannot be deleted"),
    STOCK_ON_HAND(HttpStatus.CONFLICT, "The operation is not allowed while stock is on hand"),
    STOCK_HISTORY_EXISTS(HttpStatus.CONFLICT, "The change would reinterpret existing stock history"),
    BATCH_METADATA_MISMATCH(HttpStatus.CONFLICT, "The batch already exists with different production or expiration dates"),

    // 422
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "The request violates a business rule"),
    INACTIVE_RESOURCE(HttpStatus.UNPROCESSABLE_CONTENT, "An inactive resource cannot be used here"),
    INSUFFICIENT_STOCK(HttpStatus.UNPROCESSABLE_CONTENT, "Not enough stock is available for this movement"),
    RECEIPT_EXCEEDS_ORDER(HttpStatus.UNPROCESSABLE_CONTENT, "Receiving this quantity would exceed the ordered quantity"),
    BATCH_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "This product is batch-tracked; a batch number is required"),
    BATCH_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "This product is not batch-tracked; omit the batch number"),
    BATCH_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, "The batch has expired; it can only be written off"),
    EXPIRY_DATE_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "This product is expiry-tracked; an expiry date is required"),
    INVALID_CATEGORY_HIERARCHY(HttpStatus.UNPROCESSABLE_CONTENT, "A category cannot be placed under itself or its descendants"),
    NOT_A_WAREHOUSE_MANAGER(HttpStatus.UNPROCESSABLE_CONTENT, "The user does not have the WAREHOUSE_MANAGER role"),

    // 500
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    private static final String TYPE_PREFIX = "urn:wms:problem:";

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /** RFC 9457 problem {@code type}, e.g. {@code urn:wms:problem:resource-not-found}. */
    public URI type() {
        return URI.create(TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** Fallback code for errors raised by the framework, where only the HTTP status is known. */
    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_PARAMETER;
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> ACCESS_DENIED;
            case 404 -> ENDPOINT_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> DATA_INTEGRITY_VIOLATION;
            case 413 -> FILE_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> BUSINESS_RULE_VIOLATION;
            default -> INTERNAL_ERROR;
        };
    }
}
