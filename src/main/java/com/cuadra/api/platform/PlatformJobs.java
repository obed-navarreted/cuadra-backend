package com.cuadra.api.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cada minuto: envía los anuncios cuya hora ya llegó; cada hora, el borrado definitivo de negocios eliminados. Se apaga con `cuadra.jobs.enabled=false` (pruebas). */
@Component
@ConditionalOnProperty(name = "cuadra.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformJobs {
    private static final Logger log = LoggerFactory.getLogger(PlatformJobs.class);

    private final AnnouncementService announcements;
    private final com.cuadra.api.business.BusinessPurgeService purge;
    private final java.time.Clock clock;

    public PlatformJobs(AnnouncementService announcements, com.cuadra.api.business.BusinessPurgeService purge, java.time.Clock clock) {
        this.announcements = announcements;
        this.purge = purge;
        this.clock = clock;
    }

    /** Cada hora: borra definitivamente los negocios eliminados hace más de 30 días. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void purgeDeletedBusinesses() {
        try {
            var done = purge.purgeDue(clock.instant());
            if (!done.isEmpty()) log.info("Negocios borrados definitivamente: {}", done);
        } catch (RuntimeException e) {
            log.error("El borrado de negocios eliminados falló", e);
        }
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
