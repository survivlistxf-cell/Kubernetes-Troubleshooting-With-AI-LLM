package com.example.config;

import com.example.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
            // Stateless JWT API: no server-side session, CSRF not applicable.
            .csrf(csrf -> csrf.disable())
            // Applies the global corsConfigurationSource bean (allowlist, not *).
            // Controllers no longer carry @CrossOrigin — one source of truth.
            .cors(org.springframework.security.config.Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // NOTE: antMatcher(...) is used instead of plain string patterns. With more than
            // one servlet in the context (e.g. the H2 console's JakartaWebServlet next to
            // Spring MVC's DispatcherServlet), Spring Security cannot tell whether a plain
            // string is an MVC pattern or not and fails at startup with "This method cannot
            // decide whether these patterns are Spring MVC patterns or not". Explicit
            // AntPath matchers are servlet-agnostic and remove the ambiguity.
            .authorizeHttpRequests(auth -> auth
                // CORS pre-flight must not require authentication.
                .requestMatchers(antMatcher(HttpMethod.OPTIONS, "/**")).permitAll()
                // Public endpoints: auth (login/register) and health probe.
                .requestMatchers(antMatcher("/api/auth/**"), antMatcher("/api/health")).permitAll()
                // Everything else requires a valid JWT.
                .anyRequest().authenticated()
            )
            // Return 401 (not the default 403) when authentication is missing/invalid.
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                (request, response, authException) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
