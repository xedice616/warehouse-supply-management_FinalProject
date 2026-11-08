package com.anbar.wms.common.exception;

import java.util.Map;
import java.util.Optional;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    public static boolean violates(DataIntegrityViolationException ex, String constraintName) {
        String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
        return message != null && message.contains("\"" + constraintName + "\"");
    }

    /**
     * Maps a unique-constraint violation to the field it protects, so losing a race to a concurrent insert
     * reads the same as the pre-check that usually catches it.
     */
    public static Optional<DuplicateResourceException> duplicate(DataIntegrityViolationException ex,
                                                                 Map<String, String> fieldByConstraint) {
        return fieldByConstraint.entrySet().stream()
                .filter(entry -> violates(ex, entry.getKey()))
                .findFirst()
                .map(entry -> new DuplicateResourceException(entry.getValue()));
    }
}
