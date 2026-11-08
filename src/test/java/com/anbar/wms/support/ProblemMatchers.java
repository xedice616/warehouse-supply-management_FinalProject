package com.anbar.wms.support;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;

public final class ProblemMatchers {

    private ProblemMatchers() {
    }

    /** An RFC 9457 problem response with the given status and error code. */
    public static ResultMatcher problem(int status, String code) {
        return result -> {
            status().is(status).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.code").value(code).match(result);
            jsonPath("$.type").value(startsWith("urn:wms:problem:")).match(result);
            jsonPath("$.correlationId").exists().match(result);
        };
    }
}
