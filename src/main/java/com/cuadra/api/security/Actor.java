package com.cuadra.api.security;

import java.util.UUID;

/**
 * Quién hace la petición: una sesión de usuario (Google) o un teléfono vinculado a un negocio.
 * Con dispositivo, la persona concreta llega en la cabecera X-Member-Id (validada con su PIN en el teléfono).
 */
public record Actor(UUID userId, UUID sessionId, UUID deviceId, UUID deviceBusinessId, UUID viewAsBusinessId) {
    public static Actor user(UUID userId, UUID sessionId) {
        return new Actor(userId, sessionId, null, null, null);
    }

    public static Actor device(UUID deviceId, UUID businessId) {
        return new Actor(null, null, deviceId, businessId, null);
    }

    /** "Ver como" (soporte): sesión corta y de SOLO LECTURA sobre un negocio; nunca sirve para escribir ni para entrar a la consola. */
    public static Actor viewAs(UUID userId, UUID sessionId, UUID businessId) {
        return new Actor(userId, sessionId, null, null, businessId);
    }

    public boolean isViewAs() { return viewAsBusinessId != null; }

    public boolean isDevice() { return deviceId != null; }
    public boolean isUser() { return userId != null; }
}
