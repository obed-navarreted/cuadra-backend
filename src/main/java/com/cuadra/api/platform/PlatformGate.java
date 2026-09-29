package com.cuadra.api.platform;

import com.cuadra.api.security.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * La puerta de TODA la consola, antes de leer el cuerpo de la petición: sin esto, un cuerpo inválido daba 400 a cualquiera y así revelaba que la ruta
 * existe (lo encontró `AuthzSweepTest`). Para quien no es admin de plataforma, todo `/api/platform/**` responde 404, sea cual sea la petición.
 */
@Configuration
public class PlatformGate implements WebMvcConfigurer {
    private final PlatformAccess access;

    public PlatformGate(PlatformAccess access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                access.requireAdmin(auth != null && auth.getPrincipal() instanceof Actor a ? a : null);
                return true;
            }
        }).addPathPatterns("/api/platform/**");
    }
}
