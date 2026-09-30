package com.cuadra.api.business;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Borrado definitivo de los negocios que su dueño eliminó: pasados {@link #GRACE} desde que se pidió, se borran TODAS sus filas (en el orden que exigen
 * las llaves foráneas, calculado del catálogo de la base para que una tabla nueva no se quede atrás), cada negocio en su propia transacción, y queda
 * constancia en `platform_audit_log` (actor = el sistema, uuid cero). La cuenta de Google del dueño no se borra (puede tener otros negocios).
 */
@Service
public class BusinessPurgeService {
    private static final Logger log = LoggerFactory.getLogger(BusinessPurgeService.class);
    public static final Duration GRACE = Duration.ofDays(30);
    public static final UUID SYSTEM_ACTOR = new UUID(0, 0);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public BusinessPurgeService(JdbcClient jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
    }

    /** Borra los negocios en eliminación desde hace más de 30 días a la hora `now` (la da quien llama: el trabajo programado o una prueba). */
    public List<UUID> purgeDue(Instant now) {
        List<UUID> due = jdbc.sql("SELECT id FROM business WHERE status = 'DELETING' AND deletion_requested_at IS NOT NULL AND deletion_requested_at <= :cut ORDER BY deletion_requested_at")
                .param("cut", Timestamp.from(now.minus(GRACE))).query(UUID.class).list();
        List<UUID> done = new ArrayList<>();
        for (UUID b : due) {
            try {
                tx.executeWithoutResult(s -> purge(b));
                done.add(b);
            } catch (RuntimeException e) {
                log.error("No se pudo borrar el negocio {}", b, e);
            }
        }
        return done;
    }

    private record Fk(String child, String column, String parent, String parentColumn) {}

    private void purge(UUID businessId) {
        // Se vuelve a comprobar dentro de la transacción (alguien pudo cancelar la eliminación).
        String status = jdbc.sql("SELECT status FROM business WHERE id = :b FOR UPDATE").param("b", businessId).query(String.class).optional().orElse(null);
        if (!"DELETING".equals(status)) return;
        String name = jdbc.sql("SELECT name FROM business WHERE id = :b").param("b", businessId).query(String.class).single();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : deleteOrder()) {
            String where = predicate(table);
            if (where == null) continue;
            long n = jdbc.sql("DELETE FROM " + table + " WHERE " + where).param("b", businessId).update();
            if (n > 0) counts.put(table, n);
        }
        jdbc.sql("INSERT INTO platform_audit_log (actor_user_id, action, target, payload) VALUES (:a, 'business.purged', :t, :p)")
                .param("a", SYSTEM_ACTOR).param("t", businessId.toString()).param("p", "name=" + name + " rows=" + counts).update();
    }

    private Set<String> scoped;
    private Map<String, List<Fk>> fksByChild;

    /** Tablas con filas del negocio (con `business_id`, la tabla `business`, o que cuelgan por llave foránea de una de ellas), en orden hijos → padres. */
    synchronized List<String> deleteOrder() {
        Set<String> withBusinessId = new LinkedHashSet<>(jdbc.sql("""
                        SELECT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'business_id'
                        """).query(String.class).list());
        List<Fk> fks = jdbc.sql("""
                        SELECT c.conrelid::regclass::text AS child, a.attname AS col, c.confrelid::regclass::text AS parent, af.attname AS pcol
                          FROM pg_constraint c
                          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                          JOIN pg_attribute af ON af.attrelid = c.confrelid AND af.attnum = c.confkey[1]
                         WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
                        """).query((rs, n) -> new Fk(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        Map<String, List<Fk>> byChild = new HashMap<>();
        for (Fk f : fks) byChild.computeIfAbsent(f.child(), k -> new ArrayList<>()).add(f);
        Set<String> sc = new LinkedHashSet<>(withBusinessId);
        sc.add("business");
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Fk f : fks) if (sc.contains(f.parent()) && !sc.contains(f.child())) { sc.add(f.child()); grew = true; }
        }
        // Orden: una tabla se borra cuando ya se borraron todas las que la referencian (Kahn sobre el grafo hijo → padre, sin autorreferencias).
        Map<String, Set<String>> referencedBy = new HashMap<>();
        for (String t : sc) referencedBy.put(t, new HashSet<>());
        for (Fk f : fks) if (sc.contains(f.child()) && sc.contains(f.parent()) && !f.child().equals(f.parent())) referencedBy.get(f.parent()).add(f.child());
        List<String> order = new ArrayList<>();
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (String t : sc) if (referencedBy.get(t).isEmpty()) ready.add(t);
        Set<String> placed = new HashSet<>();
        while (!ready.isEmpty()) {
            String t = ready.poll();
            if (!placed.add(t)) continue;
            order.add(t);
            for (Fk f : byChild.getOrDefault(t, List.of())) {
                if (!sc.contains(f.parent()) || f.parent().equals(t)) continue;
                Set<String> refs = referencedBy.get(f.parent());
                refs.remove(t);
                if (refs.isEmpty() && !placed.contains(f.parent())) ready.add(f.parent());
            }
        }
        if (order.size() != sc.size()) throw new IllegalStateException("Ciclo de llaves foráneas entre tablas del negocio: " + sc);
        this.scoped = sc;
        this.fksByChild = byChild;
        this.withBusinessIdCache = withBusinessId;
        return order;
    }

    private Set<String> withBusinessIdCache = Set.of();

    /** Filas de una tabla que pertenecen al negocio `:b`. */
    private String predicate(String table) {
        if (table.equals("business")) return "id = :b";
        if (withBusinessIdCache.contains(table)) return "business_id = :b";
        List<String> ors = new ArrayList<>();
        for (Fk f : fksByChild.getOrDefault(table, List.of())) {
            if (!scoped.contains(f.parent()) || f.parent().equals(table)) continue;
            String parentWhere = f.parent().equals("business") ? "id = :b" : withBusinessIdCache.contains(f.parent()) ? "business_id = :b" : null;
            if (parentWhere == null) continue;   // cadenas más largas no existen hoy; si aparecen, la llave foránea lo hará notar
            ors.add(f.column() + " IN (SELECT " + f.parentColumn() + " FROM " + f.parent() + " WHERE " + parentWhere + ")");
        }
        return ors.isEmpty() ? null : String.join(" OR ", ors);
    }
}
