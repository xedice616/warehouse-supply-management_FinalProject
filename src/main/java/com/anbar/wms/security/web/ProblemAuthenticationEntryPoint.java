package com.anbar.wms.security.web;

import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ProblemDetailFactory;
import com.anbar.wms.security.jwt.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 401 for protected endpoints called without valid credentials. The code tells the client what to do:
 * {@code AUTHENTICATION_REQUIRED} (send a token), {@code TOKEN_EXPIRED} (log in again) or {@code INVALID_TOKEN}.
 * Also sets {@code WWW-Authenticate} as RFC 6750 requires.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemDetailFactory problems;
    private final ProblemResponseWriter writer;

    public ProblemAuthenticationEntryPoint(ProblemDetailFactory problems, ProblemResponseWriter writer) {
        this.problems = problems;
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        ErrorCode code = request.getAttribute(JwtAuthenticationFilter.TOKEN_ERROR_ATTRIBUTE) instanceof ErrorCode c
                ? c
                : ErrorCode.AUTHENTICATION_REQUIRED;
        String challenge = code == ErrorCode.AUTHENTICATION_REQUIRED
                ? "Bearer realm=\"wms\""
                : "Bearer realm=\"wms\", error=\"invalid_token\", error_description=\"" + code.defaultMessage() + "\"";
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        writer.write(response, problems.create(code));
    }
}
