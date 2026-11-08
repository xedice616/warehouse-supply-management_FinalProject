package com.anbar.wms.common.exception;

import com.anbar.wms.common.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builds the RFC 9457 problem body used by every error response, whether it comes from a controller
 * ({@link GlobalExceptionHandler}) or from the security filter chain (401/403 before any controller runs).
 *
 * <p>Extension members: {@code code} (stable {@link ErrorCode} name), {@code timestamp} (UTC) and
 * {@code correlationId} (matches the {@code X-Correlation-Id} header and the logs).
 */
@Component
public class ProblemDetailFactory {

    public static final String CODE = "code";
    public static final String TIMESTAMP = "timestamp";
    public static final String CORRELATION_ID = "correlationId";
    public static final String ERRORS = "errors";

    private final Clock clock;

    public ProblemDetailFactory(Clock clock) {
        this.clock = clock;
    }

    public ProblemDetail create(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(code.type());
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty(CODE, code.name());
        enrich(problem, code);
        return problem;
    }

    public ProblemDetail create(ErrorCode code) {
        return create(code, code.defaultMessage());
    }

    /** Adds the extension members a problem is missing; never overwrites a more specific code. */
    public void enrich(ProblemDetail problem, ErrorCode fallbackCode) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey(CODE)) {
            problem.setProperty(CODE, fallbackCode.name());
            problem.setType(fallbackCode.type());
        }
        problem.setProperty(TIMESTAMP, Instant.now(clock));
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty(CORRELATION_ID, correlationId);
        }
    }
}
