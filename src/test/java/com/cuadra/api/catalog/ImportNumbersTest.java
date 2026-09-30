package com.cuadra.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cuadra.api.common.ApiException;
import org.junit.jupiter.api.Test;

/** Números de una hoja de cálculo: el separador decimal es el último que aparece; mejor un error que un precio mil veces más chico. */
class ImportNumbersTest {
    private static long money(String s) { return ProductImportService.money(s, 2, "INVALID_PRICE"); }

    @Test
    void theLastSeparatorIsTheDecimalOne() {
        assertEquals(100000L, money("1,000.00"));
        assertEquals(100000L, money("1.000,00"));
        assertEquals(123456789L, money("1,234,567.89"));
        assertEquals(123456789L, money("1.234.567,89"));
        assertEquals(1250L, money("12,50"));
        assertEquals(1250L, money("12.50"));
        assertEquals(1250L, money("12.5"));
        assertEquals(9000L, money("90"));
    }

    @Test
    void aSingleSeparatorFollowedByThreeDigitsIsThousandsForMoney() {
        assertEquals(100000L, money("1,000"));
        assertEquals(100000L, money("1.000"));
        assertEquals(100000000L, money("1.000.000"));
        assertEquals(1234500L, money("12.345"));   // el dinero no tiene 3 decimales: son 12 mil
        assertEquals(1000L, ProductImportService.money("1.000", 0, "INVALID_PRICE"));   // moneda sin centavos: mil
    }

    @Test
    void malformedGroupsAreRejectedNotGuessed() {
        assertThrows(ApiException.class, () -> money("1.000.00"));
        assertThrows(ApiException.class, () -> money("10,00,000"));
        assertThrows(ApiException.class, () -> money("1,2345.00"));
    }

    @Test
    void quantitiesKeepThreeDecimals() {
        assertEquals(1500L, ProductImportService.quantity("1,5", "INVALID_STOCK"));
        assertEquals(1500L, ProductImportService.quantity("1.500", "INVALID_STOCK"));
        assertEquals(1500000L, ProductImportService.quantity("1,500.000", "INVALID_STOCK"));
        assertEquals(12000L, ProductImportService.quantity("12", "INVALID_STOCK"));
    }
}
