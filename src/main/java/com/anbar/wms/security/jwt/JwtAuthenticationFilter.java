package com.anbar.wms.security.jwt;

import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.security.AuthenticatedUserResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <token>}.
 *
 * <ul>
 *   <li>No bearer header: the request continues anonymously; protected endpoints then answer 401.</li>
 *   <li>Valid token of an active user: the security context gets an authenticated {@link AuthenticatedUser}
 *       with the user's <em>current</em> roles from the database.</li>
 *   <li>Expired or invalid token, or a disabled/deleted user: the request continues anonymously and the reason is
 *       stored in {@link #TOKEN_ERROR_ATTRIBUTE}, so the entry point can answer {@code TOKEN_EXPIRED} or
 *       {@code INVALID_TOKEN} instead of a generic 401. Public endpoints still work.</li>
 * </ul>
 *
 * <p>Deliberately not a Spring bean: it is added only to the security filter chain, never registered a second time
 * as a plain servlet filter.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".TOKEN_ERROR";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AuthenticatedUserResolver userResolver;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    public JwtAuthenticationFilter(JwtService jwtService, AuthenticatedUserResolver userResolver) {
        this.jwtService = jwtService;
        this.userResolver = userResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            authenticate(header.substring(BEARER_PREFIX.length()).trim(), request);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            TokenClaims claims = jwtService.parse(token);
            Optional<AuthenticatedUser> user = userResolver.resolve(claims.userId());
            if (user.isEmpty()) {
                // Signed by us, but the account is disabled or gone: the token must stop working now.
                request.setAttribute(TOKEN_ERROR_ATTRIBUTE, ErrorCode.INVALID_TOKEN);
                return;
            }
            UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                    user.get(), null, user.get().authorities());
            authentication.setDetails(detailsSource.buildDetails(request));
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            contextHolder.setContext(context);
        } catch (InvalidTokenException ex) {
            request.setAttribute(TOKEN_ERROR_ATTRIBUTE, ex.getCode());
        }
    }
}
