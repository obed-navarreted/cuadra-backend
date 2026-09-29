package com.cuadra.api.business;

import com.cuadra.api.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Jornada comercial: la fecha cambia a la hora de corte del negocio (por defecto 02:00), no a medianoche,
 * así una venta a la 1:30 a. m. pertenece al día anterior. La zona horaria y el corte son del NEGOCIO (nunca de la persona ni del teléfono)
 * y tienen historial: cambiarlos rige desde una jornada futura y los días ya vividos no se reagrupan.
 */
@Service
public class BusinessDayService {
    public static final LocalDate SINCE_FOREVER = LocalDate.of(1970, 1, 1);

    private final JdbcClient jdbc;

    public BusinessDayService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Una regla: desde la jornada `from` el día empieza a `cutoff` hora local de `zone`. */
    public record Rule(LocalDate from, ZoneId zone, LocalTime cutoff) {
        Instant startOfDay(LocalDate date) {
            return date.atTime(cutoff).atZone(zone).toInstant();
        }

        LocalDate localDate(Instant instant) {
            return instant.atZone(zone).toLocalDateTime().minusHours(cutoff.getHour()).minusMinutes(cutoff.getMinute()).toLocalDate();
        }
    }

    /**
     * Las reglas del negocio en orden. Los días son contiguos por construcción: `endOf(d) == startOf(d + 1)`, y en el cambio de regla el último día
     * viejo termina justo cuando empieza el primero nuevo (puede durar algo menos o más de 24 horas, una sola vez).
     */
    public static final class Info {
        private final List<Rule> rules;

        public Info(List<Rule> rules) {
            if (rules.isEmpty()) throw new IllegalArgumentException("Hace falta al menos una regla");
            this.rules = new ArrayList<>(rules);
            this.rules.sort(Comparator.comparing(Rule::from));
        }

        /** Una sola regla desde siempre (negocio sin historial de cambios). */
        public Info(ZoneId zone, LocalTime cutoff) {
            this(List.of(new Rule(SINCE_FOREVER, zone, cutoff)));
        }

        public List<Rule> rules() { return rules; }

        /** La regla más reciente (puede empezar en el futuro): la zona con que se muestran las horas del negocio. */
        public Rule current() { return rules.get(rules.size() - 1); }

        public ZoneId zone() { return current().zone(); }

        public LocalTime cutoff() { return current().cutoff(); }

        private Rule ruleForDate(LocalDate date) {
            Rule found = rules.get(0);
            for (Rule r : rules) if (!r.from().isAfter(date)) found = r;
            return found;
        }

        public Instant startOf(LocalDate date) {
            return ruleForDate(date).startOfDay(date);
        }

        public Instant endOf(LocalDate date) {
            return startOf(date.plusDays(1));
        }

        public LocalDate dateOf(Instant instant) {
            Rule r = rules.get(0);
            for (Rule x : rules) if (x.from().equals(SINCE_FOREVER) || !x.startOfDay(x.from()).isAfter(instant)) r = x;
            LocalDate d = r.localDate(instant);
            // En el borde de un cambio de regla, ajusta al día cuyos límites contienen el instante.
            while (instant.isBefore(startOf(d))) d = d.minusDays(1);
            while (!instant.isBefore(startOf(d.plusDays(1)))) d = d.plusDays(1);
            return d;
        }

        /** ¿Hay una regla que todavía no rige en `instant`? Devuelve la fecha desde la que regirá. */
        public LocalDate pendingFrom(Instant instant) {
            Rule c = current();
            return c.from().equals(SINCE_FOREVER) || !c.startOfDay(c.from()).isAfter(instant) ? null : c.from();
        }
    }

    public Info info(UUID businessId) {
        List<Rule> rules = jdbc.sql("SELECT effective_from, timezone, day_cutoff FROM business_day_rule WHERE business_id = :b ORDER BY effective_from")
                .param("b", businessId)
                .query((rs, n) -> new Rule(rs.getObject("effective_from", LocalDate.class), ZoneId.of(rs.getString("timezone")), rs.getObject("day_cutoff", LocalTime.class)))
                .list();
        if (rules.isEmpty()) {
            // Negocio anterior a las reglas o sin ellas: se usa lo configurado en el propio negocio.
            return jdbc.sql("SELECT timezone, day_cutoff FROM business WHERE id = :b").param("b", businessId)
                    .query((rs, n) -> new Info(ZoneId.of(rs.getString("timezone")), rs.getObject("day_cutoff", LocalTime.class)))
                    .optional().orElseThrow(() -> ApiException.notFound("BUSINESS_NOT_FOUND", "Business not found"));
        }
        return new Info(rules);
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
