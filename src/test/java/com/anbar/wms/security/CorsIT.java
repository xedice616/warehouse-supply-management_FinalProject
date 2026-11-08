package com.anbar.wms.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "wms.cors.allowed-origins=https://app.example.com")
class CorsIT extends AbstractIntegrationTest {

    @Test
    void preflightFromAnAllowedOriginIsAccepted() throws Exception {
        mockMvc.perform(options("/api/v1/products")
                        .header("Origin", "https://app.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"));
    }

    @Test
    void preflightFromAnUnknownOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/products")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
