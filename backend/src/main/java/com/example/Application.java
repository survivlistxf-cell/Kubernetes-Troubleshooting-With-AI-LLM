package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Arrays;

// Exclude UserDetailsServiceAutoConfiguration so Spring Security does NOT auto-generate
// a default user/password on every boot. Our SecurityFilterChain (see SecurityConfig)
// already permits all requests, and authentication is handled by our own AuthController
// + JWT setup — no Spring-managed user store is needed.
@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableScheduling
@ComponentScan(basePackages = {"com.example", "com.example.controllers", "com.example.services"})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public static WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * CORS allowlist. In productie browserul vorbeste oricum same-origin cu proxy-ul
     * Express al frontend-ului, deci lista e relevanta doar pentru dezvoltare locala
     * (frontend pe :3000 care loveste direct backend-ul pe :8080). Extensibila prin
     * CORS_ALLOWED_ORIGINS (lista separata prin virgula), fara wildcard.
     */
    @Bean
    public static CorsConfigurationSource corsConfigurationSource(
            @Value("${security.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.trim().split("\\s*,\\s*")));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

@RestController
class ApiController {

    @GetMapping("/api/hello")
    public String hello() {
        return "Hello from Spring Boot Backend!";
    }

    @GetMapping("/api/status")
    public String status() {
        return "Backend is running!";
    }
}
