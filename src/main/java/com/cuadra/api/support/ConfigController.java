package com.cuadra.api.support;

import com.cuadra.api.config.CuadraProperties;
import com.cuadra.api.platform.AnnouncementService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configuración remota pública: versión mínima y contacto de soporte/apoyo (correo y WhatsApp, editables sin lanzar versión). */
@RestController
@RequestMapping("/api/config")
public class ConfigController {
    private final JdbcClient jdbc;
    private final CuadraProperties props;
    private final AnnouncementService announcements;

    public ConfigController(JdbcClient jdbc, CuadraProperties props, AnnouncementService announcements) {
        this.jdbc = jdbc;
        this.props = props;
        this.announcements = announcements;
    }

    /** `panelUrl`: el panel web (la consola de la plataforma vive en `panelUrl + "/console"`; la app la abre en el navegador). */
    public record PublicConfig(String minAppVersion, String supportEmail, String supportWhatsapp,
                              String recommendedAppVersion, AnnouncementService.AnnouncementBanner announcement, String panelUrl) {}

    /** Un país con la moneda, zona horaria e idioma que sugiere al crear un negocio (todo editable). */
    public record Country(String code, String currency, String timezone, String locale) {}

    @GetMapping("/countries")
    public java.util.List<Country> countries() {
        return com.cuadra.api.business.CountryDefaults.all().entrySet().stream()
                .map(e -> new Country(e.getKey(), e.getValue().currency(), e.getValue().timezone(), e.getValue().locale())).toList();
    }

    @GetMapping
    public PublicConfig get() {
        // Los valores de la tabla remote_config (editables desde la consola) mandan sobre los de entorno.
        Map<String, String> remote = new HashMap<>();
        jdbc.sql("SELECT key, value FROM remote_config").query((rs, n) -> {
            remote.put(rs.getString("key"), rs.getString("value"));
            return null;
        }).list();
        return new PublicConfig(
                remote.getOrDefault("min_app_version", props.app().minAppVersion()),
                remote.getOrDefault("support_email", props.support().inboxEmail()),
                remote.getOrDefault("support_whatsapp", props.support().whatsapp()),
                remote.get("recommended_app_version"),
                announcements.activeBanner().orElse(null),
                props.app().baseUrl() == null ? null : props.app().baseUrl().replaceAll("/+$", ""));
    }
}
