package com.example.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Test-only filter that can slow API requests enough for ingress timeout drills.
 *
 * <p>It skips /api/health so readiness and liveness probes remain stable.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngressDelayFilter extends OncePerRequestFilter {

    private static final String HEALTH_PATH = "/api/health";

    @Value("${demo.api-delay-ms:0}")
    private long delayMs;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return delayMs <= 0 || path == null || !path.startsWith("/api/") || path.startsWith(HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        filterChain.doFilter(request, response);
    }
}