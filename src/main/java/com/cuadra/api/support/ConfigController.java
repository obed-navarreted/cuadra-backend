package com.cuadra.api.support;

import com.cuadra.api.config.CuadraProperties;
import com.cuadra.api.platform.AnnouncementService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configuración remota pública: versión mínima, enlace de "Invítame un café" y su modo por canal (sección 8.3 del plan). */
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

    public record PublicConfig(String minAppVersion, String donationUrl, String donationMode, String supportEmail,
                              String recommendedAppVersion, AnnouncementService.AnnouncementBanner announcement) {}

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
                remote.getOrDefault("donation_url", props.app().donationUrl()),
                remote.getOrDefault("donation_mode", props.app().donationMode()),
                props.support().inboxEmail(),
                remote.get("recommended_app_version"),
                announcements.activeBanner().orElse(null));
    }
}
