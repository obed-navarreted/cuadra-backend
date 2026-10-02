package com.cuadra.api.sync;

import com.cuadra.api.business.BusinessService;
import com.cuadra.api.catalog.CategoryService;
import com.cuadra.api.catalog.ProductService;
import com.cuadra.api.cash.CashMovementService;
import com.cuadra.api.cash.ExpenseService;
import com.cuadra.api.cash.ShiftService;
import com.cuadra.api.common.ApiException;
import com.cuadra.api.credit.CreditService;
import com.cuadra.api.credit.CustomerService;
import com.cuadra.api.credit.TemplateService;
import com.cuadra.api.member.MemberService;
import com.cuadra.api.notification.NotificationService;
import com.cuadra.api.sale.SaleService;
import com.cuadra.api.stock.PurchaseService;
import com.cuadra.api.stock.StockService;
import com.cuadra.api.stock.SupplierService;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sincronización de los teléfonos (PLAN.md sección 14).
 * push: lote de operaciones idempotentes, cada una en su propia transacción y con su recibo.
 * pull: cambios del negocio con `rev` mayor que el cursor del teléfono.
 */
@Service
public class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    public static final int MAX_OPS = 100;
    /** Rechazos que dependen del momento y pueden dejar de ocurrir sin cambiar la operación. */
    private static final java.util.Set<String> TRANSIENT_CODES = java.util.Set.of("SALE_LOCKED", "DEVICES_PENDING", "PIN_VERIFICATION_REQUIRED");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper mapper;
    private final Clock clock;
    private final ProductService products;
    private final CategoryService categories;
    private final SaleService sales;
    private final MemberService members;
    private final BusinessService businesses;
    private final CustomerService customers;
    private final CreditService credits;
    private final TemplateService templates;
    private final ExpenseService expenses;
    private final CashMovementService movements;
    private final ShiftService shifts;
    private final StockService stock;
    private final SupplierService suppliers;
    private final PurchaseService purchases;
    private final NotificationService notifications;
    private final com.cuadra.api.tenancy.Access access;
    private final com.cuadra.api.sale.ReturnService returns;
    private final com.cuadra.api.common.Audit audit;
    private final com.cuadra.api.catalog.PromotionService promotions;

    public SyncService(com.cuadra.api.catalog.PromotionService promotions, com.cuadra.api.tenancy.Access access, JdbcClient jdbc, PlatformTransactionManager tm, JsonMapper mapper, Clock clock, ProductService products,
                       CategoryService categories, SaleService sales, MemberService members, BusinessService businesses, CustomerService customers,
                       CreditService credits, TemplateService templates, ExpenseService expenses, CashMovementService movements, ShiftService shifts,
                       StockService stock, SupplierService suppliers, PurchaseService purchases, NotificationService notifications,
                       com.cuadra.api.sale.ReturnService returns, com.cuadra.api.common.Audit audit) {
        this.promotions = promotions;
        this.access = access;
        this.returns = returns;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.mapper = mapper;
        this.clock = clock;
        this.products = products;
        this.categories = categories;
        this.sales = sales;
        this.members = members;
        this.businesses = businesses;
        this.customers = customers;
        this.credits = credits;
        this.templates = templates;
        this.expenses = expenses;
        this.movements = movements;
        this.shifts = shifts;
        this.stock = stock;
        this.suppliers = suppliers;
        this.purchases = purchases;
        this.notifications = notifications;
    }

    /**
     * `memberId`: quién hizo la operación (el teléfono lo guarda al hacerla; sin él se usa la persona de la cabecera). `createdAt`: la hora del teléfono al
     * hacerla; decide si alguien dado de baja la hizo antes de la baja.
     */
    public record OpInput(UUID opId, String kind, UUID entityId, JsonNode payload, UUID memberId, java.time.Instant createdAt) {
        public OpInput(UUID opId, String kind, UUID entityId, JsonNode payload) { this(opId, kind, entityId, payload, null, null); }
    }

    /**
     * status: APPLIED | DUPLICATE (ya recibida) | STALE (versión vieja ignorada) | REJECTED (con `code`; el teléfono la muestra).
     * Un APPLIED puede traer `code` como aviso (p. ej. SALE_CONFLICT_COPY: se guardó como venta aparte para revisar). `detail`: datos para explicar un rechazo.
     */
    public record OpResult(UUID opId, String status, String code, Long rev, Map<String, Object> detail) {
        public OpResult(UUID opId, String status, String code, Long rev) { this(opId, status, code, rev, null); }
    }

    public record Change(String type, long rev, Object data) {}

    public record PullResult(List<Change> changes, long cursor, boolean hasMore) {}

    // ---------- push ----------

    public List<OpResult> push(com.cuadra.api.tenancy.Access.Pusher pusher, List<OpInput> ops, Integer pendingOps) {
        MemberContext ctx = pusher.ctx();
        if (ops == null || ops.isEmpty()) {
            touchDevice(ctx, pendingOps);
            return List.of();
        }
        if (ops.size() > MAX_OPS) throw ApiException.badRequest("TOO_MANY_OPS", "At most " + MAX_OPS + " operations per batch");
        List<OpResult> results = new ArrayList<>();
        Map<UUID, Object[]> members = new java.util.HashMap<>();
        Map<UUID, long[]> late = new LinkedHashMap<>();
        for (OpInput op : ops) results.add(pushOne(pusher, ctx, op, members, late));
        touchDevice(ctx, finalPending(pendingOps, results));
        notifyLate(ctx.businessId(), late, ops.get(0).opId());
        return results;
    }

    /**
     * Lo pendiente que informó el teléfono cuenta también las operaciones de ESTE envío: se descuentan las que quedaron resueltas (aplicadas o rechazadas
     * para siempre), para que el cierre del día no avise de pendientes que ya llegaron.
     */
    private static Integer finalPending(Integer reported, List<OpResult> results) {
        if (reported == null) return null;
        long settled = results.stream().filter(r -> r.code() == null || (!TRANSIENT_CODES.contains(r.code()) && !"INTERNAL_ERROR".equals(r.code()))).count();
        return (int) Math.max(0, reported - settled);
    }

    /** Un aviso al dueño por persona y envío: cuántas operaciones de alguien dado de baja llegaron después de su baja, y por cuánto. */
    private void notifyLate(UUID businessId, Map<UUID, long[]> late, UUID batchId) {
        late.forEach((member, v) -> {
            String name = jdbc.sql("SELECT display_name FROM member WHERE id = :m").param("m", member).query(String.class).optional().orElse("");
            notifications.notify(businessId, NotificationService.Type.LATE_AFTER_DISABLE, Map.of("memberId", member.toString(), "memberName", name, "count", v[0], "amountMinor", v[1]),
                    null, null, "LATE_AFTER_DISABLE:" + member + ":" + batchId, "cuadra://ventas");
        });
    }

    private OpResult pushOne(com.cuadra.api.tenancy.Access.Pusher pusher, MemberContext sender, OpInput original, Map<UUID, Object[]> members, Map<UUID, long[]> late) {
        OpInput op = original;
        if (op.opId() == null || op.kind() == null) return new OpResult(op.opId(), "REJECTED", "INVALID_OP", null);
        OpResult seen = receipt(sender, op.opId());
        if (seen != null) return seen;
        try {
            // Cada operación se aplica con la persona que la HIZO (no con la que está activa al enviarla): así una venta de Ana enviada cuando ya atiende
            // Beto queda a nombre de Ana, y un retiro del dueño no llega con el rol de un cajero.
            MemberContext acting = access.actingFor(pusher, op.memberId(), op.createdAt(), members);
            boolean afterDisable = access.isDisabled(pusher, op.memberId(), members);
            boolean[] clockAdjusted = {false};
            final OpInput toApply = clampClock(pusher, acting, op, clockAdjusted);
            OpResult applied = tx.execute(status -> {
                OpResult r = apply(acting, toApply);
                insertReceipt(acting, toApply, r);
                if ("APPLIED".equals(r.status()) && (afterDisable || clockAdjusted[0])) {
                    long amount = flag(acting, toApply, afterDisable ? "LATE_AFTER_DISABLE" : "CLOCK_ADJUSTED");
                    if (afterDisable) late.computeIfAbsent(acting.memberId(), k -> new long[2])[0]++;
                    if (afterDisable) late.get(acting.memberId())[1] += amount;
                }
                return r;
            });
            return applied;
        } catch (DuplicateKeyException e) {
            // Dos envíos simultáneos de la misma operación: el segundo pierde contra el recibo del primero.
            OpResult again = receipt(sender, op.opId());
            if (again != null) return again;
            // Sin recibo visible: el id de la operación o el de la entidad ya lo usa OTRO negocio (invisible por el aislamiento).
            boolean receiptKey = com.cuadra.api.common.Constraints.name(e).map("operation_receipt_pkey"::equals).orElse(false);
            boolean primaryKey = com.cuadra.api.common.Constraints.isPrimaryKey(e);
            return new OpResult(op.opId(), "REJECTED", receiptKey ? "OP_ID_TAKEN" : primaryKey ? "ID_TAKEN" : "CONFLICT", null);
        } catch (ApiException e) {
            OpResult rejected = new OpResult(op.opId(), "REJECTED", e.code(), null, e.details().isEmpty() ? null : Map.copyOf(withoutNulls(e.details())));
            // Un rechazo pasajero (p. ej. la cuenta está abierta en otro teléfono) NO se recuerda: al repetir la misma operación debe
            // volver a evaluarse, no devolver para siempre el veredicto viejo.
            if (!TRANSIENT_CODES.contains(e.code())) tx.executeWithoutResult(s -> insertReceipt(sender, op, rejected));
            return rejected;
        } catch (JacksonException e) {
            OpResult rejected = new OpResult(op.opId(), "REJECTED", "MALFORMED_PAYLOAD", null);
            tx.executeWithoutResult(s -> insertReceipt(sender, op, rejected));
            return rejected;
        } catch (RuntimeException e) {
            // Error nuestro (no del teléfono): no se guarda recibo para que el reintento pueda tener éxito.
            log.error("Operación {} ({}) falló", op.opId(), op.kind(), e);
            return new OpResult(op.opId(), "REJECTED", "INTERNAL_ERROR", null);
        }
    }

    private OpResult apply(MemberContext ctx, OpInput op) {
        UUID id = op.entityId();
        if (id == null) throw ApiException.badRequest("INVALID_OP", "entityId is required");
        return switch (op.kind()) {
            case "PRODUCT_UPSERT" -> {
                var r = products.upsert(ctx, id, mapper.treeToValue(op.payload(), ProductService.ProductInput.class));
                yield new OpResult(op.opId(), "APPLIED", null, r.product().rev());
            }
            case "PRODUCT_PATCH" -> {
                // Solo los campos que cambió el teléfono (`set`): un teléfono con datos viejos no revierte el precio o el costo que otro cambió.
                var r = products.patch(ctx, id, op.payload());
                yield new OpResult(op.opId(), "APPLIED", null, r.product().rev());
            }
            case "PROMOTION_UPSERT" -> {
                var r = promotions.upsert(ctx, id, mapper.treeToValue(op.payload(), com.cuadra.api.catalog.PromotionService.PromotionInput.class));
                yield new OpResult(op.opId(), "APPLIED", null, r.promotion().rev());
            }
            case "PROMOTION_DELETE" -> {
                promotions.delete(ctx, id);
                yield new OpResult(op.opId(), "APPLIED", null, null);
            }
            case "CATEGORY_UPSERT" -> {
                var r = categories.upsert(ctx, id, mapper.treeToValue(op.payload(), CategoryService.CategoryInput.class));
                yield new OpResult(op.opId(), "APPLIED", null, r.rev());
            }
            case "SALE_UPSERT" -> {
                var r = sales.upsert(ctx, id, mapper.treeToValue(op.payload(), SaleService.SaleInput.class), op.opId());
                // Se guardó como venta aparte (chocó con otra versión cobrada o descartada): aplicada, con un aviso para que el teléfono la muestre en
                // «Requiere atención» con el id de la copia.
                if (r.outcome() == SaleService.Outcome.CONFLICT_COPY) {
                    yield new OpResult(op.opId(), "APPLIED", "SALE_CONFLICT_COPY", r.sale().rev(), Map.of("copySaleId", r.sale().id().toString()));
                }
                yield new OpResult(op.opId(), r.outcome() == SaleService.Outcome.STALE ? "STALE" : "APPLIED", null, r.sale().rev());
            }
            case "SALE_CANCEL" -> {
                String reason = op.payload() != null && op.payload().hasNonNull("reason") ? op.payload().get("reason").asString() : null;
                var v = sales.cancel(ctx, id, reason, op.createdAt());
                yield new OpResult(op.opId(), "APPLIED", null, v.rev());
            }
            case "SALE_RETURN" -> {
                var in = mapper.treeToValue(op.payload(), com.cuadra.api.sale.ReturnService.ReturnInput.class);
                // Sin hora propia en la devolución, la de la operación (la hora del teléfono al hacerla): cuenta en la jornada en que se hizo.
                if (in.occurredAt() == null && op.createdAt() != null) in = new com.cuadra.api.sale.ReturnService.ReturnInput(in.saleId(), in.items(), in.reason(), in.refundMethod(), op.createdAt());
                var r = returns.create(ctx, id, in);
                yield new OpResult(op.opId(), "APPLIED", null, sales.view(ctx.businessId(), r.ret().saleId()).rev());
            }
            case "CUSTOMER_UPSERT" -> {
                var r = customers.upsert(ctx, id, mapper.treeToValue(op.payload(), CustomerService.CustomerInput.class));
                yield new OpResult(op.opId(), "APPLIED", null, r.customer().rev());
            }
            case "CREDIT_UPSERT" -> {
                var r = credits.upsertManual(ctx, id, mapper.treeToValue(op.payload(), CreditService.ManualCreditInput.class));
                yield new OpResult(op.opId(), "APPLIED", null, r.credit().rev());
            }
            case "CREDIT_PAYMENT" -> {
                var r = credits.pay(ctx, id, mapper.treeToValue(op.payload(), CreditService.PayInput.class));
                yield new OpResult(op.opId(), "APPLIED", null, r.payments().stream().mapToLong(CreditService.PaymentView::rev).max().orElse(0));
            }
            case "CREDIT_PAYMENT_VOID" -> {
                var r = credits.voidPayment(ctx, id, reasonOf(op));
                yield new OpResult(op.opId(), "APPLIED", null, r.payments().stream().mapToLong(CreditService.PaymentView::rev).max().orElse(0));
            }
            case "CREDIT_LINK" -> {
                var v = credits.linkCustomer(ctx, id, UUID.fromString(op.payload().get("customerId").asString()));
                yield new OpResult(op.opId(), "APPLIED", null, v.rev());
            }
            case "CREDIT_WRITE_OFF" -> {
                var v = credits.writeOff(ctx, id, reasonOf(op));
                yield new OpResult(op.opId(), "APPLIED", null, v.rev());
            }
            case "CREDIT_EVENT" -> {
                credits.recordEvent(ctx, mapper.treeToValue(op.payload(), CreditService.EventInput.class));
                yield new OpResult(op.opId(), "APPLIED", null, null);
            }
            case "TEMPLATE_UPSERT" -> {
                var t = templates.upsert(ctx, op.payload().get("kind").asString(), op.payload().get("locale").asString(), op.payload().get("body").asString());
                yield new OpResult(op.opId(), "APPLIED", null, t.rev());
            }
            case "EXPENSE_UPSERT" -> {
                var r = expenses.upsert(ctx, id, mapper.treeToValue(op.payload(), ExpenseService.ExpenseInput.class));
                yield new OpResult(op.opId(), "APPLIED", null, r.expense().rev());
            }
            case "EXPENSE_VOID" -> new OpResult(op.opId(), "APPLIED", null, expenses.voidExpense(ctx, id, reasonOf(op)).rev());
            case "EXPENSE_CATEGORY_UPSERT" -> new OpResult(op.opId(), "APPLIED", null, expenses.upsertCategory(ctx, id, mapper.treeToValue(op.payload(), ExpenseService.CategoryInput.class)).rev());
            case "CASH_MOVEMENT_UPSERT" -> {
                var r = movements.upsert(ctx, id, mapper.treeToValue(op.payload(), CashMovementService.MovementInput.class));
                yield new OpResult(op.opId(), "APPLIED", null, r.movement().rev());
            }
            case "CASH_MOVEMENT_VOID" -> new OpResult(op.opId(), "APPLIED", null, movements.voidMovement(ctx, id, reasonOf(op)).rev());
            case "SHIFT_OPEN" -> {
                var r = shifts.open(ctx, id, mapper.treeToValue(op.payload(), ShiftService.OpenInput.class));
                // Otro teléfono ya había abierto esa caja: el turno de este se descarta (STALE) y el suyo llegará en la descarga.
                yield new OpResult(op.opId(), r.outcome() == ShiftService.Outcome.EXISTING ? "STALE" : "APPLIED", null, r.shift().rev());
            }
            case "SHIFT_CLOSE" -> new OpResult(op.opId(), "APPLIED", null, shifts.close(ctx, id, mapper.treeToValue(op.payload(), ShiftService.CloseInput.class)).shift().rev());
            case "SHIFT_REOPEN" -> new OpResult(op.opId(), "APPLIED", null, shifts.reopen(ctx, id, reasonOf(op)).rev());
            case "NOTIFICATION_READ" -> new OpResult(op.opId(), "APPLIED", null, notifications.markRead(ctx, id).rev());
            case "STOCK_MOVEMENT_ADD" -> new OpResult(op.opId(), "APPLIED", null, stock.add(ctx, id, mapper.treeToValue(op.payload(), StockService.MovementInput.class)).movement().rev());
            case "SUPPLIER_UPSERT" -> new OpResult(op.opId(), "APPLIED", null, suppliers.upsert(ctx, id, mapper.treeToValue(op.payload(), SupplierService.SupplierInput.class)).rev());
            case "PURCHASE_REGISTER" -> new OpResult(op.opId(), "APPLIED", null, purchases.register(ctx, id, mapper.treeToValue(op.payload(), PurchaseService.PurchaseInput.class)).purchase().rev());
            case "PURCHASE_VOID" -> new OpResult(op.opId(), "APPLIED", null, purchases.voidPurchase(ctx, id, reasonOf(op)).rev());
            case "SUPPLIER_PAYMENT" -> new OpResult(op.opId(), "APPLIED", null, purchases.pay(ctx, id, mapper.treeToValue(op.payload(), PurchaseService.PaymentInput.class)).payment().rev());
            case "SUPPLIER_PAYMENT_VOID" -> new OpResult(op.opId(), "APPLIED", null, purchases.voidPayment(ctx, id, reasonOf(op)).rev());
            default -> throw ApiException.badRequest("UNKNOWN_KIND", "Unknown operation kind " + op.kind());
        };
    }

    // ---------- relojes imposibles y operaciones tardías ----------

    /** Operaciones que CREAN algo con hora propia: la tabla donde vive, para saber si ya existe (entonces su hora ya es la del servidor y no se toca). */
    private static final Map<String, String> CREATES = Map.of("SALE_UPSERT", "sale", "EXPENSE_UPSERT", "expense", "CASH_MOVEMENT_UPSERT", "cash_movement",
            "CREDIT_UPSERT", "credit", "CREDIT_PAYMENT", "credit_payment", "STOCK_MOVEMENT_ADD", "stock_movement", "SALE_RETURN", "sale_return",
            "PURCHASE_REGISTER", "purchase", "SUPPLIER_PAYMENT", "supplier_payment");
    private static final List<String> TIME_FIELDS = List.of("createdAt", "completedAt", "occurredAt");

    /**
     * Un teléfono con la hora imposible (antes de que se vinculara —p. ej. reiniciado al año 2000— o más de unos minutos en el futuro) mandaría sus ventas
     * a otro día. Lo nuevo que llega con esa hora se registra con la hora del SERVIDOR al recibirlo y queda marcado (CLOCK_ADJUSTED) para revisar.
     */
    private OpInput clampClock(com.cuadra.api.tenancy.Access.Pusher pusher, MemberContext ctx, OpInput op, boolean[] adjusted) {
        String table = CREATES.get(op.kind());
        if (table == null || pusher.deviceLinkedAt() == null || op.entityId() == null || op.payload() == null || !op.payload().isObject()) return op;
        java.time.Instant now = clock.instant();
        java.time.Instant floor = pusher.deviceLinkedAt().minus(com.cuadra.api.tenancy.Access.CLOCK_SKEW);
        java.time.Instant ceil = now.plus(com.cuadra.api.tenancy.Access.CLOCK_SKEW);
        tools.jackson.databind.node.ObjectNode copy = null;
        for (String f : TIME_FIELDS) {
            if (!op.payload().hasNonNull(f)) continue;
            java.time.Instant t;
            try {
                t = java.time.Instant.parse(op.payload().get(f).asString());
            } catch (RuntimeException e) {
                continue;
            }
            if (!t.isBefore(floor) && !t.isAfter(ceil)) continue;
            if (copy == null) {
                String exists = "credit_payment".equals(table) ? "SELECT count(*) FROM credit_payment WHERE (id = :id OR group_id = :id) AND business_id = :b"
                        : "SELECT count(*) FROM " + table + " WHERE id = :id AND business_id = :b";
                if (jdbc.sql(exists).param("id", op.entityId()).param("b", ctx.businessId()).query(Integer.class).single() > 0) return op;
                copy = ((tools.jackson.databind.node.ObjectNode) op.payload()).deepCopy();
            }
            copy.put(f, now.toString());
        }
        if (copy == null) return op;
        adjusted[0] = true;
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sync.clock_adjusted", op.kind(), op.entityId(), "createdAt=" + op.createdAt());
        return new OpInput(op.opId(), op.kind(), op.entityId(), copy, op.memberId(), op.createdAt());
    }

    /** Marca lo aplicado para revisar (la venta lleva la etiqueta) y devuelve su monto, para el aviso al dueño. */
    private long flag(MemberContext ctx, OpInput op, String flag) {
        long amount = 0;
        if ("SALE_UPSERT".equals(op.kind())) {
            UUID saleId = op.entityId();
            jdbc.sql("UPDATE sale SET review_flag = :f, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b AND status = 'COMPLETED' AND (review_flag IS NULL OR :f = 'LATE_AFTER_DISABLE')")
                    .param("f", flag).param("id", saleId).param("b", ctx.businessId()).update();
            amount = jdbc.sql("SELECT total_minor FROM sale WHERE id = :id AND business_id = :b AND status = 'COMPLETED'").param("id", saleId).param("b", ctx.businessId())
                    .query(Long.class).optional().orElse(0L);
        } else if ("SALE_RETURN".equals(op.kind())) {
            amount = jdbc.sql("SELECT total_minor FROM sale_return WHERE id = :id").param("id", op.entityId()).query(Long.class).optional().orElse(0L);
        } else if (op.payload() != null && op.payload().hasNonNull("amountMinor")) {
            amount = op.payload().get("amountMinor").asLong();
        }
        if ("LATE_AFTER_DISABLE".equals(flag)) {
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "sync.late_after_disable", op.kind(), op.entityId(),
                    "llegó después de la baja; hecha " + op.createdAt() + " amount=" + amount);
        }
        return amount;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> { if (v != null) out.put(k, v); });
        return out;
    }

    private static String reasonOf(OpInput op) {
        return op.payload() != null && op.payload().hasNonNull("reason") ? op.payload().get("reason").asString() : null;
    }

    private OpResult receipt(MemberContext ctx, UUID opId) {
        return jdbc.sql("SELECT status, code, rev, business_id FROM operation_receipt WHERE op_id = :id").param("id", opId)
                .query((rs, n) -> {
                    if (!ctx.businessId().equals(rs.getObject("business_id", UUID.class))) {
                        return new OpResult(opId, "REJECTED", "OP_ID_TAKEN", null);
                    }
                    return new OpResult(opId, "DUPLICATE", rs.getString("code"), (Long) rs.getObject("rev"));
                }).optional().orElse(null);
    }

    private void insertReceipt(MemberContext ctx, OpInput op, OpResult r) {
        jdbc.sql("""
                        INSERT INTO operation_receipt (op_id, business_id, device_id, kind, entity_id, status, code, rev)
                        VALUES (:id, :b, :d, :k, :e, :s, :c, :r)
                        """)
                .param("id", op.opId()).param("b", ctx.businessId()).param("d", ctx.deviceId(), java.sql.Types.OTHER).param("k", op.kind())
                .param("e", op.entityId(), java.sql.Types.OTHER).param("s", r.status()).param("c", r.code()).param("r", r.rev(), java.sql.Types.BIGINT).update();
    }

    // ---------- pull ----------

    /**
     * Cambios con `rev` mayor que `since`, en orden. Un cajero recibe las cuentas apartadas del negocio y sus propias ventas;
     * una cuenta abierta (OPEN) solo la ve quien la abrió. El teléfono guarda cada página y su cursor en una sola transacción.
     * Nota: `rev` sale de una secuencia; una transacción larga podría confirmar tarde con un `rev` ya superado. El teléfono
     * vuelve a pedir desde su cursor en cada ciclo, y esa ventana se cerrará con el horizonte de xmin en la fase 9.
     */
    public PullResult pull(MemberContext ctx, boolean device, long since, int limit) {
        return pull(ctx, device, since, limit, null);
    }

    /** `pendingOps`: lo que el teléfono aún tiene sin enviar al bajar (ya después de subir): el cierre del día avisa con este número. */
    public PullResult pull(MemberContext ctx, boolean device, long since, int limit, Integer pendingOps) {
        limit = Math.max(1, Math.min(limit, 500));
        boolean cashier = ctx.role() == Role.CASHIER;
        String saleFilter = cashier
                ? "(s.status = 'PARKED' OR s.created_by_member_id = :me OR s.completed_by_member_id = :me)"
                : "(s.status <> 'OPEN' OR s.created_by_member_id = :me)";
        var rows = jdbc.sql("""
                        SELECT rev, t, id FROM (
                          SELECT rev, 'business' AS t, id FROM business WHERE id = :b AND rev > :s
                          UNION ALL SELECT rev, 'category', id FROM category WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'product', id FROM product WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'promotion', id FROM promotion WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'member', id FROM member WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'cash_register', id FROM cash_register WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'customer', id FROM customer WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'credit', id FROM credit WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'credit_payment', id FROM credit_payment WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'message_template', id FROM message_template WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'expense_category', id FROM expense_category WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'shift', id FROM shift WHERE business_id = :b AND rev > :s
                          UNION ALL SELECT rev, 'expense', id FROM expense WHERE business_id = :b AND rev > :s AND (:all OR created_by_member_id = :me)
                          UNION ALL SELECT rev, 'cash_movement', id FROM cash_movement WHERE business_id = :b AND rev > :s AND (:all OR created_by_member_id = :me)
                          UNION ALL SELECT rev, 'stock_movement', id FROM stock_movement WHERE business_id = :b AND rev > :s AND :all
                          UNION ALL SELECT rev, 'supplier', id FROM supplier WHERE business_id = :b AND rev > :s AND :all
                          UNION ALL SELECT rev, 'purchase', id FROM purchase WHERE business_id = :b AND rev > :s AND :all
                          UNION ALL SELECT rev, 'supplier_payment', id FROM supplier_payment WHERE business_id = :b AND rev > :s AND :all
                          UNION ALL SELECT rev, 'notification', id FROM notification WHERE business_id = :b AND rev > :s AND (recipient_member_id = :me OR (recipient_device_id IS NOT NULL AND recipient_device_id = CAST(:dev AS uuid)))
                          UNION ALL SELECT s.rev, 'sale', s.id FROM sale s WHERE s.business_id = :b AND s.rev > :s AND\n                          """ + saleFilter + """
                        ) x ORDER BY rev LIMIT :n
                        """)
                .param("b", ctx.businessId()).param("s", since).param("me", ctx.memberId()).param("all", !cashier).param("n", limit + 1).param("dev", ctx.deviceId(), java.sql.Types.OTHER)
                .query((rs, i) -> new Object[] {rs.getLong("rev"), rs.getString("t"), rs.getObject("id", UUID.class)}).list();
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = rows.subList(0, limit);

        List<MemberService.MemberView> allMembers = null;
        Map<UUID, CategoryService.CategoryView> cats = null;
        Map<UUID, Map<String, Object>> registers = null;
        // Clientes, fiados, abonos y plantillas se cargan por lotes (una consulta por tipo), no una por fila.
        final List<Object[]> pageRows = rows;
        java.util.function.Function<String, List<UUID>> idsOf = t -> pageRows.stream().filter(r -> r[1].equals(t)).map(r -> (UUID) r[2]).toList();
        Map<UUID, Object> bulk = new LinkedHashMap<>();
        customers.viewsByIds(ctx.businessId(), idsOf.apply("customer")).forEach(v -> bulk.put(v.id(), v));
        credits.viewsByIds(ctx.businessId(), idsOf.apply("credit")).forEach(v -> bulk.put(v.id(), v));
        credits.paymentsByIds(ctx.businessId(), idsOf.apply("credit_payment")).forEach(v -> bulk.put(v.id(), v));
        templates.byIds(ctx.businessId(), idsOf.apply("message_template")).forEach(v -> bulk.put(v.id(), v));
        expenses.viewsByIds(ctx.businessId(), idsOf.apply("expense")).forEach(v -> bulk.put(v.id(), v));
        movements.viewsByIds(ctx.businessId(), idsOf.apply("cash_movement")).forEach(v -> bulk.put(v.id(), v));
        stock.viewsByIds(ctx.businessId(), idsOf.apply("stock_movement")).forEach(v -> bulk.put(v.id(), v));
        suppliers.viewsByIds(ctx.businessId(), idsOf.apply("supplier")).forEach(v -> bulk.put(v.id(), v));
        purchases.viewsByIds(ctx.businessId(), idsOf.apply("purchase")).forEach(v -> bulk.put(v.id(), v));
        purchases.paymentsByIds(ctx.businessId(), idsOf.apply("supplier_payment")).forEach(v -> bulk.put(v.id(), v));
        notifications.viewsByIds(ctx.businessId(), idsOf.apply("notification")).forEach(v -> bulk.put(v.id(), v));
        shifts.viewsByIds(ctx, idsOf.apply("shift")).forEach(v -> bulk.put(v.id(), v));
        expenses.categoriesByIds(ctx.businessId(), idsOf.apply("expense_category")).forEach(v -> bulk.put(v.id(), v));
        promotions.viewsByIds(ctx.businessId(), idsOf.apply("promotion")).forEach(v -> bulk.put(v.id(), v));
        List<Change> changes = new ArrayList<>();
        for (Object[] row : rows) {
            long rev = (Long) row[0];
            String type = (String) row[1];
            UUID id = (UUID) row[2];
            Object data;
            switch (type) {
                case "business" -> data = businesses.get(id);
                case "category" -> {
                    if (cats == null) {
                        cats = new LinkedHashMap<>();
                        for (var c : categories.list(ctx.businessId())) cats.put(c.id(), c);
                    }
                    data = cats.get(id);
                }
                case "product" -> data = products.get(ctx.businessId(), id);
                case "member" -> {
                    if (allMembers == null) allMembers = device ? members.listForDevice(ctx.businessId()) : members.list(ctx.businessId(), false);
                    data = allMembers.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
                }
                case "cash_register" -> {
                    if (registers == null) registers = registers(ctx.businessId());
                    data = registers.get(id);
                }
                case "customer", "credit", "credit_payment", "message_template", "expense", "cash_movement", "shift", "expense_category", "stock_movement", "supplier", "purchase", "supplier_payment", "notification", "promotion" -> data = bulk.get(id);
                default -> data = sales.view(ctx.businessId(), id);
            }
            if (data != null) changes.add(new Change(type, rev, data));
        }
        touchDevice(ctx, pendingOps);
        long cursor = rows.isEmpty() ? since : (Long) rows.get(rows.size() - 1)[0];
        return new PullResult(changes, cursor, hasMore);
    }

    private Map<UUID, Map<String, Object>> registers(UUID businessId) {
        Map<UUID, Map<String, Object>> out = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name, active FROM cash_register WHERE business_id = :b").param("b", businessId).query((rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            out.put(id, Map.of("id", id, "name", rs.getString("name"), "active", rs.getBoolean("active")));
            return null;
        }).list();
        return out;
    }

    private void touchDevice(MemberContext ctx, Integer pendingOps) {
        if (ctx.deviceId() == null) return;
        jdbc.sql("UPDATE device SET last_sync_at = :now, pending_ops = coalesce(:p, pending_ops) WHERE id = :d")
                .param("now", Timestamp.from(clock.instant())).param("p", pendingOps, java.sql.Types.INTEGER).param("d", ctx.deviceId()).update();
    }
}
