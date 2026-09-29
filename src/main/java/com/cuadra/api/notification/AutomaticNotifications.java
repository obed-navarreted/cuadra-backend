package com.cuadra.api.notification;

import com.cuadra.api.business.BusinessDayService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Avisos que dependen del paso del tiempo y no de una acción: turno sin cerrar, teléfono sin sincronizar, resumen del día.
 * Cada aviso lleva una clave de repetición (por turno o teléfono y día): correr el job muchas veces no repite el aviso.
 */
@Component
public class AutomaticNotifications {
    private static final Logger log = LoggerFactory.getLogger(AutomaticNotifications.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final NotificationService notifications;
    private final BusinessDayService days;

    public AutomaticNotifications(JdbcClient jdbc, Clock clock, NotificationService notifications, BusinessDayService days) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.notifications = notifications;
        this.days = days;
    }

    public void checkAll() {
        for (UUID business : jdbc.sql("SELECT id FROM business WHERE status = 'ACTIVE'").query(UUID.class).list()) {
            try {
                check(business);
            } catch (RuntimeException e) {
                log.error("Los avisos automáticos del negocio {} fallaron", business, e);
            }
        }
    }

    public void check(UUID businessId) {
        Instant now = clock.instant();
        ZoneId zone = ZoneId.of(jdbc.sql("SELECT timezone FROM business WHERE id = :b").param("b", businessId).query(String.class).single());
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalTime local = now.atZone(zone).toLocalTime();
        NotificationService.Settings s = notifications.settings(businessId);

        if (s.shiftReminderTime() != null && !local.isBefore(LocalTime.parse(s.shiftReminderTime()))) {
            for (Object[] shift : openShifts(businessId)) {
                notifications.notify(businessId, NotificationService.Type.SHIFT_NOT_CLOSED, Map.of("shiftId", shift[0].toString(), "memberName", shift[2]), List.of((UUID) shift[1]), null,
                        "SHIFT_NOT_CLOSED:" + shift[0] + ":" + today, "cuadra://cierre/" + shift[0]);
            }
        }

        Instant staleBefore = now.minus(Duration.ofHours(s.staleHours()));
        var stale = jdbc.sql("SELECT id, name, pending_ops FROM device WHERE business_id = :b AND revoked_at IS NULL AND pending_ops > 0 AND COALESCE(last_sync_at, linked_at) < :t")
                .param("b", businessId).param("t", Timestamp.from(staleBefore)).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)}).list();
        for (Object[] d : stale) {
            notifications.notify(businessId, NotificationService.Type.DEVICE_STALE, Map.of("deviceId", d[0].toString(), "deviceName", d[1], "pending", d[2]), null, null,
                    "DEVICE_STALE:" + d[0] + ":" + today, null);
        }

        if (s.summaryEnabled() && !local.isBefore(LocalTime.parse(s.summaryTime()))) summary(businessId, now, today);
    }

    private List<Object[]> openShifts(UUID businessId) {
        return jdbc.sql("SELECT s.id, s.opened_by_member_id, m.display_name FROM shift s JOIN member m ON m.id = s.opened_by_member_id WHERE s.business_id = :b AND s.status = 'OPEN'")
                .param("b", businessId).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)}).list();
    }

    private void summary(UUID businessId, Instant now, LocalDate localToday) {
        BusinessDayService.Info info = days.info(businessId);
        LocalDate day = info.dateOf(now);
        Timestamp from = Timestamp.from(info.startOf(day));
        Timestamp to = Timestamp.from(info.endOf(day));
        var sales = jdbc.sql("SELECT count(*), coalesce(sum(total_minor), 0) FROM sale WHERE business_id = :b AND status = 'COMPLETED' AND completed_at >= :f AND completed_at < :t")
                .param("b", businessId).param("f", from).param("t", to).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        long expenses = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM expense WHERE business_id = :b AND voided_at IS NULL AND occurred_at >= :f AND occurred_at < :t")
                .param("b", businessId).param("f", from).param("t", to).query(Long.class).single();
        notifications.notify(businessId, NotificationService.Type.DAILY_SUMMARY, Map.of("salesCount", sales[0], "totalMinor", sales[1], "expensesMinor", expenses), null, null, "DAILY_SUMMARY:" + day, "cuadra://notificaciones");
    }
}
