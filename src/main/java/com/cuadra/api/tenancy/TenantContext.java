package com.cuadra.api.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * El negocio al que pertenece la petición en curso. Lo fija el filtro de autenticación (ruta `/api/b/{negocio}/…` o teléfono vinculado) y
 * `TenantDataSource` lo lleva a la base: con contexto, la conexión queda sujeta a las políticas de aislamiento (RLS, migración V8).
 * Sin contexto (inicio de sesión, consola, trabajos programados) no hay restricción: esos flujos cruzan negocios a propósito.
 */
public final class TenantContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<UUID> current() { return Optional.ofNullable(CURRENT.get()); }

    public static void set(UUID businessId) { CURRENT.set(businessId); }

    public static void clear() { CURRENT.remove(); }

    /** Ejecuta con un negocio fijado y deja el contexto como estaba. */
    public static <T> T call(UUID businessId, Supplier<T> action) {
        UUID before = CURRENT.get();
        CURRENT.set(businessId);
        try {
            return action.get();
        } finally {
            if (before == null) CURRENT.remove(); else CURRENT.set(before);
        }
    }
}
