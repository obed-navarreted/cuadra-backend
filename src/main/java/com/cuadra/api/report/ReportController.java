package com.cuadra.api.report;

import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reportes del negocio (dueño y admins). `from`/`to` son jornadas del negocio, inclusivas; sin fechas es hoy. Cada reporte de tabla tiene su
 * versión `.csv` (con BOM para Excel); `lang=es|en` cambia los encabezados (por defecto, el idioma del negocio).
 */
@RestController
@RequestMapping("/api/b/{businessId}/reports")
public class ReportController {
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ReportService reports;
    private final Access access;
    private final com.cuadra.api.plan.PlanService plans;

    public ReportController(ReportService reports, Access access, com.cuadra.api.plan.PlanService plans) {
        this.reports = reports;
        this.access = access;
        this.plans = plans;
    }

    private MemberContext ctx(Actor actor, UUID businessId, UUID memberId) {
        return access.member(actor, businessId, memberId);
    }

    /** Las descargas CSV son de Pro. */
    private MemberContext exportCtx(Actor actor, UUID businessId, UUID memberId) {
        MemberContext c = access.member(actor, businessId, memberId);
        plans.requireExport(businessId);
        return c;
    }

    private ReportService.Range range(MemberContext c, LocalDate from, LocalDate to) {
        return reports.range(c.businessId(), from, to);
    }

    private static String t(ReportService.Range r, String lang, String es, String en) {
        String l = lang != null ? lang : r.locale();
        return "en".equalsIgnoreCase(l) ? en : es;
    }

