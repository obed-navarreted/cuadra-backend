package com.cuadra.api.push;

import com.cuadra.api.notification.PushSender;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Avisos al instante por Firebase (mensajes de DATOS, sin texto visible):
 * - `{type: "SYNC", businessId}`: «sincroniza ya». Cuando cambian promociones, productos y precios, cuentas por cobrar en caja, el equipo o los ajustes del
 *   negocio. Se junta por negocio: varios cambios seguidos dentro de `cuadra.push.sync-debounce-ms` (3 s) salen como UN solo aviso.
 * - `{type: "NOTIFY", businessId, notificationId, notificationType}`: hay un aviso nuevo en la bandeja de esa persona o ese teléfono. La app sincroniza y lo
 *   muestra como notificación del sistema (en su idioma, una sola vez; las preferencias y las horas de silencio ya las aplicó el servidor al crearlo).
 * Todo sale DESPUÉS de confirmar la transacción (el teléfono que sincroniza ya ve el cambio) y en otro hilo (nunca retrasa la petición). Un token que Firebase
 * da por desinstalado (UNREGISTERED) se borra. Sin cuenta de servicio no se envía nada: los teléfonos se ponen al día con la sincronización frecuente.
 */
@Component
@Primary
public class PushDispatcher implements PushSender {
    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final JdbcClient jdbc;
    private final FcmTransport fcm;
    private final long debounceMillis;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fcm-push");
        t.setDaemon(true);
        return t;
    });
    /** Negocios con un «sincroniza ya» ya programado (los cambios que llegan mientras tanto viajan en ese mismo aviso). */
    private final Set<UUID> pendingSync = ConcurrentHashMap.newKeySet();

    public PushDispatcher(JdbcClient jdbc, FcmTransport fcm, @Value("${cuadra.push.sync-debounce-ms:3000}") long debounceMillis) {
        this.jdbc = jdbc;
        this.fcm = fcm;
        this.debounceMillis = debounceMillis;
    }

    @PreDestroy
    void stop() { executor.shutdownNow(); }

    /** Pide un «sincroniza ya» para todos los teléfonos del negocio (juntado por negocio). No hace nada sin Firebase. */
    public void requestSync(UUID businessId) {
        if (businessId == null || !fcm.enabled()) return;
        afterCommit(() -> {
            if (pendingSync.add(businessId)) executor.schedule(() -> flushSync(businessId), debounceMillis, TimeUnit.MILLISECONDS);
        });
    }

    void flushSync(UUID businessId) {
        pendingSync.remove(businessId);
        try {
            List<String[]> tokens = jdbc.sql("SELECT id::text, fcm_token FROM push_token WHERE business_id = :b").param("b", businessId)
                    .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
            Map<String, String> data = Map.of("type", "SYNC", "businessId", businessId.toString());
            for (String[] t : tokens) deliver(t[0], t[1], data);
        } catch (RuntimeException e) {
            log.warn("Firebase: no se pudo enviar el aviso de sincronizar ({})", e.getClass().getSimpleName());
        }
    }

    /** Avisos de la bandeja: a los teléfonos de esa persona (su token) o a ese teléfono. */
    @Override
    public void send(List<Message> messages) {
        if (messages == null || messages.isEmpty() || !fcm.enabled()) return;
        List<Message> copy = List.copyOf(messages);
        afterCommit(() -> executor.execute(() -> sendNow(copy)));
    }

    private void sendNow(List<Message> messages) {
        try {
            for (Message m : messages) {
                UUID business = jdbc.sql("SELECT business_id FROM notification WHERE id = :id").param("id", m.notificationId()).query(UUID.class).optional().orElse(null);
                if (business == null) continue;
                Map<String, String> tokens = new LinkedHashMap<>();
                jdbc.sql("SELECT id::text, fcm_token FROM push_token WHERE business_id = :b AND ((CAST(:m AS uuid) IS NOT NULL AND member_id = CAST(:m AS uuid)) OR (CAST(:d AS uuid) IS NOT NULL AND device_id = CAST(:d AS uuid)))")
                        .param("b", business).param("m", m.memberId(), java.sql.Types.OTHER).param("d", m.deviceId(), java.sql.Types.OTHER)
                        .query((rs, n) -> tokens.put(rs.getString(1), rs.getString(2))).list();
                Map<String, String> data = new LinkedHashMap<>();
                data.put("type", "NOTIFY");
                data.put("businessId", business.toString());
                data.put("notificationId", m.notificationId().toString());
                data.put("notificationType", m.type());
                data.put("alert", String.valueOf(m.alert()));
                for (var t : tokens.entrySet()) {
                    FcmTransport.Outcome o = deliver(t.getKey(), t.getValue(), data);
                    jdbc.sql("INSERT INTO notification_delivery (notification_id, push_token_id, status, error) VALUES (:n, :t, :s, :e)")
                            .param("n", m.notificationId()).param("t", o == FcmTransport.Outcome.UNREGISTERED ? null : UUID.fromString(t.getKey()), java.sql.Types.OTHER)
                            .param("s", o == FcmTransport.Outcome.SENT ? "SENT" : "FAILED").param("e", o == FcmTransport.Outcome.SENT ? null : o.name()).update();
                }
            }
        } catch (RuntimeException e) {
            log.warn("Firebase: no se pudieron enviar los avisos ({}); quedan en la bandeja", e.getClass().getSimpleName());
        }
    }

    private FcmTransport.Outcome deliver(String tokenId, String token, Map<String, String> data) {
        FcmTransport.Outcome o = fcm.send(token, data);
        if (o == FcmTransport.Outcome.UNREGISTERED) {
            jdbc.sql("DELETE FROM push_token WHERE id = CAST(:id AS uuid)").param("id", tokenId).update();
            log.info("Firebase: token dado de baja (UNREGISTERED), se borró");
        }
        return o;
    }

    /** Dentro de una transacción, al confirmarla (si se deshace, no se avisa de nada); fuera de una, ya. */
    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    /** Solo pruebas: espera a que lo programado termine. */
    public void drainForTests() throws InterruptedException {
        var f = executor.schedule(() -> {}, debounceMillis + 50, TimeUnit.MILLISECONDS);
        try { f.get(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
