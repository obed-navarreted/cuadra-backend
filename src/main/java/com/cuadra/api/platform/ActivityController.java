package com.cuadra.api.platform;

import com.cuadra.api.common.PageResponse;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Registro de actividad del negocio, para su dueño y sus administradores (solo lectura; el cajero no la ve): incluye cuando la plataforma miró el negocio ("Ver como") con su motivo. */
@RestController
@RequestMapping("/api/b/{businessId}/activity")
public class ActivityController {
    private final JdbcClient jdbc;
    private final Access access;

    public ActivityController(JdbcClient jdbc, Access access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record ActivityEntry(long id, String action, String entity, String detail, String actorName, boolean byPlatform, Instant at) {}

    @GetMapping
    public PageResponse<ActivityEntry> list(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                            @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        ctx.require(Role.Permission.VIEW_ACTIVITY);   // dueño y admin (solo lectura); el cajero no
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        long total = jdbc.sql("SELECT count(*) FROM audit_log WHERE business_id = :b").param("b", businessId).query(Long.class).single();
        List<ActivityEntry> rows = jdbc.sql("""
                        SELECT a.id, a.action, a.entity, a.detail, a.at, m.display_name
                          FROM audit_log a LEFT JOIN member m ON m.id = a.actor_member_id
                         WHERE a.business_id = :b ORDER BY a.id DESC LIMIT :lim OFFSET :off
                        """)
                .param("b", businessId).param("lim", s).param("off", p * s)
                .query((rs, i) -> new ActivityEntry(rs.getLong("id"), rs.getString("action"), rs.getString("entity"), rs.getString("detail"), rs.getString("display_name"),
                        rs.getString("action").startsWith("platform."), rs.getTimestamp("at").toInstant()))
                .list();
        return PageResponse.of(rows, p, s, total);
    }
}
