package com.cuadra.api.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cuadra")
public record CuadraProperties(
        Duration sessionTtlApp,
        Duration sessionTtlWeb,
        int pinBcryptStrength,
        Google google,
        Support support,
        Platform platform,
        App app) {

    public record Google(List<String> clientIds) {
        public Google {
            clientIds = clientIds == null ? List.of() : clientIds.stream().filter(s -> !s.isBlank()).toList();
        }
    }

    public record Support(String inboxEmail) {}

    /**
     * `adminUser`/`adminPasswordHash`: acceso de la consola con usuario y contraseña (además de Google). Solo el HASH bcrypt de la contraseña, y solo por
     * variable de entorno: nunca en un archivo del repositorio. Sin ambos, ese acceso no existe (404).
     */
    public record Platform(List<String> adminEmails, String adminUser, String adminPasswordHash) {
        public Platform {
            adminEmails = adminEmails == null ? List.of() : adminEmails.stream().map(String::toLowerCase).toList();
            adminUser = adminUser == null ? "" : adminUser.trim();
            adminPasswordHash = adminPasswordHash == null ? "" : adminPasswordHash.trim();
        }

        public boolean passwordLoginEnabled() { return !adminUser.isEmpty() && adminPasswordHash.startsWith("$2"); }
    }

    public record App(String baseUrl, String minAppVersion, String donationUrl, String donationMode) {}
}
