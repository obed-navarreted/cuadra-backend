package com.cuadra.api.support;

/** Envío de correo saliente. La implementación real (API HTTP de un proveedor transaccional) se elige por configuración. */
public interface MailSender {
    void send(String to, String replyTo, String subject, String body);
}
