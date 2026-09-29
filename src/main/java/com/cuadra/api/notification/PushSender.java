package com.cuadra.api.notification;

import java.util.List;
import java.util.UUID;

/**
 * Envío inmediato al teléfono (FCM). La bandeja no depende de esto: si no hay proveedor configurado, el aviso igual se ve dentro de la app
 * en la siguiente sincronización. La implementación con Firebase se añade cuando exista el proyecto (ver PENDIENTES.md).
 */
public interface PushSender {
    record Message(UUID notificationId, UUID memberId, UUID deviceId, String type, String title, String body, String deepLink, boolean alert) {}

    void send(List<Message> messages);
}
