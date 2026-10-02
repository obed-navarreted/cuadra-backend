package com.cuadra.api.push;

import java.util.Map;

/**
 * Envía UN mensaje de datos a UN token de Firebase Cloud Messaging (HTTP v1). La implementación real es `HttpFcmTransport`; las pruebas usan una que solo
 * anota. Solo mensajes de DATOS (sin bloque `notification`): la app decide qué mostrar, en su idioma y con las preferencias de la persona.
 */
public interface FcmTransport {
    enum Outcome { SENT, UNREGISTERED, FAILED }

    /** ¿Hay cuenta de servicio configurada? Si no, nada se envía y todo sigue funcionando con la sincronización frecuente. */
    boolean enabled();

    Outcome send(String token, Map<String, String> data);
}
