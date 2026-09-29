package com.cuadra.api.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MailConfig {
    private static final Logger log = LoggerFactory.getLogger("cuadra.mail");

    /**
     * Por defecto solo registra el correo en el log: el ticket queda igualmente guardado en la base y visible
     * en la consola. Al elegir proveedor (decisión P9 del plan) se añade un bean MailSender real.
     */
    @Bean
    @ConditionalOnMissingBean(MailSender.class)
    public MailSender loggingMailSender() {
        return (to, replyTo, subject, body) -> log.info("[correo no enviado: sin proveedor] to={} replyTo={} subject={}", to, replyTo, subject);
    }
}
