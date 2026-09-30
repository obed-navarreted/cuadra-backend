package com.cuadra.api.credit;

import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.common.Phones;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La libreta digital. Reglas que no se rompen:
 * saldo de un fiado = monto − abonos vigentes (0 si está condonado o cancelado); saldo de un cliente = suma de los saldos de sus fiados.
 * Un cliente es opcional: basta el nombre en texto ("fiado a doña Karla") y se puede vincular a un cliente después.
 */
@Service
public class CreditService {
    private static final long MAX_MINOR = 1_000_000_000_000L;
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Set<String> PAY_METHODS = Set.of("CASH", "TRANSFER", "CARD", "OTHER");
    private static final Set<String> EVENT_KINDS = Set.of("REMINDER_OPENED", "STATEMENT_OPENED", "RECEIPT_OPENED", "CREDIT_OPENED");

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final CustomerService customers;
    private final BusinessDayService days;
    private final com.cuadra.api.cash.RegisterResolver registers;

    public CreditService(JdbcClient jdbc, Audit audit, Clock clock, CustomerService customers, BusinessDayService days, com.cuadra.api.cash.RegisterResolver registers) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.customers = customers;
        this.days = days;
        this.registers = registers;
    }

    // ---------- contrato ----------

    public record ManualCreditInput(String debtorLabel, String debtorPhone, UUID customerId, Long amountMinor, String note, LocalDate dueDate, Instant createdAt) {}

    public record PayInput(UUID creditId, UUID customerId, Long amountMinor, String method, String reference, Instant occurredAt) {}

    public record EventInput(UUID creditId, UUID customerId, String kind, String format) {}

    /** Un pago a fiado dentro de una venta cobrada. */
    public record SaleCredit(UUID paymentId, long amountMinor, String debtorLabel, String debtorPhone, UUID customerId) {}

    public record CreditView(UUID id, UUID saleId, UUID customerId, String customerName, String debtorLabel, String debtorPhone, long amountMinor,
                             long balanceMinor, long paidMinor, String status, LocalDate dueDate, String note, Instant createdAt, String createdByName,
                             long ageDays, Instant lastReminderAt, long rev) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CreditPaymentView")
    public record PaymentView(UUID id, UUID creditId, UUID customerId, UUID groupId, long amountMinor, String method, String reference, String createdByName,
                              Instant occurredAt, boolean voided, String voidReason, UUID cashRegisterId, long rev) {}

    public record PayResult(List<PaymentView> payments, List<CreditView> credits, boolean created) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "CreditSummary")
    public record Summary(long openCount, long openTotalMinor, long overdueCount, long overdueMinor, int overdueAfterDays, long customersWithDebt) {}

    public record Movement(String kind, Instant at, long amountMinor, String label, String memberName, UUID creditId, UUID paymentId, boolean voided) {}

    public record Statement(CustomerService.CustomerView customer, List<Movement> movements) {}

    public record Filter(String status, String linked, UUID customerId, Integer minDays, String q, String sort) {}

    public enum Outcome { CREATED, UPDATED, UNCHANGED }

    public record Result(CreditView credit, Outcome outcome) {}

    // ---------- fiado sin venta ----------

    @Transactional
    public Result upsertManual(MemberContext ctx, UUID id, ManualCreditInput in) {
        ctx.require(Permission.SELL);
        if (in.amountMinor() == null || in.amountMinor() <= 0 || in.amountMinor() > MAX_MINOR) throw ApiException.badRequest("INVALID_AMOUNT", "Invalid amount");
        String country = customers.country(ctx.businessId());
        String phone = Phones.normalize(in.debtorPhone(), country);
        String label = in.debtorLabel() == null ? "" : in.debtorLabel().trim();
        String customerName = null;
        if (in.customerId() != null) customerName = customers.get(ctx.businessId(), in.customerId()).name();
        if (label.isEmpty()) label = customerName != null ? customerName : "";
        if (label.isEmpty() || label.length() > 120) throw ApiException.badRequest("DEBTOR_REQUIRED", "Say who owes it");
        if (in.customerId() == null && requiresCustomer(ctx.businessId())) throw ApiException.badRequest("CUSTOMER_REQUIRED", "This business needs a customer on every credit");
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();

        Instant now = clock.instant();
        Instant created = in.createdAt() == null || in.createdAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.createdAt();
        LocalDate due = in.dueDate() != null ? in.dueDate() : defaultDue(ctx.businessId(), created);

        Optional<Row> existing = lock(ctx.businessId(), id);
        if (existing.isEmpty()) {
            if (jdbc.sql("SELECT count(*) FROM credit WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");
            requireWithinLimit(ctx.businessId(), in.customerId(), in.amountMinor());
            jdbc.sql("""
                            INSERT INTO credit (id, business_id, customer_id, debtor_label, debtor_phone_e164, amount_minor, balance_minor, due_date, note,
                                                created_by_member_id, device_id, created_at)
                            VALUES (:id, :b, :c, :label, :phone, :amt, :amt, :due, :note, :m, :d, :at)
                            """)
                    .param("id", id).param("b", ctx.businessId()).param("c", in.customerId(), java.sql.Types.OTHER).param("label", label).param("phone", phone)
                    .param("amt", in.amountMinor()).param("due", due, java.sql.Types.DATE).param("note", note).param("m", ctx.memberId())
                    .param("d", ctx.deviceId(), java.sql.Types.OTHER).param("at", Timestamp.from(created)).update();
            customers.recompute(in.customerId());
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "credit.create", "credit", id, "amount=" + in.amountMinor());
            return new Result(view(ctx.businessId(), id), Outcome.CREATED);
        }
        Row c = existing.get();
        if (c.saleId != null) throw ApiException.conflict("CREDIT_FROM_SALE", "Change this credit by editing its sale");
        boolean sameAmount = c.amount == in.amountMinor();
        if (!sameAmount && paid(id) > 0) throw ApiException.conflict("CREDIT_HAS_PAYMENTS", "This credit already has payments");
        CreditView current = view(ctx.businessId(), id);
        if (sameAmount && label.equals(current.debtorLabel()) && java.util.Objects.equals(phone, current.debtorPhone()) && java.util.Objects.equals(in.customerId(), current.customerId())
                && java.util.Objects.equals(note, current.note())) {
            return new Result(current, Outcome.UNCHANGED);
        }
        // Subir el monto (o pasarlo a otro cliente) también respeta el límite de crédito del cliente.
        if ("OPEN".equals(c.status)) {
            boolean sameCustomer = java.util.Objects.equals(in.customerId(), c.customerId);
            requireWithinLimit(ctx.businessId(), in.customerId(), sameCustomer ? in.amountMinor() - c.amount : c.balance + (in.amountMinor() - c.amount));
        }
        jdbc.sql("""
                        UPDATE credit SET customer_id = :c, debtor_label = :label, debtor_phone_e164 = :phone, amount_minor = :amt, note = :note,
                               rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b
                        """)
                .param("c", in.customerId(), java.sql.Types.OTHER).param("label", label).param("phone", phone).param("amt", in.amountMinor()).param("note", note)
                .param("id", id).param("b", ctx.businessId()).update();
        recompute(id);
        customers.recompute(c.customerId);
        customers.recompute(in.customerId());
        return new Result(view(ctx.businessId(), id), Outcome.UPDATED);
    }

    // ---------- fiados que nacen de una venta ----------

    /**
     * Deja los fiados de una venta cobrada iguales a sus pagos "a fiado". Se llama al cobrar y al editar la venta.
     * Un fiado que ya tiene abonos no puede quedar por debajo de lo abonado, ni desaparecer.
     */
    public void syncSaleCredits(MemberContext ctx, UUID saleId, List<SaleCredit> wanted, Instant completedAt) {
        Set<UUID> keep = new LinkedHashSet<>();
        Set<UUID> touchedCustomers = new LinkedHashSet<>();
        // Límite de crédito: lo que esta venta AGREGA a la deuda de cada cliente (un fiado nuevo, o lo que sube uno existente), antes de tocar nada.
        Map<UUID, Long> added = new java.util.LinkedHashMap<>();
        for (SaleCredit sc : wanted) {
            if (sc.customerId() == null) continue;
            Optional<Row> prev = jdbc.sql("SELECT id, sale_id, customer_id, amount_minor, balance_minor, status FROM credit WHERE id = :id AND business_id = :b")
                    .param("id", saleCreditId(saleId, sc.paymentId())).param("b", ctx.businessId()).query((rs, n) -> row(rs)).optional();
            long before = prev.filter(r -> !"CANCELLED".equals(r.status) && sc.customerId().equals(r.customerId)).map(r -> r.amount).orElse(0L);
            added.merge(sc.customerId(), sc.amountMinor() - before, Long::sum);
        }
        added.forEach((customer, extra) -> requireWithinLimit(ctx.businessId(), customer, extra));
        for (SaleCredit sc : wanted) {
            UUID id = saleCreditId(saleId, sc.paymentId());
            keep.add(id);
            String label = sc.debtorLabel();
            if (sc.customerId() != null) {
                CustomerService.CustomerView cust = customers.find(ctx.businessId(), sc.customerId()).orElseThrow(() -> ApiException.badRequest("INVALID_CUSTOMER", "Customer not found"));
                if (label == null || label.isBlank()) label = cust.name();
                touchedCustomers.add(sc.customerId());
            }
            Optional<Row> existing = lock(ctx.businessId(), id);
            if (existing.isEmpty()) {
                jdbc.sql("""
                                INSERT INTO credit (id, business_id, sale_id, sale_payment_id, customer_id, debtor_label, debtor_phone_e164, amount_minor, balance_minor,
                                                    due_date, created_by_member_id, device_id, created_at)
                                VALUES (:id, :b, :s, :sp, :c, :label, :phone, :amt, :amt, :due, :m, :d, :at)
                                """)
                        .param("id", id).param("b", ctx.businessId()).param("s", saleId).param("sp", sc.paymentId()).param("c", sc.customerId(), java.sql.Types.OTHER)
                        .param("label", label).param("phone", sc.debtorPhone()).param("amt", sc.amountMinor())
                        .param("due", defaultDue(ctx.businessId(), completedAt), java.sql.Types.DATE).param("m", ctx.memberId())
                        .param("d", ctx.deviceId(), java.sql.Types.OTHER).param("at", Timestamp.from(completedAt)).update();
                audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "credit.from_sale", "credit", id, "amount=" + sc.amountMinor());
                continue;
            }
            Row c = existing.get();
            if (c.customerId != null) touchedCustomers.add(c.customerId);
            if (sc.amountMinor() < paid(id)) throw ApiException.conflict("CREDIT_PAID_EXCEEDS", "The credit already has more payments than the new amount");
            jdbc.sql("""
                            UPDATE credit SET amount_minor = :amt, debtor_label = :label, debtor_phone_e164 = :phone, customer_id = :c,
                                   status = CASE WHEN status = 'CANCELLED' THEN 'OPEN' ELSE status END, rev = nextval('change_rev_seq')
                             WHERE id = :id AND business_id = :b
                            """)
                    .param("amt", sc.amountMinor()).param("label", label).param("phone", sc.debtorPhone()).param("c", sc.customerId(), java.sql.Types.OTHER)
                    .param("id", id).param("b", ctx.businessId()).update();
            if (c.amount != sc.amountMinor()) event(ctx.businessId(), id, sc.customerId(), "SALE_EDITED", "from=" + c.amount + " to=" + sc.amountMinor(), ctx.memberId());
        }
        // Los pagos a fiado que ya no están en la venta editada: su fiado se cancela.
        for (UUID old : jdbc.sql("SELECT id FROM credit WHERE sale_id = :s AND business_id = :b AND status <> 'CANCELLED'")
                .param("s", saleId).param("b", ctx.businessId()).query(UUID.class).list()) {
            if (keep.contains(old)) continue;
            cancelCredit(ctx, old, touchedCustomers);
        }
        for (UUID id : keep) recompute(id);
        for (UUID c : touchedCustomers) customers.recompute(c);
    }

    /** Al eliminar una venta cobrada, sus fiados se cancelan (si ya tienen abonos, primero hay que anularlos). */
    public void cancelSaleCredits(MemberContext ctx, UUID saleId) {
        Set<UUID> touched = new LinkedHashSet<>();
        for (UUID id : jdbc.sql("SELECT id FROM credit WHERE sale_id = :s AND business_id = :b AND status <> 'CANCELLED'").param("s", saleId).param("b", ctx.businessId())
                .query(UUID.class).list()) {
            cancelCredit(ctx, id, touched);
        }
        for (UUID c : touched) customers.recompute(c);
    }

    /** Lo que aún se debe de los fiados de una venta (para una devolución con nota de crédito). */
    public long openBalanceOfSale(UUID businessId, UUID saleId) {
        return jdbc.sql("SELECT coalesce(sum(balance_minor), 0) FROM credit WHERE sale_id = :s AND business_id = :b AND status = 'OPEN'")
                .param("s", saleId).param("b", businessId).query(Long.class).single();
    }

    public record CreditNote(UUID creditId, long amountMinor) {}

    /**
     * Devolución con nota de crédito: baja el MONTO de los fiados abiertos de la venta (del más viejo al más nuevo), hasta `amountMinor`. Nunca por debajo
     * de lo ya abonado (quien llama ya comprobó que no pasa del saldo). Queda un evento RETURN en la libreta.
     */
    public List<CreditNote> applyCreditNote(MemberContext ctx, UUID saleId, long amountMinor, UUID returnId) {
        List<CreditNote> out = new ArrayList<>();
        Set<UUID> touched = new LinkedHashSet<>();
        long left = amountMinor;
        for (Row c : jdbc.sql("SELECT id, sale_id, customer_id, amount_minor, balance_minor, status FROM credit WHERE sale_id = :s AND business_id = :b AND status = 'OPEN' AND balance_minor > 0 ORDER BY created_at, id FOR UPDATE")
                .param("s", saleId).param("b", ctx.businessId()).query((rs, n) -> row(rs)).list()) {
            if (left <= 0) break;
            long take = Math.min(left, c.balance);
            if (take >= c.amount) {
                // Se devolvió todo lo fiado (sin abonos): el fiado queda cancelado (el monto no puede quedar en cero).
                jdbc.sql("UPDATE credit SET status = 'CANCELLED', balance_minor = 0, rev = nextval('change_rev_seq') WHERE id = :id").param("id", c.id).update();
            } else {
                jdbc.sql("UPDATE credit SET amount_minor = amount_minor - :x, rev = nextval('change_rev_seq') WHERE id = :id").param("x", take).param("id", c.id).update();
            }
            recompute(c.id);
            event(ctx.businessId(), c.id, c.customerId, "RETURN", "return=" + returnId + " amount=" + take, ctx.memberId());
            if (c.customerId != null) touched.add(c.customerId);
            out.add(new CreditNote(c.id, take));
            left -= take;
        }
        for (UUID cu : touched) customers.recompute(cu);
        return out;
    }

    private void cancelCredit(MemberContext ctx, UUID creditId, Set<UUID> touchedCustomers) {
        Row c = lock(ctx.businessId(), creditId).orElseThrow();
        if (paid(creditId) > 0) throw ApiException.conflict("CREDIT_HAS_PAYMENTS", "Void the payments of this credit first");
        if (c.customerId != null) touchedCustomers.add(c.customerId);
        jdbc.sql("UPDATE credit SET status = 'CANCELLED', balance_minor = 0, rev = nextval('change_rev_seq') WHERE id = :id").param("id", creditId).update();
        event(ctx.businessId(), creditId, c.customerId, "CANCELLED_WITH_SALE", null, ctx.memberId());
    }

    /**
     * Límite de crédito (Ajustes del negocio › «El límite bloquea»): con `credit_limit_enforced`, lo que el cliente ya debe más lo nuevo no puede pasar su
     * límite. Es la misma regla que aplica la caja (`CreditRules`); aquí la hace cumplir también la web y dos teléfonos sin conexión. Una venta sin conexión
     * que la rebasa se RECHAZA con los datos para explicarlo; el teléfono la conserva en «Requiere atención» (no se pierde).
     */
    void requireWithinLimit(UUID businessId, UUID customerId, long extraMinor) {
        if (customerId == null || extraMinor <= 0) return;
        if (!jdbc.sql("SELECT credit_limit_enforced FROM business WHERE id = :b").param("b", businessId).query(Boolean.class).single()) return;
        var c = jdbc.sql("SELECT credit_limit_minor, balance_minor, name FROM customer WHERE id = :c AND business_id = :b").param("c", customerId).param("b", businessId)
                .query((rs, n) -> new Object[] {rs.getObject("credit_limit_minor"), rs.getLong("balance_minor"), rs.getString("name")}).optional().orElse(null);
        if (c == null || c[0] == null) return;
        long limit = ((Number) c[0]).longValue();
        long balance = (Long) c[1];
        if (balance + extraMinor > limit) {
            throw ApiException.conflict("CREDIT_LIMIT_EXCEEDED", "This credit goes over the customer's limit")
                    .with("limitMinor", limit).with("balanceMinor", balance).with("amountMinor", extraMinor).with("customerName", c[2]);
        }
    }

    // ---------- abonos ----------

    /**
     * Registra un abono a un fiado o al cliente (repartido del más viejo al más nuevo). Idempotente por `id`.
     * Un abono nunca se rechaza por pasarse del saldo: el dinero ya se recibió. Se aplica y queda un aviso (OVERPAYMENT_REVIEW) para revisarlo.
     */
    @Transactional
    public PayResult pay(MemberContext ctx, UUID id, PayInput in) {
        ctx.require(Permission.SELL);
        if ((in.creditId() == null) == (in.customerId() == null)) throw ApiException.badRequest("INVALID_TARGET", "Pay either a credit or a customer");
        if (in.amountMinor() == null || in.amountMinor() <= 0 || in.amountMinor() > MAX_MINOR) throw ApiException.badRequest("INVALID_AMOUNT", "Invalid amount");
        String method = in.method() == null ? "CASH" : in.method();
        if (!PAY_METHODS.contains(method)) throw ApiException.badRequest("INVALID_METHOD", "Invalid payment method");
        String reference = in.reference() == null || in.reference().isBlank() ? null : in.reference().trim();

        List<UUID> already = jdbc.sql("SELECT id FROM credit_payment WHERE (id = :id OR group_id = :id) AND business_id = :b").param("id", id).param("b", ctx.businessId()).query(UUID.class).list();
        if (!already.isEmpty()) return resultFor(ctx.businessId(), already, false);
        if (jdbc.sql("SELECT count(*) FROM credit_payment WHERE id = :id OR group_id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");

        Instant now = clock.instant();
        Instant at = in.occurredAt() == null || in.occurredAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.occurredAt();
        List<UUID> created = new ArrayList<>();
        Set<UUID> touchedCustomers = new LinkedHashSet<>();

        UUID payCustomer = in.customerId();
        UUID redirectedFrom = null;
        if (in.creditId() != null) {
            Row closed = lock(ctx.businessId(), in.creditId()).orElseThrow(() -> ApiException.notFound("CREDIT_NOT_FOUND", "Credit not found"));
            if (closed.status.equals("CANCELLED") || closed.status.equals("WRITTEN_OFF")) {
                // "Nunca se rechaza un abono: el dinero ya se recibió." El fiado se condonó o anuló en otro teléfono mientras este cobraba sin conexión:
                // el abono va a las OTRAS deudas abiertas del mismo cliente (de la más vieja a la más nueva). Sin cliente o sin otras deudas no hay dónde
                // ponerlo (no existe "saldo a favor"): se rechaza con un código claro y el teléfono lo conserva en «Requiere atención».
                if (closed.customerId == null || !hasOpenCredits(ctx.businessId(), closed.customerId)) {
                    throw ApiException.conflict("CREDIT_CLOSED", "This credit is closed").with("creditStatus", closed.status);
                }
                payCustomer = closed.customerId;
                redirectedFrom = closed.id;
            }
        }
        if (in.creditId() != null && redirectedFrom == null) {
            Row c = lock(ctx.businessId(), in.creditId()).orElseThrow(() -> ApiException.notFound("CREDIT_NOT_FOUND", "Credit not found"));
            insertPayment(ctx, id, c.id, c.customerId, null, in.amountMinor(), method, reference, at);
            created.add(id);
            if (in.amountMinor() > c.balance) event(ctx.businessId(), c.id, c.customerId, "OVERPAYMENT_REVIEW", "excess=" + (in.amountMinor() - c.balance), ctx.memberId());
            recompute(c.id);
            if (c.customerId != null) touchedCustomers.add(c.customerId);
        } else {
            customers.get(ctx.businessId(), payCustomer);
            List<Row> open = jdbc.sql("""
                            SELECT id, sale_id, customer_id, amount_minor, balance_minor, status FROM credit
                             WHERE customer_id = :c AND business_id = :b AND status = 'OPEN' AND balance_minor > 0 ORDER BY created_at, id FOR UPDATE
                            """)
                    .param("c", payCustomer).param("b", ctx.businessId()).query((rs, n) -> row(rs)).list();
            if (open.isEmpty()) throw ApiException.conflict("NO_OPEN_CREDITS", "This customer owes nothing");
            long remaining = in.amountMinor();
            UUID lastChild = null;
            for (Row c : open) {
                if (remaining == 0) break;
                long take = Math.min(remaining, c.balance);
                UUID child = UUID.nameUUIDFromBytes(("pay:" + id + ":" + c.id).getBytes(StandardCharsets.UTF_8));
                insertPayment(ctx, child, c.id, payCustomer, id, take, method, reference, at);
                created.add(child);
                lastChild = child;
                remaining -= take;
            }
            if (remaining > 0 && lastChild != null) {
                jdbc.sql("UPDATE credit_payment SET amount_minor = amount_minor + :x WHERE id = :id").param("x", remaining).param("id", lastChild).update();
                UUID lastCredit = jdbc.sql("SELECT credit_id FROM credit_payment WHERE id = :id").param("id", lastChild).query(UUID.class).single();
                event(ctx.businessId(), lastCredit, payCustomer, "OVERPAYMENT_REVIEW", "excess=" + remaining, ctx.memberId());
            }
            for (UUID child : created) recompute(jdbc.sql("SELECT credit_id FROM credit_payment WHERE id = :id").param("id", child).query(UUID.class).single());
            touchedCustomers.add(payCustomer);
            if (redirectedFrom != null) event(ctx.businessId(), redirectedFrom, payCustomer, "PAYMENT_REDIRECTED", "payment=" + id + " amount=" + in.amountMinor(), ctx.memberId());
        }
        for (UUID c : touchedCustomers) customers.recompute(c);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "credit.payment", "credit_payment", id, "amount=" + in.amountMinor());
        return resultFor(ctx.businessId(), created, true);
    }

    private boolean hasOpenCredits(UUID businessId, UUID customerId) {
        return jdbc.sql("SELECT count(*) FROM credit WHERE customer_id = :c AND business_id = :b AND status = 'OPEN' AND balance_minor > 0")
                .param("c", customerId).param("b", businessId).query(Integer.class).single() > 0;
    }

    private void insertPayment(MemberContext ctx, UUID id, UUID creditId, UUID customerId, UUID groupId, long amount, String method, String reference, Instant at) {
        jdbc.sql("""
                        INSERT INTO credit_payment (id, business_id, credit_id, customer_id, group_id, amount_minor, method, reference, created_by_member_id, device_id, occurred_at, cash_register_id)
                        VALUES (:id, :b, :cr, :cu, :g, :amt, :m, :ref, :by, :d, :at, :reg)
                        """)
                .param("reg", registers.resolve(ctx, null))
                .param("id", id).param("b", ctx.businessId()).param("cr", creditId).param("cu", customerId, java.sql.Types.OTHER).param("g", groupId, java.sql.Types.OTHER)
                .param("amt", amount).param("m", method).param("ref", reference).param("by", ctx.memberId()).param("d", ctx.deviceId(), java.sql.Types.OTHER)
                .param("at", Timestamp.from(at)).update();
    }

    /** Anular un abono (o todo un abono repartido) devuelve el saldo. Solo quien gestiona el crédito. */
    @Transactional
    public PayResult voidPayment(MemberContext ctx, UUID id, String reason) {
        ctx.require(Permission.MANAGE_CREDIT);
        List<UUID> targets = jdbc.sql("SELECT id FROM credit_payment WHERE (id = :id OR group_id = :id) AND business_id = :b").param("id", id).param("b", ctx.businessId()).query(UUID.class).list();
        if (targets.isEmpty()) throw ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found");
        jdbc.sql("""
                        UPDATE credit_payment SET voided_by_member_id = :m, voided_at = :now, void_reason = :r, rev = nextval('change_rev_seq')
                         WHERE id IN (:ids) AND voided_at IS NULL
                        """)
                .param("m", ctx.memberId()).param("now", Timestamp.from(clock.instant())).param("r", reason == null || reason.isBlank() ? null : reason.trim()).param("ids", targets).update();
        Set<UUID> customersTouched = new LinkedHashSet<>();
        for (UUID creditId : jdbc.sql("SELECT DISTINCT credit_id FROM credit_payment WHERE id IN (:ids)").param("ids", targets).query(UUID.class).list()) {
            lock(ctx.businessId(), creditId).ifPresent(c -> { if (c.customerId != null) customersTouched.add(c.customerId); });
            recompute(creditId);
        }
        for (UUID c : customersTouched) customers.recompute(c);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "credit.payment_void", "credit_payment", id, reason);
        return resultFor(ctx.businessId(), targets, false);
    }

    // ---------- condonar, vincular, eventos ----------

    /** Cierra la deuda sin cobrarla. Motivo obligatorio y solo quien gestiona el crédito. No suma a la caja. */
    @Transactional
    public CreditView writeOff(MemberContext ctx, UUID id, String reason) {
        ctx.require(Permission.MANAGE_CREDIT);
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required");
        Row c = lock(ctx.businessId(), id).orElseThrow(() -> ApiException.notFound("CREDIT_NOT_FOUND", "Credit not found"));
        if (c.status.equals("WRITTEN_OFF")) return view(ctx.businessId(), id);
        if (c.status.equals("CANCELLED")) throw ApiException.conflict("CREDIT_CLOSED", "This credit is closed");
        event(ctx.businessId(), id, c.customerId, "WRITTEN_OFF", "forgiven=" + c.balance, ctx.memberId());
        jdbc.sql("""
                        UPDATE credit SET status = 'WRITTEN_OFF', balance_minor = 0, written_off_by_member_id = :m, written_off_at = :now, write_off_reason = :r,
                               rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b
                        """)
                .param("m", ctx.memberId()).param("now", Timestamp.from(clock.instant())).param("r", reason.trim()).param("id", id).param("b", ctx.businessId()).update();
        customers.recompute(c.customerId);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "credit.write_off", "credit", id, reason);
        return view(ctx.businessId(), id);
    }

    /** Vincula un fiado de "solo nota" a un cliente sin tocar montos ni fechas. */
    @Transactional
    public CreditView linkCustomer(MemberContext ctx, UUID id, UUID customerId) {
        ctx.require(Permission.SELL);
        CustomerService.CustomerView cust = customers.get(ctx.businessId(), customerId);
        Row c = lock(ctx.businessId(), id).orElseThrow(() -> ApiException.notFound("CREDIT_NOT_FOUND", "Credit not found"));
        if (customerId.equals(c.customerId)) return view(ctx.businessId(), id);
        jdbc.sql("""
                        UPDATE credit SET customer_id = :c, debtor_phone_e164 = COALESCE(debtor_phone_e164, :phone), rev = nextval('change_rev_seq')
                         WHERE id = :id AND business_id = :b
                        """)
                .param("c", customerId).param("phone", cust.phone()).param("id", id).param("b", ctx.businessId()).update();
        // Los abonos ya hechos también pasan al cliente, para que su estado de cuenta sea completo.
        jdbc.sql("UPDATE credit_payment SET customer_id = :c, rev = nextval('change_rev_seq') WHERE credit_id = :id").param("c", customerId).param("id", id).update();
        event(ctx.businessId(), id, customerId, "LINKED_TO_CUSTOMER", null, ctx.memberId());
        customers.recompute(c.customerId);
        customers.recompute(customerId);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "credit.link_customer", "credit", id, customerId.toString());
        return view(ctx.businessId(), id);
    }

    /** Se registró que se abrió WhatsApp (recordatorio, comprobante, estado de cuenta). No se puede saber si se envió. */
    @Transactional
    public void recordEvent(MemberContext ctx, EventInput in) {
        ctx.require(Permission.SELL);
        if (in.kind() == null || !EVENT_KINDS.contains(in.kind())) throw ApiException.badRequest("INVALID_KIND", "Invalid event kind");
        if (in.creditId() == null && in.customerId() == null) throw ApiException.badRequest("INVALID_TARGET", "Say which credit or customer");
        UUID customerId = in.customerId();
        if (in.creditId() != null) {
            Row c = lock(ctx.businessId(), in.creditId()).orElseThrow(() -> ApiException.notFound("CREDIT_NOT_FOUND", "Credit not found"));
            if (customerId == null) customerId = c.customerId;
        }
        event(ctx.businessId(), in.creditId(), customerId, in.kind(), in.format() == null ? null : "format=" + in.format(), ctx.memberId());
        if (in.kind().equals("REMINDER_OPENED")) {
            Timestamp now = Timestamp.from(clock.instant());
            if (in.creditId() != null) jdbc.sql("UPDATE credit SET last_reminder_at = :t, rev = nextval('change_rev_seq') WHERE id = :id").param("t", now).param("id", in.creditId()).update();
            if (customerId != null) jdbc.sql("UPDATE customer SET last_reminder_at = :t, rev = nextval('change_rev_seq') WHERE id = :id").param("t", now).param("id", customerId).update();
        }
    }

    // ---------- lectura ----------

    public CreditView get(UUID businessId, UUID id) {
        return view(businessId, id);
    }

    public PageResponse<CreditView> list(UUID businessId, Filter f, int page, int size) {
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        StringBuilder where = new StringBuilder("c.business_id = :b");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("b", businessId);
        String status = f.status() == null ? "OPEN" : f.status();
        if (status.equals("OPEN")) where.append(" AND c.status = 'OPEN'");
        else if (!status.equals("ALL")) {
            where.append(" AND c.status = :status");
            params.put("status", status);
        }
        if ("WITH".equals(f.linked())) where.append(" AND c.customer_id IS NOT NULL");
        if ("WITHOUT".equals(f.linked())) where.append(" AND c.customer_id IS NULL");
        if (f.customerId() != null) {
            where.append(" AND c.customer_id = :cust");
            params.put("cust", f.customerId());
        }
        if (f.minDays() != null) {
            where.append(" AND c.created_at <= :cutoff");
            params.put("cutoff", Timestamp.from(clock.instant().minus(Duration.ofDays(f.minDays()))));
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (lower(c.debtor_label) LIKE :like OR lower(coalesce(cu.name, '')) LIKE :like OR c.debtor_phone_e164 LIKE :digits OR lower(coalesce(c.note, '')) LIKE :like)");
            params.put("like", "%" + f.q().trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%");
            params.put("digits", "%" + f.q().replaceAll("\\D", "") + "%");
        }
        String order = switch (f.sort() == null ? "OLDEST" : f.sort()) {
            case "AMOUNT" -> "c.balance_minor DESC, c.created_at";
            case "RECENT" -> "c.created_at DESC";
            default -> "c.created_at, c.id";
        };
        var count = jdbc.sql("SELECT count(*) FROM credit c LEFT JOIN customer cu ON cu.id = c.customer_id WHERE " + where);
        var rows = jdbc.sql(SELECT_VIEW + " WHERE " + where + " ORDER BY " + order + " LIMIT " + size + " OFFSET " + (long) page * size);
        for (var e : params.entrySet()) {
            count = count.param(e.getKey(), e.getValue());
            rows = rows.param(e.getKey(), e.getValue());
        }
        return PageResponse.of(rows.param("now", Timestamp.from(clock.instant())).query((rs, n) -> mapView(rs)).list(), page, size, count.query(Long.class).single());
    }

    public Summary summary(UUID businessId) {
        int overdueAfter = jdbc.sql("SELECT credit_overdue_days FROM business WHERE id = :b").param("b", businessId).query(Integer.class).single();
        Timestamp cutoff = Timestamp.from(clock.instant().minus(Duration.ofDays(overdueAfter)));
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'OPEN' AND balance_minor > 0) AS open_count,
                               coalesce(sum(balance_minor) FILTER (WHERE status = 'OPEN'), 0) AS open_total,
                               count(*) FILTER (WHERE status = 'OPEN' AND balance_minor > 0 AND created_at <= :cutoff) AS overdue_count,
                               coalesce(sum(balance_minor) FILTER (WHERE status = 'OPEN' AND created_at <= :cutoff), 0) AS overdue_total,
                               count(DISTINCT customer_id) FILTER (WHERE status = 'OPEN' AND balance_minor > 0) AS customers
                          FROM credit WHERE business_id = :b
                        """)
                .param("b", businessId).param("cutoff", cutoff)
                .query((rs, n) -> new Summary(rs.getLong("open_count"), rs.getLong("open_total"), rs.getLong("overdue_count"), rs.getLong("overdue_total"), overdueAfter, rs.getLong("customers")))
                .single();
    }

    /** Estado de cuenta: fiados, abonos y condonaciones del cliente en orden cronológico. */
    public Statement statement(UUID businessId, UUID customerId) {
        CustomerService.CustomerView customer = customers.get(businessId, customerId);
        List<Movement> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT c.id, c.created_at, c.amount_minor, c.debtor_label, c.note, m.display_name FROM credit c JOIN member m ON m.id = c.created_by_member_id
                         WHERE c.customer_id = :c AND c.business_id = :b AND c.status <> 'CANCELLED'
                        """)
                .param("c", customerId).param("b", businessId).query((rs, n) -> {
                    out.add(new Movement("CREDIT", rs.getTimestamp("created_at").toInstant(), rs.getLong("amount_minor"), rs.getString("note") != null ? rs.getString("note") : rs.getString("debtor_label"),
                            rs.getString("display_name"), rs.getObject("id", UUID.class), null, false));
                    return null;
                }).list();
        jdbc.sql("""
                        SELECT p.id, p.credit_id, p.occurred_at, p.amount_minor, p.method, p.voided_at, m.display_name FROM credit_payment p
                          JOIN member m ON m.id = p.created_by_member_id WHERE p.customer_id = :c AND p.business_id = :b
                        """)
                .param("c", customerId).param("b", businessId).query((rs, n) -> {
                    out.add(new Movement("PAYMENT", rs.getTimestamp("occurred_at").toInstant(), rs.getLong("amount_minor"), rs.getString("method"), rs.getString("display_name"),
                            rs.getObject("credit_id", UUID.class), rs.getObject("id", UUID.class), rs.getTimestamp("voided_at") != null));
                    return null;
                }).list();
        jdbc.sql("""
                        SELECT c.id, c.written_off_at, c.write_off_reason, c.amount_minor, m.display_name FROM credit c LEFT JOIN member m ON m.id = c.written_off_by_member_id
                         WHERE c.customer_id = :c AND c.business_id = :b AND c.status = 'WRITTEN_OFF'
                        """)
                .param("c", customerId).param("b", businessId).query((rs, n) -> {
                    out.add(new Movement("WRITE_OFF", rs.getTimestamp("written_off_at").toInstant(), rs.getLong("amount_minor"), rs.getString("write_off_reason"), rs.getString("display_name"),
                            rs.getObject("id", UUID.class), null, false));
                    return null;
                }).list();
        out.sort(java.util.Comparator.comparing(Movement::at));
        return new Statement(customer, out);
    }

    public List<PaymentView> paymentsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(SELECT_PAYMENT + " WHERE p.business_id = :b AND p.id IN (:ids) ORDER BY p.occurred_at, (SELECT c.created_at FROM credit c WHERE c.id = p.credit_id), p.id").param("b", businessId).param("ids", ids)
                .query((rs, n) -> mapPayment(rs)).list();
    }

    public PaymentView payment(UUID businessId, UUID id) {
        return paymentsByIds(businessId, List.of(id)).stream().findFirst().orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
    }

    // ---------- internos ----------

    static UUID saleCreditId(UUID saleId, UUID paymentId) {
        return UUID.nameUUIDFromBytes(("credit:" + saleId + ":" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    private record Row(UUID id, UUID saleId, UUID customerId, long amount, long balance, String status) {}

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("sale_id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getLong("amount_minor"),
                rs.getLong("balance_minor"), rs.getString("status"));
    }

    private Optional<Row> lock(UUID businessId, UUID id) {
        return jdbc.sql("SELECT id, sale_id, customer_id, amount_minor, balance_minor, status FROM credit WHERE id = :id AND business_id = :b FOR UPDATE")
                .param("id", id).param("b", businessId).query((rs, n) -> row(rs)).optional();
    }

    private long paid(UUID creditId) {
        return jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM credit_payment WHERE credit_id = :id AND voided_at IS NULL").param("id", creditId).query(Long.class).single();
    }

    /** saldo = monto − abonos vigentes; 0 si está condonado o cancelado. Estado: PAGADO cuando llega a 0. */
    void recompute(UUID creditId) {
        Row c = jdbc.sql("SELECT id, sale_id, customer_id, amount_minor, balance_minor, status FROM credit WHERE id = :id").param("id", creditId).query((rs, n) -> row(rs)).single();
        if (c.status.equals("WRITTEN_OFF") || c.status.equals("CANCELLED")) {
            jdbc.sql("UPDATE credit SET balance_minor = 0, rev = nextval('change_rev_seq') WHERE id = :id AND balance_minor <> 0").param("id", creditId).update();
            return;
        }
        long balance = Math.max(0, c.amount - paid(creditId));
        String status = balance == 0 ? "PAID" : "OPEN";
        jdbc.sql("UPDATE credit SET balance_minor = :bal, status = :st, rev = nextval('change_rev_seq') WHERE id = :id").param("bal", balance).param("st", status).param("id", creditId).update();
    }

    private void event(UUID businessId, UUID creditId, UUID customerId, String kind, String payload, UUID memberId) {
        jdbc.sql("INSERT INTO credit_event (business_id, credit_id, customer_id, kind, payload, member_id) VALUES (:b, :cr, :cu, :k, :p, :m)")
                .param("b", businessId).param("cr", creditId, java.sql.Types.OTHER).param("cu", customerId, java.sql.Types.OTHER).param("k", kind).param("p", payload)
                .param("m", memberId, java.sql.Types.OTHER).update();
    }

    private boolean requiresCustomer(UUID businessId) {
        return jdbc.sql("SELECT credit_requires_customer FROM business WHERE id = :b").param("b", businessId).query(Boolean.class).single();
    }

    private LocalDate defaultDue(UUID businessId, Instant at) {
        Integer dueDays = jdbc.sql("SELECT credit_default_due_days FROM business WHERE id = :b").param("b", businessId).query((rs, n) -> (Integer) rs.getObject(1)).optional().orElse(null);
        return dueDays == null ? null : days.info(businessId).dateOf(at).plusDays(dueDays);
    }

    private PayResult resultFor(UUID businessId, List<UUID> paymentIds, boolean created) {
        List<PaymentView> payments = paymentsByIds(businessId, paymentIds);
        List<UUID> creditIds = payments.stream().map(PaymentView::creditId).distinct().toList();
        List<CreditView> credits = creditIds.stream().map(id -> view(businessId, id)).toList();
        return new PayResult(payments, credits, created);
    }

    private static final String SELECT_VIEW = """
            SELECT c.*, cu.name AS customer_name, m.display_name AS created_name,
                   coalesce((SELECT sum(amount_minor) FROM credit_payment p WHERE p.credit_id = c.id AND p.voided_at IS NULL), 0) AS paid,
                   floor(extract(epoch FROM (:now - c.created_at)) / 86400) AS age_days
              FROM credit c LEFT JOIN customer cu ON cu.id = c.customer_id JOIN member m ON m.id = c.created_by_member_id""";

    private CreditView view(UUID businessId, UUID id) {
        return jdbc.sql(SELECT_VIEW + " WHERE c.id = :id AND c.business_id = :b").param("id", id).param("b", businessId).param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> mapView(rs)).optional().orElseThrow(() -> ApiException.notFound("CREDIT_NOT_FOUND", "Credit not found"));
    }

    public List<CreditView> viewsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(SELECT_VIEW + " WHERE c.business_id = :b AND c.id IN (:ids)").param("b", businessId).param("ids", ids).param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> mapView(rs)).list();
    }

    private static CreditView mapView(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp last = rs.getTimestamp("last_reminder_at");
        java.sql.Date due = rs.getDate("due_date");
        return new CreditView(rs.getObject("id", UUID.class), rs.getObject("sale_id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                rs.getString("debtor_label"), rs.getString("debtor_phone_e164"), rs.getLong("amount_minor"), rs.getLong("balance_minor"), rs.getLong("paid"),
                rs.getString("status"), due == null ? null : due.toLocalDate(), rs.getString("note"), rs.getTimestamp("created_at").toInstant(), rs.getString("created_name"),
                rs.getLong("age_days"), last == null ? null : last.toInstant(), rs.getLong("rev"));
    }

    private static final String SELECT_PAYMENT = """
            SELECT p.*, m.display_name AS created_name FROM credit_payment p JOIN member m ON m.id = p.created_by_member_id""";

    private static PaymentView mapPayment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentView(rs.getObject("id", UUID.class), rs.getObject("credit_id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("method"), rs.getString("reference"), rs.getString("created_name"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("voided_at") != null, rs.getString("void_reason"), rs.getObject("cash_register_id", UUID.class), rs.getLong("rev"));
    }
}
