package com.cuadra.api.common;

import java.util.Map;

/**
 * Teléfonos a formato E.164 sin "+" ("50588551234"), que es lo que pide wa.me. Un número nacional recibe el código del país del negocio;
 * uno que ya trae su código (más de 9 dígitos, o con "+") se respeta.
 */
public final class Phones {
    private static final Map<String, String> CALLING_CODE = Map.ofEntries(
            Map.entry("NI", "505"), Map.entry("HN", "504"), Map.entry("GT", "502"), Map.entry("SV", "503"), Map.entry("CR", "506"),
            Map.entry("PA", "507"), Map.entry("MX", "52"), Map.entry("CO", "57"), Map.entry("PE", "51"), Map.entry("EC", "593"),
            Map.entry("CL", "56"), Map.entry("AR", "54"), Map.entry("DO", "1"), Map.entry("ES", "34"), Map.entry("US", "1"));

    /** Largo del número nacional (sin código de país). Sirve para distinguir "5512345678" (México, nacional) de "50588551234" (Nicaragua, ya con código). */
    private static final Map<String, Integer> NATIONAL_LENGTH = Map.ofEntries(
            Map.entry("NI", 8), Map.entry("HN", 8), Map.entry("GT", 8), Map.entry("SV", 8), Map.entry("CR", 8), Map.entry("PA", 8),
            Map.entry("MX", 10), Map.entry("CO", 10), Map.entry("PE", 9), Map.entry("EC", 9), Map.entry("CL", 9), Map.entry("AR", 10),
            Map.entry("DO", 10), Map.entry("ES", 9), Map.entry("US", 10));

    private Phones() {}

    /** @return el número normalizado, o null si venía vacío. @throws ApiException 400 INVALID_PHONE si no parece un teléfono. */
    public static String normalize(String raw, String country) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        boolean international = trimmed.startsWith("+") || trimmed.startsWith("00");
        String digits = trimmed.replaceAll("\\D", "");
        if (trimmed.startsWith("00")) digits = digits.substring(2);
        if (digits.isEmpty()) throw ApiException.badRequest("INVALID_PHONE", "Invalid phone number");
        String country2 = country == null ? "" : country.toUpperCase();
        String code = CALLING_CODE.get(country2);
        Integer national = NATIONAL_LENGTH.get(country2);
        if (!international && code != null && national != null) {
            boolean alreadyHasCode = digits.startsWith(code) && digits.length() >= code.length() + national;
            if (!alreadyHasCode && digits.length() <= national) digits = code + digits;
        }
        if (digits.length() < 8 || digits.length() > 15) throw ApiException.badRequest("INVALID_PHONE", "Invalid phone number");
        return digits;
    }
}
