package com.cuadra.api.notification;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Json;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.tenancy.MemberContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bandeja de notificaciones. Toda notificación se guarda para sus destinatarios (y llega al teléfono con la sincronización); enviarla por FCM
 * es un añadido. Reglas: preferencias por persona y tipo, horas de silencio del negocio (salvo lo crítico) y "un aviso por evento y día".
 */
@Service
public class NotificationService {
    /** Qué canal de Android usa y a quién llega por defecto. `critical` ignora las horas de silencio. */
    public enum Type {
        LOW_STOCK("STOCK", Audience.OWNER_ADMINS, false),
        OUT_OF_STOCK("STOCK", Audience.OWNER_ADMINS, false),
        SHIFT_CLOSED("CASH", Audience.OWNER_ADMINS, false),
        SHIFT_DIFFERENCE("CASH", Audience.OWNER, true),
        SHIFT_NOT_CLOSED("CASH", Audience.OWNER_ADMINS, false),
        SALE_DELETED("CASH", Audience.OWNER, false),
        DEVICE_STALE("TEAM", Audience.OWNER_ADMINS, false),
        PIN_LOCKOUT("TEAM", Audience.OWNER_ADMINS, true),
        MEMBER_JOINED("TEAM", Audience.NONE, false),
        DAILY_SUMMARY("CASH", Audience.OWNER, false),
        SCHEDULED("SCHEDULED", Audience.NONE, false),
        /** Anuncio de la plataforma (novedades, avisos de servicio): el texto ya viene escrito, en un idioma. */
        PLATFORM_ANNOUNCEMENT("PLATFORM", Audience.NONE, false);

        public final String channel;
        final Audience audience;
        final boolean critical;

        Type(String channel, Audience audience, boolean critical) {
            this.channel = channel;
            this.audience = audience;
            this.critical = critical;
        }
    }

    enum Audience {
        OWNER(List.of("OWNER")), OWNER_ADMINS(List.of("OWNER", "ADMIN")), NONE(List.of());

        final List<String> roles;

        Audience(List<String> roles) { this.roles = roles; }
    }

    /** Ajustes del negocio para los avisos. Las horas son locales; `null` en la hora del recordatorio = apagado. */
    public record Settings(String quietStart, String quietEnd, boolean summaryEnabled, String summaryTime, String shiftReminderTime, int staleHours) {}

    /** `recipientMemberId`/`recipientDeviceId`: a quién va (uno de los dos). En un teléfono compartido la bandeja solo muestra lo de la persona activa y lo del propio teléfono. */
    public record NotificationView(UUID id, String type, String channel, Map<String, Object> args, String title, String body, String deepLink, boolean push,
                                   Instant createdAt, Instant readAt, UUID scheduleId, UUID recipientMemberId, UUID recipientDeviceId, long rev) {}

    public record TokenInput(String fcmToken, String platform, String locale, String appVersion) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Json json;
    private final JsonMapper mapper;
    private final PushSender push;

