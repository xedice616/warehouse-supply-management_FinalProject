package com.anbar.wms.security.jwt;

import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.security.config.WmsSecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies HMAC-SHA256 signed access tokens (JJWT).
 *
 * <p>Claims: {@code sub} = user id, {@code username}, {@code roles}, {@code iss}, {@code aud}, {@code iat},
 * {@code exp} and a random {@code jti}. Verification requires a valid signature with our key, the expected issuer
 * and audience, and an unexpired token (with a small clock-skew allowance). Unsigned ({@code alg: none}) and
 * otherwise-signed tokens are rejected by the parser.
 */
@Component
public class JwtService {

    static final String CLAIM_USERNAME = "username";
    static final String CLAIM_ROLES = "roles";

    private static final int MIN_KEY_BYTES = 32; // 256 bits, required for HS256

    private final SecretKey key;
    private final WmsSecurityProperties.Jwt settings;
    private final Clock clock;
    private final JwtParser parser;

    public JwtService(WmsSecurityProperties properties, Clock clock) {
        this.settings = properties.jwt();
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(decodeSecret(settings.secret()));
        this.parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(settings.issuer())
                .requireAudience(settings.audience())
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds(settings.clockSkew().toSeconds())
                .build();
    }

    public AccessToken issue(long userId, String username, Collection<String> roles) {
        // JWT timestamps have second precision; truncate so expiresAt matches the exp claim exactly.
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(settings.accessTokenTtl());
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(settings.issuer())
                .audience().add(settings.audience()).and()
                .subject(Long.toString(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLES, roles.stream().sorted().toList())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new AccessToken(token, expiresAt);
    }

    /**
     * @throws InvalidTokenException with {@link ErrorCode#TOKEN_EXPIRED} for an expired but otherwise valid token,
     *                               {@link ErrorCode#INVALID_TOKEN} for anything else wrong with it
     */
    public TokenClaims parse(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            long userId = Long.parseLong(claims.getSubject());
            List<?> roles = claims.get(CLAIM_ROLES, List.class);
            return new TokenClaims(
                    userId,
                    claims.get(CLAIM_USERNAME, String.class),
                    roles == null ? List.of() : roles.stream().map(String::valueOf).toList(),
                    claims.getExpiration().toInstant());
        } catch (ExpiredJwtException ex) {
            throw new InvalidTokenException(ErrorCode.TOKEN_EXPIRED, ex);
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            // IllegalArgumentException: blank token or non-numeric subject; NPE: missing subject/expiration
            throw new InvalidTokenException(ErrorCode.INVALID_TOKEN, ex);
        }
    }

    public long accessTokenTtlSeconds() {
        return settings.accessTokenTtl().toSeconds();
    }

    private static byte[] decodeSecret(String secret) {
        byte[] bytes;
        try {
            bytes = Decoders.BASE64.decode(secret);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("wms.security.jwt.secret (WMS_JWT_SECRET) must be Base64-encoded", ex);
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "wms.security.jwt.secret (WMS_JWT_SECRET) must decode to at least 256 bits (32 bytes); "
                            + "generate one with: openssl rand -base64 48");
        }
        return bytes;
    }
}
