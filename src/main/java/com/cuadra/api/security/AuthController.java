package com.cuadra.api.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record GoogleLoginRequest(@NotBlank String idToken, @jakarta.validation.constraints.Pattern(regexp = "APP|WEB") String kind) {}

    public record LoginResponse(String token, String tokenType, Instant expiresAt, UUID userId) {}

    @PostMapping("/google")
    public LoginResponse google(@Valid @RequestBody GoogleLoginRequest body,
                                @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        AuthService.LoginResult r = auth.loginWithGoogle(body.idToken(), body.kind() == null ? "APP" : body.kind(), userAgent);
        return new LoginResponse(r.token(), "Bearer", r.expiresAt(), r.userId());
    }

    public record PlatformLoginRequest(@NotBlank @jakarta.validation.constraints.Size(max = 100) String username, @NotBlank @jakarta.validation.constraints.Size(max = 200) String password) {}

    /** Consola de plataforma con usuario y contraseña (definidos por entorno). Si no están configurados, esta ruta no existe. */
    @PostMapping("/platform")
    public LoginResponse platform(@Valid @RequestBody PlatformLoginRequest body, @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        AuthService.LoginResult r = auth.loginPlatformAdmin(body.username(), body.password(), userAgent);
        return new LoginResponse(r.token(), "Bearer", r.expiresAt(), r.userId());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Actor actor) {
        if (actor.isUser()) auth.logout(actor.sessionId());
    }
}
