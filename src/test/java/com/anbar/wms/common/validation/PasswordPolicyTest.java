package com.anbar.wms.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"Warehouse2026", "correct horse 42", "Şifrə-ilə-1234"})
    void acceptsReasonablePasswords(String password) {
        assertThat(PasswordPolicy.violation(password)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Short1",            // too short
            "onlyletters",       // no digit
            "1234567890",        // no letter
            " Leading-space-1",  // leading whitespace
            "Trailing-space-1 "  // trailing whitespace
    })
    void rejectsWeakPasswords(String password) {
        assertThat(PasswordPolicy.violation(password)).isPresent();
    }

    @Test
    void rejectsPasswordsTooLongForBcrypt() {
        String tooManyCharacters = "a1" + "x".repeat(PasswordPolicy.MAX_LENGTH - 1);
        String tooManyBytes = "Ş".repeat(37) + "1"; // 38 characters, 75 UTF-8 bytes

        assertThat(PasswordPolicy.violation(tooManyCharacters)).isPresent();
        assertThat(PasswordPolicy.violation(tooManyBytes)).hasValueSatisfying(m -> assertThat(m).contains("bytes"));
        assertThat(PasswordPolicy.violation("a1" + "x".repeat(PasswordPolicy.MAX_LENGTH - 2))).isEmpty();
    }
}
