package com.cuadra.api.business;

import java.util.Map;

/** El país sugiere moneda, zona horaria e idioma (editables después). */
public final class CountryDefaults {
    public record Defaults(String currency, String timezone, String locale) {}

    private static final Map<String, Defaults> BY_COUNTRY = Map.ofEntries(
            Map.entry("NI", new Defaults("NIO", "America/Managua", "es")),
            Map.entry("HN", new Defaults("HNL", "America/Tegucigalpa", "es")),
            Map.entry("GT", new Defaults("GTQ", "America/Guatemala", "es")),
            Map.entry("SV", new Defaults("USD", "America/El_Salvador", "es")),
            Map.entry("CR", new Defaults("CRC", "America/Costa_Rica", "es")),
            Map.entry("PA", new Defaults("USD", "America/Panama", "es")),
            Map.entry("MX", new Defaults("MXN", "America/Mexico_City", "es")),
            Map.entry("CO", new Defaults("COP", "America/Bogota", "es")),
            Map.entry("PE", new Defaults("PEN", "America/Lima", "es")),
            Map.entry("EC", new Defaults("USD", "America/Guayaquil", "es")),
            Map.entry("CL", new Defaults("CLP", "America/Santiago", "es")),
            Map.entry("AR", new Defaults("ARS", "America/Argentina/Buenos_Aires", "es")),
            Map.entry("DO", new Defaults("DOP", "America/Santo_Domingo", "es")),
            Map.entry("ES", new Defaults("EUR", "Europe/Madrid", "es")),
            Map.entry("US", new Defaults("USD", "America/New_York", "en")));

    private CountryDefaults() {}

    public static Defaults of(String country) {
        return BY_COUNTRY.getOrDefault(country.toUpperCase(), new Defaults("USD", "UTC", "en"));
    }
}
