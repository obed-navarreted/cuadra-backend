package com.cuadra.api.push;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FCM HTTP v1 con la cuenta de servicio del proyecto de Firebase. La cuenta llega por entorno, nunca por el repositorio:
 * - `FIREBASE_SERVICE_ACCOUNT_JSON`: el JSON tal cual o en base64 (Railway);
 * - `FIREBASE_SERVICE_ACCOUNT_FILE`: ruta del archivo (desarrollo: backend/secrets/firebase-service-account.json desde backend/.env.local).
 * Sin ninguna de las dos (o con una que no se puede leer) queda APAGADO sin fallar el arranque: se registra una sola línea, sin el contenido.
 */
@Component
public class HttpFcmTransport implements FcmTransport {
    private static final Logger log = LoggerFactory.getLogger(HttpFcmTransport.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final GoogleCredentials credentials;
    private final String projectId;
    private final JsonMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HttpFcmTransport(@Value("${cuadra.firebase.service-account-json:}") String json, @Value("${cuadra.firebase.service-account-file:}") String file, JsonMapper mapper) {
        this.mapper = mapper;
        GoogleCredentials creds = null;
        String project = null;
        try {
            byte[] raw = load(json, file);
            if (raw != null) {
                JsonNode node = mapper.readTree(raw);
                project = node.hasNonNull("project_id") ? node.get("project_id").asString() : null;
                creds = GoogleCredentials.fromStream(new ByteArrayInputStream(raw)).createScoped(List.of(SCOPE));
                if (project == null) creds = null;
            }
        } catch (Exception e) {
            // Nunca se registra el contenido: solo que no se pudo leer.
            log.warn("Firebase: la cuenta de servicio no se pudo leer ({}); los avisos al instante quedan apagados", e.getClass().getSimpleName());
            creds = null;
        }
        this.credentials = creds;
        this.projectId = project;
        if (creds != null) log.info("Firebase: avisos al instante activados (proyecto {})", project);
        else log.info("Firebase: sin cuenta de servicio; los teléfonos se ponen al día con la sincronización frecuente");
    }

    /** JSON crudo o base64 (lo primero que no sea espacio es «{» en el crudo); si no, el archivo. */
    static byte[] load(String json, String file) throws java.io.IOException {
        if (json != null && !json.isBlank()) {
            String v = json.trim();
            return v.startsWith("{") ? v.getBytes(StandardCharsets.UTF_8) : Base64.getMimeDecoder().decode(v);
        }
        if (file != null && !file.isBlank()) {
            Path p = Path.of(file.trim());
            if (Files.isReadable(p)) return Files.readAllBytes(p);
            log.warn("Firebase: el archivo de la cuenta de servicio no existe o no se puede leer");
        }
        return null;
    }

    @Override
    public boolean enabled() { return credentials != null; }

    @Override
    public Outcome send(String token, Map<String, String> data) {
        if (credentials == null) return Outcome.FAILED;
        try {
            credentials.refreshIfExpired();
            String access = credentials.getAccessToken().getTokenValue();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("token", token);
            message.put("data", data);
            // Alta prioridad: el teléfono despierta y sincroniza aunque la app esté en segundo plano. Vence en 10 minutos (después ya sincronizó solo).
            message.put("android", Map.of("priority", "high", "ttl", "600s"));
            String body = mapper.writeValueAsString(Map.of("message", message));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + access).header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) return Outcome.SENT;
            return classify(res.statusCode(), res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        } catch (Exception e) {
            log.warn("Firebase: el envío falló ({})", e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    /** 404 / UNREGISTERED: la instalación ya no existe (app desinstalada, token renovado) y el token se borra. Lo demás es un fallo pasajero. */
    static Outcome classify(int status, String body) {
        String b = body == null ? "" : body;
        if (status == 404 || b.contains("UNREGISTERED")) return Outcome.UNREGISTERED;
        if (status == 400 && b.contains("INVALID_ARGUMENT") && b.toLowerCase().contains("registration token")) return Outcome.UNREGISTERED;
        log.warn("Firebase: respuesta {} al enviar", status);
        return Outcome.FAILED;
    }
}
