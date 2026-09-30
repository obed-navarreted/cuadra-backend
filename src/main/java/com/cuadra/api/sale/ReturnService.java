package com.cuadra.api.sale;

import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.credit.CreditService;
import com.cuadra.api.notification.NotificationService;
import com.cuadra.api.stock.StockService;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devoluciones (docs/adr/0013). Reglas:
 * - Solo de una venta COBRADA; por líneas y cantidades (parcial), nunca más de lo vendido menos lo ya devuelto. Motivo obligatorio (≥ 5 letras).
 * - Cuenta en la jornada en que se HACE (`occurred_at`), no en la de la venta: los días ya cerrados no cambian.
 * - El dinero: CASH (sale del cajón), SAME (por los mismos medios con que se pagó, en proporción) o CREDIT_NOTE (baja el fiado de la venta).
 * - Devuelve existencias (si el producto lleva inventario y la venta ya las había descontado).
 * - Dueño y admins devuelven cualquier venta; un cajero, solo las suyas y de la MISMA jornada.
 * - Idempotente por id (lo genera el teléfono): repetirla no duplica nada. Funciona sin conexión por la cola (`SALE_RETURN`).
 */
@Service
public class ReturnService {
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Set<String> METHODS = Set.of("CASH", "SAME", "CREDIT_NOTE");
    private static final int MAX_ITEMS = 500;

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final BusinessDayService days;
    private final CreditService credits;
    private final StockService stock;
    private final NotificationService notifications;
    private final com.cuadra.api.cash.RegisterResolver registers;

