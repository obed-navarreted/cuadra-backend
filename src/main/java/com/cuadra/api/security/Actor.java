package com.cuadra.api.security;

import java.util.UUID;

/**
 * Quién hace la petición: una sesión de usuario (Google) o un teléfono vinculado a un negocio.
 * Con dispositivo, la persona concreta llega en la cabecera X-Member-Id (validada con su PIN en el teléfono).
 */
public record Actor(UUID userId, UUID sessionId, UUID deviceId, UUID deviceBusinessId, UUID viewAsBusinessId, String deviceTrust, java.time.Instant drainUntil) {
    public static Actor user(UUID userId, UUID sessionId) {
        return new Actor(userId, sessionId, null, null, null, null, null);
    }

    /** `trustRole`: el rol MÁXIMO con el que este teléfono puede actuar (el de quien lo vinculó). */
    public static Actor device(UUID deviceId, UUID businessId, String trustRole) {
        return new Actor(null, null, deviceId, businessId, null, trustRole, null);
    }

    /** "Ver como" (soporte): sesión corta y de SOLO LECTURA sobre un negocio; nunca sirve para escribir ni para entrar a la consola. */
    public static Actor viewAs(UUID userId, UUID sessionId, UUID businessId) {
        return new Actor(userId, sessionId, null, null, businessId, null, null);
    }

    /**
     * El teléfono PERSONAL de alguien que el dueño dio de baja: quedó revocado, pero puede terminar de ENVIAR (solo `sync/push`) lo que esa persona hizo
     * sin conexión antes de `drainUntil` (la hora de la baja). Nada más: ni bajar datos ni otra ruta.
     */
    public static Actor draining(UUID deviceId, UUID businessId, String trustRole, java.time.Instant revokedAt) {
        return new Actor(null, null, deviceId, businessId, null, trustRole, revokedAt);
    }

    public boolean isDraining() { return drainUntil != null; }

    public boolean isViewAs() { return viewAsBusinessId != null; }

    public boolean isDevice() { return deviceId != null; }
    public boolean isUser() { return userId != null; }
}
