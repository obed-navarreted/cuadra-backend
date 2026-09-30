package com.cuadra.api.common;

import org.springframework.http.HttpStatus;

/** Error de negocio con un código estable que el cliente traduce a su idioma. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    /** Datos extra para que el cliente arme un mensaje claro (p. ej. límite y saldo). Salen como propiedades del Problem Details y en el resultado de sincronizar. */
    private final java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public java.util.Map<String, Object> details() { return details; }

    public ApiException with(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    public static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }
    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }
    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    public static ApiException tooMany(String code, String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }
}
