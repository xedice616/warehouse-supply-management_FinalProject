package com.anbar.wms.security.web;

import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 403 for an authenticated user whose roles don't allow the request ({@code ACCESS_DENIED}). */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemDetailFactory problems;
    private final ProblemResponseWriter writer;

    public ProblemAccessDeniedHandler(ProblemDetailFactory problems, ProblemResponseWriter writer) {
        this.problems = problems;
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        writer.write(response, problems.create(ErrorCode.ACCESS_DENIED));
    }
}
