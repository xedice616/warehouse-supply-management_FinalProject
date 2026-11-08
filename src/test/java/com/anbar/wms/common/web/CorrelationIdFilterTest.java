package com.anbar.wms.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void reusesWellFormedIncomingIdAndExposesItDuringTheRequestOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123_DEF.456");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(seenInChain).hasValue("abc-123_DEF.456");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123_DEF.456");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("downstream failure");
            });
        } catch (Exception expected) {
            // the failure itself is not under test
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "has spaces in it", "line\nbreak-injection", "<script>alert(1)</script>",
            "x234567890123456789012345678901234567890123456789012345678901234567890"})
    void replacesMissingOrUnsafeIdsWithUuid(String incoming) {
        String resolved = CorrelationIdFilter.resolve(incoming);

        assertThat(resolved).isNotEqualTo(incoming);
        assertThat(UUID.fromString(resolved)).isNotNull();
    }
}
