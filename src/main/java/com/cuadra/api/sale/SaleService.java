package com.cuadra.api.sale;

import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.common.Phones;
import com.cuadra.api.credit.CreditService;
import com.cuadra.api.security.TokenHasher;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleService {
    static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final Set<String> METHODS = Set.of("CASH", "TRANSFER", "CARD", "CREDIT", "OTHER");
    private static final int MAX_ITEMS = 500;
    private static final int MAX_PAYMENTS = 6;
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final BusinessDayService days;
    private final CreditService credits;
    private final com.cuadra.api.cash.RegisterResolver registers;
    private final com.cuadra.api.stock.StockService stock;
    private final com.cuadra.api.notification.NotificationService notifications;

    public SaleService(JdbcClient jdbc, Audit audit, Clock clock, BusinessDayService days, CreditService credits, com.cuadra.api.cash.RegisterResolver registers,
                       com.cuadra.api.stock.StockService stock, com.cuadra.api.notification.NotificationService notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.days = days;
        this.credits = credits;
        this.registers = registers;
        this.stock = stock;
        this.notifications = notifications;
    }

    // ---------- contrato ----------

    public record ItemInput(UUID id, UUID productId, String barcode, String name, String variant, Long unitPriceMinor,
                            Long unitCostMinor, Long quantityMilli, Long discountMinor) {}

    public record PaymentInput(UUID id, String method, String otherLabel, Long amountMinor, Long tenderedMinor, String reference,
                               String debtorLabel, String debtorPhone, UUID customerId) {}

    public record SaleInput(String status, String label, UUID cashRegisterId, Long discountMinor, Instant createdAt,
                            Instant completedAt, List<ItemInput> items, List<PaymentInput> payments) {}

    public record ItemView(UUID id, UUID productId, String barcode, String name, String variant, long unitPriceMinor,
                           Long unitCostMinor, long quantityMilli, long discountMinor, long lineTotalMinor) {}

    public record PaymentView(UUID id, String method, String otherLabel, long amountMinor, Long tenderedMinor, Long changeMinor, String reference,
                              String debtorLabel, String debtorPhone, UUID customerId) {}

    public record MemberRef(UUID id, String name) {}

    public record SaleView(UUID id, String status, String label, UUID cashRegisterId, UUID deviceId, UUID businessDayId,
                           long subtotalMinor, long discountMinor, long totalMinor, MemberRef createdBy, MemberRef completedBy,
                           Instant completedAt, MemberRef editedBy, Instant editedAt, MemberRef cancelledBy, Instant cancelledAt,
                           String cancelReason, UUID lockedByDeviceId, Instant lockedUntil, Instant createdAt, Instant updatedAt,
                           long rev, List<ItemView> items, List<PaymentView> payments) {}

    public enum Outcome { CREATED, UPDATED, UNCHANGED, STALE }

    public record Result(SaleView sale, Outcome outcome) {}

    public record Summary(LocalDate date, long salesCount, long totalMinor, Map<String, Long> byMethod, long cancelledCount) {}

    // ---------- normalización ----------

    private record NItem(ItemInput in, String name, long price, long qty, long discount, long lineTotal) {}

    private record NPayment(UUID id, String method, String otherLabel, long amount, Long tendered, Long change, String reference,
                            String debtorLabel, String debtorPhone, UUID customerId) {}

    private record Norm(String status, String label, UUID register, long discount, List<NItem> items, List<NPayment> payments,
                        long subtotal, long total, Instant createdAt, Instant completedAt) {
        Norm withRegister(UUID r) {
            return new Norm(status, label, r, discount, items, payments, subtotal, total, createdAt, completedAt);
        }
    }

    private Norm normalize(SaleInput in, Instant now, UUID businessId) {
        String status = in.status();
        if (!"OPEN".equals(status) && !"PARKED".equals(status) && !"COMPLETED".equals(status)) {
            throw ApiException.badRequest("INVALID_STATUS", "Status must be OPEN, PARKED or COMPLETED");
        }
        List<ItemInput> rawItems = in.items() == null ? List.of() : in.items();
        if (rawItems.size() > MAX_ITEMS) throw ApiException.badRequest("TOO_MANY_ITEMS", "Too many lines");
        if (!"OPEN".equals(status) && rawItems.isEmpty()) throw ApiException.badRequest("EMPTY_SALE", "A sale needs at least one line");

        List<NItem> items = new ArrayList<>();
        Set<UUID> seenItems = new java.util.HashSet<>();
        long subtotal = 0;
        for (ItemInput i : rawItems) {
            if (i.id() == null || !seenItems.add(i.id())) throw ApiException.badRequest("INVALID_ITEM", "Each line needs its own id");
            String name = i.name() == null ? "" : i.name().trim();
            if (name.isEmpty() || name.length() > 200) throw ApiException.badRequest("INVALID_ITEM", "Line name is required (max 200)");
            if (i.unitPriceMinor() == null || i.unitPriceMinor() < 0 || i.unitPriceMinor() > SaleMath.MAX_MINOR) throw ApiException.badRequest("INVALID_PRICE", "Invalid price");
            if (i.quantityMilli() == null || i.quantityMilli() <= 0 || i.quantityMilli() > 999_999_999L) throw ApiException.badRequest("INVALID_QUANTITY", "Invalid quantity");
            long disc = i.discountMinor() == null ? 0 : i.discountMinor();
            if (disc < 0) throw ApiException.badRequest("INVALID_DISCOUNT", "Invalid discount");
            long line = SaleMath.lineTotal(i.unitPriceMinor(), i.quantityMilli(), disc);
            subtotal = Math.addExact(subtotal, line);
            items.add(new NItem(i, name, i.unitPriceMinor(), i.quantityMilli(), disc, line));
        }
        long discount = in.discountMinor() == null ? 0 : in.discountMinor();
        if (discount < 0 || discount > subtotal) throw ApiException.badRequest("INVALID_DISCOUNT", "Invalid discount");
        long total = subtotal - discount;

        List<PaymentInput> rawPays = in.payments() == null ? List.of() : in.payments();
        List<NPayment> pays = new ArrayList<>();
        if (!"COMPLETED".equals(status)) {
            if (!rawPays.isEmpty()) throw ApiException.badRequest("PAYMENTS_NOT_ALLOWED", "Only completed sales carry payments");
        } else {
            if (rawPays.size() > MAX_PAYMENTS) throw ApiException.badRequest("TOO_MANY_PAYMENTS", "Too many payments");
            long sum = 0;
            Set<UUID> seenPays = new java.util.HashSet<>();
            for (PaymentInput p : rawPays) {
                if (p.id() == null || !seenPays.add(p.id())) throw ApiException.badRequest("INVALID_PAYMENT", "Each payment needs its own id");
                if (p.method() == null || !METHODS.contains(p.method())) throw ApiException.badRequest("INVALID_METHOD", "Invalid payment method");
                if (p.amountMinor() == null || p.amountMinor() <= 0 || p.amountMinor() > SaleMath.MAX_MINOR) throw ApiException.badRequest("INVALID_PAYMENT", "Invalid payment amount");
                if ("OTHER".equals(p.method()) && (p.otherLabel() == null || p.otherLabel().isBlank())) throw ApiException.badRequest("INVALID_PAYMENT", "Label required for OTHER");
                Long tendered = null;
                Long change = null;
                if ("CASH".equals(p.method())) {
                    // Solo el efectivo puede entregarse de más: el exceso es el vuelto.
                    tendered = p.tenderedMinor() == null ? p.amountMinor() : p.tenderedMinor();
                    if (tendered < p.amountMinor() || tendered > SaleMath.MAX_MINOR) throw ApiException.badRequest("TENDERED_TOO_LOW", "Cash received is less than the amount");
                    change = tendered - p.amountMinor();
                }
                String debtor = null;
                String debtorPhone = null;
                UUID customerId = null;
                if ("CREDIT".equals(p.method())) {
                    // Un fiado se anota con el nombre de quien debe (texto libre); el cliente y el teléfono son opcionales.
                    debtor = p.debtorLabel() == null || p.debtorLabel().isBlank() ? null : p.debtorLabel().trim();
                    customerId = p.customerId();
                    if (debtor != null && debtor.length() > 120) throw ApiException.badRequest("INVALID_DEBTOR", "Debtor name too long");
                    if (customerId == null && debtor == null) throw ApiException.badRequest("DEBTOR_REQUIRED", "Say who the credit is for");
                    if (customerId == null && requiresCustomer(businessId)) throw ApiException.badRequest("CUSTOMER_REQUIRED", "This business needs a customer on every credit");
                    debtorPhone = Phones.normalize(p.debtorPhone(), countryOf(businessId));
                }
                sum = Math.addExact(sum, p.amountMinor());
                pays.add(new NPayment(p.id(), p.method(), "OTHER".equals(p.method()) ? p.otherLabel().trim() : null, p.amountMinor(), tendered, change,
                        p.reference() == null || p.reference().isBlank() ? null : p.reference().trim(), debtor, debtorPhone, customerId));
            }
            if (total > 0 && pays.isEmpty()) throw ApiException.badRequest("PAYMENT_REQUIRED", "A completed sale needs a payment");
            if (sum != total) throw ApiException.badRequest("PAYMENT_MISMATCH", "Payments (" + sum + ") do not add up to the total (" + total + ")");
        }

        Instant created = in.createdAt() == null || in.createdAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.createdAt();
        Instant completed = null;
        if ("COMPLETED".equals(status)) {
            completed = in.completedAt() == null || in.completedAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.completedAt();
        }
        return new Norm(status, in.label() == null || in.label().isBlank() ? null : in.label().trim(), in.cashRegisterId(), discount, items, pays, subtotal,
                total, created, completed);
    }

    /** Huella del contenido: repetir exactamente lo mismo no cambia nada ni sube la revisión. */
    private static String hash(Norm n) {
        StringBuilder sb = new StringBuilder();
        sb.append(n.status).append('|').append(n.label).append('|').append(n.register).append('|').append(n.discount).append('|').append(n.completedAt);
        for (NItem i : n.items) {
            sb.append("|I").append(i.in.id()).append(',').append(i.in.productId()).append(',').append(i.in.barcode()).append(',').append(i.name)
                    .append(',').append(i.in.variant()).append(',').append(i.price).append(',').append(i.in.unitCostMinor()).append(',').append(i.qty)
                    .append(',').append(i.discount);
        }
        for (NPayment p : n.payments) {
            sb.append("|P").append(p.id).append(',').append(p.method).append(',').append(p.otherLabel).append(',').append(p.amount).append(',')
                    .append(p.tendered).append(',').append(p.reference).append(',').append(p.debtorLabel).append(',').append(p.debtorPhone).append(',').append(p.customerId);
        }
        return TokenHasher.hash(sb.toString());
    }

    // ---------- operaciones ----------

    private record Row(String status, String hash, UUID lockedBy, Instant lockedUntil) {}

    @Transactional
    public Result upsert(MemberContext ctx, UUID id, SaleInput in) {
        Instant now = clock.instant();
        Norm n = normalize(in, now, ctx.businessId());
        // Al editar una venta sin indicar caja se conserva la suya (quien edita puede no tener teléfono ni ser de esa caja).
        UUID keep = n.register != null ? n.register : jdbc.sql("SELECT cash_register_id FROM sale WHERE id = :id AND business_id = :b").param("id", id).param("b", ctx.businessId())
                .query((rs, i) -> rs.getObject(1, UUID.class)).optional().orElse(null);
        n = n.withRegister(registers.resolve(ctx, keep));
        String hash = hash(n);

        Row row = jdbc.sql("SELECT status, content_hash, locked_by_device_id, locked_until FROM sale WHERE id = :id AND business_id = :b FOR UPDATE")
                .param("id", id).param("b", ctx.businessId())
                .query((rs, i) -> new Row(rs.getString("status"), rs.getString("content_hash"), rs.getObject("locked_by_device_id", UUID.class),
                        rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant()))
                .optional().orElse(null);

        if (row == null) {
            ctx.require(Permission.SELL);
            if (jdbc.sql("SELECT count(*) FROM sale WHERE id = :id").param("id", id).query(Integer.class).single() > 0) {
                throw ApiException.conflict("ID_TAKEN", "Id already in use");
            }
            UUID day = n.completedAt == null ? null : days.idFor(ctx.businessId(), n.completedAt);
            jdbc.sql("""
                            INSERT INTO sale (id, business_id, cash_register_id, device_id, business_day_id, status, label, subtotal_minor, discount_minor,
                                              total_minor, created_by_member_id, completed_by_member_id, completed_at, content_hash, created_at, updated_at)
                            VALUES (:id, :b, :reg, :dev, :day, :st, :label, :sub, :disc, :tot, :by, :cby, :cat, :hash, :created, :now)
                            """)
                    .param("id", id).param("b", ctx.businessId()).param("reg", n.register, java.sql.Types.OTHER).param("dev", ctx.deviceId(), java.sql.Types.OTHER)
                    .param("day", day, java.sql.Types.OTHER).param("st", n.status).param("label", n.label).param("sub", n.subtotal).param("disc", n.discount)
                    .param("tot", n.total).param("by", ctx.memberId()).param("cby", "COMPLETED".equals(n.status) ? ctx.memberId() : null, java.sql.Types.OTHER)
                    .param("cat", n.completedAt == null ? null : Timestamp.from(n.completedAt), java.sql.Types.TIMESTAMP)
                    .param("hash", hash).param("created", Timestamp.from(n.createdAt)).param("now", Timestamp.from(now)).update();
            replaceChildren(ctx.businessId(), id, n);
            stock.reconcileSale(ctx, id);
            if ("COMPLETED".equals(n.status)) {
                credits.syncSaleCredits(ctx, id, saleCredits(n), n.completedAt);
                audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.complete", "sale", id, "total=" + n.total);
            }
            return new Result(view(ctx.businessId(), id), Outcome.CREATED);
        }

        // Una venta cancelada no revive y una cobrada no retrocede: la versión vieja de otro teléfono se ignora.
        if ("CANCELLED".equals(row.status)) return new Result(view(ctx.businessId(), id), Outcome.STALE);
        if (hash.equals(row.hash)) return new Result(view(ctx.businessId(), id), Outcome.UNCHANGED);

        if ("COMPLETED".equals(row.status)) {
            if (!"COMPLETED".equals(n.status)) return new Result(view(ctx.businessId(), id), Outcome.STALE);
            ctx.require(Permission.EDIT_SALES);
            jdbc.sql("""
                            UPDATE sale SET label = :label, cash_register_id = :reg, subtotal_minor = :sub, discount_minor = :disc, total_minor = :tot,
                                   edited_by_member_id = :m, edited_at = :now, content_hash = :hash, updated_at = :now, rev = nextval('change_rev_seq')
                             WHERE id = :id AND business_id = :b
                            """)
                    .param("label", n.label).param("reg", n.register, java.sql.Types.OTHER).param("sub", n.subtotal).param("disc", n.discount)
                    .param("tot", n.total).param("m", ctx.memberId()).param("now", Timestamp.from(now)).param("hash", hash).param("id", id)
                    .param("b", ctx.businessId()).update();
            replaceChildren(ctx.businessId(), id, n);
            stock.reconcileSale(ctx, id);
            Instant originalCompletedAt = jdbc.sql("SELECT completed_at FROM sale WHERE id = :id").param("id", id).query((rs, i) -> rs.getTimestamp(1).toInstant()).single();
            credits.syncSaleCredits(ctx, id, saleCredits(n), originalCompletedAt);
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.edit", "sale", id, "total=" + n.total);
            return new Result(view(ctx.businessId(), id), Outcome.UPDATED);
        }

        ctx.require(Permission.SELL);
        if (row.lockedBy != null && row.lockedUntil != null && row.lockedUntil.isAfter(now) && !row.lockedBy.equals(ctx.deviceId())) {
            throw ApiException.conflict("SALE_LOCKED", "This ticket is open on another phone");
        }
        boolean completing = "COMPLETED".equals(n.status);
        UUID day = completing ? days.idFor(ctx.businessId(), n.completedAt) : null;
        jdbc.sql("""
                        UPDATE sale SET status = :st, label = :label, cash_register_id = :reg, subtotal_minor = :sub, discount_minor = :disc, total_minor = :tot,
                               completed_by_member_id = :cby, completed_at = :cat, business_day_id = :day, locked_by_device_id = NULL, locked_until = NULL,
                               content_hash = :hash, updated_at = :now, rev = nextval('change_rev_seq')
                         WHERE id = :id AND business_id = :b
                        """)
                .param("st", n.status).param("label", n.label).param("reg", n.register, java.sql.Types.OTHER).param("sub", n.subtotal).param("disc", n.discount)
                .param("tot", n.total).param("cby", completing ? ctx.memberId() : null, java.sql.Types.OTHER)
                .param("cat", n.completedAt == null ? null : Timestamp.from(n.completedAt), java.sql.Types.TIMESTAMP).param("day", day, java.sql.Types.OTHER)
                .param("hash", hash).param("now", Timestamp.from(now)).param("id", id).param("b", ctx.businessId()).update();
        replaceChildren(ctx.businessId(), id, n);
        stock.reconcileSale(ctx, id);
        if (completing) {
            credits.syncSaleCredits(ctx, id, saleCredits(n), n.completedAt);
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.complete", "sale", id, "total=" + n.total);
        }
        return new Result(view(ctx.businessId(), id), Outcome.UPDATED);
    }

    private java.util.List<CreditService.SaleCredit> saleCredits(Norm n) {
        return n.payments.stream().filter(p -> "CREDIT".equals(p.method))
                .map(p -> new CreditService.SaleCredit(p.id, p.amount, p.debtorLabel, p.debtorPhone, p.customerId)).toList();
    }

    private boolean requiresCustomer(UUID businessId) {
        return jdbc.sql("SELECT credit_requires_customer FROM business WHERE id = :b").param("b", businessId).query(Boolean.class).single();
    }

    private String countryOf(UUID businessId) {
        return jdbc.sql("SELECT country FROM business WHERE id = :b").param("b", businessId).query(String.class).single();
    }

    private void replaceChildren(UUID businessId, UUID saleId, Norm n) {
        jdbc.sql("DELETE FROM sale_item WHERE sale_id = :s").param("s", saleId).update();
        jdbc.sql("DELETE FROM sale_payment WHERE sale_id = :s").param("s", saleId).update();
        int pos = 0;
        for (NItem i : n.items) {
            jdbc.sql("""
                            INSERT INTO sale_item (id, sale_id, business_id, product_id, barcode, name, variant, unit_price_minor, unit_cost_minor,
                                                   quantity_milli, discount_minor, position)
                            VALUES (:id, :s, :b, :p, :bc, :n, :v, :price, :cost, :q, :d, :pos)
                            """)
                    .param("id", i.in.id()).param("s", saleId).param("b", businessId).param("p", i.in.productId(), java.sql.Types.OTHER)
                    .param("bc", i.in.barcode()).param("n", i.name).param("v", i.in.variant()).param("price", i.price)
                    .param("cost", i.in.unitCostMinor(), java.sql.Types.BIGINT).param("q", i.qty).param("d", i.discount).param("pos", pos++).update();
        }
        pos = 0;
        for (NPayment p : n.payments) {
            jdbc.sql("""
                            INSERT INTO sale_payment (id, sale_id, business_id, method, other_label, amount_minor, tendered_minor, change_minor, reference, position,
                                                      debtor_label, debtor_phone_e164, customer_id)
                            VALUES (:id, :s, :b, :m, :ol, :a, :t, :c, :r, :pos, :dl, :dp, :cu)
                            """)
                    .param("id", p.id).param("s", saleId).param("b", businessId).param("m", p.method).param("ol", p.otherLabel).param("a", p.amount)
                    .param("t", p.tendered, java.sql.Types.BIGINT).param("c", p.change, java.sql.Types.BIGINT).param("r", p.reference).param("pos", pos++)
                    .param("dl", p.debtorLabel).param("dp", p.debtorPhone).param("cu", p.customerId, java.sql.Types.OTHER).update();
        }
    }

    @Transactional
    public SaleView cancel(MemberContext ctx, UUID id, String reason) {
        String status = jdbc.sql("SELECT status FROM sale WHERE id = :id AND business_id = :b FOR UPDATE").param("id", id).param("b", ctx.businessId())
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("SALE_NOT_FOUND", "Sale not found"));
        if ("CANCELLED".equals(status)) return view(ctx.businessId(), id);
        // Descartar una cuenta apartada lo hace cualquiera que venda; eliminar una venta cobrada, quien puede editarlas.
        ctx.require("COMPLETED".equals(status) ? Permission.EDIT_SALES : Permission.SELL);
        if ("COMPLETED".equals(status)) credits.cancelSaleCredits(ctx, id);
        jdbc.sql("""
                        UPDATE sale SET status = 'CANCELLED', cancelled_by_member_id = :m, cancelled_at = :now, cancel_reason = :r, locked_by_device_id = NULL,
                               locked_until = NULL, updated_at = :now, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b
                        """)
                .param("m", ctx.memberId()).param("now", Timestamp.from(clock.instant())).param("r", reason == null || reason.isBlank() ? null : reason.trim())
                .param("id", id).param("b", ctx.businessId()).update();
        stock.reconcileSale(ctx, id);
        if ("COMPLETED".equals(status)) notifySaleDeleted(ctx, id);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.cancel", "sale", id, status + (reason == null ? "" : ": " + reason));
        return view(ctx.businessId(), id);
    }

    /** Eliminar una venta ya cobrada es de las cosas que el dueño quiere saber; si la borró él mismo, no hace falta avisarle. */
    private void notifySaleDeleted(MemberContext ctx, UUID saleId) {
        long total = jdbc.sql("SELECT total_minor FROM sale WHERE id = :id").param("id", saleId).query(Long.class).single();
        String member = jdbc.sql("SELECT display_name FROM member WHERE id = :m").param("m", ctx.memberId()).query(String.class).single();
        notifications.notify(ctx.businessId(), com.cuadra.api.notification.NotificationService.Type.SALE_DELETED, java.util.Map.of("saleId", saleId.toString(), "memberName", member, "totalMinor", total),
                null, ctx.memberId(), "SALE_DELETED:" + saleId, "cuadra://notificaciones");
    }

    @Transactional
    public SaleView lock(MemberContext ctx, UUID id) {
        ctx.require(Permission.SELL);
        if (ctx.deviceId() == null) throw ApiException.badRequest("DEVICE_REQUIRED", "Only a linked phone can hold a ticket");
        Instant now = clock.instant();
        var row = jdbc.sql("SELECT status, locked_by_device_id, locked_until FROM sale WHERE id = :id AND business_id = :b FOR UPDATE")
                .param("id", id).param("b", ctx.businessId())
                .query((rs, i) -> new Object[] {rs.getString("status"), rs.getObject("locked_by_device_id", UUID.class), rs.getTimestamp("locked_until")})
                .optional().orElseThrow(() -> ApiException.notFound("SALE_NOT_FOUND", "Sale not found"));
        if (!"PARKED".equals(row[0])) throw ApiException.conflict("SALE_NOT_PARKED", "Only parked tickets can be resumed");
        UUID holder = (UUID) row[1];
        Timestamp until = (Timestamp) row[2];
        if (holder != null && !holder.equals(ctx.deviceId()) && until != null && until.toInstant().isAfter(now)) {
            throw ApiException.conflict("SALE_LOCKED", "This ticket is open on another phone");
        }
        jdbc.sql("UPDATE sale SET locked_by_device_id = :d, locked_until = :u WHERE id = :id").param("d", ctx.deviceId())
                .param("u", Timestamp.from(now.plus(LOCK_TTL))).param("id", id).update();
        return view(ctx.businessId(), id);
    }

    @Transactional
    public void unlock(MemberContext ctx, UUID id) {
        jdbc.sql("UPDATE sale SET locked_by_device_id = NULL, locked_until = NULL WHERE id = :id AND business_id = :b AND locked_by_device_id = :d")
                .param("id", id).param("b", ctx.businessId()).param("d", ctx.deviceId(), java.sql.Types.OTHER).update();
    }

    // ---------- lectura ----------

    /** Un cajero ve las cuentas apartadas del negocio y sus propias ventas; dueño y admins ven todo. */
    public boolean visible(MemberContext ctx, SaleView v) {
        if (ctx.role() != Role.CASHIER) return true;
        return "PARKED".equals(v.status()) || (v.createdBy() != null && v.createdBy().id().equals(ctx.memberId()))
                || (v.completedBy() != null && v.completedBy().id().equals(ctx.memberId()));
    }

    public SaleView get(MemberContext ctx, UUID id) {
        SaleView v = view(ctx.businessId(), id);
        if (!visible(ctx, v)) throw ApiException.notFound("SALE_NOT_FOUND", "Sale not found");
        return v;
    }

    public SaleView view(UUID businessId, UUID id) {
        List<SaleView> list = load(businessId, "s.id = :id", Map.of("id", id), "", 1);
        if (list.isEmpty()) throw ApiException.notFound("SALE_NOT_FOUND", "Sale not found");
        return list.get(0);
    }

    public record Filter(String status, LocalDate from, LocalDate to, Integer hourFrom, Integer hourTo, UUID memberId, String method) {}

    public PageResponse<SaleView> list(MemberContext ctx, Filter f, int page, int size) {
        size = Math.max(1, Math.min(size, 100));
        page = Math.max(0, page);
        BusinessDayService.Info info = days.info(ctx.businessId());
        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (f.status() != null) {
            where.append(" AND s.status = :status");
            params.put("status", f.status());
        }
        if (f.from() != null) {
            where.append(" AND coalesce(s.completed_at, s.created_at) >= :from");
            params.put("from", Timestamp.from(info.startOf(f.from())));
        }
        if (f.to() != null) {
            where.append(" AND coalesce(s.completed_at, s.created_at) < :to");
            params.put("to", Timestamp.from(info.endOf(f.to())));
        }
        if (f.hourFrom() != null || f.hourTo() != null) {
            int hf = f.hourFrom() == null ? 0 : f.hourFrom();
            int ht = f.hourTo() == null ? 23 : f.hourTo();
            where.append(" AND extract(hour FROM coalesce(s.completed_at, s.created_at) AT TIME ZONE :tz) BETWEEN :hf AND :ht");
            params.put("tz", info.zone().getId());
            params.put("hf", hf);
            params.put("ht", ht);
        }
        if (f.memberId() != null) {
            where.append(" AND (s.created_by_member_id = :fm OR s.completed_by_member_id = :fm)");
            params.put("fm", f.memberId());
        }
        if (f.method() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM sale_payment p WHERE p.sale_id = s.id AND p.method = :method)");
            params.put("method", f.method());
        }
        if (ctx.role() == Role.CASHIER) {
            where.append(" AND (s.status = 'PARKED' OR s.created_by_member_id = :me OR s.completed_by_member_id = :me)");
            params.put("me", ctx.memberId());
        }
        String clause = where.toString();
        var count = jdbc.sql("SELECT count(*) FROM sale s WHERE s.business_id = :b AND " + clause).param("b", ctx.businessId());
        for (var e : params.entrySet()) count = count.param(e.getKey(), e.getValue());
        long total = count.query(Long.class).single();
        List<SaleView> items = load(ctx.businessId(), clause, params,
                " ORDER BY coalesce(s.completed_at, s.created_at) DESC, s.id LIMIT " + size + " OFFSET " + (long) page * size, size);
        return PageResponse.of(items, page, size, total);
    }

    /** Resumen de una jornada comercial. Un cajero solo ve lo que él cobró. */
    public Summary summary(MemberContext ctx, LocalDate date) {
        BusinessDayService.Info info = days.info(ctx.businessId());
        LocalDate day = date != null ? date : info.dateOf(clock.instant());
        Timestamp start = Timestamp.from(info.startOf(day));
        Timestamp end = Timestamp.from(info.endOf(day));
        boolean own = ctx.role() == Role.CASHIER;
        String mine = own ? " AND s.completed_by_member_id = :me" : "";
        var byMethodQ = jdbc.sql("""
                        SELECT p.method, sum(p.amount_minor) AS total FROM sale_payment p JOIN sale s ON s.id = p.sale_id
                         WHERE s.business_id = :b AND s.status = 'COMPLETED' AND s.completed_at >= :s AND s.completed_at < :e""" + mine + " GROUP BY p.method")
                .param("b", ctx.businessId()).param("s", start).param("e", end);
        if (own) byMethodQ = byMethodQ.param("me", ctx.memberId());
        Map<String, Long> byMethod = new LinkedHashMap<>();
        for (String m : List.of("CASH", "TRANSFER", "CARD", "CREDIT", "OTHER")) byMethod.put(m, 0L);
        byMethodQ.query((rs, i) -> {
            byMethod.put(rs.getString("method"), rs.getLong("total"));
            return null;
        }).list();
        var totalsQ = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE s.status = 'COMPLETED') AS n, coalesce(sum(s.total_minor) FILTER (WHERE s.status = 'COMPLETED'), 0) AS total
                          FROM sale s WHERE s.business_id = :b AND s.completed_at >= :s AND s.completed_at < :e""" + mine)
                .param("b", ctx.businessId()).param("s", start).param("e", end);
        if (own) totalsQ = totalsQ.param("me", ctx.memberId());
        long[] totals = totalsQ.query((rs, i) -> new long[] {rs.getLong("n"), rs.getLong("total")}).single();
        var cancelQ = jdbc.sql("SELECT count(*) FROM sale s WHERE s.business_id = :b AND s.status = 'CANCELLED' AND s.cancelled_at >= :s AND s.cancelled_at < :e"
                        + (own ? " AND s.cancelled_by_member_id = :me" : ""))
                .param("b", ctx.businessId()).param("s", start).param("e", end);
        if (own) cancelQ = cancelQ.param("me", ctx.memberId());
        return new Summary(day, totals[0], totals[1], byMethod, cancelQ.query(Long.class).single());
    }

    private List<SaleView> load(UUID businessId, String clause, Map<String, Object> params, String tail, int limit) {
        var q = jdbc.sql("""
                        SELECT s.*, mc.display_name AS created_name, mk.display_name AS completed_name, me.display_name AS edited_name,
                               mx.display_name AS cancelled_name
                          FROM sale s
                          JOIN member mc ON mc.id = s.created_by_member_id
                          LEFT JOIN member mk ON mk.id = s.completed_by_member_id
                          LEFT JOIN member me ON me.id = s.edited_by_member_id
                          LEFT JOIN member mx ON mx.id = s.cancelled_by_member_id
                         WHERE s.business_id = :b""" + " AND " + clause + tail).param("b", businessId);
        for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());
        List<SaleView> heads = q.query((rs, i) -> new SaleView(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("label"),
                rs.getObject("cash_register_id", UUID.class), rs.getObject("device_id", UUID.class), rs.getObject("business_day_id", UUID.class),
                rs.getLong("subtotal_minor"), rs.getLong("discount_minor"), rs.getLong("total_minor"),
                ref(rs, "created_by_member_id", "created_name"), ref(rs, "completed_by_member_id", "completed_name"), instant(rs, "completed_at"),
                ref(rs, "edited_by_member_id", "edited_name"), instant(rs, "edited_at"), ref(rs, "cancelled_by_member_id", "cancelled_name"),
                instant(rs, "cancelled_at"), rs.getString("cancel_reason"), rs.getObject("locked_by_device_id", UUID.class), instant(rs, "locked_until"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("rev"), List.of(), List.of())).list();
        if (heads.isEmpty()) return heads;

        List<UUID> ids = heads.stream().map(SaleView::id).toList();
        Map<UUID, List<ItemView>> items = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM sale_item WHERE sale_id IN (:ids) ORDER BY sale_id, position").param("ids", ids).query((rs, i) -> {
            long qty = rs.getLong("quantity_milli");
            long price = rs.getLong("unit_price_minor");
            long disc = rs.getLong("discount_minor");
            items.computeIfAbsent(rs.getObject("sale_id", UUID.class), k -> new ArrayList<>()).add(new ItemView(rs.getObject("id", UUID.class),
                    rs.getObject("product_id", UUID.class), rs.getString("barcode"), rs.getString("name"), rs.getString("variant"), price,
                    (Long) rs.getObject("unit_cost_minor"), qty, disc, SaleMath.lineTotal(price, qty, disc)));
            return null;
        }).list();
        Map<UUID, List<PaymentView>> pays = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM sale_payment WHERE sale_id IN (:ids) ORDER BY sale_id, position").param("ids", ids).query((rs, i) -> {
            pays.computeIfAbsent(rs.getObject("sale_id", UUID.class), k -> new ArrayList<>()).add(new PaymentView(rs.getObject("id", UUID.class),
                    rs.getString("method"), rs.getString("other_label"), rs.getLong("amount_minor"), (Long) rs.getObject("tendered_minor"),
                    (Long) rs.getObject("change_minor"), rs.getString("reference"), rs.getString("debtor_label"), rs.getString("debtor_phone_e164"),
                    rs.getObject("customer_id", UUID.class)));
            return null;
        }).list();
        return heads.stream().map(h -> new SaleView(h.id(), h.status(), h.label(), h.cashRegisterId(), h.deviceId(), h.businessDayId(), h.subtotalMinor(),
                h.discountMinor(), h.totalMinor(), h.createdBy(), h.completedBy(), h.completedAt(), h.editedBy(), h.editedAt(), h.cancelledBy(),
                h.cancelledAt(), h.cancelReason(), h.lockedByDeviceId(), h.lockedUntil(), h.createdAt(), h.updatedAt(), h.rev(),
                items.getOrDefault(h.id(), List.of()), pays.getOrDefault(h.id(), List.of()))).collect(Collectors.toList());
    }

    private static MemberRef ref(java.sql.ResultSet rs, String idCol, String nameCol) throws java.sql.SQLException {
        UUID id = rs.getObject(idCol, UUID.class);
        return id == null ? null : new MemberRef(id, rs.getString(nameCol));
    }

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
