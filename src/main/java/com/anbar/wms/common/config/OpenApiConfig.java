package com.anbar.wms.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document served at {@code /v3/api-docs} with Swagger UI at {@code /swagger-ui.html} (disabled in prod).
 * Declares the JWT bearer scheme used by every endpoint from stage 2 on.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI wmsOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        String version = buildProperties.stream().map(BuildProperties::getVersion).findFirst().orElse("dev");
        return new OpenAPI()
                .info(new Info()
                        .title("Warehouse & Supply Management API")
                        .version(version)
                        .description("""
                                Products, warehouses, stock movements, suppliers, purchase orders, \
                                inventory counts and reports.

                                Errors are returned as RFC 9457 `application/problem+json` with a stable \
                                `code` field. Stock is never edited directly: every change is an immutable \
                                stock movement."""))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
