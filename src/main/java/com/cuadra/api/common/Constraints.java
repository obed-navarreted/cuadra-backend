package com.cuadra.api.common;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;

/** Qué restricción única se violó. Con el aislamiento por negocio (RLS) un id ajeno es invisible: solo la llave primaria lo delata. */
public final class Constraints {
    private static final Pattern UNIQUE = Pattern.compile("violates unique constraint \"([^\"]+)\"");

    private Constraints() {}

    public static Optional<String> name(DuplicateKeyException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() == null) continue;
            Matcher m = UNIQUE.matcher(t.getMessage());
            if (m.find()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    public static boolean isPrimaryKey(DuplicateKeyException e) {
        return name(e).map(n -> n.endsWith("_pkey")).orElse(false);
    }
}
