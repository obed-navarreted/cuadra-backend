package com.cuadra.api.notification;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.Json;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notificaciones programadas por dueño y admins. Cada envío se calcula en la zona horaria del negocio; el job toma lo vencido con
 * `FOR UPDATE SKIP LOCKED` (seguro con varias instancias). Un envío atrasado más de 2 horas se salta (no se mandan recordatorios viejos),
 * y pausar/reanudar nunca dispara lo que se perdió: el siguiente sale de "ahora".
 */
@Service
public class ScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    static final Duration MAX_LATE = Duration.ofHours(2);
    private static final Set<String> LINKS = Set.of("cuadra://caja", "cuadra://cierre", "cuadra://fiados", "cuadra://inventario", "cuadra://gastos", "cuadra://notificaciones");
    private static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "CASHIER");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Json json;
    private final JsonMapper mapper;
    private final Audit audit;
    private final NotificationService notifications;
    private final com.cuadra.api.plan.PlanService plans;

    public ScheduleService(JdbcClient jdbc, Clock clock, Json json, JsonMapper mapper, Audit audit, NotificationService notifications, com.cuadra.api.plan.PlanService plans) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
        this.mapper = mapper;
        this.audit = audit;
        this.notifications = notifications;
        this.plans = plans;
    }

    /** A quién: `all`, `roles`, `memberIds` o `deviceIds` (se pueden combinar). */
    public record Audience(Boolean all, List<String> roles, List<UUID> memberIds, List<UUID> deviceIds) {}

    public record ScheduleInput(String title, String body, String deepLink, Audience audience, ScheduleRule rule, Boolean active) {}

    public record ScheduleView(UUID id, String title, String body, String deepLink, Audience audience, ScheduleRule rule, String timezone, Instant nextRunAt, Instant lastRunAt, boolean active,
                               long sent, long read, Instant createdAt) {}

    public record RunView(Instant runAt, String status, int recipients) {}

    // ---------- crear y editar ----------

    @Transactional
    public ScheduleView upsert(MemberContext ctx, UUID id, ScheduleInput in) {
        ctx.require(Permission.PROGRAM_NOTIFICATIONS);
        String title = clean(in.title(), 100, "INVALID_TITLE");
        String body = clean(in.body(), 500, "INVALID_BODY");
        if (in.deepLink() != null && !in.deepLink().isBlank() && !LINKS.contains(in.deepLink())) throw ApiException.badRequest("INVALID_LINK", "Unknown action");
        Audience audience = normalizeAudience(ctx.businessId(), in.audience());
        ZoneId zone = zone(ctx.businessId());
        Instant now = clock.instant();
        if (in.rule() == null) throw ApiException.badRequest("INVALID_RULE", "Repeat rule is required");
        ScheduleRule rule = in.rule().validated(zone, now);
        boolean active = in.active() == null || in.active();
        boolean exists = jdbc.sql("SELECT count(*) FROM notification_schedule WHERE id = :id AND business_id = :b AND deleted_at IS NULL").param("id", id).param("b", ctx.businessId()).query(Integer.class).single() > 0;
        if (!exists && jdbc.sql("SELECT count(*) FROM notification_schedule WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");
        if (active && jdbc.sql("SELECT count(*) FROM notification_schedule WHERE business_id = :b AND active AND deleted_at IS NULL AND id <> :id").param("b", ctx.businessId()).param("id", id)
                .query(Integer.class).single() >= plans.scheduleLimit(ctx.businessId())) throw new com.cuadra.api.plan.PlanLimitException(com.cuadra.api.plan.PlanService.Feature.SCHEDULES, plans.scheduleLimit(ctx.businessId()));
        // Editar o crear recalcula desde AHORA: lo que ya pasó no se dispara.
        Instant next = active ? rule.next(zone, now).orElse(null) : null;
        if (active && next == null) throw ApiException.badRequest("RULE_ENDED", "That schedule would never send: check the date");
        if (!exists) {
            jdbc.sql("""
                            INSERT INTO notification_schedule (id, business_id, created_by_member_id, title, body, deep_link, audience, rule, timezone, next_run_at, active)
                            VALUES (:id, :b, :m, :t, :bo, :dl, :a, :r, :z, :n, :act)""")
                    .param("id", id).param("b", ctx.businessId()).param("m", ctx.memberId()).param("t", title).param("bo", body).param("dl", blank(in.deepLink())).param("a", json.write(audience))
                    .param("r", json.write(rule)).param("z", zone.getId()).param("n", next == null ? null : Timestamp.from(next), java.sql.Types.TIMESTAMP).param("act", active).update();
        } else {
            jdbc.sql("""
                            UPDATE notification_schedule SET title = :t, body = :bo, deep_link = :dl, audience = :a, rule = :r, timezone = :z, next_run_at = :n, active = :act,
                                   updated_at = now(), rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b""")
                    .param("id", id).param("b", ctx.businessId()).param("t", title).param("bo", body).param("dl", blank(in.deepLink())).param("a", json.write(audience)).param("r", json.write(rule))
                    .param("z", zone.getId()).param("n", next == null ? null : Timestamp.from(next), java.sql.Types.TIMESTAMP).param("act", active).update();
        }
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), exists ? "schedule.update" : "schedule.create", "notification_schedule", id, title);
        return get(ctx.businessId(), id);
    }

    /** Pausar o reanudar. Reanudar calcula el siguiente a partir de ahora. */
    @Transactional
    public ScheduleView setActive(MemberContext ctx, UUID id, boolean active) {
        ctx.require(Permission.PROGRAM_NOTIFICATIONS);
        ScheduleView s = get(ctx.businessId(), id);
        if (s.active() == active) return s;
        Instant next = null;
        if (active) {
            next = s.rule().next(ZoneId.of(s.timezone()), clock.instant()).orElseThrow(() -> ApiException.badRequest("RULE_ENDED", "That schedule would never send: check the date"));
            if (jdbc.sql("SELECT count(*) FROM notification_schedule WHERE business_id = :b AND active AND deleted_at IS NULL").param("b", ctx.businessId()).query(Integer.class).single() >= plans.scheduleLimit(ctx.businessId())) throw new com.cuadra.api.plan.PlanLimitException(com.cuadra.api.plan.PlanService.Feature.SCHEDULES, plans.scheduleLimit(ctx.businessId()));
        }
        jdbc.sql("UPDATE notification_schedule SET active = :a, next_run_at = :n, updated_at = now(), rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b")
                .param("a", active).param("n", next == null ? null : Timestamp.from(next), java.sql.Types.TIMESTAMP).param("id", id).param("b", ctx.businessId()).update();
        return get(ctx.businessId(), id);
    }

    @Transactional
    public void delete(MemberContext ctx, UUID id) {
        ctx.require(Permission.PROGRAM_NOTIFICATIONS);
        get(ctx.businessId(), id);
        jdbc.sql("UPDATE notification_schedule SET deleted_at = now(), active = false, next_run_at = NULL, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b").param("id", id).param("b", ctx.businessId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "schedule.delete", "notification_schedule", id, null);
    }

    /** "Enviar ahora": se guarda como una programación de una vez ya ejecutada, para que quede en el historial. */
    @Transactional
    public ScheduleView sendNow(MemberContext ctx, UUID id, ScheduleInput in) {
        ctx.require(Permission.PROGRAM_NOTIFICATIONS);
        String title = clean(in.title(), 100, "INVALID_TITLE");
        String body = clean(in.body(), 500, "INVALID_BODY");
        if (in.deepLink() != null && !in.deepLink().isBlank() && !LINKS.contains(in.deepLink())) throw ApiException.badRequest("INVALID_LINK", "Unknown action");
        Audience audience = normalizeAudience(ctx.businessId(), in.audience());
        ZoneId zone = zone(ctx.businessId());
        Instant now = clock.instant();
        if (jdbc.sql("SELECT count(*) FROM notification_schedule WHERE id = :id").param("id", id).query(Integer.class).single() > 0) return get(ctx.businessId(), id);
        ScheduleRule rule = new ScheduleRule("ONCE", null, now.atZone(zone).toLocalDateTime().withNano(0).toString(), null, null, null, null, null);
        jdbc.sql("""
                        INSERT INTO notification_schedule (id, business_id, created_by_member_id, title, body, deep_link, audience, rule, timezone, next_run_at, last_run_at, active)
                        VALUES (:id, :b, :m, :t, :bo, :dl, :a, :r, :z, NULL, :now, false)""")
                .param("id", id).param("b", ctx.businessId()).param("m", ctx.memberId()).param("t", title).param("bo", body).param("dl", blank(in.deepLink())).param("a", json.write(audience))
                .param("r", json.write(rule)).param("z", zone.getId()).param("now", Timestamp.from(now)).update();
        int sent = deliver(ctx.businessId(), id, title, body, blank(in.deepLink()), audience, now);
        jdbc.sql("INSERT INTO notification_schedule_run (schedule_id, run_at, status, recipients) VALUES (:s, :at, 'SENT', :n)").param("s", id).param("at", Timestamp.from(now)).param("n", sent).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "schedule.send_now", "notification_schedule", id, title);
        return get(ctx.businessId(), id);
    }

    // ---------- job ----------

    /**
     * Envía lo vencido. Devuelve cuántas programaciones se procesaron. Cada una se calcula de nuevo desde "ahora" al terminar, así un servidor
     * caído no acumula envíos.
     */
    @Transactional
    public int runDue() {
        Instant now = clock.instant();
        record Due(UUID id, UUID businessId, String title, String body, String deepLink, String audience, String rule, String timezone, Instant nextRunAt) {}
        List<Due> due = jdbc.sql("""
                        SELECT id, business_id, title, body, deep_link, audience, rule, timezone, next_run_at FROM notification_schedule
                         WHERE active AND deleted_at IS NULL AND next_run_at <= :now ORDER BY next_run_at LIMIT 100 FOR UPDATE SKIP LOCKED""")
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getObject("business_id", UUID.class), rs.getString("title"), rs.getString("body"), rs.getString("deep_link"),
                        rs.getString("audience"), rs.getString("rule"), rs.getString("timezone"), rs.getTimestamp("next_run_at").toInstant())).list();
        for (Due d : due) {
            try {
                ScheduleRule rule = mapper.readValue(d.rule, ScheduleRule.class);
                Audience audience = mapper.readValue(d.audience, Audience.class);
                boolean late = Duration.between(d.nextRunAt, now).compareTo(MAX_LATE) > 0;
                int recipients = 0;
                if (!late) recipients = deliver(d.businessId, d.id, d.title, d.body, d.deepLink, audience, d.nextRunAt);
                jdbc.sql("INSERT INTO notification_schedule_run (schedule_id, run_at, status, recipients) VALUES (:s, :at, :st, :n)")
                        .param("s", d.id).param("at", Timestamp.from(d.nextRunAt)).param("st", late ? "SKIPPED_LATE" : "SENT").param("n", recipients).update();
                Instant next = rule.next(ZoneId.of(d.timezone), now).orElse(null);
                jdbc.sql("UPDATE notification_schedule SET last_run_at = :now, next_run_at = :n, active = :a, rev = nextval('change_rev_seq') WHERE id = :id")
                        .param("now", Timestamp.from(now)).param("n", next == null ? null : Timestamp.from(next), java.sql.Types.TIMESTAMP).param("a", next != null).param("id", d.id).update();
            } catch (RuntimeException e) {
                // Una programación rota no debe detener a las demás: se apaga y queda constancia.
                log.error("La programación {} falló y se pausó", d.id, e);
                jdbc.sql("UPDATE notification_schedule SET active = false, next_run_at = NULL, rev = nextval('change_rev_seq') WHERE id = :id").param("id", d.id).update();
            }
        }
        return due.size();
    }

    private int deliver(UUID businessId, UUID scheduleId, String title, String body, String deepLink, Audience audience, Instant runAt) {
        Set<UUID> members = new LinkedHashSet<>();
        if (Boolean.TRUE.equals(audience.all())) members.addAll(notifications.membersWithRoles(businessId, List.of("OWNER", "ADMIN", "CASHIER")));
        if (audience.roles() != null && !audience.roles().isEmpty()) members.addAll(notifications.membersWithRoles(businessId, audience.roles()));
        if (audience.memberIds() != null) members.addAll(activeMembers(businessId, audience.memberIds()));
        Set<UUID> devices = new LinkedHashSet<>(audience.deviceIds() == null ? List.of() : activeDevices(businessId, audience.deviceIds()));
        String key = "SCHEDULED:" + scheduleId + ":" + runAt.toEpochMilli();
        return notifications.deliver(businessId, NotificationService.Type.SCHEDULED, Map.of("title", title, "body", body), members, devices, key, deepLink, title, body, scheduleId, true);
    }

    // ---------- lectura ----------

    private static final String SELECT = """
            SELECT s.*, (SELECT count(*) FROM notification n WHERE n.schedule_id = s.id) AS sent, (SELECT count(*) FROM notification n WHERE n.schedule_id = s.id AND n.read_at IS NOT NULL) AS read_count
              FROM notification_schedule s""";

    public ScheduleView get(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE s.id = :id AND s.business_id = :b AND s.deleted_at IS NULL").param("id", id).param("b", businessId).query((rs, n) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND", "Schedule not found"));
    }

    public List<ScheduleView> list(MemberContext ctx) {
        ctx.require(Permission.PROGRAM_NOTIFICATIONS);
        return jdbc.sql(SELECT + " WHERE s.business_id = :b AND s.deleted_at IS NULL ORDER BY s.active DESC, s.next_run_at NULLS LAST, s.created_at DESC").param("b", ctx.businessId()).query((rs, n) -> map(rs)).list();
    }

    public List<RunView> runs(MemberContext ctx, UUID id) {
        ctx.require(Permission.PROGRAM_NOTIFICATIONS);
        get(ctx.businessId(), id);
        return jdbc.sql("SELECT run_at, status, recipients FROM notification_schedule_run WHERE schedule_id = :s ORDER BY run_at DESC LIMIT 50").param("s", id)
                .query((rs, n) -> new RunView(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getInt(3))).list();
    }

    private ScheduleView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScheduleView(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"), rs.getString("deep_link"), mapper.readValue(rs.getString("audience"), Audience.class),
                mapper.readValue(rs.getString("rule"), ScheduleRule.class), rs.getString("timezone"), rs.getTimestamp("next_run_at") == null ? null : rs.getTimestamp("next_run_at").toInstant(),
                rs.getTimestamp("last_run_at") == null ? null : rs.getTimestamp("last_run_at").toInstant(), rs.getBoolean("active"), rs.getLong("sent"), rs.getLong("read_count"), rs.getTimestamp("created_at").toInstant());
    }

    // ---------- validación ----------

    private Audience normalizeAudience(UUID businessId, Audience in) {
        if (in == null) throw ApiException.badRequest("INVALID_AUDIENCE", "Pick who receives it");
        List<String> roles = in.roles() == null ? List.of() : in.roles().stream().distinct().toList();
        if (roles.stream().anyMatch(r -> !ROLES.contains(r))) throw ApiException.badRequest("INVALID_AUDIENCE", "Unknown role");
        List<UUID> members = in.memberIds() == null ? List.of() : in.memberIds().stream().distinct().toList();
        List<UUID> devices = in.deviceIds() == null ? List.of() : in.deviceIds().stream().distinct().toList();
        if (!members.isEmpty() && activeMembers(businessId, members).size() != members.size()) throw ApiException.badRequest("INVALID_AUDIENCE", "Unknown person");
        if (!devices.isEmpty() && activeDevices(businessId, devices).size() != devices.size()) throw ApiException.badRequest("INVALID_AUDIENCE", "Unknown phone");
        boolean all = Boolean.TRUE.equals(in.all());
        if (!all && roles.isEmpty() && members.isEmpty() && devices.isEmpty()) throw ApiException.badRequest("INVALID_AUDIENCE", "Pick who receives it");
        return new Audience(all, roles, members, devices);
    }

    private List<UUID> activeMembers(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT id FROM member WHERE business_id = :b AND status = 'ACTIVE' AND id IN (:ids)").param("b", businessId).param("ids", ids).query(UUID.class).list();
    }

    private List<UUID> activeDevices(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT id FROM device WHERE business_id = :b AND revoked_at IS NULL AND id IN (:ids)").param("b", businessId).param("ids", ids).query(UUID.class).list();
    }

    private ZoneId zone(UUID businessId) {
        return ZoneId.of(jdbc.sql("SELECT timezone FROM business WHERE id = :b").param("b", businessId).query(String.class).single());
    }

    private static String clean(String s, int max, String code) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty() || t.length() > max) throw ApiException.badRequest(code, "Invalid text");
        return t;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
