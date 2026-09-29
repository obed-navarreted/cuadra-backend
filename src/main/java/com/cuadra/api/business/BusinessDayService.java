package com.cuadra.api.business;

import com.cuadra.api.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Jornada comercial: la fecha cambia a la hora de corte del negocio (por defecto 02:00), no a medianoche,
 * así una venta a la 1:30 a. m. pertenece al día anterior.
 */
@Service
public class BusinessDayService {
    private final JdbcClient jdbc;

    public BusinessDayService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Info(ZoneId zone, LocalTime cutoff) {
        public LocalDate dateOf(Instant instant) {
            return instant.atZone(zone).toLocalDateTime().minusHours(cutoff.getHour()).minusMinutes(cutoff.getMinute()).toLocalDate();
        }

        public Instant startOf(LocalDate date) {
            return date.atTime(cutoff).atZone(zone).toInstant();
        }

        public Instant endOf(LocalDate date) {
            return date.plusDays(1).atTime(cutoff).atZone(zone).toInstant();
        }
    }

    public Info info(UUID businessId) {
        return jdbc.sql("SELECT timezone, day_cutoff FROM business WHERE id = :b").param("b", businessId)
                .query((rs, n) -> new Info(ZoneId.of(rs.getString("timezone")), rs.getObject("day_cutoff", LocalTime.class)))
                .optional().orElseThrow(() -> ApiException.notFound("BUSINESS_NOT_FOUND", "Business not found"));
    }

    /** Devuelve (creándola si hace falta) la jornada a la que pertenece un instante. */
    public UUID idFor(UUID businessId, Instant instant) {
        Info info = info(businessId);
        LocalDate date = info.dateOf(instant);
        jdbc.sql("""
                        INSERT INTO business_day (id, business_id, date, starts_at, ends_at) VALUES (:id, :b, :d, :s, :e)
                        ON CONFLICT (business_id, date) DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("b", businessId).param("d", date)
                .param("s", Timestamp.from(info.startOf(date))).param("e", Timestamp.from(info.endOf(date))).update();
        return jdbc.sql("SELECT id FROM business_day WHERE business_id = :b AND date = :d").param("b", businessId).param("d", date)
                .query(UUID.class).single();
    }
}
