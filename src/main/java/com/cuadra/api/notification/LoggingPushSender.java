package com.cuadra.api.notification;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Proveedor por defecto: no envía nada fuera; deja constancia. Se sustituye al configurar FCM. */
@Component
@ConditionalOnMissingBean(value = PushSender.class, ignored = LoggingPushSender.class)
public class LoggingPushSender implements PushSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void send(List<Message> messages) {
        if (!messages.isEmpty()) log.debug("Sin proveedor de push configurado: {} aviso(s) quedan en la bandeja", messages.size());
    }
}
