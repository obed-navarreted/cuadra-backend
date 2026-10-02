package com.cuadra.api.business;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.Json;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessService {
    private static final List<String> MODULE_KEYS = List.of("credit", "expenses", "inventory", "shifts", "catalog", "team");
    private static final List<String> POS_VIEWS = List.of("TYPE", "QUICK", "LIST");

    private final JdbcClient jdbc;
    private final Json json;
    private final Audit audit;
    private final Clock clock;
    private final com.cuadra.api.plan.PlanService plans;
    private final BusinessDayService days;

    private final com.cuadra.api.push.PushDispatcher push;

    public BusinessService(JdbcClient jdbc, Json json, Audit audit, Clock clock, com.cuadra.api.plan.PlanService plans, BusinessDayService days,
                           com.cuadra.api.push.PushDispatcher push) {
        this.push = push;
        this.days = days;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.plans = plans;
    }

    public record BusinessView(UUID id, String name, String type, String country, String currency, String timezone,
                               String defaultLocale, String dayCutoff, String inventoryMode, Map<String, Boolean> modules,
                               List<String> posViews, boolean creditRequiresCustomer, Integer creditDefaultDueDays,
                               int creditOverdueDays, boolean creditLimitEnforced, boolean shiftRequired, Long shiftNoteThresholdMinor, String status,
                               /** Reglas de zona/corte con su historial (la última puede regir desde una jornada futura) y, si hay una pendiente, desde cuándo. */
                               List<DayRuleView> dayRules, LocalDate dayRuleEffectiveFrom,
                               /** Código corto del negocio: con él, un usuario y su PIN, las personas del equipo entran desde su teléfono (no es secreto; el acceso lo dan usuario + PIN). */
                               String accessCode,
                               /** La moneda ya no se puede cambiar: hay actividad (ventas, gastos, fiados…) registrada en ella. Antes de la primera, sí. */
                               boolean currencyLocked,
                               /** Cobro en caja (ADR 0015): quien atiende puede "enviar a caja" la cuenta y otro la cobra. Apagado por omisión. */
                               boolean registerCheckout) {}

    public record DayRuleView(LocalDate from, String timezone, String dayCutoff) {}

    public record CreateBusiness(String name, String type, String country, String currency, String timezone, String locale) {}

    public record UpdateBusiness(String name, String type, String currency, String timezone, String defaultLocale,
                                 String dayCutoff, Map<String, Boolean> modules, List<String> posViews,
                                 Boolean creditRequiresCustomer, Integer creditDefaultDueDays, Integer creditOverdueDays,
                                 Boolean creditLimitEnforced, Boolean shiftRequired, Long shiftNoteThresholdMinor,
                                 /** En una actualización parcial un valor ausente significa "sin cambio"; para dejar estos dos SIN valor se pide explícitamente. */
                                 Boolean clearCreditDefaultDueDays, Boolean clearShiftNoteThreshold,
                                 /** País (código ISO de 2 letras): da el prefijo de WhatsApp y las sugerencias; se puede corregir cuando sea. */
                                 String country,
                                 /** Cobro en caja (ADR 0015). */
                                 Boolean registerCheckout,
                                 /** Al apagar «Cobro en caja» con cuentas por cobrar: true = anularlas (ADR 0015). */
                                 Boolean confirmDiscardPending) {
        public UpdateBusiness(String name, String type, String currency, String timezone, String defaultLocale, String dayCutoff, Map<String, Boolean> modules,
                              List<String> posViews, Boolean creditRequiresCustomer, Integer creditDefaultDueDays, Integer creditOverdueDays, Boolean creditLimitEnforced,
                              Boolean shiftRequired, Long shiftNoteThresholdMinor, Boolean clearCreditDefaultDueDays, Boolean clearShiftNoteThreshold, String country) {
            this(name, type, currency, timezone, defaultLocale, dayCutoff, modules, posViews, creditRequiresCustomer, creditDefaultDueDays, creditOverdueDays,
                    creditLimitEnforced, shiftRequired, shiftNoteThresholdMinor, clearCreditDefaultDueDays, clearShiftNoteThreshold, country, null, null);
        }

        public UpdateBusiness(String name, String type, String currency, String timezone, String defaultLocale, String dayCutoff, Map<String, Boolean> modules,
                              List<String> posViews, Boolean creditRequiresCustomer, Integer creditDefaultDueDays, Integer creditOverdueDays, Boolean creditLimitEnforced,
                              Boolean shiftRequired, Long shiftNoteThresholdMinor, Boolean clearCreditDefaultDueDays, Boolean clearShiftNoteThreshold) {
            this(name, type, currency, timezone, defaultLocale, dayCutoff, modules, posViews, creditRequiresCustomer, creditDefaultDueDays, creditOverdueDays,
                    creditLimitEnforced, shiftRequired, shiftNoteThresholdMinor, clearCreditDefaultDueDays, clearShiftNoteThreshold, null);
        }
    }

    /** Crea el negocio, su dueño y la "Caja 1". Un solo campo obligatorio: el nombre (5.0 del plan). */
    @Transactional
    public BusinessView create(UUID userId, CreateBusiness req) {
        plans.requireCanCreateBusiness(userId);
        String country = req.country() == null || req.country().isBlank() ? "NI" : req.country().toUpperCase();
        CountryDefaults.Defaults d = CountryDefaults.of(country);
        String timezone = req.timezone() == null || req.timezone().isBlank() ? d.timezone() : req.timezone();
        try {
            ZoneId.of(timezone);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "Invalid timezone");
        }
        String currency = req.currency() == null || req.currency().isBlank() ? d.currency() : req.currency().toUpperCase();
        String locale = req.locale() == null || req.locale().isBlank() ? d.locale() : req.locale();

        UUID businessId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business (id, name, type, country, currency, timezone, default_locale, day_cutoff, access_code)
                        VALUES (:id, :n, :t, :c, :cur, :tz, :loc, :cut, :code)
                        """)
                .param("id", businessId).param("n", req.name().trim()).param("t", req.type()).param("c", country)
                .param("cur", currency).param("tz", timezone).param("loc", locale).param("cut", defaultCutoff(timezone)).param("code", newAccessCode()).update();
        jdbc.sql("INSERT INTO business_day_rule (business_id, effective_from, timezone, day_cutoff, created_by) VALUES (:b, :f, :tz, :cut, :u)")
                .param("b", businessId).param("f", BusinessDayService.SINCE_FOREVER).param("tz", timezone).param("cut", defaultCutoff(timezone)).param("u", userId).update();

        var user = jdbc.sql("SELECT COALESCE(full_name, email) AS n FROM user_account WHERE id = :u")
                .param("u", userId).query(String.class).single();
        UUID memberId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO member (id, business_id, user_account_id, display_name, role, created_by_member_id)
                        VALUES (:id, :b, :u, :n, 'OWNER', :id)
                        """)
                .param("id", memberId).param("b", businessId).param("u", userId).param("n", user).update();
        jdbc.sql("INSERT INTO cash_register (id, business_id, name) VALUES (:id, :b, 'Caja 1')")
                .param("id", UUID.randomUUID()).param("b", businessId).update();
        plans.startTrial(businessId);
        // Categorías de gasto de fábrica: se guardan por clave y la app las traduce; si el negocio renombra una se guarda su nombre.
        for (String key : List.of("goods", "utilities", "payroll", "rent", "transport", "supplies", "maintenance", "other")) {
            jdbc.sql("INSERT INTO expense_category (id, business_id, key) VALUES (:id, :b, :k)").param("id", UUID.randomUUID()).param("b", businessId).param("k", key).update();
        }
        audit.log(businessId, memberId, userId, null, "business.create", "business", businessId, req.name());
        return get(businessId);
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** Un código de 5 dígitos (sin cero inicial, fácil de decir y de recordar), único entre negocios. */
    private String newAccessCode() {
        for (int i = 0; i < 50; i++) {
            String code = String.valueOf(10000 + RANDOM.nextInt(90000));
            if (jdbc.sql("SELECT count(*) FROM business WHERE access_code = :c").param("c", code).query(Integer.class).single() == 0) return code;
        }
        throw new IllegalStateException("No se pudo generar un código de negocio");
    }

    /** El dueño elige un código propio de 5 dígitos (por ejemplo el que ya usa su equipo): tiene que estar libre. */
    @Transactional
    public String setAccessCode(UUID businessId, UUID memberId, UUID userId, String requested) {
        push.requestSync(businessId);  // los demás teléfonos se ponen al día al instante (solo si se confirma)
        String code = requested == null ? "" : requested.trim();
        if (!code.matches("[1-9]\\d{4}")) throw ApiException.badRequest("INVALID_ACCESS_CODE", "The code must be 5 digits");
        try {
            // Con el aislamiento por negocio (RLS) no se ven los códigos de otros negocios: el índice único es quien lo decide.
            jdbc.sql("UPDATE business SET access_code = :c, rev = nextval('change_rev_seq') WHERE id = :b").param("c", code).param("b", businessId).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("ACCESS_CODE_TAKEN", "That code is already used by another business");
        }
        audit.log(businessId, memberId, userId, null, "business.access_code_set", "business", businessId, null);
        return code;
    }

    /** El dueño renueva el código (por ejemplo si se filtró): quien ya tiene su teléfono vinculado no se ve afectado; los nuevos usan el código nuevo. */
    @Transactional
    public String regenerateAccessCode(UUID businessId, UUID memberId, UUID userId) {
        push.requestSync(businessId);  // los demás teléfonos se ponen al día al instante (solo si se confirma)
        for (int i = 0; i < 50; i++) {
            String code = String.valueOf(10000 + RANDOM.nextInt(90000));
            try {
                jdbc.sql("UPDATE business SET access_code = :c, rev = nextval('change_rev_seq') WHERE id = :b").param("c", code).param("b", businessId).update();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                continue;   // choque con otro negocio (invisible por RLS): se prueba otro
            }
            audit.log(businessId, memberId, userId, null, "business.access_code_regenerated", "business", businessId, null);
            return code;
        }
        throw new IllegalStateException("No se pudo generar un código de negocio");
    }

    /**
     * Corte por defecto de la jornada: 02:00, como en ARMarket; 04:00 si la zona cambia de hora en verano (a las 02:00 hay un salto o una hora repetida
     * y la jornada no debería empezar justo ahí).
     */
    static LocalTime defaultCutoff(String timezone) {
        var rules = ZoneId.of(timezone).getRules();
        boolean dst = !rules.isFixedOffset() && !rules.getTransitionRules().isEmpty();
        return dst ? LocalTime.of(4, 0) : LocalTime.of(2, 0);
    }

    public BusinessView get(UUID businessId) {
        return jdbc.sql("SELECT * FROM business WHERE id = :id").param("id", businessId)
                .query((rs, n) -> new BusinessView(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("type"), rs.getString("country"),
                        rs.getString("currency"), rs.getString("timezone"), rs.getString("default_locale"),
                        rs.getObject("day_cutoff", LocalTime.class).toString(), rs.getString("inventory_mode"),
                        json.readFlags(rs.getString("modules")), json.readList(rs.getString("pos_views")),
                        rs.getBoolean("credit_requires_customer"), (Integer) rs.getObject("credit_default_due_days"),
                        rs.getInt("credit_overdue_days"), rs.getBoolean("credit_limit_enforced"),
                        rs.getBoolean("shift_required"), (Long) rs.getObject("shift_note_threshold_minor"), rs.getString("status"), List.of(), null, rs.getString("access_code"), false,
                        rs.getBoolean("register_checkout")))
                .optional().map(this::withRules).orElseThrow(() -> ApiException.notFound("BUSINESS_NOT_FOUND", "Business not found"));
    }

    private BusinessView withRules(BusinessView b) {
        BusinessDayService.Info info = days.info(b.id());
        List<DayRuleView> rules = info.rules().stream().map(r -> new DayRuleView(r.from(), r.zone().getId(), r.cutoff().toString())).toList();
        return new BusinessView(b.id(), b.name(), b.type(), b.country(), b.currency(), b.timezone(), b.defaultLocale(), b.dayCutoff(), b.inventoryMode(), b.modules(),
                b.posViews(), b.creditRequiresCustomer(), b.creditDefaultDueDays(), b.creditOverdueDays(), b.creditLimitEnforced(), b.shiftRequired(),
                b.shiftNoteThresholdMinor(), b.status(), rules, info.pendingFrom(clock.instant()), b.accessCode(), hasActivity(b.id()), b.registerCheckout());
    }

    /** Hay algo registrado con dinero (ventas, turnos, gastos, movimientos, fiados): la moneda queda fija y la zona u hora de corte rigen desde mañana. */
    boolean hasActivity(UUID businessId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM sale WHERE business_id = :b) OR EXISTS (SELECT 1 FROM shift WHERE business_id = :b)
                            OR EXISTS (SELECT 1 FROM expense WHERE business_id = :b) OR EXISTS (SELECT 1 FROM cash_movement WHERE business_id = :b)
                            OR EXISTS (SELECT 1 FROM credit WHERE business_id = :b)
                        """).param("b", businessId).query(Boolean.class).single());
    }

    /**
     * Cambiar la zona horaria o el corte. Sin ninguna actividad todavía (ventas, turnos, gastos, movimientos) se aplica de una vez y sin historial;
     * con actividad rige desde la PRÓXIMA jornada, para que los días ya vividos no cambien. Devuelve si el cambio quedó pendiente.
     */
    private void changeDayRule(UUID businessId, UUID userId, ZoneId zone, LocalTime cutoff) {
        BusinessDayService.Info info = days.info(businessId);
        var current = info.current();
        if (current.zone().equals(zone) && current.cutoff().equals(cutoff)) return;
        boolean activity = Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM sale WHERE business_id = :b) OR EXISTS (SELECT 1 FROM shift WHERE business_id = :b)
                            OR EXISTS (SELECT 1 FROM expense WHERE business_id = :b) OR EXISTS (SELECT 1 FROM cash_movement WHERE business_id = :b)
                        """).param("b", businessId).query(Boolean.class).single());
        LocalDate from;
        if (!activity) {
            jdbc.sql("DELETE FROM business_day_rule WHERE business_id = :b").param("b", businessId).update();
            from = BusinessDayService.SINCE_FOREVER;
        } else {
            from = info.dateOf(clock.instant()).plusDays(1);
            // Una regla pendiente anterior (aún no vigente) se reemplaza por esta.
            jdbc.sql("DELETE FROM business_day_rule WHERE business_id = :b AND effective_from >= :f").param("b", businessId).param("f", from).update();
        }
        jdbc.sql("INSERT INTO business_day_rule (business_id, effective_from, timezone, day_cutoff, created_by) VALUES (:b, :f, :tz, :cut, :u)")
                .param("b", businessId).param("f", from).param("tz", zone.getId()).param("cut", cutoff).param("u", userId, java.sql.Types.OTHER).update();
    }

    /** Apagar «Cobro en caja» (ADR 0015): las cuentas por cobrar no se quedan invisibles; se piden confirmar y se anulan con auditoría. */
    private void discardPendingRegisterTickets(UUID businessId, UUID memberId, UUID userId, boolean confirmed) {
        record P(UUID id, long total, boolean locked) {}
        Timestamp now = Timestamp.from(clock.instant());
        List<P> pending = jdbc.sql("""
                SELECT id, total_minor, (locked_until IS NOT NULL AND locked_until > :now) AS locked FROM sale
                WHERE business_id = :b AND status = 'PARKED' AND sent_to_register_at IS NOT NULL ORDER BY id FOR UPDATE
                """).param("b", businessId).param("now", now)
                .query((rs, i) -> new P(rs.getObject("id", UUID.class), rs.getLong("total_minor"), rs.getBoolean("locked"))).list();
        if (pending.isEmpty()) return;
        if (!confirmed) {
            throw ApiException.conflict("REGISTER_QUEUE_NOT_EMPTY", "There are tickets pending at the register").with("count", pending.size())
                    .with("totalMinor", pending.stream().mapToLong(P::total).sum());
        }
        if (pending.stream().anyMatch(P::locked)) {
            throw ApiException.conflict("REGISTER_QUEUE_BUSY", "Someone is charging a ticket right now; try again in a moment");
        }
        for (P t : pending) {
            jdbc.sql("""
                    UPDATE sale SET status = 'CANCELLED', cancelled_by_member_id = :m, cancelled_at = :now, cancel_reason = :r, locked_by_device_id = NULL,
                           locked_until = NULL, locked_by_member_id = NULL, updated_at = :now, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b
                    """).param("m", memberId).param("now", now).param("r", "Cobro en caja desactivado").param("id", t.id()).param("b", businessId).update();
            audit.log(businessId, memberId, userId, null, "sale.register_cancel", "sale", t.id(), "PARKED: Cobro en caja desactivado");
        }
        push.requestSync(businessId);
    }

    /** Actualización parcial: solo cambia lo que llega. Los módulos se mezclan con los actuales. */
    @Transactional
    public BusinessView update(UUID businessId, UUID memberId, UUID userId, UpdateBusiness req) {
        push.requestSync(businessId);  // los demás teléfonos se ponen al día al instante (solo si se confirma)
        BusinessView current = get(businessId);
        List<String> sets = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        set(sets, params, "name", req.name() == null ? null : req.name().trim());
        set(sets, params, "type", req.type());
        if (req.currency() != null && !req.currency().equalsIgnoreCase(current.currency())) {
            if (!req.currency().trim().matches("[A-Za-z]{3}")) throw ApiException.badRequest("INVALID_CURRENCY", "Currency must be a 3-letter code");
            // Cambiar la moneda con ventas registradas cambiaría el valor de todo lo anterior: solo antes de la primera actividad.
            if (current.currencyLocked()) throw ApiException.conflict("CURRENCY_LOCKED", "The currency cannot change once there is activity");
            set(sets, params, "currency", req.currency().trim().toUpperCase());
        }
        if (req.country() != null) {
            if (!req.country().trim().matches("[A-Za-z]{2}")) throw ApiException.badRequest("INVALID_COUNTRY", "Country must be a 2-letter code");
            set(sets, params, "country", req.country().trim().toUpperCase());
        }
        if (req.timezone() != null) {
            try {
                ZoneId.of(req.timezone());
            } catch (Exception e) {
                throw ApiException.badRequest("INVALID_TIMEZONE", "Invalid timezone");
            }
            set(sets, params, "timezone", req.timezone());
        }
        set(sets, params, "default_locale", req.defaultLocale());
        if (req.dayCutoff() != null) {
            try {
                set(sets, params, "day_cutoff", LocalTime.parse(req.dayCutoff()));
            } catch (Exception e) {
                throw ApiException.badRequest("INVALID_DAY_CUTOFF", "Invalid time");
            }
        }
        if (req.modules() != null) {
            Map<String, Boolean> merged = new LinkedHashMap<>(current.modules());
            req.modules().forEach((k, v) -> {
                if (!MODULE_KEYS.contains(k)) throw ApiException.badRequest("INVALID_MODULE", "Unknown module " + k);
                merged.put(k, v);
            });
            set(sets, params, "modules", json.write(merged));
            // El interruptor de inventario del negocio y su modo van siempre juntos.
            set(sets, params, "inventory_mode", Boolean.TRUE.equals(merged.get("inventory")) ? "PER_PRODUCT" : "OFF");
        }
        if (req.posViews() != null) {
            if (req.posViews().isEmpty() || !POS_VIEWS.containsAll(req.posViews())) {
                throw ApiException.badRequest("INVALID_POS_VIEWS", "Invalid cash register views");
            }
            set(sets, params, "pos_views", json.write(req.posViews()));
        }
        set(sets, params, "credit_requires_customer", req.creditRequiresCustomer());
        set(sets, params, "credit_default_due_days", req.creditDefaultDueDays());
        set(sets, params, "credit_overdue_days", req.creditOverdueDays());
        set(sets, params, "credit_limit_enforced", req.creditLimitEnforced());
        set(sets, params, "shift_required", req.shiftRequired());
        set(sets, params, "shift_note_threshold_minor", req.shiftNoteThresholdMinor());
        if (Boolean.FALSE.equals(req.registerCheckout()) && current.registerCheckout()) {
            discardPendingRegisterTickets(businessId, memberId, userId, Boolean.TRUE.equals(req.confirmDiscardPending()));
        }
        set(sets, params, "register_checkout", req.registerCheckout());
        if (Boolean.TRUE.equals(req.clearCreditDefaultDueDays()) && req.creditDefaultDueDays() == null) sets.add("credit_default_due_days = NULL");
        if (Boolean.TRUE.equals(req.clearShiftNoteThreshold()) && req.shiftNoteThresholdMinor() == null) sets.add("shift_note_threshold_minor = NULL");

        if (params.containsKey("timezone") || params.containsKey("day_cutoff")) {
            ZoneId zone = ZoneId.of(params.containsKey("timezone") ? (String) params.get("timezone") : current.timezone());
            LocalTime cutoff = params.containsKey("day_cutoff") ? (LocalTime) params.get("day_cutoff") : LocalTime.parse(current.dayCutoff());
            changeDayRule(businessId, userId, zone, cutoff);
        }
        if (!sets.isEmpty()) {
            sets.add("rev = nextval('change_rev_seq')");
            var stmt = jdbc.sql("UPDATE business SET " + String.join(", ", sets) + " WHERE id = :id").param("id", businessId);
            for (var e : params.entrySet()) stmt = stmt.param(e.getKey(), e.getValue());
            stmt.update();
            audit.log(businessId, memberId, userId, null, "business.update", "business", businessId, String.join(",", params.keySet()));
        }
        return get(businessId);
    }

    /** Eliminación diferida: se marca y el borrado definitivo ocurre a los 30 días (fase 9). */
    @Transactional
    public void requestDeletion(UUID businessId, UUID memberId, UUID userId) {
        jdbc.sql("UPDATE business SET status = 'DELETING', deletion_requested_at = :now, rev = nextval('change_rev_seq') WHERE id = :id")
                .param("now", Timestamp.from(clock.instant())).param("id", businessId).update();
        audit.log(businessId, memberId, userId, null, "business.delete_requested", "business", businessId, null);
    }

    private static void set(List<String> sets, Map<String, Object> params, String column, Object value) {
        if (value == null) return;
        sets.add(column + " = :" + column);
        params.put(column, value);
    }
}
