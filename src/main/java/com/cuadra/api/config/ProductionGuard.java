package com.cuadra.api.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Con `CUADRA_ENV=prod` la API se niega a arrancar con valores de desarrollo: contraseña de base por defecto, sin ID de cliente de Google
 * (nadie podría entrar), URL local o el aislamiento por negocio apagado. Un despliegue a medio configurar debe fallar fuerte al arrancar, no en silencio.
 */
@Configuration
public class ProductionGuard {
    @Bean
    ApplicationRunner productionChecks(CuadraProperties props,
                                       @Value("${cuadra.env:dev}") String env,
                                       @Value("${spring.datasource.password:}") String dbPassword,
                                       @Value("${cuadra.rls.enabled:true}") boolean rls,
                                       @Value("${cuadra.rate-limit.enabled:true}") boolean rateLimit,
                                       @Value("${cuadra.cors.allowed-origins:}") List<String> corsOrigins) {
        return args -> {
            List<String> problems = problems(props, env, dbPassword, rls, rateLimit, corsOrigins);
            if (!problems.isEmpty()) throw new IllegalStateException("Configuración de producción incompleta: " + String.join("; ", problems));
        };
    }

    static List<String> problems(CuadraProperties props, String env, String dbPassword, boolean rls, boolean rateLimit, List<String> corsOrigins) {
        List<String> out = new ArrayList<>();
        if (!"prod".equalsIgnoreCase(env)) return out;
        if (dbPassword == null || dbPassword.isBlank() || dbPassword.equals("cuadra")) out.add("la contraseña de la base es la de desarrollo (DB_PASSWORD / PGPASSWORD)");
        if (props.google().clientIds().isEmpty()) out.add("falta GOOGLE_CLIENT_IDS (nadie podría iniciar sesión)");
        if (props.app().baseUrl() == null || props.app().baseUrl().contains("localhost")) out.add("APP_BASE_URL apunta a localhost");
        if (props.app().baseUrl() != null && !props.app().baseUrl().startsWith("https://")) out.add("APP_BASE_URL debe ser https");
        if (!rls) out.add("cuadra.rls.enabled=false (el aislamiento por negocio debe estar activo)");
        if (!rateLimit) out.add("cuadra.rate-limit.enabled=false");
        List<String> origins = corsOrigins == null ? List.of() : corsOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (origins.isEmpty() || origins.stream().anyMatch(o -> o.contains("localhost") || o.contains("127.0.0.1") || !o.startsWith("https://"))) {
            out.add("CUADRA_CORS_ORIGINS debe listar solo orígenes https reales del panel (sin localhost)");
        }
        return out;
    }
}
