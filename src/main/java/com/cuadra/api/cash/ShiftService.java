package com.cuadra.api.cash;

import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turnos y cierre de caja. Un turno es de una caja física (no de una persona) y todo lo que pasa en esa caja durante él se le asigna por caja y hora:
 * <pre>esperado = fondo inicial + ventas en efectivo + abonos de fiado en efectivo + entradas − gastos pagados del cajón − retiros</pre>
 * El esperado se recalcula siempre desde los datos, así una operación que llega tarde de un teléfono sin conexión lo corrige sola y queda marcada como tardía.
 */
@Service
public class ShiftService {
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T00:00:00Z");
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final long MAX_MINOR = 1_000_000_000_000L;

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final RegisterResolver registers;
    private final com.cuadra.api.notification.NotificationService notifications;
    private final BusinessDayService days;

    public ShiftService(JdbcClient jdbc, Audit audit, Clock clock, RegisterResolver registers, com.cuadra.api.notification.NotificationService notifications, BusinessDayService days) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.registers = registers;
        this.notifications = notifications;
        this.days = days;
    }

    public record OpenInput(UUID cashRegisterId, Long openingFloatMinor, Instant openedAt) {}

    public record CloseInput(Long countedMinor, String denominations, String note, Instant closedAt, Boolean force, String forcedReason) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "ShiftMemberRef")
    public record MemberRef(UUID id, String name) {}

    public record Breakdown(long cashSalesMinor, long cashSalesCount, long creditPaymentsCashMinor, long depositsMinor, long expensesCashMinor, long withdrawalsMinor, long expectedNowMinor,
                            long transferMinor, long cardMinor, long otherMinor, long creditNewMinor, long cancelledCount) {}

    public record ShiftView(UUID id, UUID cashRegisterId, String registerName, MemberRef openedBy, Instant openedAt, long openingFloatMinor, MemberRef closedBy, Instant closedAt,
                            Long expectedAtCloseMinor, Long countedMinor, Long differenceMinor, String denominations, String note, String status, String forcedReason, long lateOps,
                            int reopenedCount, long rev, Breakdown breakdown) {}

    public record PendingDevice(String name, int pendingOps, Instant lastSyncAt) {}

    /** El cierre tiene que esperar: hay teléfonos de esta caja con operaciones sin enviar. Quien administra puede forzarlo. */
    public static class DevicesPending extends ApiException {
        private final List<PendingDevice> devices;

        DevicesPending(List<PendingDevice> devices) {
            super(HttpStatus.CONFLICT, "DEVICES_PENDING", "Other phones of this register still have unsynced operations");
            this.devices = devices;
        }

        public List<PendingDevice> devices() { return devices; }
    }

    public enum Outcome { CREATED, UNCHANGED, EXISTING }

    public record Result(ShiftView shift, Outcome outcome) {}

    // ---------- abrir ----------

    /** Abre un turno. Solo uno abierto por caja: si ya hay otro, se devuelve ese (EXISTING) en vez de crear un segundo. */
    @Transactional
    public Result open(MemberContext ctx, UUID id, OpenInput in) {
        ctx.require(Permission.SELL);
        if (in.openingFloatMinor() == null || in.openingFloatMinor() < 0 || in.openingFloatMinor() > MAX_MINOR) throw ApiException.badRequest("INVALID_FLOAT", "Invalid opening cash");
        UUID register = registers.resolve(ctx, in.cashRegisterId());
        Instant now = clock.instant();
        Instant openedAt = in.openedAt() == null || in.openedAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.openedAt();

        Optional<ShiftView> mine = find(ctx, id, false);
        if (mine.isPresent()) return new Result(mine.get(), Outcome.UNCHANGED);
        if (jdbc.sql("SELECT count(*) FROM shift WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");

        Optional<UUID> open = jdbc.sql("SELECT id FROM shift WHERE cash_register_id = :r AND status = 'OPEN'").param("r", register).query(UUID.class).optional();
        if (open.isPresent()) return new Result(get(ctx, open.get(), false), Outcome.EXISTING);
        try {
            jdbc.sql("INSERT INTO shift (id, business_id, cash_register_id, opened_by_member_id, opened_at, opening_float_minor) VALUES (:id, :b, :r, :m, :at, :f)")
                    .param("id", id).param("b", ctx.businessId()).param("r", register).param("m", ctx.memberId()).param("at", Timestamp.from(openedAt)).param("f", in.openingFloatMinor()).update();
        } catch (DuplicateKeyException e) {
            if (com.cuadra.api.common.Constraints.isPrimaryKey(e)) throw ApiException.conflict("ID_TAKEN", "Id already in use"); // id de otro negocio (invisible por RLS)
            // Dos teléfonos abrieron a la vez: gana el primero, el índice único es la última barrera.
            UUID winner = jdbc.sql("SELECT id FROM shift WHERE cash_register_id = :r AND status = 'OPEN'").param("r", register).query(UUID.class).single();
            return new Result(get(ctx, winner, false), Outcome.EXISTING);
        }
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "shift.open", "shift", id, "float=" + in.openingFloatMinor());
        return new Result(get(ctx, id, false), Outcome.CREATED);
    }

    // ---------- cerrar ----------

    private record Row(UUID id, UUID register, UUID openedBy, Instant openedAt, long openingFloat, String status, Instant closedAt, Long counted) {}

    @Transactional
    public Result close(MemberContext ctx, UUID id, CloseInput in) {
        ctx.require(Permission.SELL);
        if (in.countedMinor() == null || in.countedMinor() < 0 || in.countedMinor() > MAX_MINOR) throw ApiException.badRequest("INVALID_COUNTED", "Invalid counted cash");
        Row row = jdbc.sql("SELECT id, cash_register_id, opened_by_member_id, opened_at, opening_float_minor, status, closed_at, counted_cash_minor FROM shift WHERE id = :id AND business_id = :b FOR UPDATE")
                .param("id", id).param("b", ctx.businessId())
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("cash_register_id", UUID.class), rs.getObject("opened_by_member_id", UUID.class),
                        rs.getTimestamp("opened_at").toInstant(), rs.getLong("opening_float_minor"), rs.getString("status"),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(), (Long) rs.getObject("counted_cash_minor")))
                .optional().orElseThrow(() -> ApiException.notFound("SHIFT_NOT_FOUND", "Shift not found"));
        // Un cajero cierra el turno que abrió; dueño y admins, cualquiera.
        if (ctx.role() == Role.CASHIER && !row.openedBy.equals(ctx.memberId())) throw ApiException.forbidden("FORBIDDEN", "Only the person who opened the shift can close it");
        if (row.status.equals("CLOSED")) {
            if (java.util.Objects.equals(row.counted, in.countedMinor())) return new Result(get(ctx, id, true), Outcome.UNCHANGED);
            throw ApiException.conflict("SHIFT_CLOSED", "This shift is already closed");
        }

        boolean force = Boolean.TRUE.equals(in.force());
        List<PendingDevice> pending = pendingDevices(row.register, ctx.deviceId());
        if (!pending.isEmpty()) {
            if (!force) throw new DevicesPending(pending);
            ctx.require(Permission.MANAGE_EXPENSES);
            if (in.forcedReason() == null || in.forcedReason().isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required to force the closing");
        }

        Instant now = clock.instant();
        Instant closedAt = in.closedAt() == null || in.closedAt().isAfter(now.plus(CLOCK_SKEW)) || in.closedAt().isBefore(row.openedAt) ? now : in.closedAt();
        long expected = breakdown(ctx.businessId(), row.register, row.openedAt, closedAt, row.openingFloat).expectedNowMinor();
        long difference = in.countedMinor() - expected;
        Long threshold = jdbc.sql("SELECT shift_note_threshold_minor FROM business WHERE id = :b").param("b", ctx.businessId()).query((rs, n) -> (Long) rs.getObject(1)).optional().orElse(null);
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (threshold != null && Math.abs(difference) > threshold && note == null) throw ApiException.badRequest("NOTE_REQUIRED", "A note is required when the difference is above the limit");

        long rev = jdbc.sql("""
                        UPDATE shift SET status = 'CLOSED', closed_by_member_id = :m, closed_at = :at, expected_cash_minor = :e, counted_cash_minor = :c, difference_minor = :d,
                               denominations = :den, note = :note, forced_reason = :fr, rev = nextval('change_rev_seq') WHERE id = :id RETURNING rev
                        """)
                .param("m", ctx.memberId()).param("at", Timestamp.from(closedAt)).param("e", expected).param("c", in.countedMinor()).param("d", difference)
                .param("den", in.denominations()).param("note", note).param("fr", pending.isEmpty() ? null : in.forcedReason().trim()).param("id", id).query(Long.class).single();
        jdbc.sql("UPDATE shift SET closed_rev = :r WHERE id = :id").param("r", rev).param("id", id).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), pending.isEmpty() ? "shift.close" : "shift.close_forced", "shift", id,
                "expected=" + expected + " counted=" + in.countedMinor() + " diff=" + difference);
        notifyClosed(ctx, id, row.register, expected, in.countedMinor(), difference, threshold);
        return new Result(get(ctx, id, true), Outcome.CREATED);
    }

    /** Avisa a dueño y admins del cierre; y solo al dueño si la diferencia supera el umbral. Quien cerró no necesita el aviso. */
    private void notifyClosed(MemberContext ctx, UUID shiftId, UUID register, long expected, long counted, long difference, Long threshold) {
        String member = jdbc.sql("SELECT display_name FROM member WHERE id = :m").param("m", ctx.memberId()).query(String.class).single();
        String registerName = jdbc.sql("SELECT name FROM cash_register WHERE id = :r").param("r", register).query(String.class).optional().orElse("");
        java.util.Map<String, Object> args = java.util.Map.of("shiftId", shiftId.toString(), "memberName", member, "registerName", registerName, "expectedMinor", expected, "countedMinor", counted, "differenceMinor", difference);
        String link = "cuadra://cierre/" + shiftId;
        notifications.notify(ctx.businessId(), com.cuadra.api.notification.NotificationService.Type.SHIFT_CLOSED, args, null, ctx.memberId(), "SHIFT_CLOSED:" + shiftId, link);
        if (threshold != null && Math.abs(difference) > threshold) {
            notifications.notify(ctx.businessId(), com.cuadra.api.notification.NotificationService.Type.SHIFT_DIFFERENCE, args, null, ctx.memberId(), "SHIFT_DIFFERENCE:" + shiftId, link);
        }
    }

    private List<PendingDevice> pendingDevices(UUID register, UUID exceptDevice) {
        return jdbc.sql("SELECT name, pending_ops, last_sync_at FROM device WHERE cash_register_id = :r AND revoked_at IS NULL AND pending_ops > 0 AND (CAST(:d AS uuid) IS NULL OR id <> CAST(:d AS uuid))")
                .param("r", register).param("d", exceptDevice, java.sql.Types.OTHER)
                .query((rs, n) -> new PendingDevice(rs.getString("name"), rs.getInt("pending_ops"), rs.getTimestamp("last_sync_at") == null ? null : rs.getTimestamp("last_sync_at").toInstant())).list();
    }

    /** Reabrir un cierre (solo el dueño, con motivo). No puede haber otro turno abierto en esa caja. */
    @Transactional
    public ShiftView reopen(MemberContext ctx, UUID id, String reason) {
        ctx.require(Permission.REOPEN_SHIFT);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required");
        ShiftView s = get(ctx, id, false);
        if (s.status().equals("OPEN")) return s;
        if (jdbc.sql("SELECT count(*) FROM shift WHERE cash_register_id = :r AND status = 'OPEN'").param("r", s.cashRegisterId()).query(Integer.class).single() > 0) {
            throw ApiException.conflict("SHIFT_ALREADY_OPEN", "Another shift is open on this register");
        }
        jdbc.sql("""
                        UPDATE shift SET status = 'OPEN', closed_by_member_id = NULL, closed_at = NULL, expected_cash_minor = NULL, counted_cash_minor = NULL, difference_minor = NULL,
                               denominations = NULL, note = NULL, forced_reason = NULL, closed_rev = NULL, reopened_count = reopened_count + 1, rev = nextval('change_rev_seq') WHERE id = :id
                        """)
                .param("id", id).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "shift.reopen", "shift", id, reason);
        return get(ctx, id, false);
    }

    // ---------- cálculo ----------

    /** Todo lo que movió una caja entre `start` y `end`. Un cajero nunca ve esto de los turnos de otros. */
    public Breakdown breakdown(UUID businessId, UUID register, Instant start, Instant end, long openingFloat) {
        Timestamp s = Timestamp.from(start);
        Timestamp e = Timestamp.from(end);
        long[] byMethod = new long[5];   // CASH, TRANSFER, CARD, OTHER, CREDIT
        long[] cashCount = new long[1];
        jdbc.sql("""
                        SELECT p.method, coalesce(sum(p.amount_minor), 0) AS total, count(DISTINCT s.id) AS n FROM sale_payment p JOIN sale s ON s.id = p.sale_id
                         WHERE s.business_id = :b AND s.cash_register_id = :r AND s.status = 'COMPLETED' AND s.completed_at >= :s AND s.completed_at < :e GROUP BY p.method
                        """)
                .param("b", businessId).param("r", register).param("s", s).param("e", e).query((rs, n) -> {
                    switch (rs.getString("method")) {
                        case "CASH" -> { byMethod[0] = rs.getLong("total"); cashCount[0] = rs.getLong("n"); }
                        case "TRANSFER" -> byMethod[1] = rs.getLong("total");
                        case "CARD" -> byMethod[2] = rs.getLong("total");
                        case "OTHER" -> byMethod[3] = rs.getLong("total");
                        default -> byMethod[4] = rs.getLong("total");
                    }
                    return null;
                }).list();
        long creditCash = scalar("SELECT coalesce(sum(amount_minor), 0) FROM credit_payment WHERE business_id = :b AND cash_register_id = :r AND method = 'CASH' AND voided_at IS NULL AND occurred_at >= :s AND occurred_at < :e", businessId, register, s, e);
        long deposits = scalar("SELECT coalesce(sum(amount_minor), 0) FROM cash_movement WHERE business_id = :b AND cash_register_id = :r AND kind = 'DEPOSIT' AND voided_at IS NULL AND occurred_at >= :s AND occurred_at < :e", businessId, register, s, e);
        long withdrawals = scalar("SELECT coalesce(sum(amount_minor), 0) FROM cash_movement WHERE business_id = :b AND cash_register_id = :r AND kind = 'WITHDRAWAL' AND voided_at IS NULL AND occurred_at >= :s AND occurred_at < :e", businessId, register, s, e);
        long expenses = scalar("SELECT coalesce(sum(amount_minor), 0) FROM expense WHERE business_id = :b AND cash_register_id = :r AND source = 'CASH_DRAWER' AND voided_at IS NULL AND occurred_at >= :s AND occurred_at < :e", businessId, register, s, e);
        long cancelled = scalar("SELECT count(*) FROM sale WHERE business_id = :b AND cash_register_id = :r AND status = 'CANCELLED' AND cancelled_at >= :s AND cancelled_at < :e", businessId, register, s, e);
        long expected = openingFloat + byMethod[0] + creditCash + deposits - expenses - withdrawals;
        return new Breakdown(byMethod[0], cashCount[0], creditCash, deposits, expenses, withdrawals, expected, byMethod[1], byMethod[2], byMethod[3], byMethod[4], cancelled);
    }

    private long scalar(String sql, UUID businessId, UUID register, Timestamp s, Timestamp e) {
        return jdbc.sql(sql).param("b", businessId).param("r", register).param("s", s).param("e", e).query(Long.class).single();
    }

    /** Operaciones dentro del turno cuya revisión es posterior al cierre: llegaron o cambiaron después de cerrar. */
    private long lateOps(UUID businessId, UUID register, Instant start, Instant end, long closedRev) {
        Timestamp s = Timestamp.from(start);
        Timestamp e = Timestamp.from(end);
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM sale WHERE business_id = :b AND cash_register_id = :r AND completed_at >= :s AND completed_at < :e AND rev > :rev)
                             + (SELECT count(*) FROM credit_payment WHERE business_id = :b AND cash_register_id = :r AND occurred_at >= :s AND occurred_at < :e AND rev > :rev)
                             + (SELECT count(*) FROM expense WHERE business_id = :b AND cash_register_id = :r AND occurred_at >= :s AND occurred_at < :e AND rev > :rev)
                             + (SELECT count(*) FROM cash_movement WHERE business_id = :b AND cash_register_id = :r AND occurred_at >= :s AND occurred_at < :e AND rev > :rev)
                        """)
                .param("b", businessId).param("r", register).param("s", s).param("e", e).param("rev", closedRev).query(Long.class).single();
    }

    // ---------- lectura ----------

    private static final String SELECT = """
            SELECT sh.*, r.name AS register_name, mo.display_name AS opened_name, mc.display_name AS closed_name
              FROM shift sh JOIN cash_register r ON r.id = sh.cash_register_id JOIN member mo ON mo.id = sh.opened_by_member_id LEFT JOIN member mc ON mc.id = sh.closed_by_member_id""";

    public ShiftView get(MemberContext ctx, UUID id, boolean withBreakdown) {
        return find(ctx, id, withBreakdown).orElseThrow(() -> ApiException.notFound("SHIFT_NOT_FOUND", "Shift not found"));
    }

    public Optional<ShiftView> find(MemberContext ctx, UUID id, boolean withBreakdown) {
        return jdbc.sql(SELECT + " WHERE sh.id = :id AND sh.business_id = :b").param("id", id).param("b", ctx.businessId()).query((rs, n) -> map(rs, ctx, withBreakdown)).optional();
    }

    public List<ShiftView> viewsByIds(MemberContext ctx, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(SELECT + " WHERE sh.business_id = :b AND sh.id IN (:ids)").param("b", ctx.businessId()).param("ids", ids).query((rs, n) -> map(rs, ctx, false)).list();
    }

    /** El turno abierto de una caja (o de la del teléfono), con su desglose en vivo. */
    public Optional<ShiftView> current(MemberContext ctx, UUID requestedRegister) {
        UUID register = registers.resolve(ctx, requestedRegister);
        return jdbc.sql(SELECT + " WHERE sh.cash_register_id = :r AND sh.status = 'OPEN'").param("r", register).query((rs, n) -> map(rs, ctx, true)).optional();
    }

    /**
     * Historial de turnos. Filtros opcionales: caja, estado (OPEN/CLOSED), persona (quien lo abrió o lo cerró) y rango de jornadas del negocio
     * (por la fecha de apertura). Sin filtros devuelve todo, del más reciente al más viejo.
     */
    public PageResponse<ShiftView> list(MemberContext ctx, UUID register, String status, UUID member, java.time.LocalDate from, java.time.LocalDate to, int page, int size) {
        size = Math.max(1, Math.min(size, 100));
        page = Math.max(0, page);
        if (status != null && !status.equals("OPEN") && !status.equals("CLOSED")) throw ApiException.badRequest("INVALID_STATUS", "Status must be OPEN or CLOSED");
        StringBuilder where = new StringBuilder("sh.business_id = :b");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("b", ctx.businessId());
        if (register != null) { where.append(" AND sh.cash_register_id = :r"); params.put("r", register); }
        if (status != null) { where.append(" AND sh.status = :st"); params.put("st", status); }
        if (member != null) { where.append(" AND (sh.opened_by_member_id = :m OR sh.closed_by_member_id = :m)"); params.put("m", member); }
        if (from != null || to != null) {
            BusinessDayService.Info info = days.info(ctx.businessId());
            if (from != null) { where.append(" AND sh.opened_at >= :from"); params.put("from", Timestamp.from(info.startOf(from))); }
            if (to != null) { where.append(" AND sh.opened_at < :to"); params.put("to", Timestamp.from(info.endOf(to))); }
        }
        var count = jdbc.sql("SELECT count(*) FROM shift sh WHERE " + where);
        var rows = jdbc.sql(SELECT + " WHERE " + where + " ORDER BY sh.opened_at DESC LIMIT " + size + " OFFSET " + (long) page * size);
        for (var e : params.entrySet()) { count = count.param(e.getKey(), e.getValue()); rows = rows.param(e.getKey(), e.getValue()); }
        return PageResponse.of(rows.query((rs, n) -> map(rs, ctx, false)).list(), page, size, count.query(Long.class).single());
    }

    private ShiftView map(java.sql.ResultSet rs, MemberContext ctx, boolean withBreakdown) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID register = rs.getObject("cash_register_id", UUID.class);
        Instant openedAt = rs.getTimestamp("opened_at").toInstant();
        Timestamp closed = rs.getTimestamp("closed_at");
        Instant end = closed == null ? FAR_FUTURE : closed.toInstant();
        long floatMinor = rs.getLong("opening_float_minor");
        UUID openedBy = rs.getObject("opened_by_member_id", UUID.class);
        // Un cajero no ve cómo le fue en el cierre a otro (esperado, contado, diferencia).
        boolean hide = ctx.role() == Role.CASHIER && !ctx.memberId().equals(openedBy);
        Long closedRev = (Long) rs.getObject("closed_rev");
        long late = closedRev == null || hide ? 0 : lateOps(ctx.businessId(), register, openedAt, end, closedRev);
        Breakdown b = withBreakdown && !hide ? breakdown(ctx.businessId(), register, openedAt, end, floatMinor) : null;
        UUID closedById = rs.getObject("closed_by_member_id", UUID.class);
        return new ShiftView(id, register, rs.getString("register_name"), new MemberRef(openedBy, rs.getString("opened_name")), openedAt, floatMinor,
                closedById == null ? null : new MemberRef(closedById, rs.getString("closed_name")), closed == null ? null : closed.toInstant(),
                hide ? null : (Long) rs.getObject("expected_cash_minor"), hide ? null : (Long) rs.getObject("counted_cash_minor"), hide ? null : (Long) rs.getObject("difference_minor"),
                hide ? null : rs.getString("denominations"), hide ? null : rs.getString("note"), rs.getString("status"), hide ? null : rs.getString("forced_reason"), late,
                rs.getInt("reopened_count"), rs.getLong("rev"), b);
    }

}
