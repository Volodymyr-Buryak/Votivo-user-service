package com.votivo.user_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces local account revocation and prevents a bound token's Bearer downgrade. */
@RequiredArgsConstructor
public final class AccountAccessFilter extends OncePerRequestFilter {
    private final AccountStatus accountStatus;
    private final SecurityProblems problems;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String authorization = request.getHeader("Authorization");
            if (jwt.getToken().hasClaim("cnf")
                    && (authorization == null || !authorization.regionMatches(true, 0, "DPoP ", 0, 5))) {
                SecurityContextHolder.clearContext();
                problems.write(response, HttpStatus.UNAUTHORIZED);
                return;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean allowed;
            try {
                allowed = accountStatus.allows(UUID.fromString(jwt.getToken().getSubject()),
                        path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/"), jwt.getToken().getIssuedAt());
            } catch (DataAccessException | TransactionException exception) {
                problems.write(response, HttpStatus.SERVICE_UNAVAILABLE);
                return;
            }
            if (!allowed) {
                response.setHeader("X-Account-Access", "denied");
                problems.write(response, HttpStatus.FORBIDDEN);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
