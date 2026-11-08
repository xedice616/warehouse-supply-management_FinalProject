package com.anbar.wms.common.exception;

/**
 * One invalid input field, returned in the {@code errors} array of a validation problem.
 * The rejected value is deliberately not echoed back: it may be a password or other sensitive input.
 *
 * @param field   JSON path of the field, e.g. {@code lines[2].quantity}; the object name for class-level constraints
 * @param message human-readable reason
 */
public record FieldViolation(String field, String message) {
}
