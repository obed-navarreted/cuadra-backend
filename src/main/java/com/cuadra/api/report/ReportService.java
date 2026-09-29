package com.cuadra.api.report;

import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.common.ApiException;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reportes del negocio (PLAN.md 5.9). Todo es SQL agregado sobre las tablas de las fases anteriores; las fechas son jornadas del negocio (con su
 * hora de corte y su zona horaria), no días de calendario.
 *
 * Fórmula de la ganancia estimada (se muestra tal cual en pantalla):
 *   ganancia = ventas − costo de lo vendido − gastos operativos
 * · ventas: ventas cobradas (ya con el descuento de la cuenta); las ventas a fiado cuentan cuando se venden, no cuando se cobran;
 * · costo de lo vendido: cantidad × el costo que tenía CADA línea al venderse (las líneas sin costo no restan nada: por eso se informa la cobertura);
 * · gastos operativos: gastos no anulados, SIN las compras de mercadería (pagos a proveedor y la categoría "Mercadería"), que ya se reflejan en el costo;
 * · los retiros de caja no son gastos y nunca entran.
 */
@Service
public class ReportService {
    private static final int MAX_DAYS = 366;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final BusinessDayService days;
    private final com.cuadra.api.plan.PlanService plans;

    public ReportService(JdbcClient jdbc, Clock clock, BusinessDayService days, com.cuadra.api.plan.PlanService plans) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.days = days;
        this.plans = plans;
    }

    // ---------- contrato ----------

    public record Range(LocalDate from, LocalDate to, Timestamp start, Timestamp end, ZoneId zone, String cutoff, int decimals, String currency, String locale) {}

    public record Sales(long count, long totalMinor, long discountMinor, long averageTicketMinor, long cancelledCount) {}

    public record MethodAmount(String method, long amountMinor) {}

    public record DayPoint(LocalDate date, long salesMinor, long expensesMinor) {}

    public record Profit(long salesMinor, long costOfGoodsMinor, long operatingExpensesMinor, long purchasesExcludedMinor, long estimatedProfitMinor, int costCoveragePercent, String formula) {}

    public record Product(UUID productId, String name, long quantityMilli, long revenueMinor, long costMinor, long profitMinor, boolean fullyCosted) {}

    public record Bucket(String key, int fromDays, Integer toDays, long amountMinor, long count) {}

    public record Debtor(UUID customerId, String name, long balanceMinor, long oldestDays, long paidLast90Minor) {}

    public record Receivables(long totalMinor, long openCount, List<Bucket> buckets, List<Debtor> worst, List<Debtor> best) {}

    public record Row(String key, String label, long count, long totalMinor) {}

    public record CategoryAmount(UUID categoryId, String key, String name, long amountMinor) {}

    public record ExpenseReport(long operatingMinor, long purchasesMinor, long cashDrawerMinor, long otherMinor, List<CategoryAmount> byCategory) {}

    public record ShiftLine(UUID shiftId, String register, java.time.Instant closedAt, long expectedMinor, long countedMinor, long differenceMinor) {}

    public record MemberClosings(UUID memberId, String name, long shifts, long differenceMinor, long absoluteDifferenceMinor, List<ShiftLine> lines) {}

    public record StockLine(UUID productId, String name, String unit, long stockMilli, Long minStockMilli, Long costMinor, Long daysSinceLastSale) {}

    public record Inventory(long valueAtCostMinor, long trackedCount, long trackedWithoutCost, List<StockLine> low, List<StockLine> noMovement) {}

    /** Ventas del periodo con su desglose por método de pago. */
    public record SalesReport(Range range, Sales sales, List<MethodAmount> byMethod) {}

    /**
     * Cierre automático de UNA jornada del negocio (de la hora de corte de un día a la del siguiente): nadie abre ni cierra nada. Lo vendido a la 1 a. m.
     * cuenta para el día anterior. `expectedCashMinor` = ventas en efectivo + abonos en efectivo + entradas − gastos del cajón − retiros.
     */
    public record DayClose(LocalDate date, java.time.Instant startsAt, java.time.Instant endsAt, long salesCount, long salesMinor, List<MethodAmount> byMethod,
                           List<MethodAmount> creditCollected, long drawerExpensesMinor, long otherExpensesMinor, long withdrawalsMinor, long depositsMinor,
                           long expectedCashMinor, long cancelledCount, long cancelledMinor) {}

    public record DailyClose(Range range, List<DayClose> days) {}

    public record Overview(Range range, Sales sales, List<MethodAmount> byMethod, Profit profit, long receivableMinor, List<DayPoint> series, List<Product> topProducts, long lowStockCount, MemberClosings lastClosing) {}

    // ---------- rango ----------

    public Range range(UUID businessId, LocalDate from, LocalDate to) {
        BusinessDayService.Info info = days.info(businessId);
        LocalDate today = info.dateOf(clock.instant());
        LocalDate f = from != null ? from : (to != null ? to : today);
        LocalDate t = to != null ? to : (from != null ? today : f);
        if (t.isBefore(f)) throw ApiException.badRequest("INVALID_RANGE", "The end date is before the start date");
        plans.requireReportRange(businessId, f);
        if (ChronoUnit.DAYS.between(f, t) >= MAX_DAYS) throw ApiException.badRequest("RANGE_TOO_LONG", "At most " + MAX_DAYS + " days");
        var b = jdbc.sql("SELECT currency, default_locale FROM business WHERE id = :b").param("b", businessId).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).single();
        int decimals = switch (b[0]) { case "CRC", "COP", "CLP", "PYG" -> 0; default -> 2; };
        return new Range(f, t, Timestamp.from(info.startOf(f)), Timestamp.from(info.endOf(t)), info.zone(), info.cutoff().toString(), decimals, b[0], b[1]);
    }

    private static final String SALE_IN_RANGE = "s.business_id = :b AND s.status = 'COMPLETED' AND s.completed_at >= :s AND s.completed_at < :e";

    private JdbcClient.StatementSpec q(String sql, UUID business, Range r) {
        var spec = jdbc.sql(sql).param("b", business);
        if (sql.contains(":s")) spec = spec.param("s", r.start());
        if (sql.contains(":e")) spec = spec.param("e", r.end());
        if (sql.contains(":tz")) spec = spec.param("tz", r.zone().getId());
        if (sql.contains(":cutoff")) spec = spec.param("cutoff", r.cutoff());
        return spec;
    }

    /** Día comercial de una columna de fecha: la hora local menos la hora de corte. */
    private static String businessDate(String column) {
        // Con la regla vigente en cada instante (el historial de zona/corte vive en `business_day_rule`): cambiar el corte no reagrupa días pasados.
        return "business_date(:b, " + column + ")";
    }

    // ---------- ventas ----------

    public DailyClose dailyClose(MemberContext ctx, Range r) {
        ctx.require(Permission.VIEW_REPORTS);
        UUID b = ctx.businessId();
        BusinessDayService.Info info = days.info(b);
        java.util.Map<LocalDate, long[]> sales = new java.util.HashMap<>();
        q("SELECT " + businessDate("s.completed_at") + ", count(*), coalesce(sum(s.total_minor), 0) FROM sale s WHERE " + SALE_IN_RANGE + " GROUP BY 1", b, r)
                .query((rs, n) -> { sales.put(rs.getObject(1, LocalDate.class), new long[] {rs.getLong(2), rs.getLong(3)}); return null; }).list();
        java.util.Map<LocalDate, long[]> cancelled = new java.util.HashMap<>();
        q("SELECT " + businessDate("s.cancelled_at") + ", count(*), coalesce(sum(s.total_minor), 0) FROM sale s WHERE s.business_id = :b AND s.status = 'CANCELLED' AND s.cancelled_at >= :s AND s.cancelled_at < :e GROUP BY 1", b, r)
                .query((rs, n) -> { cancelled.put(rs.getObject(1, LocalDate.class), new long[] {rs.getLong(2), rs.getLong(3)}); return null; }).list();
        java.util.Map<LocalDate, java.util.Map<String, Long>> paid = new java.util.HashMap<>();
        q("SELECT " + businessDate("s.completed_at") + ", p.method, sum(p.amount_minor) FROM sale s JOIN sale_payment p ON p.sale_id = s.id WHERE " + SALE_IN_RANGE + " GROUP BY 1, 2", b, r)
                .query((rs, n) -> { paid.computeIfAbsent(rs.getObject(1, LocalDate.class), k -> new java.util.TreeMap<>()).put(rs.getString(2), rs.getLong(3)); return null; }).list();
        java.util.Map<LocalDate, java.util.Map<String, Long>> collected = new java.util.HashMap<>();
        q("SELECT " + businessDate("c.occurred_at") + ", c.method, sum(c.amount_minor) FROM credit_payment c WHERE c.business_id = :b AND c.voided_at IS NULL AND c.occurred_at >= :s AND c.occurred_at < :e GROUP BY 1, 2", b, r)
                .query((rs, n) -> { collected.computeIfAbsent(rs.getObject(1, LocalDate.class), k -> new java.util.TreeMap<>()).put(rs.getString(2), rs.getLong(3)); return null; }).list();
        java.util.Map<LocalDate, long[]> expenses = new java.util.HashMap<>();   // [cajón, otros]
        q("SELECT " + businessDate("e.occurred_at") + ", e.source = 'CASH_DRAWER', sum(e.amount_minor) FROM expense e WHERE e.business_id = :b AND e.voided_at IS NULL AND e.occurred_at >= :s AND e.occurred_at < :e GROUP BY 1, 2", b, r)
                .query((rs, n) -> { expenses.computeIfAbsent(rs.getObject(1, LocalDate.class), k -> new long[2])[rs.getBoolean(2) ? 0 : 1] = rs.getLong(3); return null; }).list();
        java.util.Map<LocalDate, long[]> moves = new java.util.HashMap<>();      // [retiros, entradas]
        q("SELECT " + businessDate("m.occurred_at") + ", m.kind, sum(m.amount_minor) FROM cash_movement m WHERE m.business_id = :b AND m.voided_at IS NULL AND m.occurred_at >= :s AND m.occurred_at < :e GROUP BY 1, 2", b, r)
                .query((rs, n) -> { moves.computeIfAbsent(rs.getObject(1, LocalDate.class), k -> new long[2])["WITHDRAWAL".equals(rs.getString(2)) ? 0 : 1] = rs.getLong(3); return null; }).list();

        List<DayClose> out = new java.util.ArrayList<>();
        long[] none = new long[2];
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
            long[] sv = sales.getOrDefault(d, none);
            long[] cv = cancelled.getOrDefault(d, none);
            java.util.Map<String, Long> pm = paid.getOrDefault(d, java.util.Map.of());
            java.util.Map<String, Long> cm = collected.getOrDefault(d, java.util.Map.of());
            long[] ev = expenses.getOrDefault(d, none);
            long[] mv = moves.getOrDefault(d, none);
            long expected = pm.getOrDefault("CASH", 0L) + cm.getOrDefault("CASH", 0L) + mv[1] - ev[0] - mv[0];
            out.add(new DayClose(d, info.startOf(d), info.endOf(d), sv[0], sv[1], amounts(pm), amounts(cm), ev[0], ev[1], mv[0], mv[1], expected, cv[0], cv[1]));
        }
        return new DailyClose(r, out);
    }

    private static List<MethodAmount> amounts(java.util.Map<String, Long> m) {
        return m.entrySet().stream().map(e -> new MethodAmount(e.getKey(), e.getValue())).toList();
    }

    public Sales sales(MemberContext ctx, Range r) {
        ctx.require(Permission.VIEW_REPORTS);
        long[] v = q("SELECT count(*), coalesce(sum(total_minor), 0), coalesce(sum(discount_minor), 0) FROM sale s WHERE " + SALE_IN_RANGE, ctx.businessId(), r)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
        long cancelled = q("SELECT count(*) FROM sale s WHERE s.business_id = :b AND s.status = 'CANCELLED' AND s.cancelled_at >= :s AND s.cancelled_at < :e", ctx.businessId(), r).query(Long.class).single();
        return new Sales(v[0], v[1], v[2], v[0] == 0 ? 0 : Math.round((double) v[1] / v[0]), cancelled);
    }

    public List<MethodAmount> byMethod(MemberContext ctx, Range r) {
        ctx.require(Permission.VIEW_REPORTS);
        return q("SELECT p.method, sum(p.amount_minor) FROM sale_payment p JOIN sale s ON s.id = p.sale_id WHERE " + SALE_IN_RANGE + " GROUP BY p.method ORDER BY 2 DESC", ctx.businessId(), r)
                .query((rs, n) -> new MethodAmount(rs.getString(1), rs.getLong(2))).list();
    }

    /** by = member | register | method | hour | day */
    public List<Row> breakdown(MemberContext ctx, Range r, String by) {
        ctx.require(Permission.VIEW_REPORTS);
        String sql = switch (by) {
            case "member" -> "SELECT s.completed_by_member_id::text, coalesce(m.display_name, '—'), count(*), sum(s.total_minor) FROM sale s LEFT JOIN member m ON m.id = s.completed_by_member_id WHERE " + SALE_IN_RANGE + " GROUP BY 1, 2 ORDER BY 4 DESC";
            case "register" -> "SELECT s.cash_register_id::text, coalesce(c.name, '—'), count(*), sum(s.total_minor) FROM sale s LEFT JOIN cash_register c ON c.id = s.cash_register_id WHERE " + SALE_IN_RANGE + " GROUP BY 1, 2 ORDER BY 4 DESC";
            case "method" -> "SELECT p.method, p.method, count(DISTINCT s.id), sum(p.amount_minor) FROM sale_payment p JOIN sale s ON s.id = p.sale_id WHERE " + SALE_IN_RANGE + " GROUP BY 1, 2 ORDER BY 4 DESC";
            case "hour" -> "SELECT lpad(extract(hour FROM s.completed_at AT TIME ZONE :tz)::int::text, 2, '0'), lpad(extract(hour FROM s.completed_at AT TIME ZONE :tz)::int::text, 2, '0') || ':00', count(*), sum(s.total_minor) FROM sale s WHERE " + SALE_IN_RANGE + " GROUP BY 1, 2 ORDER BY 1";
            case "day" -> "SELECT " + businessDate("s.completed_at") + "::text, " + businessDate("s.completed_at") + "::text, count(*), sum(s.total_minor) FROM sale s WHERE " + SALE_IN_RANGE + " GROUP BY 1, 2 ORDER BY 1";
            default -> throw ApiException.badRequest("INVALID_GROUPING", "Group by member, register, method, hour or day");
        };
        return q(sql, ctx.businessId(), r).query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4))).list();
    }

    // ---------- ganancia ----------

    public Profit profit(MemberContext ctx, Range r) {
        ctx.require(Permission.VIEW_REPORTS);
        long sales = sales(ctx, r).totalMinor();
        long[] lines = q("""
                        SELECT coalesce(sum(CASE WHEN i.unit_cost_minor IS NOT NULL THEN floor((i.unit_cost_minor::numeric * i.quantity_milli + 500) / 1000) END), 0)::bigint,
                               coalesce(sum(CASE WHEN i.unit_cost_minor IS NOT NULL THEN floor((i.unit_price_minor::numeric * i.quantity_milli + 500) / 1000) - i.discount_minor END), 0)::bigint,
                               coalesce(sum(floor((i.unit_price_minor::numeric * i.quantity_milli + 500) / 1000) - i.discount_minor), 0)::bigint
                          FROM sale_item i JOIN sale s ON s.id = i.sale_id """ + " WHERE " + SALE_IN_RANGE, ctx.businessId(), r)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
        long[] ex = expenseSplit(ctx.businessId(), r);
        long cogs = lines[0];
        int coverage = lines[2] == 0 ? 100 : (int) Math.round(100.0 * lines[1] / lines[2]);
        return new Profit(sales, cogs, ex[0], ex[1], sales - cogs - ex[0], coverage,
                "ganancia = ventas − costo de lo vendido − gastos operativos (sin compras de mercadería ni retiros de caja)");
    }

    /** [operativos, compras de mercadería excluidas] */
    private long[] expenseSplit(UUID business, Range r) {
        return q("""
                        SELECT coalesce(sum(CASE WHEN e.ref_type = 'SUPPLIER_PAYMENT' OR c.key = 'goods' THEN 0 ELSE e.amount_minor END), 0)::bigint,
                               coalesce(sum(CASE WHEN e.ref_type = 'SUPPLIER_PAYMENT' OR c.key = 'goods' THEN e.amount_minor ELSE 0 END), 0)::bigint
                          FROM expense e LEFT JOIN expense_category c ON c.id = e.category_id
                         WHERE e.business_id = :b AND e.voided_at IS NULL AND e.occurred_at >= :s AND e.occurred_at < :e""", business, r)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
    }

    // ---------- productos ----------

    /** sort = revenue | quantity | profit */
    public List<Product> topProducts(MemberContext ctx, Range r, String sort, int limit) {
        ctx.require(Permission.VIEW_REPORTS);
        String order = switch (sort == null ? "revenue" : sort) {
            case "quantity" -> "quantity DESC";
            case "profit" -> "profit DESC";
            case "revenue" -> "revenue DESC";
            default -> throw ApiException.badRequest("INVALID_SORT", "Sort by revenue, quantity or profit");
        };
        int size = Math.max(1, Math.min(limit, 200));
        return q("SELECT coalesce(i.product_id::text, 'n:' || i.name) AS k, (array_agg(i.product_id))[1] AS pid, max(i.name) AS name, sum(i.quantity_milli)::bigint AS quantity, "
                + "sum(floor((i.unit_price_minor::numeric * i.quantity_milli + 500) / 1000) - i.discount_minor)::bigint AS revenue, "
                + "coalesce(sum(CASE WHEN i.unit_cost_minor IS NOT NULL THEN floor((i.unit_cost_minor::numeric * i.quantity_milli + 500) / 1000) END), 0)::bigint AS cost, "
                + "coalesce(sum(CASE WHEN i.unit_cost_minor IS NOT NULL THEN floor((i.unit_price_minor::numeric * i.quantity_milli + 500) / 1000) - i.discount_minor - floor((i.unit_cost_minor::numeric * i.quantity_milli + 500) / 1000) END), 0)::bigint AS profit, "
                + "bool_and(i.unit_cost_minor IS NOT NULL) AS costed "
                + "FROM sale_item i JOIN sale s ON s.id = i.sale_id WHERE " + SALE_IN_RANGE + " GROUP BY 1 ORDER BY " + order + ", 3 LIMIT " + size, ctx.businessId(), r)
                .query((rs, n) -> new Product(rs.getObject("pid", UUID.class), rs.getString("name"), rs.getLong("quantity"), rs.getLong("revenue"), rs.getLong("cost"), rs.getLong("profit"), rs.getBoolean("costed"))).list();
    }

    // ---------- por cobrar ----------

    public Receivables receivables(MemberContext ctx) {
        ctx.require(Permission.VIEW_REPORTS);
        BusinessDayService.Info info = days.info(ctx.businessId());
        LocalDate today = info.dateOf(clock.instant());
        record C(UUID customer, String label, long balance, LocalDate created) {}
        List<C> open = jdbc.sql("SELECT customer_id, debtor_label, balance_minor, created_at FROM credit WHERE business_id = :b AND status = 'OPEN' AND balance_minor > 0").param("b", ctx.businessId())
                .query((rs, n) -> new C(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), info.dateOf(rs.getTimestamp(4).toInstant()))).list();
        long[][] buckets = new long[4][2];
        int[] from = {0, 16, 31, 61};
        Integer[] to = {15, 30, 60, null};
        Map<String, long[]> byDebtor = new LinkedHashMap<>();
        Map<String, UUID> ids = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        long total = 0;
        for (C c : open) {
            long age = Math.max(0, ChronoUnit.DAYS.between(c.created(), today));
            int i = age <= 15 ? 0 : age <= 30 ? 1 : age <= 60 ? 2 : 3;
            buckets[i][0] += c.balance();
            buckets[i][1]++;
            total += c.balance();
            String key = c.customer() != null ? c.customer().toString() : "n:" + c.label();
            long[] agg = byDebtor.computeIfAbsent(key, k -> new long[] {0, 0});
            agg[0] += c.balance();
            agg[1] = Math.max(agg[1], age);
            ids.put(key, c.customer());
            names.put(key, c.customer() != null ? null : c.label());
        }
        Map<UUID, String> customerNames = new LinkedHashMap<>();
        Map<UUID, Long> paid = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM customer WHERE business_id = :b").param("b", ctx.businessId()).query((rs, n) -> { customerNames.put(rs.getObject(1, UUID.class), rs.getString(2)); return null; }).list();
        jdbc.sql("SELECT customer_id, sum(amount_minor) FROM credit_payment WHERE business_id = :b AND voided_at IS NULL AND customer_id IS NOT NULL AND occurred_at >= :since GROUP BY 1")
                .param("b", ctx.businessId()).param("since", Timestamp.from(clock.instant().minus(90, ChronoUnit.DAYS))).query((rs, n) -> { paid.put(rs.getObject(1, UUID.class), rs.getLong(2)); return null; }).list();
        List<Debtor> worst = byDebtor.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(10).map(e -> {
            UUID id = ids.get(e.getKey());
            return new Debtor(id, id != null ? customerNames.getOrDefault(id, "—") : names.get(e.getKey()), e.getValue()[0], e.getValue()[1], id == null ? 0 : paid.getOrDefault(id, 0L));
        }).toList();
        List<Debtor> best = paid.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(10).map(e -> {
            long[] agg = byDebtor.get(e.getKey().toString());
            return new Debtor(e.getKey(), customerNames.getOrDefault(e.getKey(), "—"), agg == null ? 0 : agg[0], agg == null ? 0 : agg[1], e.getValue());
        }).toList();
        List<Bucket> out = new ArrayList<>();
        String[] keys = {"0-15", "16-30", "31-60", "60+"};
        for (int i = 0; i < 4; i++) out.add(new Bucket(keys[i], from[i], to[i], buckets[i][0], buckets[i][1]));
        return new Receivables(total, open.size(), out, worst, best);
    }

    // ---------- gastos ----------

    public ExpenseReport expenses(MemberContext ctx, Range r) {
        ctx.require(Permission.VIEW_REPORTS);
        long[] split = expenseSplit(ctx.businessId(), r);
        long[] sources = q("SELECT coalesce(sum(CASE WHEN source = 'CASH_DRAWER' THEN amount_minor END), 0)::bigint, coalesce(sum(CASE WHEN source <> 'CASH_DRAWER' THEN amount_minor END), 0)::bigint "
                + "FROM expense e WHERE e.business_id = :b AND e.voided_at IS NULL AND e.occurred_at >= :s AND e.occurred_at < :e", ctx.businessId(), r).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        List<CategoryAmount> cats = q("SELECT e.category_id, c.key, c.name, sum(e.amount_minor)::bigint FROM expense e LEFT JOIN expense_category c ON c.id = e.category_id "
                + "WHERE e.business_id = :b AND e.voided_at IS NULL AND e.occurred_at >= :s AND e.occurred_at < :e GROUP BY 1, 2, 3 ORDER BY 4 DESC", ctx.businessId(), r)
                .query((rs, n) -> new CategoryAmount(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4))).list();
        return new ExpenseReport(split[0], split[1], sources[0], sources[1], cats);
    }

    // ---------- cierres ----------

    public List<MemberClosings> closings(MemberContext ctx, Range r) {
        ctx.require(Permission.VIEW_REPORTS);
        record L(UUID member, String name, ShiftLine line) {}
        List<L> rows = q("""
                        SELECT s.id, s.closed_by_member_id, coalesce(m.display_name, '—'), coalesce(c.name, '—'), s.closed_at, s.expected_cash_minor, s.counted_cash_minor, s.difference_minor
                          FROM shift s LEFT JOIN member m ON m.id = s.closed_by_member_id LEFT JOIN cash_register c ON c.id = s.cash_register_id
                         WHERE s.business_id = :b AND s.status = 'CLOSED' AND s.closed_at >= :s AND s.closed_at < :e ORDER BY s.closed_at DESC""", ctx.businessId(), r)
                .query((rs, n) -> new L(rs.getObject(2, UUID.class), rs.getString(3), new ShiftLine(rs.getObject(1, UUID.class), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getLong(6), rs.getLong(7), rs.getLong(8)))).list();
        Map<UUID, List<L>> byMember = new LinkedHashMap<>();
        for (L l : rows) byMember.computeIfAbsent(l.member(), k -> new ArrayList<>()).add(l);
        return byMember.values().stream().map(list -> new MemberClosings(list.get(0).member(), list.get(0).name(), list.size(),
                list.stream().mapToLong(l -> l.line().differenceMinor()).sum(), list.stream().mapToLong(l -> Math.abs(l.line().differenceMinor())).sum(), list.stream().map(L::line).toList()))
                .sorted(Comparator.comparingLong(MemberClosings::absoluteDifferenceMinor).reversed()).toList();
    }

    // ---------- inventario ----------

    public Inventory inventory(MemberContext ctx) {
        ctx.require(Permission.VIEW_REPORTS);
        long[] v = jdbc.sql("""
                        SELECT coalesce(sum(CASE WHEN stock_milli > 0 AND cost_minor IS NOT NULL THEN floor((cost_minor::numeric * stock_milli + 500) / 1000) END), 0)::bigint,
                               count(*), count(*) FILTER (WHERE cost_minor IS NULL)
                          FROM product WHERE business_id = :b AND active AND track_stock""").param("b", ctx.businessId())
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
        String base = "SELECT p.id, p.name, p.unit, p.stock_milli, p.min_stock_milli, p.cost_minor, "
                + "(SELECT floor(extract(epoch FROM (:now - max(m.occurred_at))) / 86400)::bigint FROM stock_movement m WHERE m.product_id = p.id AND m.kind = 'SALE') AS days_since ";
        List<StockLine> low = jdbc.sql(base + "FROM product p WHERE p.business_id = :b AND p.active AND p.track_stock AND (p.stock_milli < 0 OR (p.min_stock_milli IS NOT NULL AND p.stock_milli <= p.min_stock_milli)) ORDER BY p.stock_milli, lower(p.name)")
                .param("b", ctx.businessId()).param("now", Timestamp.from(clock.instant())).query((rs, n) -> line(rs)).list();
        List<StockLine> idle = jdbc.sql(base + "FROM product p WHERE p.business_id = :b AND p.active AND p.track_stock AND p.stock_milli > 0 AND NOT EXISTS (SELECT 1 FROM stock_movement m WHERE m.product_id = p.id AND m.kind = 'SALE' AND m.occurred_at >= :since) ORDER BY p.stock_milli DESC, lower(p.name) LIMIT 100")
                .param("b", ctx.businessId()).param("now", Timestamp.from(clock.instant())).param("since", Timestamp.from(clock.instant().minus(30, ChronoUnit.DAYS))).query((rs, n) -> line(rs)).list();
        return new Inventory(v[0], v[1], v[2], low, idle);
    }

    private static StockLine line(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StockLine(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("unit"), rs.getLong("stock_milli"), (Long) rs.getObject("min_stock_milli"), (Long) rs.getObject("cost_minor"), (Long) rs.getObject("days_since"));
    }

    // ---------- resumen ----------

    public Overview overview(MemberContext ctx, Range r) {
        ctx.require(Permission.VIEW_REPORTS);
        Map<LocalDate, long[]> series = new LinkedHashMap<>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) series.put(d, new long[2]);
        q("SELECT " + businessDate("s.completed_at") + ", sum(s.total_minor) FROM sale s WHERE " + SALE_IN_RANGE + " GROUP BY 1", ctx.businessId(), r).query((rs, n) -> {
            long[] p = series.get(rs.getObject(1, LocalDate.class));
            if (p != null) p[0] = rs.getLong(2);
            return null;
        }).list();
        q("SELECT " + businessDate("e.occurred_at") + ", sum(e.amount_minor) FROM expense e LEFT JOIN expense_category c ON c.id = e.category_id WHERE e.business_id = :b AND e.voided_at IS NULL "
                + "AND e.occurred_at >= :s AND e.occurred_at < :e AND (e.ref_type IS NULL OR e.ref_type <> 'SUPPLIER_PAYMENT') AND coalesce(c.key, '') <> 'goods' GROUP BY 1", ctx.businessId(), r).query((rs, n) -> {
            long[] p = series.get(rs.getObject(1, LocalDate.class));
            if (p != null) p[1] = rs.getLong(2);
            return null;
        }).list();
        List<DayPoint> points = series.entrySet().stream().map(e -> new DayPoint(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        long receivable = jdbc.sql("SELECT coalesce(sum(balance_minor), 0) FROM credit WHERE business_id = :b AND status = 'OPEN'").param("b", ctx.businessId()).query(Long.class).single();
        long lowCount = jdbc.sql("SELECT count(*) FROM product WHERE business_id = :b AND active AND track_stock AND (stock_milli < 0 OR (min_stock_milli IS NOT NULL AND stock_milli <= min_stock_milli))").param("b", ctx.businessId()).query(Long.class).single();
        List<MemberClosings> closings = closings(ctx, new Range(r.from().minusDays(30), r.to(), Timestamp.from(r.start().toInstant().minus(30, ChronoUnit.DAYS)), r.end(), r.zone(), r.cutoff(), r.decimals(), r.currency(), r.locale()));
        MemberClosings last = closings.stream().filter(c -> !c.lines().isEmpty()).max(Comparator.comparing(c -> c.lines().get(0).closedAt())).map(c -> new MemberClosings(c.memberId(), c.name(), 1, c.lines().get(0).differenceMinor(), Math.abs(c.lines().get(0).differenceMinor()), List.of(c.lines().get(0)))).orElse(null);
        return new Overview(r, sales(ctx, r), byMethod(ctx, r), profit(ctx, r), receivable, points, topProducts(ctx, r, "revenue", 5), lowCount, last);
    }

    // ---------- exportaciones CSV ----------

    public Instant now() { return clock.instant(); }

    /** Una fila por venta cobrada: para llevar a una hoja de cálculo. */
    public List<List<String>> salesExport(MemberContext ctx, Range r) {
        ctx.require(Permission.VIEW_REPORTS);
        return q("""
                        SELECT s.id, s.completed_at, m.display_name, c.name, s.subtotal_minor, s.discount_minor, s.total_minor,
                               (SELECT string_agg(p.method || ':' || p.amount_minor, ' | ' ORDER BY p.position) FROM sale_payment p WHERE p.sale_id = s.id) AS methods
                          FROM sale s LEFT JOIN member m ON m.id = s.completed_by_member_id LEFT JOIN cash_register c ON c.id = s.cash_register_id
                         """ + " WHERE " + SALE_IN_RANGE + " ORDER BY s.completed_at LIMIT 50000", ctx.businessId(), r)
                .query((rs, n) -> List.of(rs.getString(1), local(rs.getTimestamp(2), r.zone()), str(rs.getString(3)), str(rs.getString(4)), Csv.money(rs.getLong(5), r.decimals()), Csv.money(rs.getLong(6), r.decimals()),
                        Csv.money(rs.getLong(7), r.decimals()), str(rs.getString(8)))).list();
    }

    /** Una fila por línea vendida. */
    public List<List<String>> saleItemsExport(MemberContext ctx, Range r) {
        ctx.require(Permission.VIEW_REPORTS);
        return q("SELECT s.id, s.completed_at, i.name, i.quantity_milli, i.unit_price_minor, i.unit_cost_minor, i.discount_minor FROM sale_item i JOIN sale s ON s.id = i.sale_id WHERE " + SALE_IN_RANGE
                + " ORDER BY s.completed_at, i.position LIMIT 100000", ctx.businessId(), r)
                .query((rs, n) -> List.of(rs.getString(1), local(rs.getTimestamp(2), r.zone()), rs.getString(3), new java.math.BigDecimal(rs.getLong(4)).movePointLeft(3).stripTrailingZeros().toPlainString(),
                        Csv.money(rs.getLong(5), r.decimals()), rs.getObject(6) == null ? "" : Csv.money(rs.getLong(6), r.decimals()), Csv.money(rs.getLong(7), r.decimals()))).list();
    }

    private static String local(Timestamp t, ZoneId zone) {
        return t == null ? "" : Csv.dateTime(t.toInstant(), zone);
    }

    private static String str(String s) { return s == null ? "" : s; }
}
