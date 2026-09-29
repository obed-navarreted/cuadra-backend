package com.cuadra.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Límite de peticiones por minuto (ventana fija), en memoria.
 * <ul>
 *   <li>Endpoints PÚBLICOS (login, invitaciones, vinculación de teléfonos) por IP: frenan la adivinación de códigos y el relleno de la base.</li>
 *   <li>Todo lo demás por credencial (token): un token robado o un cliente con un ciclo loco no puede tumbar el servidor. El tope es holgado:
 *       un teléfono real sincroniza unas pocas veces por minuto.</li>
 * </ul>
 * En memoria significa "por instancia": con varias instancias el límite efectivo es N veces mayor, aceptable para frenar abuso (no es un control de facturación).
 * Detrás de un proxy, `trustForwardedFor` toma la ÚLTIMA dirección de `X-Forwarded-For` (la que añadió NUESTRO proxy; las anteriores las escribe el cliente).
 */
public class RateLimitFilter extends OncePerRequestFilter {
    public record Limits(int authPerMinute, int invitationPerMinute, int linkCreatePerMinute, int linkPollPerMinute, int credentialPerMinute) {}

    private final Clock clock;
    private final Limits limits;
    private final boolean trustForwardedFor;
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private volatile long window = -1;

    public RateLimitFilter(Clock clock, Limits limits, boolean trustForwardedFor) {
        this.clock = clock;
        this.limits = limits;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String bucket;
        int limit;
        if (method.equals("POST") && path.equals("/api/auth/platform")) { bucket = "platform-login"; limit = Math.min(5, limits.authPerMinute()); }
        else if (method.equals("POST") && path.equals("/api/auth/google")) { bucket = "auth"; limit = limits.authPerMinute(); }
        else if (method.equals("GET") && path.startsWith("/api/invitations/")) { bucket = "invitation"; limit = limits.invitationPerMinute(); }
        else if (method.equals("POST") && path.equals("/api/devices/link-requests")) { bucket = "link-create"; limit = limits.linkCreatePerMinute(); }
        else if (method.equals("GET") && path.startsWith("/api/devices/link-requests/")) { bucket = "link-poll"; limit = limits.linkPollPerMinute(); }
        else if (request.getHeader("Authorization") != null) { bucket = "cred:" + request.getHeader("Authorization").hashCode(); limit = limits.credentialPerMinute(); }
        else { chain.doFilter(request, response); return; }
        String key = bucket.startsWith("cred:") ? bucket : bucket + "|" + clientIp(request);

        long now = clock.millis() / 60_000;
        if (now != window) {
            synchronized (this) {
                if (now != window) { counters.clear(); window = now; }
            }
        }
        int used = counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (used > limit) {
            long retry = 60 - (clock.millis() / 1000 % 60);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retry));
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"code\":\"RATE_LIMITED\",\"retryAfterSeconds\":" + retry + "}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                return parts[parts.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
