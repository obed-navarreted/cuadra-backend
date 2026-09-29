package com.cuadra.api.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cada minuto: envía lo programado y revisa los avisos que dependen del tiempo. Se apaga con `cuadra.jobs.enabled=false` (pruebas). */
@Component
@ConditionalOnProperty(name = "cuadra.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationJobs {
    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);

    private final ScheduleService schedules;
    private final AutomaticNotifications automatic;

    public NotificationJobs(ScheduleService schedules, AutomaticNotifications automatic) {
        this.schedules = schedules;
        this.automatic = automatic;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void tick() {
        try {
            schedules.runDue();
        } catch (RuntimeException e) {
            log.error("El envío de notificaciones programadas falló", e);
        }
        try {
            automatic.checkAll();
        } catch (RuntimeException e) {
            log.error("Los avisos automáticos fallaron", e);
        }
    }
}
