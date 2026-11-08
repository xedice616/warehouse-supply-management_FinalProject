package com.anbar.wms.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void typeUriIsDerivedFromName() {
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.type()).hasToString("urn:wms:problem:resource-not-found");
    }

    @Test
    void everyCodeHasADistinctTypeAndAMessage() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::type)).doesNotHaveDuplicates();
        assertThat(ErrorCode.values()).allSatisfy(code -> assertThat(code.defaultMessage()).isNotBlank());
    }

    @Test
    void frameworkStatusesMapToFallbackCodes() {
        assertThat(ErrorCode.fromStatus(404)).isEqualTo(ErrorCode.ENDPOINT_NOT_FOUND);
        assertThat(ErrorCode.fromStatus(415)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorCode.fromStatus(503)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
