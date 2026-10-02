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
    /** Un cajero puede anular su ÚLTIMA venta durante estos minutos (con motivo y aviso al dueño); después, solo dueño y admins. */
    public static final Duration UNDO_WINDOW = Duration.ofMinutes(5);
    /** Margen por segundos de red o de pantalla al comparar con la ventana. */
    static final Duration UNDO_TOLERANCE = Duration.ofSeconds(30);
    /** Una anulación hecha sin conexión dentro de la ventana puede llegar tarde, pero no días después. */
    static final Duration UNDO_MAX_DELAY = Duration.ofHours(48);

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final BusinessDayService days;
    private final CreditService credits;
    private final com.cuadra.api.cash.RegisterResolver registers;
    private final com.cuadra.api.stock.StockService stock;
    private final com.cuadra.api.notification.NotificationService notifications;
    private final ReturnService returns;
    private final com.cuadra.api.push.PushDispatcher push;

    public SaleService(JdbcClient jdbc, Audit audit, Clock clock, BusinessDayService days, CreditService credits, com.cuadra.api.cash.RegisterResolver registers,
                       com.cuadra.api.stock.StockService stock, com.cuadra.api.notification.NotificationService notifications, ReturnService returns,
                       com.cuadra.api.push.PushDispatcher push) {
        this.push = push;
        this.returns = returns;
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

    /**
     * `fromStatus`: el estado que tenía la venta en el teléfono antes de este cambio. "PARKED" al cobrar una cuenta apartada retomada: si en el servidor
     * esa cuenta ya se cobró (distinta) o se descartó en otro teléfono, esta venta NO se descarta ni pisa la otra: se guarda aparte para revisar.
     */
    public record SaleInput(String status, String label, UUID cashRegisterId, Long discountMinor, Instant createdAt,
                            Instant completedAt, List<ItemInput> items, List<PaymentInput> payments, String fromStatus,
                            /**
                             * Cobro en caja (ADR 0015), solo con status PARKED: true = "Enviar a caja" (queda en «Por cobrar en caja»; la nota es `label`);
                             * false = vuelve a ser una cuenta apartada común; ausente = no cambia.
                             */
                            Boolean sendToRegister,
                            /**
                             * Promociones por cantidad que el teléfono aplicó (PENDIENTES.md, «Promociones por cantidad»): su descuento ya va repartido en el
                             * `discountMinor` de las líneas. El servidor NO vuelve a calcular precios: solo comprueba que las sumas cuadren.
                             */
                            List<SalePromotionInput> promotions) {
        public SaleInput(String status, String label, UUID cashRegisterId, Long discountMinor, Instant createdAt, Instant completedAt, List<ItemInput> items,
                         List<PaymentInput> payments, String fromStatus, Boolean sendToRegister) {
            this(status, label, cashRegisterId, discountMinor, createdAt, completedAt, items, payments, fromStatus, sendToRegister, null);
        }

        public SaleInput(String status, String label, UUID cashRegisterId, Long discountMinor, Instant createdAt, Instant completedAt, List<ItemInput> items,
                         List<PaymentInput> payments, String fromStatus) {
            this(status, label, cashRegisterId, discountMinor, createdAt, completedAt, items, payments, fromStatus, null);
        }

        public SaleInput(String status, String label, UUID cashRegisterId, Long discountMinor, Instant createdAt, Instant completedAt, List<ItemInput> items,
                         List<PaymentInput> payments) {
            this(status, label, cashRegisterId, discountMinor, createdAt, completedAt, items, payments, null, null);
        }
    }

    /** Una promoción aplicada en la venta: «3 por C$ 100» (`quantity`, `priceMinor`), cuántas unidades entraron en paquetes y cuánto se descontó. */
    public record SalePromotionInput(UUID promotionId, String name, Integer quantity, Long priceMinor, Integer units, Long discountMinor) {}

    public record SalePromotionView(UUID promotionId, String name, int quantity, long priceMinor, int units, long discountMinor) {}

    /** `returnedMilli`: cuánto de esta línea ya se devolvió (se puede devolver hasta `quantityMilli − returnedMilli`). */
    public record ItemView(UUID id, UUID productId, String barcode, String name, String variant, long unitPriceMinor,
                           Long unitCostMinor, long quantityMilli, long discountMinor, long lineTotalMinor, long returnedMilli) {}

    public record PaymentView(UUID id, String method, String otherLabel, long amountMinor, Long tenderedMinor, Long changeMinor, String reference,
                              String debtorLabel, String debtorPhone, UUID customerId) {}

    public record MemberRef(UUID id, String name) {}

    public record SaleView(UUID id, String status, String label, UUID cashRegisterId, UUID deviceId, UUID businessDayId,
                           long subtotalMinor, long discountMinor, long totalMinor, MemberRef createdBy, MemberRef completedBy,
                           Instant completedAt, MemberRef editedBy, Instant editedAt, MemberRef cancelledBy, Instant cancelledAt,
                           String cancelReason, UUID lockedByDeviceId, Instant lockedUntil, Instant createdAt, Instant updatedAt,
                           long rev, List<ItemView> items, List<PaymentView> payments,
                           /** Venta guardada aparte porque chocó con otra versión de `conflictOfSaleId` (cobrada o descartada en otro teléfono): revisar. */
                           UUID conflictOfSaleId,
                           /** Para revisar: LATE_AFTER_DISABLE (llegó después de la baja de quien la hizo) o CLOCK_ADJUSTED (el teléfono tenía la hora imposible). */
                           String reviewFlag,
                           /** Lo devuelto de esta venta (cada devolución cuenta en la jornada en que se hizo) y su suma. */
                           long returnedMinor, List<ReturnService.ReturnView> returns,
                           /** Cobro en caja (ADR 0015): cuándo y quién la envió a caja. Una PARKED con `sentToRegisterAt` está «Por cobrar en caja». */
                           Instant sentToRegisterAt, MemberRef sentBy,
                           /** Quién la tiene abierta en su teléfono ahora («La está cobrando Ana»); solo mientras `lockedUntil` no pasó. */
                           MemberRef lockedBy,
                           /** ¿Está en la lista «Por cobrar en caja»? (PARKED y enviada a caja) */
                           boolean pendingCheckout,
                           /** Promociones por cantidad aplicadas (su descuento ya está en las líneas) y su suma. */
                           List<SalePromotionView> promotions, long promotionDiscountMinor) {}

    /** CONFLICT_COPY: la versión que llegó chocó con otra ya cobrada o descartada y se guardó como venta NUEVA (`sale()` es esa copia). */
    public enum Outcome { CREATED, UPDATED, UNCHANGED, STALE, CONFLICT_COPY }

    public record Result(SaleView sale, Outcome outcome) {}

    public record Summary(LocalDate date, long salesCount, long totalMinor, Map<String, Long> byMethod, long cancelledCount) {}

    // ---------- normalización ----------

    private record NItem(ItemInput in, String name, long price, long qty, long discount, long lineTotal) {}

    private record NPayment(UUID id, String method, String otherLabel, long amount, Long tendered, Long change, String reference,
                            String debtorLabel, String debtorPhone, UUID customerId) {}

    private record NPromo(UUID promotionId, String name, int quantity, long price, int units, long discount) {}

    private record Norm(String status, String label, UUID register, long discount, List<NItem> items, List<NPayment> payments,
                        long subtotal, long total, Instant createdAt, Instant completedAt, boolean pendingCheckout, List<NPromo> promotions) {
        Norm withRegister(UUID r) {
            return new Norm(status, label, r, discount, items, payments, subtotal, total, createdAt, completedAt, pendingCheckout, promotions);
        }

        Norm withPending(boolean p) {
            return new Norm(status, label, register, discount, items, payments, subtotal, total, createdAt, completedAt, p, promotions);
        }
    }

    private static final int MAX_PROMOTIONS = 50;

    /**
     * Las promociones que trae la venta: datos sanos y que su descuento no pase del descuento que llevan las líneas (allí ya está repartido). No se
     * comprueba contra la promoción vigente: una venta hecha sin conexión con una promoción vieja se acepta tal como se cobró.
     */
    private static List<NPromo> promotions(SaleInput in, List<NItem> items) {
        List<SalePromotionInput> raw = in.promotions() == null ? List.of() : in.promotions();
        if (raw.isEmpty()) return List.of();
        if (raw.size() > MAX_PROMOTIONS) throw ApiException.badRequest("TOO_MANY_PROMOTIONS", "Too many promotions");
        List<NPromo> out = new ArrayList<>();
        long sum = 0;
        for (SalePromotionInput p : raw) {
            String name = p.name() == null ? "" : p.name().trim();
            if (name.isEmpty() || name.length() > 200) throw ApiException.badRequest("INVALID_PROMOTION", "Promotion name is required (max 200)");
            if (p.quantity() == null || p.quantity() < 2) throw ApiException.badRequest("INVALID_PROMOTION", "Promotion quantity must be 2 or more");
            if (p.priceMinor() == null || p.priceMinor() < 0 || p.priceMinor() > SaleMath.MAX_MINOR) throw ApiException.badRequest("INVALID_PROMOTION", "Invalid promotion price");
            if (p.units() == null || p.units() < p.quantity() || p.units() % p.quantity() != 0) throw ApiException.badRequest("INVALID_PROMOTION", "Promotion units must be whole packs");
            if (p.discountMinor() == null || p.discountMinor() <= 0) throw ApiException.badRequest("INVALID_PROMOTION", "Invalid promotion discount");
            sum = Math.addExact(sum, p.discountMinor());
            out.add(new NPromo(p.promotionId(), name, p.quantity(), p.priceMinor(), p.units(), p.discountMinor()));
        }
        long lineDiscounts = items.stream().mapToLong(NItem::discount).sum();
        if (sum > lineDiscounts) throw ApiException.badRequest("PROMOTION_MISMATCH", "Promotion discounts (" + sum + ") exceed the line discounts (" + lineDiscounts + ")");
        return out;
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
        String label = in.label() == null || in.label().isBlank() ? null : in.label().trim();
        if (label != null && label.length() > 120) throw ApiException.badRequest("INVALID_LABEL", "Note too long (max 120)");
        return new Norm(status, label, in.cashRegisterId(), discount, items, pays, subtotal, total, created, completed, false, promotions(in, items));
    }

    /** Huella del contenido: repetir exactamente lo mismo no cambia nada ni sube la revisión. */
    private static String hash(Norm n) {
        StringBuilder sb = new StringBuilder();
        sb.append(n.status).append('|').append(n.label).append('|').append(n.register).append('|').append(n.discount).append('|').append(n.completedAt);
        // Solo si está por cobrar en caja: las huellas de las cuentas de siempre no cambian.
        if (n.pendingCheckout) sb.append("|R");
        for (NItem i : n.items) {
            sb.append("|I").append(i.in.id()).append(',').append(i.in.productId()).append(',').append(i.in.barcode()).append(',').append(i.name)
                    .append(',').append(i.in.variant()).append(',').append(i.price).append(',').append(i.in.unitCostMinor()).append(',').append(i.qty)
                    .append(',').append(i.discount);
        }
        for (NPayment p : n.payments) {
            sb.append("|P").append(p.id).append(',').append(p.method).append(',').append(p.otherLabel).append(',').append(p.amount).append(',')
                    .append(p.tendered).append(',').append(p.reference).append(',').append(p.debtorLabel).append(',').append(p.debtorPhone).append(',').append(p.customerId);
        }
        // Solo con promociones: las huellas de las ventas de siempre no cambian.
        for (NPromo p : n.promotions) {
            sb.append("|M").append(p.promotionId()).append(',').append(p.name()).append(',').append(p.quantity()).append(',').append(p.price()).append(',')
                    .append(p.units()).append(',').append(p.discount());
        }
        return TokenHasher.hash(sb.toString());
    }

    // ---------- operaciones ----------

    private record Row(String status, String hash, UUID lockedBy, Instant lockedUntil, boolean pending, UUID lockedMember) {}

    @Transactional
    public Result upsert(MemberContext ctx, UUID id, SaleInput in) {
        return upsert(ctx, id, in, null);
    }

    /** `opId`: la operación de sincronización que trae este cambio; da el id (determinista) de la copia si hay conflicto. */
    @Transactional
    public Result upsert(MemberContext ctx, UUID id, SaleInput in, UUID opId) {
        Instant now = clock.instant();
        Norm n = normalize(in, now, ctx.businessId());
        // Al editar una venta sin indicar caja se conserva la suya (quien edita puede no tener teléfono ni ser de esa caja).
        UUID keep = n.register != null ? n.register : jdbc.sql("SELECT cash_register_id FROM sale WHERE id = :id AND business_id = :b").param("id", id).param("b", ctx.businessId())
                .query((rs, i) -> rs.getObject(1, UUID.class)).optional().orElse(null);
        n = n.withRegister(registers.resolve(ctx, keep));

        Row row = jdbc.sql("SELECT status, content_hash, locked_by_device_id, locked_until, sent_to_register_at, locked_by_member_id FROM sale WHERE id = :id AND business_id = :b FOR UPDATE")
                .param("id", id).param("b", ctx.businessId())
                .query((rs, i) -> new Row(rs.getString("status"), rs.getString("content_hash"), rs.getObject("locked_by_device_id", UUID.class),
                        rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant(), rs.getTimestamp("sent_to_register_at") != null,
                        rs.getObject("locked_by_member_id", UUID.class)))
                .optional().orElse(null);
        // Cobro en caja: solo una cuenta apartada puede estar «por cobrar en caja». Sin indicarlo se conserva lo que tenía (una versión vieja de la app,
        // o el teléfono que la retomó y la vuelve a apartar). Con el ajuste del negocio apagado no se envía a caja: desde la cola (sin conexión, la
        // persona ya no está mirando) se guarda como cuenta apartada común para no perderla; directo se rechaza.
        boolean pending = false;
        if ("PARKED".equals(n.status)) {
            pending = in.sendToRegister() != null ? in.sendToRegister() : row != null && row.pending;
            if (Boolean.TRUE.equals(in.sendToRegister()) && !registerCheckoutOn(ctx.businessId())) {
                if (opId == null) throw ApiException.conflict("COBRO_EN_CAJA_OFF", "Register checkout is turned off for this business");
                pending = row != null && row.pending;
            }
        }
        n = n.withPending(pending);
        String hash = hash(n);

        if (row == null) {
            ctx.require(Permission.SELL);
            if (jdbc.sql("SELECT count(*) FROM sale WHERE id = :id").param("id", id).query(Integer.class).single() > 0) {
                throw ApiException.conflict("ID_TAKEN", "Id already in use");
            }
            insertNew(ctx, id, n, hash, now, null);
            return new Result(view(ctx.businessId(), id), Outcome.CREATED);
        }

        // Una venta cancelada no revive y una cobrada no retrocede: la versión vieja de otro teléfono se ignora.
        // EXCEPCIÓN, "un pago recibido nunca se pierde": una venta COBRADA distinta que llega sobre una descartada (la cuenta apartada se cobró sin conexión
        // después de que otro teléfono la descartó) se guarda como venta nueva, marcada para revisión.
        if ("CANCELLED".equals(row.status)) {
            if ("COMPLETED".equals(n.status) && !hash.equals(row.hash) && (opId != null || "PARKED".equals(in.fromStatus()))) return conflictCopy(ctx, id, n, opId, now);
            return new Result(view(ctx.businessId(), id), Outcome.STALE);
        }
        if (hash.equals(row.hash)) return new Result(view(ctx.businessId(), id), Outcome.UNCHANGED);

        if ("COMPLETED".equals(row.status)) {
            if (!"COMPLETED".equals(n.status)) return new Result(view(ctx.businessId(), id), Outcome.STALE);
            // La misma cuenta apartada cobrada en dos teléfonos sin conexión: la segunda no pisa las líneas y pagos de la primera (ni se rechaza, si la
            // cobró un cajero): se guarda aparte para revisar. Solo quien puede editar ventas y edita a propósito (sin `fromStatus`) la modifica.
            // (Por la cola, un cajero sin `fromStatus` —versión vieja de la app— tampoco puede editar: también se guarda aparte en vez de perderse.)
            if ("PARKED".equals(in.fromStatus()) || (opId != null && !ctx.role().can(Permission.EDIT_SALES))) return conflictCopy(ctx, id, n, opId, now);
            ctx.require(Permission.EDIT_SALES);
            if (returns.hasReturns(id)) throw ApiException.conflict("SALE_HAS_RETURNS", "This sale has returns: it can no longer be edited");
            long previousTotal = jdbc.sql("SELECT total_minor FROM sale WHERE id = :id").param("id", id).query(Long.class).single();
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
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.edit", "sale", id, "total=" + previousTotal + "→" + n.total);
            return new Result(view(ctx.businessId(), id), Outcome.UPDATED);
        }

        ctx.require(Permission.SELL);
        if (row.lockedBy != null && row.lockedUntil != null && row.lockedUntil.isAfter(now) && !row.lockedBy.equals(ctx.deviceId())) {
            throw locked(row.lockedMember);
        }
        boolean completing = "COMPLETED".equals(n.status);
        // Enviada (o reenviada) a caja: cuándo y quién. Reenviada con productos agregados: conserva CUÁNDO llegó (no pierde su lugar en la lista, la que más espera
        // primero) y pasa a decir quién la reenvió. Vuelta a cuenta común: se borra. Cobrada o sin cambio: se conserva (quién la envió queda en la venta).
        String sent = "PARKED".equals(n.status) && Boolean.TRUE.equals(in.sendToRegister()) && n.pendingCheckout
                ? "sent_to_register_at = COALESCE(sent_to_register_at, :now), sent_by_member_id = :me,"
                : "PARKED".equals(n.status) && !n.pendingCheckout ? "sent_to_register_at = NULL, sent_by_member_id = NULL," : "";
        UUID day = completing ? days.idFor(ctx.businessId(), n.completedAt) : null;
        jdbc.sql("""
                        UPDATE sale SET status = :st, label = :label, cash_register_id = :reg, subtotal_minor = :sub, discount_minor = :disc, total_minor = :tot,
                               completed_by_member_id = :cby, completed_at = :cat, business_day_id = :day, locked_by_device_id = NULL, locked_until = NULL,
                               locked_by_member_id = NULL, """ + sent + """
                               content_hash = :hash, updated_at = :now, rev = nextval('change_rev_seq')
                         WHERE id = :id AND business_id = :b
                        """)
                .param("st", n.status).param("label", n.label).param("reg", n.register, java.sql.Types.OTHER).param("sub", n.subtotal).param("disc", n.discount)
                .param("tot", n.total).param("cby", completing ? ctx.memberId() : null, java.sql.Types.OTHER)
                .param("cat", n.completedAt == null ? null : Timestamp.from(n.completedAt), java.sql.Types.TIMESTAMP).param("day", day, java.sql.Types.OTHER)
                .param("hash", hash).param("now", Timestamp.from(now)).param("id", id).param("b", ctx.businessId())
                .param("me", ctx.memberId(), java.sql.Types.OTHER).update();
        replaceChildren(ctx.businessId(), id, n);
        stock.reconcileSale(ctx, id);
        // «Por cobrar en caja» cambió (llegó, se cobró o volvió a ser común): los demás teléfonos la ven al instante.
        if (n.pendingCheckout || row.pending) push.requestSync(ctx.businessId());
        if (!sent.isEmpty() && sent.contains(":now")) audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.send_to_register", "sale", id, "total=" + n.total);
        if (completing) {
            credits.syncSaleCredits(ctx, id, saleCredits(n), n.completedAt);
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.complete", "sale", id, "total=" + n.total);
        }
        return new Result(view(ctx.businessId(), id), Outcome.UPDATED);
    }

    private void insertNew(MemberContext ctx, UUID id, Norm n, String hash, Instant now, UUID conflictOf) {
        UUID day = n.completedAt == null ? null : days.idFor(ctx.businessId(), n.completedAt);
        jdbc.sql("""
                        INSERT INTO sale (id, business_id, cash_register_id, device_id, business_day_id, status, label, subtotal_minor, discount_minor,
                                          total_minor, created_by_member_id, completed_by_member_id, completed_at, content_hash, created_at, updated_at, conflict_of_sale_id,
                                          sent_to_register_at, sent_by_member_id)
                        VALUES (:id, :b, :reg, :dev, :day, :st, :label, :sub, :disc, :tot, :by, :cby, :cat, :hash, :created, :now, :conflict, :sent, :sentBy)
                        """)
                .param("id", id).param("b", ctx.businessId()).param("reg", n.register, java.sql.Types.OTHER).param("dev", ctx.deviceId(), java.sql.Types.OTHER)
                .param("day", day, java.sql.Types.OTHER).param("st", n.status).param("label", n.label).param("sub", n.subtotal).param("disc", n.discount)
                .param("tot", n.total).param("by", ctx.memberId()).param("cby", "COMPLETED".equals(n.status) ? ctx.memberId() : null, java.sql.Types.OTHER)
                .param("cat", n.completedAt == null ? null : Timestamp.from(n.completedAt), java.sql.Types.TIMESTAMP)
                .param("hash", hash).param("created", Timestamp.from(n.createdAt)).param("now", Timestamp.from(now)).param("conflict", conflictOf, java.sql.Types.OTHER)
                .param("sent", n.pendingCheckout ? Timestamp.from(now) : null, java.sql.Types.TIMESTAMP).param("sentBy", n.pendingCheckout ? ctx.memberId() : null, java.sql.Types.OTHER).update();
        replaceChildren(ctx.businessId(), id, n);
        stock.reconcileSale(ctx, id);
        if (n.pendingCheckout) {
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.send_to_register", "sale", id, "total=" + n.total);
            push.requestSync(ctx.businessId());
        }
        if ("COMPLETED".equals(n.status)) {
            credits.syncSaleCredits(ctx, id, saleCredits(n), n.completedAt);
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.complete", "sale", id, "total=" + n.total);
        }
    }

    /**
     * La venta cobrada que chocó con otra versión se guarda como una venta NUEVA (id derivado de la operación: repetirla no la duplica), con sus líneas y
     * pagos con ids nuevos, marcada `conflict_of_sale_id` y con aviso al dueño. Así el dinero cobrado queda en los libros y alguien decide si era un duplicado.
     */
    private Result conflictCopy(MemberContext ctx, UUID originalId, Norm n, UUID opId, Instant now) {
        ctx.require(Permission.SELL);
        UUID copyId = UUID.nameUUIDFromBytes(("sale-conflict:" + (opId != null ? opId : originalId + ":" + hash(n))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (jdbc.sql("SELECT count(*) FROM sale WHERE id = :id AND business_id = :b").param("id", copyId).param("b", ctx.businessId()).query(Integer.class).single() > 0) {
            return new Result(view(ctx.businessId(), copyId), Outcome.CONFLICT_COPY);
        }
        java.util.function.Function<UUID, UUID> derive = old -> UUID.nameUUIDFromBytes((copyId + ":" + old).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<NItem> items = n.items.stream().map(i -> new NItem(new ItemInput(derive.apply(i.in.id()), i.in.productId(), i.in.barcode(), i.in.name(), i.in.variant(),
                i.in.unitPriceMinor(), i.in.unitCostMinor(), i.in.quantityMilli(), i.in.discountMinor()), i.name, i.price, i.qty, i.discount, i.lineTotal)).toList();
        List<NPayment> pays = n.payments.stream().map(p -> new NPayment(derive.apply(p.id), p.method, p.otherLabel, p.amount, p.tendered, p.change, p.reference,
                p.debtorLabel, p.debtorPhone, p.customerId)).toList();
        Norm copy = new Norm(n.status, n.label, n.register, n.discount, items, pays, n.subtotal, n.total, n.createdAt, n.completedAt, false, n.promotions);
        insertNew(ctx, copyId, copy, hash(copy), now, originalId);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sale.conflict_copy", "sale", copyId, "of=" + originalId + " total=" + n.total);
        String member = ctx.memberId() == null ? "" : jdbc.sql("SELECT display_name FROM member WHERE id = :m").param("m", ctx.memberId()).query(String.class).optional().orElse("");
        notifications.notify(ctx.businessId(), com.cuadra.api.notification.NotificationService.Type.SALE_CONFLICT,
                java.util.Map.of("saleId", copyId.toString(), "originalSaleId", originalId.toString(), "memberName", member, "totalMinor", n.total),
                null, null, "SALE_CONFLICT:" + copyId, "cuadra://ventas");
        return new Result(view(ctx.businessId(), copyId), Outcome.CONFLICT_COPY);
    }

    private java.util.List<CreditService.SaleCredit> saleCredits(Norm n) {
        return n.payments.stream().filter(p -> "CREDIT".equals(p.method))
                .map(p -> new CreditService.SaleCredit(p.id, p.amount, p.debtorLabel, p.debtorPhone, p.customerId)).toList();
    }

    private boolean registerCheckoutOn(UUID businessId) {
        return jdbc.sql("SELECT register_checkout FROM business WHERE id = :b").param("b", businessId).query(Boolean.class).single();
    }

    /** «La está cobrando Ana»: el rechazo dice quién la tiene abierta (si se sabe). */
    private ApiException locked(UUID member) {
        ApiException e = ApiException.conflict("SALE_LOCKED", "This ticket is open on another phone");
        if (member == null) return e;
        String name = jdbc.sql("SELECT display_name FROM member WHERE id = :m").param("m", member).query(String.class).optional().orElse(null);
        return name == null ? e : e.with("memberName", name);
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
        jdbc.sql("DELETE FROM sale_promotion WHERE sale_id = :s").param("s", saleId).update();
        int pos = 0;
        for (NPromo p : n.promotions) {
            jdbc.sql("""
                            INSERT INTO sale_promotion (sale_id, business_id, position, promotion_id, name, quantity, price_minor, units, discount_minor)
                            VALUES (:s, :b, :pos, :p, :n, :q, :price, :u, :d)""")
                    .param("s", saleId).param("b", businessId).param("pos", pos++).param("p", p.promotionId(), java.sql.Types.OTHER).param("n", p.name())
                    .param("q", p.quantity()).param("price", p.price()).param("u", p.units()).param("d", p.discount()).update();
        }
        pos = 0;
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

    public SaleView cancel(MemberContext ctx, UUID id, String reason) {
        return cancel(ctx, id, reason, null);
    }

    /**
     * Descartar una cuenta apartada (cualquiera que venda) o eliminar/anular una venta cobrada.
     * - Dueño y admins eliminan cualquier venta cobrada, con motivo.
     * - Un cajero solo puede ANULAR SU ÚLTIMA venta y dentro de los primeros {@link #UNDO_WINDOW} (ver `requireUndo`), con motivo; el dueño recibe aviso.
     * `requestedAt`: la hora del teléfono al pedirlo (por la cola, sin conexión); la anulación cuenta en ESA jornada (acotada entre el cobro y ahora).
     */
    @Transactional
    public SaleView cancel(MemberContext ctx, UUID id, String reason, Instant requestedAt) {
        var row = jdbc.sql("SELECT status, completed_at, completed_by_member_id, device_id, sent_to_register_at FROM sale WHERE id = :id AND business_id = :b FOR UPDATE").param("id", id).param("b", ctx.businessId())
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getTimestamp(5)})
                .optional().orElseThrow(() -> ApiException.notFound("SALE_NOT_FOUND", "Sale not found"));
        String status = (String) row[0];
        if ("CANCELLED".equals(status)) return view(ctx.businessId(), id);
        boolean completed = "COMPLETED".equals(status);
        // Anular una cuenta por cobrar en caja (no es una venta, pero alguien la tomó y se esperaba su dinero): con motivo, queda en la actividad.
        boolean pendingCheckout = "PARKED".equals(status) && row[4] != null;
        if (pendingCheckout && (reason == null || reason.trim().length() < 5)) {
            throw ApiException.badRequest("REASON_REQUIRED", "A reason of at least 5 characters is required to cancel a ticket sent to the register");
        }
        Instant now = clock.instant();
        Instant completedAt = row[1] == null ? null : ((Timestamp) row[1]).toInstant();
        boolean undo = false;
        if (completed) {
            // Eliminar una venta cobrada exige quien puede editarlas, o que sea la anulación de la última venta del cajero en sus primeros minutos.
            if (!ctx.role().can(Permission.EDIT_SALES)) {
                ctx.require(Permission.SELL);
                requireUndo(ctx, id, completedAt, (UUID) row[2], (UUID) row[3], requestedAt, now);
                undo = true;
            }
        } else {
            // Descartar una cuenta apartada lo hace cualquiera que venda.
            ctx.require(Permission.SELL);
        }
        // Eliminar una venta cobrada exige un MOTIVO (queda con quién y cuándo, y la venta se conserva como anulada): sin motivo no hay trazabilidad.
        if (completed && (reason == null || reason.trim().length() < 5)) {
            throw ApiException.badRequest("REASON_REQUIRED", "A reason of at least 5 characters is required to delete a paid sale");
        }
        if (completed && returns.hasReturns(id)) throw ApiException.conflict("SALE_HAS_RETURNS", "This sale has returns: return the rest instead of deleting it");
        if (completed) credits.cancelSaleCredits(ctx, id);
        // La anulación cuenta en la jornada en que se HIZO: la hora del teléfono (sin conexión) acotada entre el cobro y ahora.
        Instant at = now;
        if (completed && requestedAt != null && completedAt != null) at = requestedAt.isAfter(now) ? now : requestedAt.isBefore(completedAt) ? completedAt : requestedAt;
        jdbc.sql("""
                        UPDATE sale SET status = 'CANCELLED', cancelled_by_member_id = :m, cancelled_at = :at, cancel_reason = :r, locked_by_device_id = NULL,
                               locked_until = NULL, locked_by_member_id = NULL, updated_at = :now, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b
                        """)
                .param("m", ctx.memberId()).param("at", Timestamp.from(at)).param("now", Timestamp.from(now)).param("r", reason == null || reason.isBlank() ? null : reason.trim())
                .param("id", id).param("b", ctx.businessId()).update();
        stock.reconcileSale(ctx, id);
        if (completed) notifySaleDeleted(ctx, id, undo, reason == null ? "" : reason.trim());
        if (pendingCheckout) push.requestSync(ctx.businessId());
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), undo ? "sale.undo" : pendingCheckout ? "sale.register_cancel" : "sale.cancel", "sale", id, status + (reason == null ? "" : ": " + reason));
        return view(ctx.businessId(), id);
    }

    /**
     * "Anular mi última venta" (cajero). Se permite solo si:
     * 1. la cobró esa misma persona;
     * 2. es su venta cobrada MÁS RECIENTE (no hay otra suya cobrada después);
     * 3. no han pasado más de {@link #UNDO_WINDOW} (+ {@link #UNDO_TOLERANCE}) desde el cobro. El tiempo se mide así:
     *    - si la anulación llega por la cola desde el MISMO teléfono que cobró (`requestedAt` = hora del teléfono al pedirla), con ese reloj: cobro y
     *      anulación salen del mismo reloj, así que un reloj adelantado o atrasado no cambia la diferencia. Esa anulación puede llegar tarde (sin conexión),
     *      pero no más de {@link #UNDO_MAX_DELAY} después del cobro ni con una hora futura;
     *    - si no (otro teléfono, sin hora del teléfono), con la hora del servidor al recibirla.
     * Pasado eso, solo dueño y admins eliminan (flujo de siempre).
     */
    private void requireUndo(MemberContext ctx, UUID id, Instant completedAt, UUID completedBy, UUID saleDevice, Instant requestedAt, Instant now) {
        if (completedAt == null || ctx.memberId() == null || !ctx.memberId().equals(completedBy)) throw undoRefused("NOT_OWN");
        UUID latest = jdbc.sql("""
                        SELECT id FROM sale WHERE business_id = :b AND completed_by_member_id = :m AND status = 'COMPLETED' ORDER BY completed_at DESC, created_at DESC, id DESC LIMIT 1
                        """).param("b", ctx.businessId()).param("m", ctx.memberId()).query(UUID.class).optional().orElse(null);
        if (!id.equals(latest)) throw undoRefused("NOT_LATEST");
        boolean samePhoneClock = requestedAt != null && ctx.deviceId() != null && ctx.deviceId().equals(saleDevice);
        Instant ref;
        if (samePhoneClock) {
            if (requestedAt.isAfter(now.plus(CLOCK_SKEW)) || now.isAfter(completedAt.plus(UNDO_MAX_DELAY))) throw undoRefused("TOO_LATE");
            ref = requestedAt;
        } else {
            ref = now;
        }
        Duration elapsed = Duration.between(completedAt, ref);
        if (elapsed.compareTo(UNDO_WINDOW.plus(UNDO_TOLERANCE)) > 0) throw undoRefused("TOO_LATE");
    }

    private static ApiException undoRefused(String why) {
        return ApiException.forbidden("UNDO_NOT_ALLOWED", "Only your last sale, within " + UNDO_WINDOW.toMinutes() + " minutes; after that ask the owner or an admin")
                .with("reason", why).with("windowMinutes", UNDO_WINDOW.toMinutes());
    }

    /** Eliminar una venta ya cobrada es de las cosas que el dueño quiere saber; si la borró él mismo, no hace falta avisarle. */
    private void notifySaleDeleted(MemberContext ctx, UUID saleId, boolean undo, String reason) {
        long total = jdbc.sql("SELECT total_minor FROM sale WHERE id = :id").param("id", saleId).query(Long.class).single();
        String member = jdbc.sql("SELECT display_name FROM member WHERE id = :m").param("m", ctx.memberId()).query(String.class).single();
        var type = undo ? com.cuadra.api.notification.NotificationService.Type.SALE_UNDONE : com.cuadra.api.notification.NotificationService.Type.SALE_DELETED;
        notifications.notify(ctx.businessId(), type, java.util.Map.of("saleId", saleId.toString(), "memberName", member, "totalMinor", total, "reason", reason),
                null, ctx.memberId(), type.name() + ":" + saleId, "cuadra://notificaciones");
    }

    @Transactional
    public SaleView lock(MemberContext ctx, UUID id) {
        ctx.require(Permission.SELL);
        if (ctx.deviceId() == null) throw ApiException.badRequest("DEVICE_REQUIRED", "Only a linked phone can hold a ticket");
        Instant now = clock.instant();
        var row = jdbc.sql("SELECT status, locked_by_device_id, locked_until, locked_by_member_id FROM sale WHERE id = :id AND business_id = :b FOR UPDATE")
                .param("id", id).param("b", ctx.businessId())
                .query((rs, i) -> new Object[] {rs.getString("status"), rs.getObject("locked_by_device_id", UUID.class), rs.getTimestamp("locked_until"),
                        rs.getObject("locked_by_member_id", UUID.class)})
                .optional().orElseThrow(() -> ApiException.notFound("SALE_NOT_FOUND", "Sale not found"));
        if (!"PARKED".equals(row[0])) throw ApiException.conflict("SALE_NOT_PARKED", "Only parked tickets can be resumed");
        UUID holder = (UUID) row[1];
        Timestamp until = (Timestamp) row[2];
        if (holder != null && !holder.equals(ctx.deviceId()) && until != null && until.toInstant().isAfter(now)) {
            throw locked((UUID) row[3]);
        }
        jdbc.sql("UPDATE sale SET locked_by_device_id = :d, locked_until = :u, locked_by_member_id = :m WHERE id = :id").param("d", ctx.deviceId())
                .param("u", Timestamp.from(now.plus(LOCK_TTL))).param("m", ctx.memberId(), java.sql.Types.OTHER).param("id", id).update();
        return view(ctx.businessId(), id);
    }

    @Transactional
    public void unlock(MemberContext ctx, UUID id) {
        jdbc.sql("UPDATE sale SET locked_by_device_id = NULL, locked_until = NULL, locked_by_member_id = NULL WHERE id = :id AND business_id = :b AND locked_by_device_id = :d")
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
                         WHERE s.business_id = :b AND """ + " " + com.cuadra.api.report.ReportService.KEPT_SALE + " AND s.completed_at >= :s AND s.completed_at < :e" + mine + " GROUP BY p.method")
                .param("b", ctx.businessId()).param("s", start).param("e", end);
        if (own) byMethodQ = byMethodQ.param("me", ctx.memberId());
        Map<String, Long> byMethod = new LinkedHashMap<>();
        for (String m : List.of("CASH", "TRANSFER", "CARD", "CREDIT", "OTHER")) byMethod.put(m, 0L);
        byMethodQ.query((rs, i) -> {
            byMethod.put(rs.getString("method"), rs.getLong("total"));
            return null;
        }).list();
        var totalsQ = jdbc.sql("""
                        SELECT count(*) AS n, coalesce(sum(s.total_minor), 0) AS total
                          FROM sale s WHERE s.business_id = :b AND """ + " " + com.cuadra.api.report.ReportService.KEPT_SALE + " AND s.completed_at >= :s AND s.completed_at < :e" + mine)
                .param("b", ctx.businessId()).param("s", start).param("e", end);
        if (own) totalsQ = totalsQ.param("me", ctx.memberId());
        long[] totals = totalsQ.query((rs, i) -> new long[] {rs.getLong("n"), rs.getLong("total")}).single();
        var cancelQ = jdbc.sql("SELECT count(*) FROM sale s WHERE s.business_id = :b AND s.status = 'CANCELLED' AND s.completed_at IS NOT NULL AND s.cancelled_at >= :s AND s.cancelled_at < :e"
                        + (own ? " AND s.cancelled_by_member_id = :me" : ""))
                .param("b", ctx.businessId()).param("s", start).param("e", end);
        if (own) cancelQ = cancelQ.param("me", ctx.memberId());
        return new Summary(day, totals[0], totals[1], byMethod, cancelQ.query(Long.class).single());
    }

    private List<SaleView> load(UUID businessId, String clause, Map<String, Object> params, String tail, int limit) {
        var q = jdbc.sql("""
                        SELECT s.*, mc.display_name AS created_name, mk.display_name AS completed_name, me.display_name AS edited_name,
                               mx.display_name AS cancelled_name, ms.display_name AS sent_name, ml.display_name AS locked_name
                          FROM sale s
                          JOIN member mc ON mc.id = s.created_by_member_id
                          LEFT JOIN member mk ON mk.id = s.completed_by_member_id
                          LEFT JOIN member me ON me.id = s.edited_by_member_id
                          LEFT JOIN member mx ON mx.id = s.cancelled_by_member_id
                          LEFT JOIN member ms ON ms.id = s.sent_by_member_id
                          LEFT JOIN member ml ON ml.id = s.locked_by_member_id
                         WHERE s.business_id = :b""" + " AND " + clause + tail).param("b", businessId);
        for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());
        List<SaleView> heads = q.query((rs, i) -> new SaleView(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("label"),
                rs.getObject("cash_register_id", UUID.class), rs.getObject("device_id", UUID.class), rs.getObject("business_day_id", UUID.class),
                rs.getLong("subtotal_minor"), rs.getLong("discount_minor"), rs.getLong("total_minor"),
                ref(rs, "created_by_member_id", "created_name"), ref(rs, "completed_by_member_id", "completed_name"), instant(rs, "completed_at"),
                ref(rs, "edited_by_member_id", "edited_name"), instant(rs, "edited_at"), ref(rs, "cancelled_by_member_id", "cancelled_name"),
                instant(rs, "cancelled_at"), rs.getString("cancel_reason"), rs.getObject("locked_by_device_id", UUID.class), instant(rs, "locked_until"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("rev"), List.of(), List.of(), rs.getObject("conflict_of_sale_id", UUID.class),
                rs.getString("review_flag"), 0, List.of(), instant(rs, "sent_to_register_at"), ref(rs, "sent_by_member_id", "sent_name"),
                activeLock(rs) ? ref(rs, "locked_by_member_id", "locked_name") : null, false, List.of(), 0)).list();
        if (heads.isEmpty()) return heads;

        List<UUID> ids = heads.stream().map(SaleView::id).toList();
        Map<UUID, List<ReturnService.ReturnView>> saleReturns = returns.forSales(businessId, ids);
        Map<UUID, Long> returnedByItem = new java.util.HashMap<>();
        saleReturns.values().forEach(list -> list.forEach(r -> r.items().forEach(i -> returnedByItem.merge(i.saleItemId(), i.quantityMilli(), Long::sum))));
        Map<UUID, List<ItemView>> items = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM sale_item WHERE sale_id IN (:ids) ORDER BY sale_id, position").param("ids", ids).query((rs, i) -> {
            long qty = rs.getLong("quantity_milli");
            long price = rs.getLong("unit_price_minor");
            long disc = rs.getLong("discount_minor");
            items.computeIfAbsent(rs.getObject("sale_id", UUID.class), k -> new ArrayList<>()).add(new ItemView(rs.getObject("id", UUID.class),
                    rs.getObject("product_id", UUID.class), rs.getString("barcode"), rs.getString("name"), rs.getString("variant"), price,
                    (Long) rs.getObject("unit_cost_minor"), qty, disc, SaleMath.lineTotal(price, qty, disc), returnedByItem.getOrDefault(rs.getObject("id", UUID.class), 0L)));
            return null;
        }).list();
        Map<UUID, List<SalePromotionView>> promos = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM sale_promotion WHERE sale_id IN (:ids) ORDER BY sale_id, position").param("ids", ids).query((rs, i) -> {
            promos.computeIfAbsent(rs.getObject("sale_id", UUID.class), k -> new ArrayList<>()).add(new SalePromotionView(rs.getObject("promotion_id", UUID.class),
                    rs.getString("name"), rs.getInt("quantity"), rs.getLong("price_minor"), rs.getInt("units"), rs.getLong("discount_minor")));
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
                items.getOrDefault(h.id(), List.of()), pays.getOrDefault(h.id(), List.of()), h.conflictOfSaleId(), h.reviewFlag(),
                saleReturns.getOrDefault(h.id(), List.of()).stream().mapToLong(ReturnService.ReturnView::totalMinor).sum(), saleReturns.getOrDefault(h.id(), List.of()),
                h.sentToRegisterAt(), h.sentBy(), h.lockedBy(), "PARKED".equals(h.status()) && h.sentToRegisterAt() != null,
                promos.getOrDefault(h.id(), List.of()), promos.getOrDefault(h.id(), List.of()).stream().mapToLong(SalePromotionView::discountMinor).sum())).collect(Collectors.toList());
    }

    private boolean activeLock(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp until = rs.getTimestamp("locked_until");
        return until != null && until.toInstant().isAfter(clock.instant());
    }

    /** «Por cobrar en caja» (ADR 0015): las cuentas enviadas a caja que nadie ha cobrado ni anulado, de la más vieja a la más nueva. Cualquier rol las ve. */
    public List<SaleView> registerQueue(MemberContext ctx) {
        return load(ctx.businessId(), "s.status = 'PARKED' AND s.sent_to_register_at IS NOT NULL", Map.of(), " ORDER BY s.sent_to_register_at, s.id LIMIT 200", 200);
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
