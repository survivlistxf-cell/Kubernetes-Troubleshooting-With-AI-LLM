package com.example.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Static accessor for the authenticated user's id, derived from the JWT
 * ({@code uid} claim) placed in the {@link SecurityContextHolder} by
 * {@link JwtAuthenticationFilter}.
 *
 * <p>Controllers must use {@link #resolve(Long)} instead of trusting a
 * client-supplied {@code userId}: the token value always wins, and a
 * mismatching request is rejected (returns {@code null} → caller responds 403).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** @return the authenticated user's id, or {@code null} when unauthenticated. */
    public static Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) {
            return u.id();
        }
        return null;
    }

    /**
     * Resolves the effective user id for a request.
     *
     * <ul>
     *   <li>Authenticated + no requested id → token id.</li>
     *   <li>Authenticated + requested id equal to token id → token id.</li>
     *   <li>Authenticated + requested id different → {@code null} (reject with 403).</li>
     *   <li>Unauthenticated (no security context, e.g. direct controller calls in tests)
     *       → the requested id, unchanged.</li>
     * </ul>
     */
    public static Long resolve(Long requested) {
        Long tokenId = id();
        if (tokenId == null) {
            return requested;
        }
        if (requested == null || requested.equals(tokenId)) {
            return tokenId;
        }
        return null;
    }

    /** String-friendly variant of {@link #resolve(Long)} for body payloads. */
    public static Long resolve(String requested) {
        Long parsed = null;
        if (requested != null && !requested.isBlank()) {
            try {
                parsed = Long.parseLong(requested.trim());
            } catch (NumberFormatException ignoredFormat) {
                // Non-numeric client value: ignore it and rely on the token.
            }
        }
        return resolve(parsed);
    }
}
