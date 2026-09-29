package com.cuadra.api.security;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthStore store, Clock clock,
                                           @Value("${cuadra.rate-limit.enabled:true}") boolean rateLimit,
                                           @Value("${cuadra.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
                                           @Value("${cuadra.rate-limit.auth-per-minute:20}") int authPerMinute,
                                           @Value("${cuadra.rate-limit.invitation-per-minute:30}") int invitationPerMinute,
                                           @Value("${cuadra.rate-limit.link-create-per-minute:10}") int linkCreatePerMinute,
                                           @Value("${cuadra.rate-limit.link-poll-per-minute:90}") int linkPollPerMinute,
                                           @Value("${cuadra.rate-limit.credential-per-minute:900}") int credentialPerMinute,
                                           @Value("${cuadra.max-request-bytes:2097152}") long maxRequestBytes,
                                           @Value("${cuadra.cors.allowed-origins:${CUADRA_CORS_ORIGINS:}}") java.util.List<String> corsOrigins) throws Exception {
        // El panel web puede servirse desde OTRO origen (p. ej. GitHub Pages): solo esos orígenes exactos, sin cookies (la sesión va en `Authorization`).
        http.cors(c -> c.configurationSource(request -> {
                    org.springframework.web.cors.CorsConfiguration cfg = new org.springframework.web.cors.CorsConfiguration();
                    cfg.setAllowedOrigins(corsOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList());
                    cfg.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                    cfg.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "X-Member-Id", "Accept", "Accept-Language"));
                    cfg.setExposedHeaders(java.util.List.of("Content-Disposition", "Retry-After"));
                    cfg.setAllowCredentials(false);
                    cfg.setMaxAge(3600L);
                    return cfg;
                }))
                .csrf(c -> c.disable())
                // La API solo devuelve JSON: nada de esto se debe poder incrustar, interpretar ni filtrar por el navegador.
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(f -> f.deny())
                        .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/google", "/api/auth/platform").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/config").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/invitations/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/devices/link-requests").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/devices/link-requests/*").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, 401, "UNAUTHENTICATED", "Unauthorized"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403, "FORBIDDEN", "Forbidden")))
                // No es un @Bean: así no se registra también como filtro de servlet.
                .addFilterBefore(new AuthTokenFilter(store), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new RequestSizeFilter(maxRequestBytes), AuthTokenFilter.class);
        if (rateLimit) {
            http.addFilterBefore(new RateLimitFilter(clock, new RateLimitFilter.Limits(authPerMinute, invitationPerMinute, linkCreatePerMinute, linkPollPerMinute, credentialPerMinute), trustForwardedFor), RequestSizeFilter.class);
        }
        return http.build();
    }

    private static void problem(HttpServletResponse res, int status, String code, String title) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\"}");
    }
}
