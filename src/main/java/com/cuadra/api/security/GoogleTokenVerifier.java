package com.cuadra.api.security;

/** Verifica un ID token de Google. Es una interfaz para poder sustituirla en las pruebas. */
public interface GoogleTokenVerifier {
    /** @throws com.cuadra.api.common.ApiException 401 INVALID_GOOGLE_TOKEN si no es válido. */
    GoogleIdentity verify(String idToken);
}
