package com.anbar.wms.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now". Code must inject this {@link Clock} instead of calling {@code Instant.now()}
 * so that timestamps and expiry rules are deterministic in tests.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
