package com.cuadra.api.plan;

import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.tenancy.MemberContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Planes y límites (PLAN.md 10). Regla de oro: **lo que un negocio necesita para operar nunca se limita** (vender, cobrar, fiar, gastos, inventario, ver su día).
 * Los límites solo impiden AGREGAR más de la cuenta (miembros, teléfonos, programaciones, otro negocio) o usar comodidades de Pro (historial largo de
 * reportes, exportar); si un negocio bajó de plan con más miembros que el límite, los existentes siguen funcionando. Nada se borra ni se oculta.
 */
@Service
public class PlanService {
    public enum PlanCode { FREE, PRO }

    public enum Feature { MEMBERS, DEVICES, SCHEDULES, REPORT_HISTORY, EXPORT, BUSINESSES }

    /** -1 = sin límite. `webSections`: secciones del panel web disponibles. */
    public record Limits(int members, int devices, int schedules, int reportHistoryDays, boolean export, boolean multipleBusinesses, List<String> webSections) {}

    public record Usage(int members, int devices, int schedules) {}

    public record PlanView(String plan, String status, Instant trialEndsAt, Instant currentPeriodEnd, boolean trialing, int trialDaysLeft, Limits limits, Usage usage) {}

    private static final Limits FREE = new Limits(3, 2, 3, 30, false, false, List.of("resumen", "fiados", "ajustes", "ayuda"));
    /** "Ilimitado (uso justo)" es un tope alto, no infinito. */
    private static final Limits PRO = new Limits(100, 10, 50, -1, true, true,
            List.of("resumen", "ventas", "fiados", "gastos", "inventario", "cierres", "reportes", "equipo", "avisos", "ajustes", "ayuda"));
    static final Duration PAST_DUE_GRACE = Duration.ofDays(7);
    static final int TRIAL_DAYS = 30;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final BusinessDayService days;

