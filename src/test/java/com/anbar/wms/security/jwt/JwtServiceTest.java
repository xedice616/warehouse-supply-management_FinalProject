package com.anbar.wms.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.security.config.WmsSecurityProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("unit-test-secret-that-is-long-enough-for-hs256!!".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_SECRET = Base64.getEncoder()
            .encodeToString("another-secret-that-is-also-long-enough-for-hs256".getBytes(StandardCharsets.UTF_8));
    private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");

    @Test
    void issuedTokenRoundTrips() {
        JwtService service = service(SECRET, "wms", "wms-api", T0);

        AccessToken token = service.issue(42, "aysel", List.of("WAREHOUSE_MANAGER", "ADMIN"));
        TokenClaims claims = service.parse(token.value());

        assertThat(claims.userId()).isEqualTo(42);
        assertThat(claims.username()).isEqualTo("aysel");
        assertThat(claims.roles()).containsExactly("ADMIN", "WAREHOUSE_MANAGER");
        assertThat(claims.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(15))).isEqualTo(token.expiresAt());
    }

    @Test
    void expiredTokenIsReportedAsExpired() {
        String token = service(SECRET, "wms", "wms-api", T0).issue(1, "u", List.of()).value();
        JwtService later = service(SECRET, "wms", "wms-api", T0.plus(Duration.ofMinutes(16)));

        assertRejected(() -> later.parse(token), ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void smallClockSkewIsTolerated() {
        String token = service(SECRET, "wms", "wms-api", T0).issue(1, "u", List.of()).value();
        JwtService slightlyLate = service(SECRET, "wms", "wms-api", T0.plus(Duration.ofMinutes(15).plusSeconds(20)));

        assertThat(slightlyLate.parse(token).userId()).isEqualTo(1);
    }

    @Test
    void tamperedPayloadIsInvalid() {
        JwtService service = service(SECRET, "wms", "wms-api", T0);
        String[] parts = service.issue(1, "user", List.of("USER")).value().split("\\.");
        String forgedPayload = base64Url("{\"sub\":\"1\",\"username\":\"user\",\"roles\":[\"ADMIN\"],"
                + "\"iss\":\"wms\",\"aud\":\"wms-api\",\"exp\":" + T0.plusSeconds(600).getEpochSecond() + "}");

        assertRejected(() -> service.parse(parts[0] + "." + forgedPayload + "." + parts[2]), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void tokenSignedWithAnotherKeyIsInvalid() {
        String foreign = service(OTHER_SECRET, "wms", "wms-api", T0).issue(1, "u", List.of()).value();

        assertRejected(() -> service(SECRET, "wms", "wms-api", T0).parse(foreign), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void wrongIssuerOrAudienceIsInvalid() {
        String otherIssuer = service(SECRET, "someone-else", "wms-api", T0).issue(1, "u", List.of()).value();
        String otherAudience = service(SECRET, "wms", "another-api", T0).issue(1, "u", List.of()).value();
        JwtService service = service(SECRET, "wms", "wms-api", T0);

        assertRejected(() -> service.parse(otherIssuer), ErrorCode.INVALID_TOKEN);
        assertRejected(() -> service.parse(otherAudience), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void unsignedAlgNoneTokenIsInvalid() {
        String unsigned = base64Url("{\"alg\":\"none\"}") + "."
                + base64Url("{\"sub\":\"1\",\"iss\":\"wms\",\"aud\":\"wms-api\",\"exp\":"
                + T0.plusSeconds(600).getEpochSecond() + "}") + ".";

        assertRejected(() -> service(SECRET, "wms", "wms-api", T0).parse(unsigned), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void garbageIsInvalid() {
        JwtService service = service(SECRET, "wms", "wms-api", T0);

        assertRejected(() -> service.parse("not-a-jwt"), ErrorCode.INVALID_TOKEN);
        assertRejected(() -> service.parse(""), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void weakOrMalformedSecretFailsFast() {
        String shortSecret = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service(shortSecret, "wms", "wms-api", T0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("256 bits");
        assertThatThrownBy(() -> service("***not base64***", "wms", "wms-api", T0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Base64");
    }

    private static JwtService service(String secret, String issuer, String audience, Instant now) {
        WmsSecurityProperties properties = new WmsSecurityProperties(
                new WmsSecurityProperties.Jwt(secret, issuer, audience, Duration.ofMinutes(15), Duration.ofSeconds(30)),
                4,
                new WmsSecurityProperties.Lockout(5, Duration.ofMinutes(15)),
                new WmsSecurityProperties.BootstrapAdmin(null, null, null));
        return new JwtService(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRejected(Runnable parse, ErrorCode expected) {
        assertThatThrownBy(parse::run)
                .isInstanceOfSatisfying(InvalidTokenException.class, ex -> assertThat(ex.getCode()).isEqualTo(expected));
    }
}
