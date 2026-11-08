package com.anbar.wms.common.config;

import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-specific settings under the {@code wms.*} prefix.
 *
 * @param businessTimezone zone used for calendar-date rules such as batch expiry; timestamps are always stored in UTC
 */
@Validated
@ConfigurationProperties(prefix = "wms")
public record WmsProperties(@NotNull ZoneId businessTimezone) {
}