    public PlanService(JdbcClient jdbc, Clock clock, BusinessDayService days) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.days = days;
    }

    private record Row(String plan, String status, Instant trialEndsAt, Instant periodEnd) {}

    private Optional<Row> row(UUID businessId) {
        return jdbc.sql("SELECT plan_code, status, trial_ends_at, current_period_end FROM subscription WHERE business_id = :b").param("b", businessId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), instant(rs.getTimestamp(3)), instant(rs.getTimestamp(4)))).optional();
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }

    /**
     * El plan que rige HOY. Prueba vigente → Pro; prueba vencida → Gratis; Pro pagado/manual → Pro mientras su periodo no venza (sin fecha = sin vencimiento);
     * pago atrasado → Pro con 7 días de gracia; cancelado → Pro hasta el fin del periodo ya pagado.
     */
    public PlanCode effective(UUID businessId) {
        Instant now = clock.instant();
        Row r = row(businessId).orElse(null);
        if (r == null || !"PRO".equals(r.plan)) return PlanCode.FREE;
        return switch (r.status) {
            case "TRIALING" -> r.trialEndsAt != null && r.trialEndsAt.isAfter(now) ? PlanCode.PRO : PlanCode.FREE;
            case "ACTIVE", "MANUAL" -> r.periodEnd == null || r.periodEnd.isAfter(now) ? PlanCode.PRO : PlanCode.FREE;
            case "PAST_DUE" -> r.periodEnd != null && r.periodEnd.plus(PAST_DUE_GRACE).isAfter(now) ? PlanCode.PRO : PlanCode.FREE;
            case "CANCELED" -> r.periodEnd != null && r.periodEnd.isAfter(now) ? PlanCode.PRO : PlanCode.FREE;
            default -> PlanCode.FREE;
        };
    }

    public Limits limits(PlanCode plan) { return plan == PlanCode.PRO ? PRO : FREE; }

    public Limits limits(UUID businessId) { return limits(effective(businessId)); }

    public Usage usage(UUID businessId) {
        int members = jdbc.sql("SELECT count(*) FROM member WHERE business_id = :b AND status = 'ACTIVE'").param("b", businessId).query(Integer.class).single();
        int devices = jdbc.sql("SELECT count(*) FROM device WHERE business_id = :b AND revoked_at IS NULL").param("b", businessId).query(Integer.class).single();
        int schedules = jdbc.sql("SELECT count(*) FROM notification_schedule WHERE business_id = :b AND active AND deleted_at IS NULL").param("b", businessId).query(Integer.class).single();
        return new Usage(members, devices, schedules);
    }

    public PlanView view(MemberContext ctx) {
        Row r = row(ctx.businessId()).orElse(null);
        PlanCode eff = effective(ctx.businessId());
        Instant now = clock.instant();
        boolean trialing = r != null && "TRIALING".equals(r.status) && eff == PlanCode.PRO;
        int left = trialing && r.trialEndsAt != null ? (int) Math.max(0, ChronoUnit.DAYS.between(now, r.trialEndsAt)) : 0;
        return new PlanView(eff.name(), r == null ? "MANUAL" : r.status, r == null ? null : r.trialEndsAt, r == null ? null : r.periodEnd, trialing, left, limits(eff), usage(ctx.businessId()));
    }

    // ---------- aplicación de límites (solo al AGREGAR) ----------

    /** ¿Cabe uno más? Si no, `PLAN_LIMIT` con la función y el tope. */
    public void requireRoom(UUID businessId, Feature feature) {
        Limits l = limits(businessId);
        Usage u = usage(businessId);
        switch (feature) {
            case MEMBERS -> { if (u.members() >= l.members()) throw new PlanLimitException(feature, l.members()); }
            case DEVICES -> { if (u.devices() >= l.devices()) throw new PlanLimitException(feature, l.devices()); }
            case SCHEDULES -> { if (u.schedules() >= l.schedules()) throw new PlanLimitException(feature, l.schedules()); }
            default -> throw new IllegalArgumentException("Not a countable feature: " + feature);
        }
    }

    /** Programaciones activas permitidas (para reactivar/crear una activa). */
    public int scheduleLimit(UUID businessId) { return limits(businessId).schedules(); }

    /** Exportar (CSV) es de Pro. */
    public void requireExport(UUID businessId) {
        if (!limits(businessId).export()) throw new PlanLimitException(Feature.EXPORT, 0);
    }

    /** Los reportes del plan Gratis cubren hasta 30 días hacia atrás (hoy, ayer, 7 y 30 días); Pro, cualquier rango. */
    public void requireReportRange(UUID businessId, LocalDate from) {
        Limits l = limits(businessId);
        if (l.reportHistoryDays() < 0) return;
        LocalDate today = days.info(businessId).dateOf(clock.instant());
        if (from.isBefore(today.minusDays(l.reportHistoryDays()))) throw new PlanLimitException(Feature.REPORT_HISTORY, l.reportHistoryDays());
    }

    /**
     * Un dueño puede tener más de un negocio solo si alguno de los suyos está en Pro (el cobro es por negocio). Su primer negocio siempre se puede crear.
     */
    public void requireCanCreateBusiness(UUID userId) {
        List<UUID> owned = jdbc.sql("SELECT business_id FROM member WHERE user_account_id = :u AND role = 'OWNER' AND status = 'ACTIVE'").param("u", userId).query(UUID.class).list();
        if (owned.isEmpty()) return;
        boolean anyPro = owned.stream().anyMatch(b -> effective(b) == PlanCode.PRO);
        if (!anyPro) throw new PlanLimitException(Feature.BUSINESSES, 1);
    }

    /** Nuevo negocio: 30 días de prueba de Pro. */
    public void startTrial(UUID businessId) {
        jdbc.sql("INSERT INTO subscription (business_id, plan_code, status, trial_ends_at) VALUES (:b, 'PRO', 'TRIALING', :t)")
                .param("b", businessId).param("t", Timestamp.from(clock.instant().plus(TRIAL_DAYS, ChronoUnit.DAYS))).update();
    }
}
