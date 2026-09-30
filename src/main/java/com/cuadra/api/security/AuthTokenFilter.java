package com.cuadra.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.cuadra.api.tenancy.TenantContext;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** `Authorization: Bearer <sesión>` (usuario) o `Authorization: Device <token>` (teléfono vinculado). */
public class AuthTokenFilter extends OncePerRequestFilter {
    private final AuthStore store;

    public AuthTokenFilter(AuthStore store) {
        this.store = store;
    }

    private static final Pattern BUSINESS_PATH = Pattern.compile("^/api/b/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

    /** El negocio de la petición: el de la ruta `/api/b/{id}` o, para un teléfono, el suyo. Sin uno de los dos, no hay contexto. */
    private static UUID businessOf(Actor actor, HttpServletRequest request) {
        if (actor.isDevice()) return actor.deviceBusinessId();
        Matcher m = BUSINESS_PATH.matcher(request.getRequestURI());
        return m.matches() ? UUID.fromString(m.group(1)) : null;
    }

    private static final Pattern PUSH_PATH = Pattern.compile("^/api/b/[0-9a-fA-F-]{36}/sync/push$");

    private static boolean isDrainPush(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && PUSH_PATH.matcher(request.getRequestURI()).matches();
    }

    private static boolean isRead(String method) {
        return method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            Optional<Actor> actor = Optional.empty();
            if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                actor = store.findUserSession(header.substring(7).trim());
            } else if (header.regionMatches(true, 0, "Device ", 0, 7)) {
                actor = store.findDevice(header.substring(7).trim());
            }
            // "Ver como" es de solo lectura: cualquier cosa que no sea leer se rechaza antes de llegar a un controlador.
            if (actor.isPresent() && actor.get().isViewAs() && !isRead(request.getMethod())) {
                response.setStatus(403);
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"code\":\"VIEW_AS_READ_ONLY\"}");
                return;
            }
            // Teléfono personal de alguien dado de baja: solo puede enviar lo pendiente. Todo lo demás responde un 401 con un código claro para que la app
            // muestre «Tu acceso fue desactivado» (y siga enviando lo que quede).
            if (actor.isPresent() && actor.get().isDraining() && !isDrainPush(request)) {
                response.setStatus(401);
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"code\":\"ACCESS_DISABLED\"}");
                return;
            }
            actor.ifPresent(a -> SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(a, null, List.of())));
            actor.map(a -> businessOf(a, request)).ifPresent(TenantContext::set);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
