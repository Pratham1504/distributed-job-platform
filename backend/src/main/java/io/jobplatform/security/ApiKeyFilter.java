package io.jobplatform.security;

import io.jobplatform.apikeys.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final ApiKeyService keys;

    public ApiKeyFilter(ApiKeyService keys) { this.keys = keys; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            ProductPrincipal principal = keys.authenticate(request.getHeader("X-API-Key"));
            if (principal != null) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_API_KEY"))));
            }
        }
        chain.doFilter(request, response);
    }
}
