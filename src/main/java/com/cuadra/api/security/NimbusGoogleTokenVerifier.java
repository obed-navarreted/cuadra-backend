package com.cuadra.api.security;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.config.CuadraProperties;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/** Firma con las claves públicas de Google (JWKS en caché), `iss`, `aud`, `exp` y `email_verified`. */
@Component
public class NimbusGoogleTokenVerifier implements GoogleTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(NimbusGoogleTokenVerifier.class);
    private static final String JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final JwtDecoder decoder;

    public NimbusGoogleTokenVerifier(CuadraProperties props) {
        List<String> audiences = props.google().clientIds();
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(JWKS).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            String iss = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
            if (iss == null || !ISSUERS.contains(iss)) return failure("issuer");
            if (audiences.isEmpty() || jwt.getAudience().stream().noneMatch(audiences::contains)) return failure("audience");
            if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) return failure("email_verified");
            return OAuth2TokenValidatorResult.success();
        };
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), claims));
        this.decoder = nimbus;
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        try {
            Jwt jwt = decoder.decode(idToken);
            return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"), true,
                    jwt.getClaimAsString("name"), jwt.getClaimAsString("picture"));
        } catch (JwtException e) {
            log.debug("ID token de Google rechazado: {}", e.getMessage());
            throw ApiException.unauthorized("INVALID_GOOGLE_TOKEN", "Google token rejected");
        }
    }

    private static OAuth2TokenValidatorResult failure(String what) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid " + what, null));
    }
}