    public NotificationService(JdbcClient jdbc, Clock clock, Json json, JsonMapper mapper, PushSender push) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
        this.mapper = mapper;
        this.push = push;
    }

    // ---------- crear ----------

    /**
     * Crea el aviso para sus destinatarios: los del tipo por defecto, más `extraMembers`, menos `exceptMember` (quien hizo la acción no necesita que se le avise).
     * Con `dedupeKey` un mismo evento no avisa dos veces al mismo destinatario. Devuelve cuántas personas lo recibirán.
     */
    @Transactional
    public int notify(UUID businessId, Type type, Map<String, Object> args, Collection<UUID> extraMembers, UUID exceptMember, String dedupeKey, String deepLink) {
        Set<UUID> recipients = new LinkedHashSet<>(membersWithRoles(businessId, type.audience.roles));
        if (extraMembers != null) recipients.addAll(extraMembers);
        if (exceptMember != null) recipients.remove(exceptMember);
        return deliver(businessId, type, args, recipients, List.of(), dedupeKey, deepLink, null, null, null, false);
    }

    /** Envío a personas y/o teléfonos concretos, con un texto ya escrito (programaciones). `forcePush`: quien programó eligió la hora, no se silencia. */
    @Transactional
    public int deliver(UUID businessId, Type type, Map<String, Object> args, Collection<UUID> members, Collection<UUID> devices, String dedupeKey, String deepLink,
                       String title, String body, UUID scheduleId, boolean forcePush) {
        BusinessInfo b = business(businessId);
        Map<String, Object> data = args == null ? Map.of() : args;
        NotificationText.Text fallback = title != null ? new NotificationText.Text(title, body) : NotificationText.render(type, data, b.locale, b.currency);
        boolean alert = forcePush || type.critical || !inQuietHours(b, clock.instant());
        String argsJson = json.write(data);
        Set<UUID> muted = mutedMembers(members, type);
        List<PushSender.Message> messages = new ArrayList<>();
        int count = 0;
        for (UUID member : members) {
            if (muted.contains(member)) continue;
            UUID id = UUID.randomUUID();
            int n = jdbc.sql("""
                            INSERT INTO notification (id, business_id, recipient_member_id, type, args, title, body, deep_link, push, schedule_id, dedupe_key)
                            VALUES (:id, :b, :m, :t, :a, :ti, :bo, :dl, :p, :s, :k)
                            ON CONFLICT (business_id, recipient_member_id, dedupe_key) WHERE dedupe_key IS NOT NULL AND recipient_member_id IS NOT NULL DO NOTHING""")
                    .param("id", id).param("b", businessId).param("m", member).param("t", type.name()).param("a", argsJson).param("ti", fallback.title()).param("bo", fallback.body())
                    .param("dl", deepLink).param("p", alert).param("s", scheduleId, java.sql.Types.OTHER).param("k", dedupeKey).update();
            if (n > 0) {
                count++;
                messages.add(new PushSender.Message(id, member, null, type.name(), fallback.title(), fallback.body(), deepLink, alert));
            }
        }
        for (UUID device : devices) {
            UUID id = UUID.randomUUID();
            int n = jdbc.sql("""
                            INSERT INTO notification (id, business_id, recipient_device_id, type, args, title, body, deep_link, push, schedule_id, dedupe_key)
                            VALUES (:id, :b, :d, :t, :a, :ti, :bo, :dl, :p, :s, :k)
                            ON CONFLICT (business_id, recipient_device_id, dedupe_key) WHERE dedupe_key IS NOT NULL AND recipient_device_id IS NOT NULL DO NOTHING""")
                    .param("id", id).param("b", businessId).param("d", device).param("t", type.name()).param("a", argsJson).param("ti", fallback.title()).param("bo", fallback.body())
                    .param("dl", deepLink).param("p", alert).param("s", scheduleId, java.sql.Types.OTHER).param("k", dedupeKey).update();
            if (n > 0) {
                count++;
                messages.add(new PushSender.Message(id, null, device, type.name(), fallback.title(), fallback.body(), deepLink, alert));
            }
        }
        // El envío inmediato es un añadido: si falla, el aviso ya está en la bandeja y el teléfono lo recibe al sincronizar.
        try {
            push.send(messages);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(NotificationService.class).warn("El envío de push falló; los avisos quedan en la bandeja", e);
        }
        return count;
    }

    // ---------- destinatarios y reglas ----------

    public List<UUID> membersWithRoles(UUID businessId, List<String> roles) {
        if (roles.isEmpty()) return List.of();
        return jdbc.sql("SELECT id FROM member WHERE business_id = :b AND status = 'ACTIVE' AND role IN (:r)").param("b", businessId).param("r", roles).query(UUID.class).list();
    }

    private Set<UUID> mutedMembers(Collection<UUID> members, Type type) {
        if (members.isEmpty()) return Set.of();
        return new LinkedHashSet<>(jdbc.sql("SELECT member_id FROM notification_preference WHERE type = :t AND NOT enabled AND member_id IN (:m)").param("t", type.name())
                .param("m", members).query(UUID.class).list());
    }

    private record BusinessInfo(ZoneId zone, String locale, String currency, Settings settings) {}

    private BusinessInfo business(UUID businessId) {
        var row = jdbc.sql("SELECT timezone, default_locale, currency FROM business WHERE id = :b").param("b", businessId)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).optional().orElseThrow(() -> ApiException.notFound("BUSINESS_NOT_FOUND", "Business not found"));
        return new BusinessInfo(ZoneId.of(row[0]), row[1], row[2], settings(businessId));
    }

    private static boolean inQuietHours(BusinessInfo b, Instant now) {
        LocalTime start = LocalTime.parse(b.settings.quietStart());
        LocalTime end = LocalTime.parse(b.settings.quietEnd());
        LocalTime t = now.atZone(b.zone).toLocalTime();
        if (start.equals(end)) return false;
        return start.isBefore(end) ? !t.isBefore(start) && t.isBefore(end) : !t.isBefore(start) || t.isBefore(end);
    }

    // ---------- ajustes del negocio ----------

    public Settings settings(UUID businessId) {
        Map<String, String> kv = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value FROM business_settings WHERE business_id = :b AND key LIKE 'notify.%'").param("b", businessId).query((rs, n) -> {
            kv.put(rs.getString(1), rs.getString(2));
            return null;
        }).list();
        return new Settings(kv.getOrDefault("notify.quiet_start", "21:30"), kv.getOrDefault("notify.quiet_end", "07:00"), "true".equals(kv.get("notify.summary_enabled")),
                kv.getOrDefault("notify.summary_time", "21:00"), kv.get("notify.shift_reminder_time"), Integer.parseInt(kv.getOrDefault("notify.stale_hours", "24")));
    }

    @Transactional
    public Settings updateSettings(MemberContext ctx, Settings in) {
        ctx.require(com.cuadra.api.tenancy.Role.Permission.EDIT_BUSINESS);
        try {
            LocalTime.parse(in.quietStart());
            LocalTime.parse(in.quietEnd());
            LocalTime.parse(in.summaryTime());
            if (in.shiftReminderTime() != null) LocalTime.parse(in.shiftReminderTime());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_TIME", "Invalid time");
        }
        if (in.staleHours() < 1 || in.staleHours() > 720) throw ApiException.badRequest("INVALID_HOURS", "Invalid number of hours");
        put(ctx.businessId(), "notify.quiet_start", in.quietStart());
        put(ctx.businessId(), "notify.quiet_end", in.quietEnd());
        put(ctx.businessId(), "notify.summary_enabled", String.valueOf(in.summaryEnabled()));
        put(ctx.businessId(), "notify.summary_time", in.summaryTime());
        put(ctx.businessId(), "notify.stale_hours", String.valueOf(in.staleHours()));
        if (in.shiftReminderTime() == null) jdbc.sql("DELETE FROM business_settings WHERE business_id = :b AND key = 'notify.shift_reminder_time'").param("b", ctx.businessId()).update();
        else put(ctx.businessId(), "notify.shift_reminder_time", in.shiftReminderTime());
        return settings(ctx.businessId());
    }

    private void put(UUID businessId, String key, String value) {
        jdbc.sql("INSERT INTO business_settings (business_id, key, value) VALUES (:b, :k, :v) ON CONFLICT (business_id, key) DO UPDATE SET value = :v")
                .param("b", businessId).param("k", key).param("v", value).update();
    }

    // ---------- bandeja ----------

    private static final String MINE = "n.business_id = :b AND (n.recipient_member_id = :me OR (n.recipient_device_id IS NOT NULL AND n.recipient_device_id = CAST(:dev AS uuid)))";

    public PageResponse<NotificationView> list(MemberContext ctx, boolean unreadOnly, int page, int size) {
        size = Math.max(1, Math.min(size, 100));
        page = Math.max(0, page);
        String where = MINE + (unreadOnly ? " AND n.read_at IS NULL" : "");
        long total = jdbc.sql("SELECT count(*) FROM notification n WHERE " + where).param("b", ctx.businessId()).param("me", ctx.memberId()).param("dev", ctx.deviceId(), java.sql.Types.OTHER).query(Long.class).single();
        var items = jdbc.sql("SELECT n.* FROM notification n WHERE " + where + " ORDER BY n.created_at DESC, n.id LIMIT " + size + " OFFSET " + (long) page * size)
                .param("b", ctx.businessId()).param("me", ctx.memberId()).param("dev", ctx.deviceId(), java.sql.Types.OTHER).query((rs, n) -> map(rs)).list();
        return PageResponse.of(items, page, size, total);
    }

    public long unreadCount(MemberContext ctx) {
        return jdbc.sql("SELECT count(*) FROM notification n WHERE " + MINE + " AND n.read_at IS NULL").param("b", ctx.businessId()).param("me", ctx.memberId())
                .param("dev", ctx.deviceId(), java.sql.Types.OTHER).query(Long.class).single();
    }

    /** Marca como leída una notificación mía (o de este teléfono). Repetirlo no cambia nada. */
    @Transactional
    public NotificationView markRead(MemberContext ctx, UUID id) {
        NotificationView v = get(ctx, id);
        if (v.readAt() != null) return v;
        jdbc.sql("UPDATE notification SET read_at = :now, rev = nextval('change_rev_seq') WHERE id = :id AND read_at IS NULL").param("now", Timestamp.from(clock.instant())).param("id", id).update();
        return get(ctx, id);
    }

    /** Marca como leídas todas las mías (y las de este teléfono). Devuelve cuántas cambiaron. */
    @Transactional
    public int markAllRead(MemberContext ctx) {
        return jdbc.sql("UPDATE notification n SET read_at = :now, rev = nextval('change_rev_seq') WHERE " + MINE + " AND n.read_at IS NULL")
                .param("now", Timestamp.from(clock.instant())).param("b", ctx.businessId()).param("me", ctx.memberId()).param("dev", ctx.deviceId(), java.sql.Types.OTHER).update();
    }

    public NotificationView get(MemberContext ctx, UUID id) {
        return jdbc.sql("SELECT n.* FROM notification n WHERE n.id = :id AND " + MINE).param("id", id).param("b", ctx.businessId()).param("me", ctx.memberId())
                .param("dev", ctx.deviceId(), java.sql.Types.OTHER).query((rs, n) -> map(rs)).optional().orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found"));
    }

    public List<NotificationView> viewsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM notification WHERE business_id = :b AND id IN (:ids)").param("b", businessId).param("ids", ids).query((rs, n) -> map(rs)).list();
    }

    private NotificationView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> args = mapper.readValue(rs.getString("args"), new TypeReference<LinkedHashMap<String, Object>>() {});
        String type = rs.getString("type");
        return new NotificationView(rs.getObject("id", UUID.class), type, Type.valueOf(type).channel, args, rs.getString("title"), rs.getString("body"), rs.getString("deep_link"), rs.getBoolean("push"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(), rs.getObject("schedule_id", UUID.class),
                rs.getObject("recipient_member_id", UUID.class), rs.getObject("recipient_device_id", UUID.class), rs.getLong("rev"));
    }

    // ---------- preferencias ----------

    /** Tipo → activado. Todo está activado hasta que la persona lo apague. */
    public Map<String, Boolean> preferences(MemberContext ctx) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Type t : Type.values()) out.put(t.name(), true);
        jdbc.sql("SELECT type, enabled FROM notification_preference WHERE member_id = :m").param("m", ctx.memberId()).query((rs, n) -> {
            if (out.containsKey(rs.getString(1))) out.put(rs.getString(1), rs.getBoolean(2));
            return null;
        }).list();
        return out;
    }

    @Transactional
    public Map<String, Boolean> setPreference(MemberContext ctx, String type, boolean enabled) {
        try {
            Type.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("INVALID_TYPE", "Unknown notification type");
        }
        jdbc.sql("INSERT INTO notification_preference (member_id, type, enabled) VALUES (:m, :t, :e) ON CONFLICT (member_id, type) DO UPDATE SET enabled = :e")
                .param("m", ctx.memberId()).param("t", type).param("e", enabled).update();
        return preferences(ctx);
    }

    // ---------- tokens de push ----------

    /** Guarda o mueve el token FCM de esta instalación al teléfono y a la persona actuales (un token pertenece a una sola instalación). */
    @Transactional
    public void registerToken(MemberContext ctx, TokenInput in) {
        if (in.fcmToken() == null || in.fcmToken().isBlank() || in.fcmToken().length() > 4096) throw ApiException.badRequest("INVALID_TOKEN", "Invalid token");
        String platform = in.platform() == null || in.platform().isBlank() ? "ANDROID" : in.platform().trim().toUpperCase();
        String locale = "en".equalsIgnoreCase(in.locale()) ? "en" : "es";
        jdbc.sql("""
                        INSERT INTO push_token (id, business_id, device_id, member_id, user_account_id, fcm_token, platform, locale, app_version)
                        VALUES (:id, :b, :d, :m, :u, :t, :p, :l, :v)
                        ON CONFLICT (fcm_token) DO UPDATE SET business_id = :b, device_id = :d, member_id = :m, user_account_id = :u, platform = :p, locale = :l, app_version = :v, updated_at = now()""")
                .param("id", UUID.randomUUID()).param("b", ctx.businessId()).param("d", ctx.deviceId(), java.sql.Types.OTHER).param("m", ctx.memberId()).param("u", ctx.userId(), java.sql.Types.OTHER)
                .param("t", in.fcmToken().trim()).param("p", platform).param("l", locale).param("v", in.appVersion()).update();
    }

    /** Solo para jobs: nombre visible de una persona. */
    public Optional<String> memberName(UUID memberId) {
        return jdbc.sql("SELECT display_name FROM member WHERE id = :m").param("m", memberId).query(String.class).optional();
    }
}
