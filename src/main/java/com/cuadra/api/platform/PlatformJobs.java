package com.cuadra.api.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cada minuto: envía los anuncios cuya hora ya llegó. Se apaga con `cuadra.jobs.enabled=false` (pruebas). */
@Component
@ConditionalOnProperty(name = "cuadra.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformJobs {
    private static final Logger log = LoggerFactory.getLogger(PlatformJobs.class);

    private final AnnouncementService announcements;

    public PlatformJobs(AnnouncementService announcements) {
        this.announcements = announcements;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        try {
            announcements.runDue();
        } catch (RuntimeException e) {
            log.error("El envío de anuncios programados falló", e);
        }
    }
}
