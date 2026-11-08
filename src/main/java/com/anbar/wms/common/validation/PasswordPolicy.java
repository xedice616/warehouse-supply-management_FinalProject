package com.anbar.wms.common.validation;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The password rules, in one place for request validation ({@link StrongPassword}) and startup checks.
 *
 * <ul>
 *   <li>10 to 64 characters, and at most 72 bytes in UTF-8 (BCrypt ignores anything beyond 72 bytes, and Spring
 *       Security rejects such passwords outright)</li>
 *   <li>at least one letter and one digit</li>
 *   <li>no leading or trailing whitespace (a common copy-paste accident)</li>
 * </ul>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 64;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** The first rule the password breaks, or empty if it is acceptable. */
    public static Optional<String> violation(String password) {
        if (password == null) {
            return Optional.of("must not be null");
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return Optional.of("must be between %d and %d characters".formatted(MIN_LENGTH, MAX_LENGTH));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("must be at most %d bytes when UTF-8 encoded".formatted(MAX_BYTES));
        }
        if (!password.equals(password.strip())) {
            return Optional.of("must not start or end with whitespace");
        }
        if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            return Optional.of("must contain at least one letter and one digit");
        }
        return Optional.empty();
    }

    public static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