    private static ResponseEntity<String> csv(String name, ReportService.Range r, List<String> header, List<List<String>> rows) {
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + r.from() + "_" + r.to() + ".csv\"")
                .body(Csv.of(header, rows));
    }

    // ---------- JSON ----------

    @GetMapping("/overview")
    public ReportService.Overview overview(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = ctx(actor, businessId, memberId);
        return reports.overview(c, range(c, from, to));
    }

    @GetMapping("/sales")
    public ReportService.SalesReport sales(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = ctx(actor, businessId, memberId);
        ReportService.Range r = range(c, from, to);
        return new ReportService.SalesReport(r, reports.sales(c, r), reports.byMethod(c, r));
    }

    @GetMapping("/sales/breakdown")
    public List<ReportService.Row> breakdown(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                             @RequestParam String by, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = ctx(actor, businessId, memberId);
        return reports.breakdown(c, range(c, from, to), by);
    }

    @GetMapping("/profit")
    public ReportService.Profit profit(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = ctx(actor, businessId, memberId);
        return reports.profit(c, range(c, from, to));
    }

    @GetMapping("/products")
    public List<ReportService.Product> products(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                @RequestParam(defaultValue = "revenue") String sort, @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = ctx(actor, businessId, memberId);
        return reports.topProducts(c, range(c, from, to), sort, limit);
    }

    @GetMapping("/receivables")
    public ReportService.Receivables receivables(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return reports.receivables(ctx(actor, businessId, memberId));
    }

    @GetMapping("/expenses")
    public ReportService.ExpenseReport expenses(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = ctx(actor, businessId, memberId);
        return reports.expenses(c, range(c, from, to));
    }

    /** Cierre automático por jornada: una fila por día del rango, sin abrir ni cerrar nada. */
    @GetMapping("/daily-close")
    public ReportService.DailyClose dailyClose(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = ctx(actor, businessId, memberId);
        return reports.dailyClose(c, range(c, from, to));
    }

    @GetMapping("/closings")
    public List<ReportService.MemberClosings> closings(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = ctx(actor, businessId, memberId);
        return reports.closings(c, range(c, from, to));
    }

    @GetMapping("/inventory")
    public ReportService.Inventory inventory(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return reports.inventory(ctx(actor, businessId, memberId));
    }

    // ---------- CSV ----------

    @GetMapping(value = "/sales.csv", produces = "text/csv")
    public ResponseEntity<String> salesCsv(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                           @RequestParam(required = false) String lang, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = exportCtx(actor, businessId, memberId);
        ReportService.Range r = range(c, from, to);
        return csv("ventas", r, List.of("id", t(r, lang, "fecha", "date"), t(r, lang, "cajero", "cashier"), t(r, lang, "caja", "register"), "subtotal", t(r, lang, "descuento", "discount"), "total", t(r, lang, "pagos", "payments")), reports.salesExport(c, r));
    }

    @GetMapping(value = "/sale-items.csv", produces = "text/csv")
    public ResponseEntity<String> saleItemsCsv(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                               @RequestParam(required = false) String lang, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = exportCtx(actor, businessId, memberId);
        ReportService.Range r = range(c, from, to);
        return csv("lineas-vendidas", r, List.of(t(r, lang, "venta", "sale"), t(r, lang, "fecha", "date"), t(r, lang, "producto", "product"), t(r, lang, "cantidad", "quantity"), t(r, lang, "precio", "price"), t(r, lang, "costo", "cost"), t(r, lang, "descuento", "discount")),
                reports.saleItemsExport(c, r));
    }

    @GetMapping(value = "/sales/breakdown.csv", produces = "text/csv")
    public ResponseEntity<String> breakdownCsv(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                               @RequestParam String by, @RequestParam(required = false) String lang, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = exportCtx(actor, businessId, memberId);
        ReportService.Range r = range(c, from, to);
        List<List<String>> rows = new ArrayList<>();
        for (ReportService.Row x : reports.breakdown(c, r, by)) rows.add(List.of(x.label(), String.valueOf(x.count()), Csv.money(x.totalMinor(), r.decimals())));
        return csv("ventas-por-" + by, r, List.of(t(r, lang, "grupo", "group"), t(r, lang, "ventas", "sales"), "total"), rows);
    }

    @GetMapping(value = "/profit.csv", produces = "text/csv")
    public ResponseEntity<String> profitCsv(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                            @RequestParam(required = false) String lang, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = exportCtx(actor, businessId, memberId);
        ReportService.Range r = range(c, from, to);
        ReportService.Profit p = reports.profit(c, r);
        List<List<String>> rows = List.of(
                List.of(t(r, lang, "ventas", "sales"), Csv.money(p.salesMinor(), r.decimals())),
                List.of(t(r, lang, "costo de lo vendido", "cost of goods sold"), Csv.money(p.costOfGoodsMinor(), r.decimals())),
                List.of(t(r, lang, "gastos operativos", "operating expenses"), Csv.money(p.operatingExpensesMinor(), r.decimals())),
                List.of(t(r, lang, "ganancia estimada", "estimated profit"), Csv.money(p.estimatedProfitMinor(), r.decimals())),
                List.of(t(r, lang, "compras de mercadería no incluidas", "merchandise purchases not included"), Csv.money(p.purchasesExcludedMinor(), r.decimals())),
                List.of(t(r, lang, "costo anotado en (%)", "cost recorded on (%)"), String.valueOf(p.costCoveragePercent())));
        return csv("ganancia", r, List.of(t(r, lang, "concepto", "item"), t(r, lang, "monto", "amount")), rows);
    }

    @GetMapping(value = "/products.csv", produces = "text/csv")
    public ResponseEntity<String> productsCsv(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                              @RequestParam(defaultValue = "revenue") String sort, @RequestParam(defaultValue = "200") int limit, @RequestParam(required = false) String lang,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = exportCtx(actor, businessId, memberId);
        ReportService.Range r = range(c, from, to);
        List<List<String>> rows = new ArrayList<>();
        for (ReportService.Product p : reports.topProducts(c, r, sort, limit)) {
            rows.add(List.of(p.name(), new java.math.BigDecimal(p.quantityMilli()).movePointLeft(3).stripTrailingZeros().toPlainString(), Csv.money(p.revenueMinor(), r.decimals()), Csv.money(p.costMinor(), r.decimals()),
                    Csv.money(p.profitMinor(), r.decimals()), p.fullyCosted() ? "" : t(r, lang, "sin costo en algunas ventas", "cost missing on some sales")));
        }
        return csv("productos", r, List.of(t(r, lang, "producto", "product"), t(r, lang, "cantidad", "quantity"), t(r, lang, "ventas", "sales"), t(r, lang, "costo", "cost"), t(r, lang, "ganancia", "profit"), t(r, lang, "nota", "note")), rows);
    }

    @GetMapping(value = "/receivables.csv", produces = "text/csv")
    public ResponseEntity<String> receivablesCsv(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                 @RequestParam(required = false) String lang) {
        MemberContext c = exportCtx(actor, businessId, memberId);
        ReportService.Range r = range(c, null, null);
        List<List<String>> rows = new ArrayList<>();
        for (ReportService.Debtor d : reports.receivables(c).worst()) rows.add(List.of(d.name(), Csv.money(d.balanceMinor(), r.decimals()), String.valueOf(d.oldestDays()), Csv.money(d.paidLast90Minor(), r.decimals())));
        return csv("por-cobrar", r, List.of(t(r, lang, "cliente", "customer"), t(r, lang, "debe", "owes"), t(r, lang, "dias_del_mas_viejo", "days_oldest"), t(r, lang, "pagado_90_dias", "paid_90_days")), rows);
    }

    @GetMapping(value = "/expenses.csv", produces = "text/csv")
    public ResponseEntity<String> expensesCsv(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                              @RequestParam(required = false) String lang, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = exportCtx(actor, businessId, memberId);
        ReportService.Range r = range(c, from, to);
        List<List<String>> rows = new ArrayList<>();
        for (ReportService.CategoryAmount x : reports.expenses(c, r).byCategory()) rows.add(List.of(x.name() != null ? x.name() : x.key() != null ? x.key() : t(r, lang, "sin categoría", "uncategorized"), Csv.money(x.amountMinor(), r.decimals())));
        return csv("gastos-por-categoria", r, List.of(t(r, lang, "categoría", "category"), t(r, lang, "monto", "amount")), rows);
    }

    @GetMapping(value = "/closings.csv", produces = "text/csv")
    public ResponseEntity<String> closingsCsv(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                              @RequestParam(required = false) String lang, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MemberContext c = exportCtx(actor, businessId, memberId);
        ReportService.Range r = range(c, from, to);
        List<List<String>> rows = new ArrayList<>();
        for (ReportService.MemberClosings m : reports.closings(c, r)) {
            for (ReportService.ShiftLine l : m.lines()) rows.add(List.of(m.name(), l.register(), Csv.dateTime(l.closedAt(), r.zone()), Csv.money(l.expectedMinor(), r.decimals()),
                    Csv.money(l.countedMinor(), r.decimals()), Csv.money(l.differenceMinor(), r.decimals())));
        }
        return csv("cierres", r, List.of(t(r, lang, "cajero", "cashier"), t(r, lang, "caja", "register"), t(r, lang, "cierre", "closed_at"), t(r, lang, "esperado", "expected"), t(r, lang, "contado", "counted"), t(r, lang, "diferencia", "difference")), rows);
    }

    @GetMapping(value = "/inventory.csv", produces = "text/csv")
    public ResponseEntity<String> inventoryCsv(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                               @RequestParam(required = false) String lang) {
        MemberContext c = exportCtx(actor, businessId, memberId);
        ReportService.Range r = range(c, null, null);
        ReportService.Inventory inv = reports.inventory(c);
        List<List<String>> rows = new ArrayList<>();
        for (ReportService.StockLine l : inv.low()) rows.add(stockRow(t(r, lang, "stock bajo", "low stock"), l, r));
        for (ReportService.StockLine l : inv.noMovement()) rows.add(stockRow(t(r, lang, "sin movimiento 30 días", "no movement 30 days"), l, r));
        return csv("inventario", r, List.of(t(r, lang, "motivo", "reason"), t(r, lang, "producto", "product"), t(r, lang, "unidad", "unit"), "stock", t(r, lang, "mínimo", "minimum"), t(r, lang, "costo", "cost")), rows);
    }

    private static List<String> stockRow(String reason, ReportService.StockLine l, ReportService.Range r) {
        return List.of(reason, l.name(), l.unit(), new java.math.BigDecimal(l.stockMilli()).movePointLeft(3).stripTrailingZeros().toPlainString(),
                l.minStockMilli() == null ? "" : new java.math.BigDecimal(l.minStockMilli()).movePointLeft(3).stripTrailingZeros().toPlainString(), l.costMinor() == null ? "" : Csv.money(l.costMinor(), r.decimals()));
    }
}
