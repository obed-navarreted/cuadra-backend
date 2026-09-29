package com.cuadra.api.config;

import java.util.List;

/** Acceso de prueba a la comprobación (es privada del paquete). */
public final class ProductionGuardProbe {
    private ProductionGuardProbe() {}

    public static List<String> problems(CuadraProperties props, String env, String dbPassword, boolean rls, boolean rateLimit, List<String> cors) {
        return ProductionGuard.problems(props, env, dbPassword, rls, rateLimit, cors);
    }
}
