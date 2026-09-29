package com.cuadra.api.report;

import java.util.List;

/** CSV según RFC 4180 (comillas, comas y saltos de línea dentro de una celda). Con BOM: Excel abre bien los acentos. */
final class Csv {
    private static final String BOM = "﻿";

    private Csv() {}

    static String of(List<String> header, List<List<String>> rows) {
        StringBuilder out = new StringBuilder(BOM);
        line(out, header);
        for (List<String> r : rows) line(out, r);
        return out.toString();
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
    }

    static String cell(String value) {
        if (value == null) return "";
        // Una celda que empieza con = + - @ se abriría como fórmula en una hoja de cálculo: se neutraliza.
        String v = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 && !value.matches("-?\\d+([.,]\\d+)?") ? "'" + value : value;
        boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static final java.time.format.DateTimeFormatter STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Fecha y hora locales del negocio con segundos siempre (LocalDateTime.toString() los omite cuando son cero). */
    static String dateTime(java.time.Instant at, java.time.ZoneId zone) {
        return at == null ? "" : STAMP.format(at.atZone(zone));
    }

    /** Monto en unidad menor → "342.50" (según los decimales de la moneda). */
    static String money(long minor, int decimals) {
        return new java.math.BigDecimal(minor).movePointLeft(decimals).setScale(decimals).toPlainString();
    }
}