    public ReturnService(JdbcClient jdbc, Audit audit, Clock clock, BusinessDayService days, CreditService credits, StockService stock,
                         NotificationService notifications, com.cuadra.api.cash.RegisterResolver registers) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.days = days;
        this.credits = credits;
        this.stock = stock;
        this.notifications = notifications;
        this.registers = registers;
    }

    public record ReturnItemInput(UUID saleItemId, Long quantityMilli) {}

    /** `occurredAt`: la hora del teléfono al hacerla (sin conexión); se limita a no ser futura ni anterior a la venta. */
    public record ReturnInput(UUID saleId, List<ReturnItemInput> items, String reason, String refundMethod, Instant occurredAt) {}

    public record ReturnItemView(UUID id, UUID saleItemId, UUID productId, String name, long quantityMilli, long amountMinor) {}

    /** method CREDIT = nota de crédito (bajó el fiado `creditId`). */
    public record RefundView(String method, long amountMinor, UUID creditId) {}

    public record ReturnView(UUID id, UUID saleId, String reason, String refundMethod, long totalMinor, SaleService.MemberRef createdBy, Instant occurredAt,
                             List<ReturnItemView> items, List<RefundView> refunds) {}

    public record Result(ReturnView ret, boolean created) {}

    private record Line(UUID id, UUID productId, String name, long qty, long net, Long unitCost) {}

    private record Pay(String method, long amount) {}

    @Transactional
    public Result create(MemberContext ctx, UUID id, ReturnInput in) {
        ctx.require(Permission.SELL);
        if (id == null) throw ApiException.badRequest("INVALID_RETURN", "A return needs an id");
        var existing = jdbc.sql("SELECT sale_id FROM sale_return WHERE id = :id AND business_id = :b").param("id", id).param("b", ctx.businessId())
                .query(UUID.class).optional();
        if (existing.isPresent()) {
            if (in.saleId() != null && !in.saleId().equals(existing.get())) throw ApiException.conflict("ID_TAKEN", "Id already in use");
            return new Result(get(ctx.businessId(), id), false);
        }
        if (jdbc.sql("SELECT count(*) FROM sale_return WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");
        if (in.saleId() == null) throw ApiException.badRequest("INVALID_RETURN", "Say which sale");
        String reason = in.reason() == null ? "" : in.reason().trim();
        if (reason.length() < 5 || reason.length() > 300) throw ApiException.badRequest("REASON_REQUIRED", "A reason of at least 5 characters is required");
        String method = in.refundMethod() == null ? "CASH" : in.refundMethod();
        if (!METHODS.contains(method)) throw ApiException.badRequest("INVALID_REFUND_METHOD", "Refund with CASH, SAME or CREDIT_NOTE");
        List<ReturnItemInput> wanted = in.items() == null ? List.of() : in.items();
        if (wanted.isEmpty()) throw ApiException.badRequest("EMPTY_RETURN", "Choose at least one line to return");
        if (wanted.size() > MAX_ITEMS) throw ApiException.badRequest("TOO_MANY_ITEMS", "Too many lines");

        // La venta se bloquea: dos devoluciones simultáneas de la misma venta no pueden pasarse de lo vendido.
        var sale = jdbc.sql("SELECT status, completed_at, completed_by_member_id, subtotal_minor, discount_minor, total_minor FROM sale WHERE id = :s AND business_id = :b FOR UPDATE")
                .param("s", in.saleId()).param("b", ctx.businessId())
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2), rs.getObject(3, UUID.class), rs.getLong(4), rs.getLong(5), rs.getLong(6)})
                .optional().orElseThrow(() -> ApiException.notFound("SALE_NOT_FOUND", "Sale not found"));
        if (!"COMPLETED".equals(sale[0])) throw ApiException.conflict("SALE_NOT_COMPLETED", "Only a paid sale can be returned");
        Instant completedAt = ((Timestamp) sale[1]).toInstant();
        Instant now = clock.instant();
        Instant at = in.occurredAt() == null || in.occurredAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.occurredAt();
        if (at.isBefore(completedAt)) at = completedAt;

        if (!ctx.role().can(Permission.EDIT_SALES)) {
            // Un cajero: solo sus ventas y en la misma jornada (y la jornada de la venta debe ser de hoy o de ayer por el reloj del servidor, para que un
            // reloj atrasado no abra ventas viejas).
            BusinessDayService.Info info = days.info(ctx.businessId());
            boolean own = ctx.memberId() != null && ctx.memberId().equals(sale[2]);
            boolean sameDay = info.dateOf(completedAt).equals(info.dateOf(at));
            boolean recent = !info.dateOf(completedAt).isBefore(info.dateOf(now).minusDays(1));
            if (!own || !sameDay || !recent) {
                throw ApiException.forbidden("RETURN_NOT_ALLOWED", "A cashier can only return their own sales of the same day; ask the owner or an admin")
                        .with("reason", !own ? "NOT_OWN" : "OTHER_DAY");
            }
        }

        // Líneas de la venta con su neto (el descuento de la cuenta se reparte en proporción) y lo ya devuelto de cada una.
        long subtotal = (Long) sale[3];
        long saleDiscount = (Long) sale[4];
        long saleTotal = (Long) sale[5];
        List<Line> lines = new ArrayList<>();
        List<long[]> raw = new ArrayList<>();
        List<Object[]> meta = new ArrayList<>();
        jdbc.sql("SELECT id, product_id, name, unit_price_minor, quantity_milli, discount_minor, unit_cost_minor FROM sale_item WHERE sale_id = :s ORDER BY position")
                .param("s", in.saleId()).query((rs, n) -> {
                    long qty = rs.getLong("quantity_milli");
                    raw.add(new long[] {SaleMath.lineTotal(rs.getLong("unit_price_minor"), qty, rs.getLong("discount_minor")), qty});
                    meta.add(new Object[] {rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("name"), rs.getObject("unit_cost_minor")});
                    return null;
                }).list();
        long allocated = 0;
        for (int i = 0; i < raw.size(); i++) {
            long lineTotal = raw.get(i)[0];
            long share = i == raw.size() - 1 ? saleDiscount - allocated : (subtotal == 0 ? 0 : mulDivHalfUp(saleDiscount, lineTotal, subtotal));
            allocated += share;
            Object[] m = meta.get(i);
            lines.add(new Line((UUID) m[0], (UUID) m[1], (String) m[2], raw.get(i)[1], Math.max(0, lineTotal - share), m[3] == null ? null : ((Number) m[3]).longValue()));
        }
        Map<UUID, long[]> returned = new LinkedHashMap<>();   // [cantidad, monto]
        jdbc.sql("SELECT i.sale_item_id, sum(i.quantity_milli), sum(i.amount_minor) FROM sale_return_item i JOIN sale_return r ON r.id = i.return_id WHERE r.sale_id = :s GROUP BY 1")
                .param("s", in.saleId()).query((rs, n) -> { returned.put(rs.getObject(1, UUID.class), new long[] {rs.getLong(2), rs.getLong(3)}); return null; }).list();

        record Take(Line line, long qty, long amount) {}
        List<Take> takes = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        long total = 0;
        for (ReturnItemInput w : wanted) {
            if (w.saleItemId() == null || !seen.add(w.saleItemId())) throw ApiException.badRequest("INVALID_ITEM", "Each line once");
            Line line = lines.stream().filter(l -> l.id.equals(w.saleItemId())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("INVALID_ITEM", "That line is not in this sale"));
            long[] done = returned.getOrDefault(line.id, new long[2]);
            long remaining = line.qty - done[0];
            if (w.quantityMilli() == null || w.quantityMilli() <= 0) throw ApiException.badRequest("INVALID_QUANTITY", "Invalid quantity");
            if (w.quantityMilli() > remaining) {
                throw ApiException.conflict("RETURN_EXCEEDS_SOLD", "You cannot return more than was sold").with("saleItemId", line.id.toString())
                        .with("remainingMilli", remaining);
            }
            // Devolver TODO lo que queda de la línea devuelve exactamente su neto (sin residuos de redondeo); una parte, en proporción.
            long amount = w.quantityMilli() == remaining ? line.net - done[1] : mulDivHalfUp(line.net, w.quantityMilli(), line.qty);
            amount = Math.max(0, amount);
            takes.add(new Take(line, w.quantityMilli(), amount));
            total += amount;
        }
        long alreadyRefunded = jdbc.sql("SELECT coalesce(sum(total_minor), 0) FROM sale_return WHERE sale_id = :s").param("s", in.saleId()).query(Long.class).single();
        if (alreadyRefunded + total > saleTotal) total = Math.max(0, saleTotal - alreadyRefunded);   // nunca más de lo cobrado

        // Cómo sale el dinero.
        List<Pay> refunds = new ArrayList<>();
        long creditPart = 0;
        switch (method) {
            case "CASH" -> { if (total > 0) refunds.add(new Pay("CASH", total)); }
            case "CREDIT_NOTE" -> {
                long open = credits.openBalanceOfSale(ctx.businessId(), in.saleId());
                if (open <= 0) throw ApiException.conflict("NO_CREDIT_TO_REDUCE", "This sale has no open credit to reduce");
                if (total > open) throw ApiException.conflict("CREDIT_NOTE_EXCEEDS", "The return is larger than what is still owed").with("availableMinor", open);
                creditPart = total;
            }
            default -> {
                // Por los mismos medios, en proporción a lo que queda de cada uno (lo ya devuelto por ese medio se descuenta).
                Map<String, Long> paid = new LinkedHashMap<>();
                jdbc.sql("SELECT method, sum(amount_minor) FROM sale_payment WHERE sale_id = :s GROUP BY method ORDER BY min(position)").param("s", in.saleId())
                        .query((rs, n) -> { paid.put(rs.getString(1), rs.getLong(2)); return null; }).list();
                jdbc.sql("SELECT f.method, sum(f.amount_minor) FROM sale_return_refund f JOIN sale_return r ON r.id = f.return_id WHERE r.sale_id = :s GROUP BY 1").param("s", in.saleId())
                        .query((rs, n) -> { long done = rs.getLong(2); paid.computeIfPresent(rs.getString(1), (k, v) -> Math.max(0, v - done)); return null; }).list();
                long base = paid.values().stream().mapToLong(Long::longValue).sum();
                List<String> methods = paid.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
                if (base <= 0 || methods.isEmpty()) {
                    if (total > 0) refunds.add(new Pay("CASH", total));
                } else {
                    long left = total;
                    for (int i = 0; i < methods.size(); i++) {
                        String m = methods.get(i);
                        long part = i == methods.size() - 1 ? left : Math.min(left, mulDivHalfUp(total, paid.get(m), base));
                        left -= part;
                        if (part <= 0) continue;
                        if ("CREDIT".equals(m)) creditPart += part;
                        else refunds.add(new Pay(m, part));
                    }
                }
                // Lo que iba al fiado no puede pasar de lo que aún se debe: el resto se devuelve en efectivo.
                if (creditPart > 0) {
                    long open = credits.openBalanceOfSale(ctx.businessId(), in.saleId());
                    if (creditPart > open) {
                        long overflow = creditPart - open;
                        creditPart = open;
                        addTo(refunds, "CASH", overflow);
                    }
                }
            }
        }

        UUID register = registers.resolve(ctx, null);
        jdbc.sql("""
                        INSERT INTO sale_return (id, business_id, sale_id, reason, refund_method, total_minor, created_by_member_id, device_id, cash_register_id, occurred_at)
                        VALUES (:id, :b, :s, :r, :m, :t, :by, :d, :reg, :at)""")
                .param("id", id).param("b", ctx.businessId()).param("s", in.saleId()).param("r", reason).param("m", method).param("t", total)
                .param("by", ctx.memberId()).param("d", ctx.deviceId(), java.sql.Types.OTHER).param("reg", register, java.sql.Types.OTHER).param("at", Timestamp.from(at)).update();
        int pos = 0;
        for (Take t : takes) {
            UUID itemId = derive(id, "item:" + t.line.id);
            jdbc.sql("""
                            INSERT INTO sale_return_item (id, return_id, business_id, sale_item_id, product_id, name, quantity_milli, amount_minor, unit_cost_minor, position)
                            VALUES (:id, :r, :b, :si, :p, :n, :q, :a, :c, :pos)""")
                    .param("id", itemId).param("r", id).param("b", ctx.businessId()).param("si", t.line.id).param("p", t.line.productId, java.sql.Types.OTHER)
                    .param("n", t.line.name).param("q", t.qty).param("a", t.amount).param("c", t.line.unitCost, java.sql.Types.BIGINT).param("pos", pos++).update();
            if (t.line.productId != null) stock.recordReturn(ctx, derive(id, "stock:" + t.line.id), t.line.productId, t.qty, in.saleId(), id, completedAt, at);
        }
        pos = 0;
        for (Pay p : refunds) insertRefund(ctx.businessId(), id, derive(id, "refund:" + p.method), p.method, p.amount, null, pos++);
        if (creditPart > 0) {
            for (var note : credits.applyCreditNote(ctx, in.saleId(), creditPart, id)) {
                insertRefund(ctx.businessId(), id, derive(id, "refund:CREDIT:" + note.creditId()), "CREDIT", note.amountMinor(), note.creditId(), pos++);
            }
        }
        // La venta cambia (ahora trae su devolución): los teléfonos la vuelven a bajar.
        jdbc.sql("UPDATE sale SET rev = nextval('change_rev_seq'), updated_at = :now WHERE id = :s").param("s", in.saleId()).param("now", Timestamp.from(now)).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.return", "sale", in.saleId(), "return=" + id + " total=" + total + " method=" + method + ": " + reason);
        String member = ctx.memberId() == null ? "" : jdbc.sql("SELECT display_name FROM member WHERE id = :m").param("m", ctx.memberId()).query(String.class).optional().orElse("");
        notifications.notify(ctx.businessId(), NotificationService.Type.SALE_RETURNED,
                Map.of("saleId", in.saleId().toString(), "returnId", id.toString(), "memberName", member, "totalMinor", total, "reason", reason),
                null, ctx.memberId(), "SALE_RETURNED:" + id, "cuadra://ventas");
        return new Result(get(ctx.businessId(), id), true);
    }

    private static void addTo(List<Pay> refunds, String method, long amount) {
        for (int i = 0; i < refunds.size(); i++) {
            if (refunds.get(i).method.equals(method)) {
                refunds.set(i, new Pay(method, refunds.get(i).amount + amount));
                return;
            }
        }
        refunds.add(new Pay(method, amount));
    }

    private void insertRefund(UUID businessId, UUID returnId, UUID id, String method, long amount, UUID creditId, int pos) {
        if (amount <= 0) return;
        jdbc.sql("INSERT INTO sale_return_refund (id, return_id, business_id, method, amount_minor, credit_id, position) VALUES (:id, :r, :b, :m, :a, :c, :pos)")
                .param("id", id).param("r", returnId).param("b", businessId).param("m", method).param("a", amount).param("c", creditId, java.sql.Types.OTHER).param("pos", pos).update();
    }

    private static UUID derive(UUID returnId, String what) {
        return UUID.nameUUIDFromBytes(("return:" + returnId + ":" + what).getBytes(StandardCharsets.UTF_8));
    }

    /** round-half-up(a × b / c) sin desbordes. */
    static long mulDivHalfUp(long a, long b, long c) {
        if (c == 0) return 0;
        BigInteger[] qr = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divideAndRemainder(BigInteger.valueOf(c));
        long q = qr[0].longValueExact();
        return qr[1].shiftLeft(1).compareTo(BigInteger.valueOf(c)) >= 0 ? q + 1 : q;
    }

    public boolean hasReturns(UUID saleId) {
        return jdbc.sql("SELECT count(*) FROM sale_return WHERE sale_id = :s").param("s", saleId).query(Integer.class).single() > 0;
    }

    // ---------- lectura ----------

    public ReturnView get(UUID businessId, UUID id) {
        return bySales(businessId, null, id).stream().findFirst().orElseThrow(() -> ApiException.notFound("RETURN_NOT_FOUND", "Return not found"));
    }

    /** Devoluciones de varias ventas (para la vista de la venta), en orden. */
    public Map<UUID, List<ReturnView>> forSales(UUID businessId, List<UUID> saleIds) {
        Map<UUID, List<ReturnView>> out = new LinkedHashMap<>();
        if (saleIds.isEmpty()) return out;
        for (ReturnView r : bySales(businessId, saleIds, null)) out.computeIfAbsent(r.saleId(), k -> new ArrayList<>()).add(r);
        return out;
    }

    private List<ReturnView> bySales(UUID businessId, List<UUID> saleIds, UUID id) {
        String where = id != null ? "r.id = :id" : "r.sale_id IN (:ids)";
        var q = jdbc.sql("SELECT r.*, m.display_name FROM sale_return r JOIN member m ON m.id = r.created_by_member_id WHERE r.business_id = :b AND " + where + " ORDER BY r.occurred_at, r.id")
                .param("b", businessId);
        q = id != null ? q.param("id", id) : q.param("ids", saleIds);
        List<ReturnView> heads = q.query((rs, n) -> new ReturnView(rs.getObject("id", UUID.class), rs.getObject("sale_id", UUID.class), rs.getString("reason"),
                rs.getString("refund_method"), rs.getLong("total_minor"), new SaleService.MemberRef(rs.getObject("created_by_member_id", UUID.class), rs.getString("display_name")),
                rs.getTimestamp("occurred_at").toInstant(), List.of(), List.of())).list();
        if (heads.isEmpty()) return heads;
        List<UUID> ids = heads.stream().map(ReturnView::id).toList();
        Map<UUID, List<ReturnItemView>> items = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM sale_return_item WHERE return_id IN (:ids) ORDER BY return_id, position").param("ids", ids).query((rs, n) -> {
            items.computeIfAbsent(rs.getObject("return_id", UUID.class), k -> new ArrayList<>()).add(new ReturnItemView(rs.getObject("id", UUID.class),
                    rs.getObject("sale_item_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("name"), rs.getLong("quantity_milli"), rs.getLong("amount_minor")));
            return null;
        }).list();
        Map<UUID, List<RefundView>> refunds = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM sale_return_refund WHERE return_id IN (:ids) ORDER BY return_id, position").param("ids", ids).query((rs, n) -> {
            refunds.computeIfAbsent(rs.getObject("return_id", UUID.class), k -> new ArrayList<>()).add(new RefundView(rs.getString("method"), rs.getLong("amount_minor"),
                    rs.getObject("credit_id", UUID.class)));
            return null;
        }).list();
        return heads.stream().map(h -> new ReturnView(h.id(), h.saleId(), h.reason(), h.refundMethod(), h.totalMinor(), h.createdBy(), h.occurredAt(),
                items.getOrDefault(h.id(), List.of()), refunds.getOrDefault(h.id(), List.of()))).toList();
    }
}
