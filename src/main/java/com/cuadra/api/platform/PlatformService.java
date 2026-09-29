package com.cuadra.api.platform;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.plan.PlanService;
import com.cuadra.api.security.TokenHasher;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que puede hacer quien administra la plataforma. Cada acción que cambia algo pide un motivo y queda en `platform_audit_log`;
 * las que tocan un negocio además quedan en el registro de ese negocio, para que su dueño lo vea.
 */
@Service
public class PlatformService {
    public static final Duration VIEW_AS_TTL = Duration.ofMinutes(30);
    /** Claves de la configuración remota que la consola puede editar, y cómo se valida cada una. */
    static final Map<String, Pattern> CONFIG_KEYS = Map.of(
            "donation_url", Pattern.compile("https://\\S{4,300}"),
            "donation_mode", Pattern.compile("[A-Za-z_]{2,30}"),
            "min_app_version", Pattern.compile("\\d{1,4}(\\.\\d{1,4}){0,3}"),
            "recommended_app_version", Pattern.compile("\\d{1,4}(\\.\\d{1,4}){0,3}"));
    static final Set<String> FLAG_KEYS = Set.of("early_access", "extended_history", "beta_features");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Audit audit;
    private final PlanService plans;

    public PlatformService(JdbcClient jdbc, Clock clock, Audit audit, PlanService plans) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.plans = plans;
    }

    // ---------- métricas ----------

    public record PlatformCount(String key, long count) {}
    public record PlatformDayCount(String day, long sales, long businesses) {}
    public record PlatformCohort(String week, long size, List<Long> active) {}
    public record PlatformMetrics(long businesses, long createdLast7, long createdLast30, long active7, long active30, long users, long devices,
                                  List<PlatformDayCount> salesPerDay, List<PlatformCount> byCountry, List<PlatformCount> byType, List<PlatformCount> byLocale,
                                  List<PlatformCount> byPlan, List<PlatformCount> moduleUsage, List<PlatformCount> appVersions, List<PlatformCohort> retention) {}

    public PlatformMetrics metrics() {
        Instant now = clock.instant();
        Timestamp d7 = Timestamp.from(now.minus(7, ChronoUnit.DAYS));
        Timestamp d30 = Timestamp.from(now.minus(30, ChronoUnit.DAYS));
        long total = one("SELECT count(*) FROM business WHERE status <> 'DELETING'");
        long c7 = jdbc.sql("SELECT count(*) FROM business WHERE created_at >= :t").param("t", d7).query(Long.class).single();
        long c30 = jdbc.sql("SELECT count(*) FROM business WHERE created_at >= :t").param("t", d30).query(Long.class).single();
        String active = "SELECT count(DISTINCT business_id) FROM sale WHERE status = 'COMPLETED' AND completed_at >= :t";
        long a7 = jdbc.sql(active).param("t", d7).query(Long.class).single();
        long a30 = jdbc.sql(active).param("t", d30).query(Long.class).single();
        List<PlatformDayCount> perDay = jdbc.sql("""
                        SELECT (completed_at AT TIME ZONE 'UTC')::date AS d, count(*) AS n, count(DISTINCT business_id) AS b
                          FROM sale WHERE status = 'COMPLETED' AND completed_at >= :t GROUP BY 1 ORDER BY 1
                        """)
                .param("t", d30).query((rs, i) -> new PlatformDayCount(rs.getString("d"), rs.getLong("n"), rs.getLong("b"))).list();
        List<PlatformCount> modules = new ArrayList<>();
        for (String module : List.of("credit", "expenses", "inventory", "shifts", "catalog", "team")) {
            modules.add(new PlatformCount(module, jdbc.sql("SELECT count(*) FROM business WHERE status <> 'DELETING' AND (modules::jsonb ->> :k) = 'true'").param("k", module).query(Long.class).single()));
        }
        return new PlatformMetrics(total, c7, c30, a7, a30, one("SELECT count(*) FROM user_account WHERE deleted_at IS NULL"),
                one("SELECT count(*) FROM device WHERE revoked_at IS NULL"), perDay,
                counts("SELECT country AS k, count(*) AS n FROM business WHERE status <> 'DELETING' GROUP BY 1 ORDER BY 2 DESC, 1"),
                counts("SELECT COALESCE(type, '-') AS k, count(*) AS n FROM business WHERE status <> 'DELETING' GROUP BY 1 ORDER BY 2 DESC, 1"),
                counts("SELECT default_locale AS k, count(*) AS n FROM business WHERE status <> 'DELETING' GROUP BY 1 ORDER BY 2 DESC, 1"),
                counts("SELECT plan_code || ':' || status AS k, count(*) AS n FROM subscription GROUP BY 1 ORDER BY 2 DESC, 1"),
                modules,
                counts("SELECT COALESCE(app_version, '-') AS k, count(*) AS n FROM device WHERE revoked_at IS NULL GROUP BY 1 ORDER BY 2 DESC, 1"),
                retention());
    }

    private List<PlatformCohort> retention() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        Map<String, Map<Integer, Long>> active = new LinkedHashMap<>();
        jdbc.sql("SELECT to_char(date_trunc('week', created_at), 'YYYY-MM-DD') AS w, count(*) AS n FROM business WHERE created_at >= now() - interval '8 weeks' GROUP BY 1 ORDER BY 1")
                .query((rs, i) -> {
                    sizes.put(rs.getString("w"), rs.getLong("n"));
                    return null;
                }).list();
        jdbc.sql("""
                        WITH c AS (SELECT id, date_trunc('week', created_at) AS w FROM business WHERE created_at >= now() - interval '8 weeks'),
                             a AS (SELECT DISTINCT business_id, date_trunc('week', completed_at) AS w FROM sale WHERE status = 'COMPLETED' AND completed_at >= now() - interval '9 weeks')
                        SELECT to_char(c.w, 'YYYY-MM-DD') AS cohort, ((a.w::date - c.w::date) / 7) AS off, count(DISTINCT c.id) AS n
                          FROM c JOIN a ON a.business_id = c.id AND a.w >= c.w GROUP BY 1, 2
                        """)
                .query((rs, i) -> {
                    active.computeIfAbsent(rs.getString("cohort"), k -> new LinkedHashMap<>()).put(rs.getInt("off"), rs.getLong("n"));
                    return null;
                }).list();
        List<PlatformCohort> out = new ArrayList<>();
        for (var e : sizes.entrySet()) {
            Map<Integer, Long> byOffset = active.getOrDefault(e.getKey(), Map.of());
            int max = byOffset.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<Long> row = new ArrayList<>();
            for (int k = 0; k <= max; k++) row.add(byOffset.getOrDefault(k, 0L));
            out.add(new PlatformCohort(e.getKey(), e.getValue(), row));
        }
        return out;
    }

    private long countFor(String sql, UUID businessId) { return jdbc.sql(sql).param("b", businessId).query(Long.class).single(); }

    private long one(String sql) { return jdbc.sql(sql).query(Long.class).single(); }

    private List<PlatformCount> counts(String sql) {
        return jdbc.sql(sql).query((rs, i) -> new PlatformCount(rs.getString("k"), rs.getLong("n"))).list();
    }

    // ---------- negocios ----------

    public record PlatformBusinessRow(UUID id, String name, String type, String country, String status, String plan, String planStatus, String effectivePlan,
                                      Instant createdAt, long members, long devices, Instant lastSaleAt) {}

    public PageResponse<PlatformBusinessRow> businesses(String q, String plan, String country, String status, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        String where = """
                 WHERE b.status <> 'DELETING' AND (CAST(:q AS text) IS NULL OR b.name ILIKE '%' || :q || '%' OR EXISTS (
                         SELECT 1 FROM member m JOIN user_account u ON u.id = m.user_account_id WHERE m.business_id = b.id AND u.email ILIKE '%' || :q || '%'))
                   AND (CAST(:plan AS text) IS NULL OR sub.plan_code = :plan)
                   AND (CAST(:country AS text) IS NULL OR b.country = :country)
                   AND (CAST(:status AS text) IS NULL OR b.status = :status)
                """;
        String from = " FROM business b LEFT JOIN subscription sub ON sub.business_id = b.id ";
        String qq = blankToNull(q);
        long total = jdbc.sql("SELECT count(*)" + from + where).param("q", qq).param("plan", blankToNull(plan)).param("country", blankToNull(country)).param("status", blankToNull(status)).query(Long.class).single();
        List<PlatformBusinessRow> rows = jdbc.sql("""
                        SELECT b.id, b.name, b.type, b.country, b.status, sub.plan_code, sub.status AS plan_status, b.created_at,
                               (SELECT count(*) FROM member m WHERE m.business_id = b.id AND m.status = 'ACTIVE') AS members,
                               (SELECT count(*) FROM device d WHERE d.business_id = b.id AND d.revoked_at IS NULL) AS devices,
                               (SELECT max(completed_at) FROM sale x WHERE x.business_id = b.id AND x.status = 'COMPLETED') AS last_sale
                        """ + from + where + " ORDER BY b.created_at DESC, b.id LIMIT :lim OFFSET :off")
                .param("q", qq).param("plan", blankToNull(plan)).param("country", blankToNull(country)).param("status", blankToNull(status))
                .param("lim", s).param("off", p * s)
                .query((rs, i) -> new PlatformBusinessRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("type"), rs.getString("country"),
                        rs.getString("status"), rs.getString("plan_code"), rs.getString("plan_status"), plans.effective(rs.getObject("id", UUID.class)).name(),
                        rs.getTimestamp("created_at").toInstant(), rs.getLong("members"), rs.getLong("devices"),
                        rs.getTimestamp("last_sale") == null ? null : rs.getTimestamp("last_sale").toInstant()))
                .list();
        return PageResponse.of(rows, p, s, total);
    }

    public record PlatformOwner(UUID userId, String email, String fullName) {}
    public record PlatformBusinessDetail(UUID id, String name, String type, String country, String currency, String timezone, String status, String suspendedReason,
                                         Instant suspendedAt, Instant createdAt, String plan, String planStatus, String effectivePlan, Instant trialEndsAt,
                                         Instant currentPeriodEnd, String planNote, List<PlatformOwner> owners, long members, long devices, long sales30,
                                         long openTickets, Map<String, Boolean> flags) {}

    public PlatformBusinessDetail business(UUID id) {
        return jdbc.sql("""
                        SELECT b.id, b.name, b.type, b.country, b.currency, b.timezone, b.status, b.suspended_reason, b.suspended_at, b.created_at,
                               sub.plan_code, sub.status AS plan_status, sub.trial_ends_at, sub.current_period_end, sub.note
                          FROM business b LEFT JOIN subscription sub ON sub.business_id = b.id WHERE b.id = :b AND b.status <> 'DELETING'
                        """)
                .param("b", id)
                .query((rs, i) -> new PlatformBusinessDetail(id, rs.getString("name"), rs.getString("type"), rs.getString("country"), rs.getString("currency"),
                        rs.getString("timezone"), rs.getString("status"), rs.getString("suspended_reason"), ts(rs.getTimestamp("suspended_at")),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("plan_code"), rs.getString("plan_status"), plans.effective(id).name(),
                        ts(rs.getTimestamp("trial_ends_at")), ts(rs.getTimestamp("current_period_end")), rs.getString("note"), owners(id),
                        countFor("SELECT count(*) FROM member WHERE business_id = :b AND status = 'ACTIVE'", id),
                        countFor("SELECT count(*) FROM device WHERE business_id = :b AND revoked_at IS NULL", id),
                        countFor("SELECT count(*) FROM sale WHERE business_id = :b AND status = 'COMPLETED' AND completed_at >= now() - interval '30 days'", id),
                        countFor("SELECT count(*) FROM support_ticket WHERE business_id = :b AND status = 'NEW'", id), flags(id)))
                .optional().orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Business not found"));
    }

    private List<PlatformOwner> owners(UUID businessId) {
        return jdbc.sql("""
                        SELECT u.id, u.email, u.full_name FROM member m JOIN user_account u ON u.id = m.user_account_id
                         WHERE m.business_id = :b AND m.role = 'OWNER' AND m.status = 'ACTIVE'
                        """)
                .param("b", businessId).query((rs, i) -> new PlatformOwner(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"))).list();
    }

    private Map<String, Boolean> flags(UUID businessId) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        jdbc.sql("SELECT key, enabled FROM business_flag WHERE business_id = :b ORDER BY key").param("b", businessId).query((rs, i) -> {
            out.put(rs.getString("key"), rs.getBoolean("enabled"));
            return null;
        }).list();
        return out;
    }

    // ---------- acciones sobre un negocio ----------

    public record PlatformPlanChange(String plan, String status, Instant periodEnd, String note) {}

    @Transactional
    public PlatformBusinessDetail changePlan(UUID admin, UUID businessId, PlatformPlanChange in, String reason) {
        requireBusiness(businessId);
        String plan = in.plan();
        String status = in.status() != null ? in.status() : "MANUAL";
        if (!Set.of("FREE", "PRO").contains(plan)) throw ApiException.badRequest("INVALID_PLAN", "Unknown plan");
        if (!Set.of("ACTIVE", "MANUAL", "CANCELED", "PAST_DUE").contains(status)) throw ApiException.badRequest("INVALID_PLAN_STATUS", "Unknown plan status");
        jdbc.sql("""
                        INSERT INTO subscription (business_id, plan_code, status, current_period_end, provider, note, updated_at)
                        VALUES (:b, :p, :s, :e, 'MANUAL', :n, :now)
                        ON CONFLICT (business_id) DO UPDATE SET plan_code = :p, status = :s, current_period_end = :e, note = :n, updated_at = :now
                        """)
                .param("b", businessId).param("p", plan).param("s", status).param("e", in.periodEnd() == null ? null : Timestamp.from(in.periodEnd()), java.sql.Types.TIMESTAMP)
                .param("n", in.note()).param("now", Timestamp.from(clock.instant())).update();
        record(admin, businessId, "business.plan_changed", plan + "/" + status + (in.periodEnd() == null ? "" : " until " + in.periodEnd()), reason);
        return business(businessId);
    }

    @Transactional
    public PlatformBusinessDetail extendTrial(UUID admin, UUID businessId, int days, String reason) {
        requireBusiness(businessId);
        if (days < 1 || days > 365) throw ApiException.badRequest("INVALID_DAYS", "Days must be between 1 and 365");
        Instant now = clock.instant();
        Instant current = jdbc.sql("SELECT trial_ends_at FROM subscription WHERE business_id = :b").param("b", businessId)
                .query((rs, i) -> ts(rs.getTimestamp(1))).optional().orElse(null);
        Instant base = current != null && current.isAfter(now) ? current : now;
        Timestamp end = Timestamp.from(base.plus(days, ChronoUnit.DAYS));
        jdbc.sql("""
                        INSERT INTO subscription (business_id, plan_code, status, trial_ends_at, updated_at) VALUES (:b, 'PRO', 'TRIALING', :t, :now)
                        ON CONFLICT (business_id) DO UPDATE SET plan_code = 'PRO', status = 'TRIALING', trial_ends_at = :t, updated_at = :now
                        """)
                .param("b", businessId).param("t", end).param("now", Timestamp.from(now)).update();
        record(admin, businessId, "business.trial_extended", "+" + days + " days → " + end.toInstant(), reason);
        return business(businessId);
    }

    @Transactional
    public PlatformBusinessDetail suspend(UUID admin, UUID businessId, String reason) {
        requireBusiness(businessId);
        jdbc.sql("UPDATE business SET status = 'SUSPENDED', suspended_reason = :r, suspended_at = :now, rev = nextval('change_rev_seq') WHERE id = :b")
                .param("r", reason).param("now", Timestamp.from(clock.instant())).param("b", businessId).update();
        // Sus sesiones web dejan de servir de inmediato: el estado se revisa en cada llamada (Access), aquí solo se anota.
        record(admin, businessId, "business.suspended", null, reason);
        return business(businessId);
    }

    @Transactional
    public PlatformBusinessDetail unsuspend(UUID admin, UUID businessId, String reason) {
        requireBusiness(businessId);
        jdbc.sql("UPDATE business SET status = 'ACTIVE', suspended_reason = NULL, suspended_at = NULL, rev = nextval('change_rev_seq') WHERE id = :b AND status = 'SUSPENDED'")
                .param("b", businessId).update();
        record(admin, businessId, "business.unsuspended", null, reason);
        return business(businessId);
    }

    @Transactional
    public PlatformBusinessDetail setFlag(UUID admin, UUID businessId, String key, boolean enabled, String reason) {
        requireBusiness(businessId);
        if (!FLAG_KEYS.contains(key)) throw ApiException.badRequest("INVALID_FLAG", "Unknown flag");
        jdbc.sql("INSERT INTO business_flag (business_id, key, enabled) VALUES (:b, :k, :e) ON CONFLICT (business_id, key) DO UPDATE SET enabled = :e")
                .param("b", businessId).param("k", key).param("e", enabled).update();
        record(admin, businessId, "business.flag", key + "=" + enabled, reason);
        return business(businessId);
    }

    /** Marca el negocio para eliminación: deja de operar (como suspendido) y se borra tras el periodo de gracia. */
    @Transactional
    public PlatformBusinessDetail markDeletion(UUID admin, UUID businessId, String reason) {
        requireBusiness(businessId);
        jdbc.sql("UPDATE business SET status = 'SUSPENDED', suspended_reason = :r, suspended_at = COALESCE(suspended_at, :now), deletion_requested_at = :now, rev = nextval('change_rev_seq') WHERE id = :b")
                .param("r", reason).param("now", Timestamp.from(clock.instant())).param("b", businessId).update();
        record(admin, businessId, "business.deletion_marked", null, reason);
        return business(businessId);
    }

    public record PlatformViewAs(String token, Instant expiresAt, UUID businessId) {}

    /** "Ver como": sesión de 30 minutos, solo lectura, con motivo. Queda en la auditoría de la plataforma y en la del negocio (lo ve su dueño). */
    @Transactional
    public PlatformViewAs viewAs(UUID admin, UUID businessId, String reason) {
        requireBusiness(businessId);
        Instant now = clock.instant();
        Instant expires = now.plus(VIEW_AS_TTL);
        String token = TokenHasher.newToken();
        jdbc.sql("""
                        INSERT INTO auth_session (id, user_account_id, token_hash, kind, user_agent, issued_at, last_seen_at, expires_at, view_as_business_id)
                        VALUES (:id, :u, :h, 'VIEW_AS', 'platform-console', :now, :now, :exp, :b)
                        """)
                .param("id", UUID.randomUUID()).param("u", admin).param("h", TokenHasher.hash(token)).param("now", Timestamp.from(now))
                .param("exp", Timestamp.from(expires)).param("b", businessId).update();
        record(admin, businessId, "platform.view_as", null, reason);
        return new PlatformViewAs(token, expires, businessId);
    }

    private void requireBusiness(UUID id) {
        boolean exists = jdbc.sql("SELECT count(*) FROM business WHERE id = :b AND status <> 'DELETING'").param("b", id).query(Long.class).single() > 0;
        if (!exists) throw ApiException.notFound("NOT_FOUND", "Business not found");
    }

    /** Registra en la auditoría de la plataforma y, si toca un negocio, en la del negocio. */
    void record(UUID admin, UUID businessId, String action, String detail, String reason) {
        String payload = (detail == null ? "" : detail + " — ") + "motivo: " + reason;
        jdbc.sql("INSERT INTO platform_audit_log (actor_user_id, action, target, payload) VALUES (:a, :ac, :t, :p)")
                .param("a", admin).param("ac", action).param("t", businessId == null ? null : businessId.toString()).param("p", payload).update();
        if (businessId != null) audit.log(businessId, null, admin, null, action.startsWith("platform.") ? action : "platform." + action.substring(action.indexOf('.') + 1),
                "business", businessId, payload);
    }

    // ---------- usuarios, teléfonos, tickets ----------

    public record PlatformUserRow(UUID id, String email, String fullName, String locale, Instant lastLoginAt, boolean platformAdmin, List<String> businesses) {}

    public List<PlatformUserRow> users(String q) {
        String qq = blankToNull(q);
        if (qq == null) return List.of();
        return jdbc.sql("""
                        SELECT u.id, u.email, u.full_name, u.locale, u.last_login_at, u.is_platform_admin,
                               (SELECT COALESCE(string_agg(b.name || ' (' || m.role || ')', ', ' ORDER BY b.name), '') FROM member m JOIN business b ON b.id = m.business_id
                                 WHERE m.user_account_id = u.id AND m.status = 'ACTIVE') AS biz
                          FROM user_account u WHERE u.deleted_at IS NULL AND (u.email ILIKE '%' || :q || '%' OR u.full_name ILIKE '%' || :q || '%')
                         ORDER BY u.email LIMIT 50
                        """)
                .param("q", qq)
                .query((rs, i) -> new PlatformUserRow(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"), rs.getString("locale"),
                        ts(rs.getTimestamp("last_login_at")), rs.getBoolean("is_platform_admin"),
                        rs.getString("biz").isEmpty() ? List.of() : List.of(rs.getString("biz").split(", "))))
                .list();
    }

    public record PlatformDeviceRow(UUID id, UUID businessId, String businessName, String name, String model, String appVersion, Instant lastSeenAt, Instant lastSyncAt, int pendingOps) {}

    /** Teléfonos con problemas: sin sincronizar hace `staleDays` o más, o con una versión anterior a `belowVersion`. */
    public List<PlatformDeviceRow> devices(Integer staleDays, String belowVersion) {
        Timestamp cutoff = staleDays == null ? null : Timestamp.from(clock.instant().minus(staleDays, ChronoUnit.DAYS));
        List<PlatformDeviceRow> all = jdbc.sql("""
                        SELECT d.id, d.business_id, b.name AS business_name, d.name, d.model, d.app_version, d.last_seen_at, d.last_sync_at, d.pending_ops
                          FROM device d JOIN business b ON b.id = d.business_id
                         WHERE d.revoked_at IS NULL AND b.status <> 'DELETING' AND (CAST(:c AS timestamptz) IS NULL OR COALESCE(d.last_sync_at, d.linked_at) < :c)
                         ORDER BY d.last_sync_at NULLS FIRST LIMIT 500
                        """)
                .param("c", cutoff, java.sql.Types.TIMESTAMP)
                .query((rs, i) -> new PlatformDeviceRow(rs.getObject("id", UUID.class), rs.getObject("business_id", UUID.class), rs.getString("business_name"), rs.getString("name"),
                        rs.getString("model"), rs.getString("app_version"), ts(rs.getTimestamp("last_seen_at")), ts(rs.getTimestamp("last_sync_at")), rs.getInt("pending_ops")))
                .list();
        if (belowVersion == null || belowVersion.isBlank()) return all;
        return all.stream().filter(d -> d.appVersion() == null || compareVersions(d.appVersion(), belowVersion) < 0).toList();
    }

    public record PlatformTicketRow(UUID id, UUID businessId, String businessName, String category, String message, String replyToEmail, String replyToPhone,
                                    String diagnostics, String locale, String status, Instant createdAt) {}

    public PageResponse<PlatformTicketRow> tickets(String status, int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        String st = blankToNull(status);
        long total = jdbc.sql("SELECT count(*) FROM support_ticket WHERE (CAST(:s AS text) IS NULL OR status = :s)").param("s", st).query(Long.class).single();
        List<PlatformTicketRow> rows = jdbc.sql("""
                        SELECT t.id, t.business_id, b.name AS business_name, t.category, t.message, t.reply_to_email, t.reply_to_phone, t.diagnostics, t.locale, t.status, t.created_at
                          FROM support_ticket t LEFT JOIN business b ON b.id = t.business_id WHERE (CAST(:s AS text) IS NULL OR t.status = :s)
                         ORDER BY t.created_at DESC LIMIT :lim OFFSET :off
                        """)
                .param("s", st).param("lim", s).param("off", p * s)
                .query((rs, i) -> new PlatformTicketRow(rs.getObject("id", UUID.class), rs.getObject("business_id", UUID.class), rs.getString("business_name"),
                        rs.getString("category"), rs.getString("message"), rs.getString("reply_to_email"), rs.getString("reply_to_phone"), rs.getString("diagnostics"),
                        rs.getString("locale"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()))
                .list();
        return PageResponse.of(rows, p, s, total);
    }

    @Transactional
    public void setTicketStatus(UUID admin, UUID id, String status) {
        if (!Set.of("NEW", "ANSWERED", "CLOSED").contains(status)) throw ApiException.badRequest("INVALID_STATUS", "Unknown status");
        int n = jdbc.sql("UPDATE support_ticket SET status = :s WHERE id = :id").param("s", status).param("id", id).update();
        if (n == 0) throw ApiException.notFound("NOT_FOUND", "Ticket not found");
        jdbc.sql("INSERT INTO platform_audit_log (actor_user_id, action, target, payload) VALUES (:a, 'ticket.status', :t, :p)")
                .param("a", admin).param("t", id.toString()).param("p", status).update();
    }

    // ---------- configuración remota y auditoría ----------

    public Map<String, String> config() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : new java.util.TreeSet<>(CONFIG_KEYS.keySet())) out.put(key, null);
        jdbc.sql("SELECT key, value FROM remote_config").query((rs, i) -> {
            if (CONFIG_KEYS.containsKey(rs.getString("key"))) out.put(rs.getString("key"), rs.getString("value"));
            return null;
        }).list();
        return out;
    }

    /** `value` vacío borra la clave (vuelve a valer lo del entorno). */
    @Transactional
    public Map<String, String> setConfig(UUID admin, String key, String value, String reason) {
        Pattern rule = CONFIG_KEYS.get(key);
        if (rule == null) throw ApiException.badRequest("INVALID_CONFIG_KEY", "This key cannot be edited");
        if (value == null || value.isBlank()) {
            jdbc.sql("DELETE FROM remote_config WHERE key = :k").param("k", key).update();
        } else {
            if (!rule.matcher(value.trim()).matches()) throw ApiException.badRequest("INVALID_CONFIG_VALUE", "Invalid value for " + key);
            jdbc.sql("INSERT INTO remote_config (key, value, updated_at) VALUES (:k, :v, :now) ON CONFLICT (key) DO UPDATE SET value = :v, updated_at = :now")
                    .param("k", key).param("v", value.trim()).param("now", Timestamp.from(clock.instant())).update();
        }
        record(admin, null, "config.set", key + "=" + (value == null ? "" : value.trim()), reason);
        return config();
    }

    public record PlatformAuditEntry(long id, String actorEmail, String action, String target, String payload, Instant at) {}

    public PageResponse<PlatformAuditEntry> auditLog(int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        long total = one("SELECT count(*) FROM platform_audit_log");
        List<PlatformAuditEntry> rows = jdbc.sql("""
                        SELECT l.id, u.email, l.action, l.target, l.payload, l.at FROM platform_audit_log l LEFT JOIN user_account u ON u.id = l.actor_user_id
                         ORDER BY l.id DESC LIMIT :lim OFFSET :off
                        """)
                .param("lim", s).param("off", p * s)
                .query((rs, i) -> new PlatformAuditEntry(rs.getLong("id"), rs.getString("email"), rs.getString("action"), rs.getString("target"), rs.getString("payload"),
                        rs.getTimestamp("at").toInstant()))
                .list();
        return PageResponse.of(rows, p, s, total);
    }

    // ---------- utilidades ----------

    static int compareVersions(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int yi = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
