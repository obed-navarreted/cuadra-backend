package com.cuadra.api.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Problem Details (RFC 9457) con una propiedad `code` estable en todas las respuestas de error. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException e) {
        ProblemDetail pd = problem(e.status(), e.code(), e.getMessage());
        if (e instanceof com.cuadra.api.catalog.ProductService.CodeInUse c) pd.setProperty("existingId", c.existingId());
        if (e instanceof com.cuadra.api.cash.ShiftService.DevicesPending d) pd.setProperty("devices", d.devices());
        if (e instanceof com.cuadra.api.plan.PlanLimitException l) {
            pd.setProperty("feature", l.feature().name());
            pd.setProperty("limit", l.limit());
        }
        return pd;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Datos inválidos");
        pd.setProperty("fields", fields);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "Cuerpo inválido"));
    }

    /**
     * Un id (UUID que elige el teléfono) ya existe en otro negocio: gracias al aislamiento por negocio esa fila es invisible y solo la llave
     * primaria lo delata. Se responde igual que cuando se detecta antes (`ID_TAKEN`), sin revelar nada del otro negocio.
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateKey(org.springframework.dao.DuplicateKeyException e) {
        if (Constraints.isPrimaryKey(e)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT, "ID_TAKEN", "Id already in use"));
        }
        log.error("Violación de restricción única", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error interno"));
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Error no controlado", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error interno");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return pd;
    }
}
