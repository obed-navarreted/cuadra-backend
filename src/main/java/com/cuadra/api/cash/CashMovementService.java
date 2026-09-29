package com.cuadra.api.cash;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Retiros (el dueño saca dinero) y entradas (se agrega sencillo). Mueven el cajón, pero no son gasto ni venta: no cambian la ganancia. */
@Service
public class CashMovementService {
    private static final long MAX_MINOR = 1_000_000_000_000L;
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final RegisterResolver registers;
    private final BusinessDayService days;

    public CashMovementService(JdbcClient jdbc, Audit audit, Clock clock, RegisterResolver registers, BusinessDayService days) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.registers = registers;
        this.days = days;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CashMovementInput")
    public record MovementInput(String kind, Long amountMinor, String reason, UUID cashRegisterId, Instant occurredAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CashMovementView")
    public record MovementView(UUID id, String kind, long amountMinor, String reason, UUID cashRegisterId, String createdByName, UUID createdById, Instant occurredAt, boolean voided, String voidReason, long rev) {}

    public enum Outcome { CREATED, UNCHANGED }

    public record Result(MovementView movement, Outcome outcome) {}

    @Transactional
    public Result upsert(MemberContext ctx, UUID id, MovementInput in) {
        if (in.kind() == null || (!in.kind().equals("WITHDRAWAL") && !in.kind().equals("DEPOSIT"))) throw ApiException.badRequest("INVALID_KIND", "Kind must be WITHDRAWAL or DEPOSIT");
        if (in.amountMinor() == null || in.amountMinor() <= 0 || in.amountMinor() > MAX_MINOR) throw ApiException.badRequest("INVALID_AMOUNT", "Invalid amount");
        // Agregar sencillo lo hace cualquiera que venda; sacar dinero, quien administra.
        ctx.require(in.kind().equals("WITHDRAWAL") ? Permission.MANAGE_EXPENSES : Permission.SELL);
        String reason = in.reason() == null || in.reason().isBlank() ? null : in.reason().trim();
        if (reason != null && reason.length() > 200) throw ApiException.badRequest("INVALID_REASON", "Reason too long");
        Optional<MovementView> existing = find(ctx.businessId(), id);
        if (existing.isPresent()) {
            MovementView m = existing.get();
            if (m.kind().equals(in.kind()) && m.amountMinor() == in.amountMinor() && java.util.Objects.equals(m.reason(), reason)) return new Result(m, Outcome.UNCHANGED);
            throw ApiException.conflict("MOVEMENT_IMMUTABLE", "A cash movement is not edited: void it and register it again");
        }
        if (jdbc.sql("SELECT count(*) FROM cash_movement WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");
        Instant now = clock.instant();
        Instant at = in.occurredAt() == null || in.occurredAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.occurredAt();
        UUID register = registers.resolve(ctx, in.cashRegisterId());
        jdbc.sql("""
                        INSERT INTO cash_movement (id, business_id, cash_register_id, kind, amount_minor, reason, created_by_member_id, device_id, occurred_at)
                        VALUES (:id, :b, :r, :k, :a, :reason, :m, :d, :at)
                        """)
                .param("id", id).param("b", ctx.businessId()).param("r", register).param("k", in.kind()).param("a", in.amountMinor()).param("reason", reason)
                .param("m", ctx.memberId()).param("d", ctx.deviceId(), java.sql.Types.OTHER).param("at", Timestamp.from(at)).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "cash_movement." + in.kind().toLowerCase(), "cash_movement", id, "amount=" + in.amountMinor());
        return new Result(get(ctx.businessId(), id), Outcome.CREATED);
    }

    @Transactional
    public MovementView voidMovement(MemberContext ctx, UUID id, String reason) {
        ctx.require(Permission.MANAGE_EXPENSES);
        MovementView m = get(ctx.businessId(), id);
        if (m.voided()) return m;
        jdbc.sql("UPDATE cash_movement SET voided_by_member_id = :m, voided_at = :now, void_reason = :r, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b")
                .param("m", ctx.memberId()).param("now", Timestamp.from(clock.instant())).param("r", reason == null || reason.isBlank() ? null : reason.trim()).param("id", id).param("b", ctx.businessId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "cash_movement.void", "cash_movement", id, reason);
        return get(ctx.businessId(), id);
    }

    public MovementView get(UUID businessId, UUID id) {
        return find(businessId, id).orElseThrow(() -> ApiException.notFound("MOVEMENT_NOT_FOUND", "Cash movement not found"));
    }

    private static final String SELECT = "SELECT x.*, m.display_name AS created_name FROM cash_movement x JOIN member m ON m.id = x.created_by_member_id";

    public Optional<MovementView> find(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE x.id = :id AND x.business_id = :b").param("id", id).param("b", businessId).query((rs, n) -> map(rs)).optional();
    }

    public List<MovementView> viewsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(SELECT + " WHERE x.business_id = :b AND x.id IN (:ids)").param("b", businessId).param("ids", ids).query((rs, n) -> map(rs)).list();
    }

    public PageResponse<MovementView> list(MemberContext ctx, LocalDate from, LocalDate to, int page, int size) {
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        BusinessDayService.Info info = days.info(ctx.businessId());
        String own = ctx.role() == Role.CASHIER ? " AND x.created_by_member_id = :me" : "";
        Timestamp start = Timestamp.from(info.startOf(from != null ? from : info.dateOf(clock.instant())));
        Timestamp end = Timestamp.from(info.endOf(to != null ? to : (from != null ? from : info.dateOf(clock.instant()))));
        var count = jdbc.sql("SELECT count(*) FROM cash_movement x WHERE x.business_id = :b AND x.occurred_at >= :s AND x.occurred_at < :e" + own).param("b", ctx.businessId()).param("s", start).param("e", end);
        var rows = jdbc.sql(SELECT + " WHERE x.business_id = :b AND x.occurred_at >= :s AND x.occurred_at < :e" + own + " ORDER BY x.occurred_at DESC LIMIT " + size + " OFFSET " + (long) page * size)
                .param("b", ctx.businessId()).param("s", start).param("e", end);
        if (!own.isEmpty()) { count = count.param("me", ctx.memberId()); rows = rows.param("me", ctx.memberId()); }
        return PageResponse.of(rows.query((rs, n) -> map(rs)).list(), page, size, count.query(Long.class).single());
    }

    private static MovementView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MovementView(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getLong("amount_minor"), rs.getString("reason"), rs.getObject("cash_register_id", UUID.class),
                rs.getString("created_name"), rs.getObject("created_by_member_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("voided_at") != null,
                rs.getString("void_reason"), rs.getLong("rev"));
    }
}
