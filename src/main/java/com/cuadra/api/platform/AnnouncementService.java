package com.cuadra.api.platform;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.notification.NotificationService;
import com.cuadra.api.plan.PlanService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anuncios de la plataforma: llegan a la bandeja (y por push) de quien corresponda, ahora o a una hora, y opcionalmente como franja en la app.
 * A quién: por país, plan, versión de la app o negocios concretos; y a qué personas dentro del negocio (dueños / dueños y admins / todos).
 * La franja (banner) es para todos: con segmento no tiene sentido, porque `/api/config` es público y no sabe quién mira.
 */
@Service
public class AnnouncementService {
    public record AnnouncementSegment(List<String> countries, List<String> plans, List<String> appVersions, List<UUID> businessIds) {
        public AnnouncementSegment {
            countries = countries == null ? List.of() : countries.stream().map(String::toUpperCase).toList();
            plans = plans == null ? List.of() : plans.stream().map(String::toUpperCase).toList();
            appVersions = appVersions == null ? List.of() : appVersions;
            businessIds = businessIds == null ? List.of() : businessIds;
        }

        boolean everyone() { return countries.isEmpty() && plans.isEmpty() && appVersions.isEmpty() && businessIds.isEmpty(); }
    }

    public record AnnouncementInput(@NotBlank @Size(max = 80) String title, @NotBlank @Size(max = 500) String body, @Size(max = 200) String deepLink,
                                    AnnouncementSegment segment, @NotNull String audience, Boolean banner, Instant bannerUntil, Instant scheduledAt) {}

    public record AnnouncementView(UUID id, String title, String body, String deepLink, AnnouncementSegment segment, String audience, boolean banner,
                                   Instant bannerUntil, Instant scheduledAt, Instant sentAt, int recipients, Instant cancelledAt, Instant createdAt, String state) {}

    public record AnnouncementReach(int businesses, int people) {}

    public record AnnouncementBanner(UUID id, String title, String body, String deepLink) {}

    private static final Map<String, List<String>> ROLES = Map.of("OWNERS", List.of("OWNER"), "OWNERS_ADMINS", List.of("OWNER", "ADMIN"), "ALL", List.of("OWNER", "ADMIN", "CASHIER"));

    private final JdbcClient jdbc;
    private final Clock clock;
    private final JsonMapper mapper;
    private final NotificationService notifications;
    private final PlanService plans;
    private final PlatformService platform;

