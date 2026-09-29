package com.cuadra.api.stock;

import com.cuadra.api.cash.RegisterResolver;
import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.sale.SaleMath;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compras a proveedores y cuentas por pagar. Una compra guarda sus líneas, suma existencias de lo que lleva stock, actualiza el costo del producto
 * (último costo) y, por lo que se paga, crea un pago y su gasto: todo en una transacción. Lo que no se paga queda por pagar.
 * Una compra no se edita: se anula (deshace existencias y pagos) y se registra de nuevo.
 */
@Service
public class PurchaseService {
    private static final long MAX_MINOR = 1_000_000_000_000L;
    private static final long MAX_MILLI = 1_000_000_000_000L;
    private static final int MAX_LINES = 200;
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Set<String> SOURCES = Set.of("CASH_DRAWER", "BANK", "CARD", "OWNER", "OTHER");

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final StockService stock;
    private final RegisterResolver registers;

    public PurchaseService(JdbcClient jdbc, Audit audit, Clock clock, StockService stock, RegisterResolver registers) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.stock = stock;
        this.registers = registers;
    }

    public record LineInput(UUID id, UUID productId, String name, Long quantityMilli, Long unitCostMinor) {}

    /** `paidMinor` con `paidSource`: lo que se pagó al comprar (0 = todo queda por pagar). */
    public record PurchaseInput(UUID supplierId, String supplierName, List<LineInput> lines, Long paidMinor, String paidSource, String note, Instant occurredAt) {}

    public record LineView(UUID id, UUID productId, String name, long quantityMilli, long unitCostMinor, long lineTotalMinor) {}

    public record PurchaseView(UUID id, UUID supplierId, String supplierName, long totalMinor, long paidMinor, long balanceMinor, String note, String createdByName, UUID createdById,
                               Instant occurredAt, boolean voided, String voidReason, List<LineView> lines, long rev) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "SupplierPaymentInput")
    public record PaymentInput(UUID purchaseId, Long amountMinor, String source, String note, Instant occurredAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "SupplierPaymentView")
    public record PaymentView(UUID id, UUID purchaseId, UUID supplierId, long amountMinor, String source, String note, String createdByName, UUID createdById, Instant occurredAt,
                              boolean voided, String voidReason, long rev) {}

    public enum Outcome { CREATED, UNCHANGED }

    public record Result(PurchaseView purchase, Outcome outcome) {}

    public record PaymentResult(PaymentView payment, Outcome outcome) {}

    /** Ids derivados: el teléfono los calcula igual sin hablar con el servidor, y lo que baja del servidor reemplaza lo local sin duplicarse. */
    public static UUID derived(String tag, UUID a, UUID b) {
        return UUID.nameUUIDFromBytes((tag + ":" + a + ":" + (b == null ? "" : b)).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID movementId(UUID purchaseId, UUID lineId) { return derived("purchase-move", purchaseId, lineId); }

    public static UUID reversalId(UUID purchaseId, UUID lineId) { return derived("purchase-reversal", purchaseId, lineId); }

    public static UUID firstPaymentId(UUID purchaseId) { return derived("purchase-pay", purchaseId, null); }

    // ---------- compras ----------

    @Transactional
    public Result register(MemberContext ctx, UUID id, PurchaseInput in) {
        ctx.require(Permission.MANAGE_STOCK);
        List<LineInput> lines = in.lines();
        if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES) throw ApiException.badRequest("INVALID_LINES", "A purchase needs between 1 and " + MAX_LINES + " lines");
        Set<UUID> lineIds = new HashSet<>();
        long total = 0;
        List<LineView> views = new ArrayList<>();
        for (LineInput l : lines) {
            if (l.id() == null || !lineIds.add(l.id())) throw ApiException.badRequest("INVALID_LINES", "Each line needs a unique id");
            if (l.quantityMilli() == null || l.quantityMilli() <= 0 || l.quantityMilli() > MAX_MILLI) throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be positive");
            if (l.unitCostMinor() == null || l.unitCostMinor() < 0 || l.unitCostMinor() > MAX_MINOR) throw ApiException.badRequest("INVALID_COST", "Invalid cost");
            String name = l.name() == null || l.name().isBlank() ? null : l.name().trim();
            if (l.productId() != null) {
                String productName = jdbc.sql("SELECT name FROM product WHERE id = :p AND business_id = :b").param("p", l.productId()).param("b", ctx.businessId()).query(String.class).optional()
                        .orElseThrow(() -> ApiException.badRequest("INVALID_PRODUCT", "Product not found"));
                if (name == null) name = productName;
            }
            if (name == null || name.length() > 200) throw ApiException.badRequest("INVALID_NAME", "Each line needs a name");
            long lineTotal = SaleMath.lineTotal(l.unitCostMinor(), l.quantityMilli(), 0);
            total += lineTotal;
            if (total > MAX_MINOR) throw ApiException.badRequest("AMOUNT_TOO_LARGE", "Amount too large");
            views.add(new LineView(l.id(), l.productId(), name, l.quantityMilli(), l.unitCostMinor(), lineTotal));
        }
        long paid = in.paidMinor() == null ? 0 : in.paidMinor();
        if (paid < 0 || paid > total) throw ApiException.badRequest("INVALID_PAID", "Paid amount must be between 0 and the total");
        if (paid > 0 && (in.paidSource() == null || !SOURCES.contains(in.paidSource()))) throw ApiException.badRequest("INVALID_SOURCE", "Invalid payment source");
        String supplierName = in.supplierName() == null || in.supplierName().isBlank() ? null : in.supplierName().trim();
        if (supplierName != null && supplierName.length() > 120) throw ApiException.badRequest("INVALID_NAME", "Supplier name too long");
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > 300) throw ApiException.badRequest("INVALID_NOTE", "Note too long");
        if (in.supplierId() != null) {
            supplierName = jdbc.sql("SELECT name FROM supplier WHERE id = :s AND business_id = :b").param("s", in.supplierId()).param("b", ctx.businessId()).query(String.class).optional()
                    .orElseThrow(() -> ApiException.badRequest("INVALID_SUPPLIER", "Supplier not found"));
        }

        Optional<PurchaseView> existing = find(ctx.businessId(), id);
        if (existing.isPresent()) {
            if (existing.get().totalMinor() == total) return new Result(existing.get(), Outcome.UNCHANGED);
            throw ApiException.conflict("PURCHASE_IMMUTABLE", "A purchase is not edited: void it and register it again");
        }
        if (jdbc.sql("SELECT count(*) FROM purchase WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");

        Instant now = clock.instant();
        Instant at = in.occurredAt() == null || in.occurredAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.occurredAt();
        jdbc.sql("""
                        INSERT INTO purchase (id, business_id, supplier_id, supplier_label, total_minor, note, occurred_at, created_by_member_id, device_id)
                        VALUES (:id, :b, :s, :label, :t, :n, :at, :m, :d)""")
                .param("id", id).param("b", ctx.businessId()).param("s", in.supplierId(), java.sql.Types.OTHER).param("label", in.supplierId() == null ? supplierName : null)
                .param("t", total).param("n", note).param("at", Timestamp.from(at)).param("m", ctx.memberId()).param("d", ctx.deviceId(), java.sql.Types.OTHER).update();
        int pos = 0;
        for (LineView l : views) {
            jdbc.sql("""
                            INSERT INTO purchase_item (purchase_id, id, business_id, product_id, name, quantity_milli, unit_cost_minor, line_total_minor, position)
                            VALUES (:p, :id, :b, :prod, :n, :q, :c, :t, :pos)""")
                    .param("p", id).param("id", l.id()).param("b", ctx.businessId()).param("prod", l.productId(), java.sql.Types.OTHER).param("n", l.name())
                    .param("q", l.quantityMilli()).param("c", l.unitCostMinor()).param("t", l.lineTotalMinor()).param("pos", pos++).update();
        }
        // Las líneas del mismo producto se aplican en un orden fijo para no bloquearse con otra compra simultánea.
        views.stream().filter(l -> l.productId() != null).sorted((a, b) -> a.productId().compareTo(b.productId())).forEach(l -> {
            stock.recordSystem(ctx, movementId(id, l.id()), l.productId(), "PURCHASE", l.quantityMilli(), l.unitCostMinor(), "PURCHASE", id, at);
            // Costo = último costo de compra (v1). Sirve para la ganancia aunque el producto no lleve inventario.
            jdbc.sql("""
                            UPDATE product SET cost_minor = :c, updated_at = now(), rev = nextval('change_rev_seq') WHERE id = :p AND business_id = :b AND cost_minor IS DISTINCT FROM :c""")
                    .param("c", l.unitCostMinor()).param("p", l.productId()).param("b", ctx.businessId()).update();
            jdbc.sql("INSERT INTO product_price_history (business_id, product_id, price_minor, cost_minor, changed_by_member_id) SELECT business_id, id, price_minor, cost_minor, :m FROM product WHERE id = :p AND cost_minor = :c")
                    .param("m", ctx.memberId()).param("p", l.productId()).param("c", l.unitCostMinor()).update();
        });
        if (paid > 0) pay(ctx, firstPaymentId(id), id, in.supplierId(), paid, in.paidSource(), null, at);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "purchase.create", "purchase", id, "total=" + total + " paid=" + paid);
        return new Result(get(ctx.businessId(), id), Outcome.CREATED);
    }

    @Transactional
    public PurchaseView voidPurchase(MemberContext ctx, UUID id, String reason) {
        ctx.require(Permission.MANAGE_STOCK);
        PurchaseView p = get(ctx.businessId(), id);
        if (p.voided()) return p;
        jdbc.sql("UPDATE purchase SET voided_by_member_id = :m, voided_at = :now, void_reason = :r, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b")
                .param("m", ctx.memberId()).param("now", Timestamp.from(clock.instant())).param("r", clean(reason)).param("id", id).param("b", ctx.businessId()).update();
        p.lines().stream().filter(l -> l.productId() != null).sorted((a, b) -> a.productId().compareTo(b.productId()))
                .forEach(l -> stock.recordSystem(ctx, reversalId(id, l.id()), l.productId(), "PURCHASE_REVERSAL", -l.quantityMilli(), l.unitCostMinor(), "PURCHASE", id, clock.instant()));
        // Anular la compra devuelve también el dinero: los pagos y sus gastos se anulan con ella.
        for (UUID payment : jdbc.sql("SELECT id FROM supplier_payment WHERE purchase_id = :p AND voided_at IS NULL").param("p", id).query(UUID.class).list()) voidPaymentRow(ctx, payment, "purchase voided");
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "purchase.void", "purchase", id, reason);
        return get(ctx.businessId(), id);
    }

    // ---------- pagos a proveedores ----------

    @Transactional
    public PaymentResult pay(MemberContext ctx, UUID id, PaymentInput in) {
        ctx.require(Permission.MANAGE_STOCK);
        if (in.purchaseId() == null) throw ApiException.badRequest("INVALID_PURCHASE", "Purchase is required");
        if (in.amountMinor() == null || in.amountMinor() <= 0 || in.amountMinor() > MAX_MINOR) throw ApiException.badRequest("INVALID_AMOUNT", "Invalid amount");
        if (in.source() == null || !SOURCES.contains(in.source())) throw ApiException.badRequest("INVALID_SOURCE", "Invalid payment source");
        Optional<PaymentView> existing = findPayment(ctx.businessId(), id);
        if (existing.isPresent()) return new PaymentResult(existing.get(), Outcome.UNCHANGED);
        if (jdbc.sql("SELECT count(*) FROM supplier_payment WHERE id = :id OR (SELECT count(*) FROM expense WHERE id = :id) > 0").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");
        // El bloqueo va sobre la compra: un pago y una anulación simultáneos no se cruzan.
        jdbc.sql("SELECT id FROM purchase WHERE id = :p AND business_id = :b AND voided_at IS NULL FOR UPDATE").param("p", in.purchaseId()).param("b", ctx.businessId())
                .query(UUID.class).optional().orElseThrow(() -> ApiException.badRequest("INVALID_PURCHASE", "Purchase not found or voided"));
        // Sin proveedor registrado el valor es nulo: `optional()` lo trataría como "no hay fila", por eso se lee con un envoltorio.
        UUID supplier = jdbc.sql("SELECT supplier_id FROM purchase WHERE id = :p").param("p", in.purchaseId()).query((rs, n) -> java.util.Optional.ofNullable(rs.getObject(1, UUID.class))).single().orElse(null);
        Instant now = clock.instant();
        Instant at = in.occurredAt() == null || in.occurredAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.occurredAt();
        // Nunca se rechaza por pasarse: el dinero ya salió. El saldo de la compra no baja de cero.
        pay(ctx, id, in.purchaseId(), supplier, in.amountMinor(), in.source(), clean(in.note()), at);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "supplier.pay", "purchase", in.purchaseId(), in.source() + " " + in.amountMinor());
        return new PaymentResult(getPayment(ctx.businessId(), id), Outcome.CREATED);
    }

    private void pay(MemberContext ctx, UUID paymentId, UUID purchaseId, UUID supplierId, long amount, String source, String note, Instant at) {
        jdbc.sql("""
                        INSERT INTO supplier_payment (id, business_id, purchase_id, supplier_id, amount_minor, source, note, occurred_at, created_by_member_id, device_id)
                        VALUES (:id, :b, :p, :s, :a, :src, :n, :at, :m, :d)""")
                .param("id", paymentId).param("b", ctx.businessId()).param("p", purchaseId).param("s", supplierId, java.sql.Types.OTHER).param("a", amount).param("src", source)
                .param("n", note).param("at", Timestamp.from(at)).param("m", ctx.memberId()).param("d", ctx.deviceId(), java.sql.Types.OTHER).update();
        UUID register = "CASH_DRAWER".equals(source) ? registers.resolve(ctx, null) : null;
        String description = jdbc.sql("SELECT coalesce(s.name, p.supplier_label) FROM purchase p LEFT JOIN supplier s ON s.id = p.supplier_id WHERE p.id = :p").param("p", purchaseId)
                .query((rs, n) -> rs.getString(1)).optional().orElse(null);
        // El gasto lleva el id del pago y queda ligado a él: el cierre de caja lo cuenta si salió del cajón.
        jdbc.sql("""
                        INSERT INTO expense (id, business_id, category_id, description, amount_minor, source, cash_register_id, created_by_member_id, device_id, occurred_at, ref_type, ref_id)
                        VALUES (:id, :b, (SELECT id FROM expense_category WHERE business_id = :b AND key = 'goods'), :d, :a, :src, :r, :m, :dev, :at, 'SUPPLIER_PAYMENT', :id)""")
                .param("id", paymentId).param("b", ctx.businessId()).param("d", description == null ? "Compra" : "Compra · " + description).param("a", amount).param("src", source)
                .param("r", register, java.sql.Types.OTHER).param("m", ctx.memberId()).param("dev", ctx.deviceId(), java.sql.Types.OTHER).param("at", Timestamp.from(at)).update();
    }

    @Transactional
    public PaymentView voidPayment(MemberContext ctx, UUID id, String reason) {
        ctx.require(Permission.MANAGE_STOCK);
        PaymentView p = getPayment(ctx.businessId(), id);
        if (p.voided()) return p;
        voidPaymentRow(ctx, id, reason);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "supplier.pay.void", "supplier_payment", id, reason);
        return getPayment(ctx.businessId(), id);
    }

    private void voidPaymentRow(MemberContext ctx, UUID id, String reason) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("UPDATE supplier_payment SET voided_by_member_id = :m, voided_at = :now, void_reason = :r, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b")
                .param("m", ctx.memberId()).param("now", now).param("r", clean(reason)).param("id", id).param("b", ctx.businessId()).update();
        jdbc.sql("UPDATE expense SET voided_by_member_id = :m, voided_at = :now, void_reason = :r, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b AND voided_at IS NULL")
                .param("m", ctx.memberId()).param("now", now).param("r", clean(reason)).param("id", id).param("b", ctx.businessId()).update();
    }

    // ---------- lectura ----------

    private static String clean(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static final String SELECT = """
            SELECT p.*, coalesce(s.name, p.supplier_label) AS supplier_name, m.display_name AS created_name,
                   coalesce((SELECT sum(a.amount_minor) FROM supplier_payment a WHERE a.purchase_id = p.id AND a.voided_at IS NULL), 0) AS paid
              FROM purchase p LEFT JOIN supplier s ON s.id = p.supplier_id JOIN member m ON m.id = p.created_by_member_id""";

    public PurchaseView get(UUID businessId, UUID id) {
        return find(businessId, id).orElseThrow(() -> ApiException.notFound("PURCHASE_NOT_FOUND", "Purchase not found"));
    }

    public Optional<PurchaseView> find(UUID businessId, UUID id) {
        return viewsByIds(businessId, List.of(id)).stream().findFirst();
    }

    public List<PurchaseView> viewsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return withLines(businessId, jdbc.sql(SELECT + " WHERE p.business_id = :b AND p.id IN (:ids)").param("b", businessId).param("ids", ids).query((rs, n) -> map(rs)).list());
    }

    public PageResponse<PurchaseView> list(MemberContext ctx, UUID supplierId, boolean onlyOwed, boolean includeVoided, int page, int size) {
        ctx.require(Permission.MANAGE_STOCK);
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        String where = " WHERE p.business_id = :b" + (supplierId == null ? "" : " AND p.supplier_id = :s") + (includeVoided ? "" : " AND p.voided_at IS NULL");
        String owed = onlyOwed ? " AND p.total_minor > coalesce((SELECT sum(a.amount_minor) FROM supplier_payment a WHERE a.purchase_id = p.id AND a.voided_at IS NULL), 0)" : "";
        var count = jdbc.sql("SELECT count(*) FROM purchase p" + where + owed).param("b", ctx.businessId());
        var rows = jdbc.sql(SELECT + where + owed + " ORDER BY p.occurred_at DESC, p.id LIMIT " + size + " OFFSET " + (long) page * size).param("b", ctx.businessId());
        if (supplierId != null) { count = count.param("s", supplierId); rows = rows.param("s", supplierId); }
        return PageResponse.of(withLines(ctx.businessId(), rows.query((rs, n) -> map(rs)).list()), page, size, count.query(Long.class).single());
    }

    private List<PurchaseView> withLines(UUID businessId, List<PurchaseView> purchases) {
        if (purchases.isEmpty()) return purchases;
        java.util.Map<UUID, List<LineView>> byPurchase = new java.util.HashMap<>();
        jdbc.sql("SELECT * FROM purchase_item WHERE business_id = :b AND purchase_id IN (:ids) ORDER BY purchase_id, position").param("b", businessId).param("ids", purchases.stream().map(PurchaseView::id).toList())
                .query((rs, n) -> {
                    byPurchase.computeIfAbsent(rs.getObject("purchase_id", UUID.class), k -> new ArrayList<>()).add(new LineView(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                            rs.getString("name"), rs.getLong("quantity_milli"), rs.getLong("unit_cost_minor"), rs.getLong("line_total_minor")));
                    return null;
                }).list();
        return purchases.stream().map(p -> new PurchaseView(p.id(), p.supplierId(), p.supplierName(), p.totalMinor(), p.paidMinor(), p.balanceMinor(), p.note(), p.createdByName(), p.createdById(),
                p.occurredAt(), p.voided(), p.voidReason(), byPurchase.getOrDefault(p.id(), List.of()), p.rev())).toList();
    }

    private static PurchaseView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long total = rs.getLong("total_minor");
        long paid = rs.getLong("paid");
        boolean voided = rs.getTimestamp("voided_at") != null;
        return new PurchaseView(rs.getObject("id", UUID.class), rs.getObject("supplier_id", UUID.class), rs.getString("supplier_name"), total, paid, voided ? 0 : Math.max(0, total - paid),
                rs.getString("note"), rs.getString("created_name"), rs.getObject("created_by_member_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(), voided,
                rs.getString("void_reason"), List.of(), rs.getLong("rev"));
    }

    private static final String PAY_SELECT = """
            SELECT a.*, m.display_name AS created_name FROM supplier_payment a JOIN member m ON m.id = a.created_by_member_id""";

    public PaymentView getPayment(UUID businessId, UUID id) {
        return findPayment(businessId, id).orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
    }

    public Optional<PaymentView> findPayment(UUID businessId, UUID id) {
        return jdbc.sql(PAY_SELECT + " WHERE a.id = :id AND a.business_id = :b").param("id", id).param("b", businessId).query((rs, n) -> mapPayment(rs)).optional();
    }

    public List<PaymentView> paymentsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(PAY_SELECT + " WHERE a.business_id = :b AND a.id IN (:ids)").param("b", businessId).param("ids", ids).query((rs, n) -> mapPayment(rs)).list();
    }

    public List<PaymentView> paymentsOf(MemberContext ctx, UUID purchaseId) {
        ctx.require(Permission.MANAGE_STOCK);
        return jdbc.sql(PAY_SELECT + " WHERE a.business_id = :b AND a.purchase_id = :p ORDER BY a.occurred_at").param("b", ctx.businessId()).param("p", purchaseId).query((rs, n) -> mapPayment(rs)).list();
    }

    private static PaymentView mapPayment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentView(rs.getObject("id", UUID.class), rs.getObject("purchase_id", UUID.class), rs.getObject("supplier_id", UUID.class), rs.getLong("amount_minor"), rs.getString("source"),
                rs.getString("note"), rs.getString("created_name"), rs.getObject("created_by_member_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("voided_at") != null,
                rs.getString("void_reason"), rs.getLong("rev"));
    }
}
