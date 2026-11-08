package com.anbar.wms.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id, so a client-reported error can be matched to server logs.
 *
 * <p>A well-formed incoming {@value #HEADER} is reused (so ids flow across services); anything else is replaced
 * with a fresh UUID, which stops clients from injecting arbitrary text into logs. The id is put in the logging MDC
 * under {@value #MDC_KEY}, echoed in the response header, and included in error responses.
 *
 * <p>Runs first, before security, so that authentication failures are correlated too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String resolve(String incoming) {
        return incoming != null && VALID_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
    }
}