    public AnnouncementService(JdbcClient jdbc, Clock clock, JsonMapper mapper, NotificationService notifications, PlanService plans, PlatformService platform) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.mapper = mapper;
        this.notifications = notifications;
        this.plans = plans;
        this.platform = platform;
    }

    @Transactional
    public AnnouncementView create(UUID admin, AnnouncementInput in) {
        Instant now = clock.instant();
        if (!ROLES.containsKey(in.audience())) throw ApiException.badRequest("INVALID_AUDIENCE", "Unknown audience");
        AnnouncementSegment segment = in.segment() == null ? new AnnouncementSegment(null, null, null, null) : in.segment();
        boolean banner = Boolean.TRUE.equals(in.banner());
        if (banner && !segment.everyone()) throw ApiException.badRequest("BANNER_NEEDS_EVERYONE", "A banner is shown to everyone; remove the segment");
        if (in.bannerUntil() != null && !in.bannerUntil().isAfter(now)) throw ApiException.badRequest("INVALID_BANNER_UNTIL", "The banner end must be in the future");
        if (in.scheduledAt() != null && in.scheduledAt().isBefore(now.minusSeconds(60))) throw ApiException.badRequest("INVALID_SCHEDULE", "The send time is in the past");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO platform_announcement (id, title, body, deep_link, segment, audience, banner, banner_until, scheduled_at, created_by)
                        VALUES (:id, :t, :b, :dl, :seg, :aud, :bn, :bu, :sa, :cb)
                        """)
                .param("id", id).param("t", in.title().trim()).param("b", in.body().trim()).param("dl", blank(in.deepLink())).param("seg", mapper.writeValueAsString(segment))
                .param("aud", in.audience()).param("bn", banner).param("bu", ts(in.bannerUntil()), java.sql.Types.TIMESTAMP)
                .param("sa", ts(in.scheduledAt()), java.sql.Types.TIMESTAMP).param("cb", admin).update();
        jdbc.sql("INSERT INTO platform_audit_log (actor_user_id, action, target, payload) VALUES (:a, 'announcement.created', :t, :p)")
                .param("a", admin).param("t", id.toString()).param("p", in.title().trim()).update();
        if (in.scheduledAt() == null) send(id);
        return get(id);
    }

    /** A cuántos negocios y personas llegaría, antes de enviar. */
    public AnnouncementReach reach(AnnouncementSegment segment, String audience) {
        if (!ROLES.containsKey(audience)) throw ApiException.badRequest("INVALID_AUDIENCE", "Unknown audience");
        Map<UUID, List<UUID>> targets = targets(segment == null ? new AnnouncementSegment(null, null, null, null) : segment, audience);
        return new AnnouncementReach(targets.size(), targets.values().stream().mapToInt(List::size).sum());
    }

    @Transactional
    public AnnouncementView cancel(UUID admin, UUID id) {
        int n = jdbc.sql("UPDATE platform_announcement SET cancelled_at = :now WHERE id = :id AND cancelled_at IS NULL AND sent_at IS NULL").param("now", Timestamp.from(clock.instant())).param("id", id).update();
        if (n == 0) {
            get(id);
            throw ApiException.conflict("ANNOUNCEMENT_NOT_CANCELLABLE", "Already sent or cancelled");
        }
        jdbc.sql("INSERT INTO platform_audit_log (actor_user_id, action, target, payload) VALUES (:a, 'announcement.cancelled', :t, NULL)").param("a", admin).param("t", id.toString()).update();
        return get(id);
    }

    /** Termina una franja antes de tiempo (el anuncio ya enviado sigue en las bandejas). */
    @Transactional
    public AnnouncementView endBanner(UUID admin, UUID id) {
        get(id);
        jdbc.sql("UPDATE platform_announcement SET banner_until = :now WHERE id = :id AND banner").param("now", Timestamp.from(clock.instant())).param("id", id).update();
        jdbc.sql("INSERT INTO platform_audit_log (actor_user_id, action, target, payload) VALUES (:a, 'announcement.banner_ended', :t, NULL)").param("a", admin).param("t", id.toString()).update();
        return get(id);
    }

    public AnnouncementView get(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> view(rs)).optional().orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Announcement not found"));
    }

    public List<AnnouncementView> list() {
        return jdbc.sql(SELECT + " ORDER BY created_at DESC LIMIT 100").query((rs, i) -> view(rs)).list();
    }

    /** La franja que la app muestra hoy (la más reciente vigente), si hay. */
    public Optional<AnnouncementBanner> activeBanner() {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.sql("""
                        SELECT id, title, body, deep_link FROM platform_announcement
                         WHERE banner AND cancelled_at IS NULL AND (scheduled_at IS NULL OR scheduled_at <= :now) AND (banner_until IS NULL OR banner_until > :now)
                         ORDER BY created_at DESC LIMIT 1
                        """)
                .param("now", now).query((rs, i) -> new AnnouncementBanner(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"), rs.getString("deep_link"))).optional();
    }

    /** Envía lo programado que ya tocó. Varias instancias no lo duplican: se toma con SKIP LOCKED y se marca antes de repartir. */
    @Transactional
    public int runDue() {
        List<UUID> due = jdbc.sql("""
                        SELECT id FROM platform_announcement WHERE sent_at IS NULL AND cancelled_at IS NULL AND scheduled_at IS NOT NULL AND scheduled_at <= :now
                         ORDER BY scheduled_at FOR UPDATE SKIP LOCKED
                        """)
                .param("now", Timestamp.from(clock.instant())).query(UUID.class).list();
        due.forEach(this::send);
        return due.size();
    }

    private void send(UUID id) {
        var row = jdbc.sql("SELECT title, body, deep_link, segment, audience FROM platform_announcement WHERE id = :id AND sent_at IS NULL AND cancelled_at IS NULL FOR UPDATE")
                .param("id", id).query((rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}).optional();
        if (row.isEmpty()) return;
        String[] r = row.get();
        Map<UUID, List<UUID>> targets = targets(mapper.readValue(r[3], AnnouncementSegment.class), r[4]);
        int people = 0;
        for (var e : targets.entrySet()) {
            people += notifications.deliver(e.getKey(), NotificationService.Type.PLATFORM_ANNOUNCEMENT, Map.of("title", r[0], "body", r[1]), e.getValue(), List.of(),
                    "announcement:" + id, r[2], r[0], r[1], null, false);
        }
        jdbc.sql("UPDATE platform_announcement SET sent_at = :now, recipients = :n WHERE id = :id").param("now", Timestamp.from(clock.instant())).param("n", people).param("id", id).update();
    }

    /** Negocio → personas que lo recibirían. Solo negocios activos. */
    private Map<UUID, List<UUID>> targets(AnnouncementSegment seg, String audience) {
        List<UUID> businesses = jdbc.sql("""
                        SELECT b.id FROM business b WHERE b.status = 'ACTIVE'
                           AND (:noCountry OR b.country IN (:countries))
                           AND (:noIds OR b.id IN (:ids))
                           AND (:noVersions OR EXISTS (SELECT 1 FROM device d WHERE d.business_id = b.id AND d.revoked_at IS NULL AND d.app_version IN (:versions)))
                         ORDER BY b.id
                        """)
                .param("noCountry", seg.countries().isEmpty()).param("countries", seg.countries().isEmpty() ? List.of("-") : seg.countries())
                .param("noIds", seg.businessIds().isEmpty()).param("ids", seg.businessIds().isEmpty() ? List.of(UUID.randomUUID()) : seg.businessIds())
                .param("noVersions", seg.appVersions().isEmpty()).param("versions", seg.appVersions().isEmpty() ? List.of("-") : seg.appVersions())
                .query(UUID.class).list();
        Map<UUID, List<UUID>> out = new LinkedHashMap<>();
        for (UUID b : businesses) {
            if (!seg.plans().isEmpty() && !seg.plans().contains(plans.effective(b).name())) continue;
            List<UUID> members = notifications.membersWithRoles(b, ROLES.get(audience));
            if (!members.isEmpty()) out.put(b, new ArrayList<>(members));
        }
        return out;
    }

    private static final String SELECT = """
            SELECT id, title, body, deep_link, segment, audience, banner, banner_until, scheduled_at, sent_at, recipients, cancelled_at, created_at
              FROM platform_announcement
            """;

    private AnnouncementView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant sent = inst(rs.getTimestamp("sent_at"));
        Instant cancelled = inst(rs.getTimestamp("cancelled_at"));
        String state = cancelled != null ? "CANCELLED" : sent != null ? "SENT" : "SCHEDULED";
        return new AnnouncementView(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"), rs.getString("deep_link"),
                mapper.readValue(rs.getString("segment"), AnnouncementSegment.class), rs.getString("audience"), rs.getBoolean("banner"),
                inst(rs.getTimestamp("banner_until")), inst(rs.getTimestamp("scheduled_at")), sent, rs.getInt("recipients"), cancelled,
                rs.getTimestamp("created_at").toInstant(), state);
    }

    private static Instant inst(Timestamp t) { return t == null ? null : t.toInstant(); }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
