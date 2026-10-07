package com.autox.config;

import com.autox.api.error.SecurityProblemHandlers;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless, header-based security. No sessions, no cookies, no CSRF tokens — the
 * API must behave identically for the Angular app and for a native mobile client,
 * neither of which can rely on a browser session.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * BCrypt at cost 12: roughly 250 ms per hash on current hardware, which makes
     * offline cracking expensive while keeping login responsive. The cost is stored
     * in the hash, so raising it later leaves existing hashes verifiable.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    SecurityProblemHandlers problemHandlers,
                                    CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Obtaining or renewing credentials cannot itself require credentials.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        // The seller's own views stay private. Declared before the public
                        // rule below, which would otherwise match /listings/mine as an id.
                        .requestMatchers(HttpMethod.GET, "/api/v1/listings/mine", "/api/v1/listings/mine/**")
                            .authenticated()
                        // The shop window: searchable and linkable without an account.
                        .requestMatchers(HttpMethod.GET, "/api/v1/listings", "/api/v1/listings/*").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers));

        return http.build();
    }

    /**
     * Maps the token's {@code role} claim onto a single {@code ROLE_*} authority, so
     * {@code hasRole("ADMIN")} and {@code @PreAuthorize} work as usual. The role
     * travels in the token rather than being loaded per request — that is the point
     * of a stateless API, and the 15-minute access-token lifetime bounds how long a
     * role change takes to apply.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtClaims.ROLE);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Browser clients only. A native app is not subject to CORS, so this list is
     * about the Angular dev server and the deployed web origin, nothing else.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${AUTOX_CORS_ALLOWED_ORIGINS:http://localhost:4200}") List<String> allowedOrigins) {

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept-Language"));
        configuration.setExposedHeaders(List.of("Location"));
        // No cookies are used, so credentialed requests are not needed.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
