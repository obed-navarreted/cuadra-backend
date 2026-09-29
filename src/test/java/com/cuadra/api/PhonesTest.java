package com.cuadra.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Phones;
import org.junit.jupiter.api.Test;

class PhonesTest {
    @Test
    void aNationalNumberGetsTheCountryCode() {
        assertThat(Phones.normalize("8855 1234", "NI")).isEqualTo("50588551234");
        assertThat(Phones.normalize("(505) 8855-1234", "NI")).isEqualTo("50588551234");
        assertThat(Phones.normalize("5512345678", "MX")).isEqualTo("525512345678");      // 10 dígitos nacionales
        assertThat(Phones.normalize("305 555 0100", "US")).isEqualTo("13055550100");
        assertThat(Phones.normalize("13055550100", "US")).isEqualTo("13055550100");      // ya trae el 1
    }

    @Test
    void anInternationalNumberIsKept() {
        assertThat(Phones.normalize("+505 8855 1234", "NI")).isEqualTo("50588551234");
        assertThat(Phones.normalize("+1 305 555 0100", "NI")).isEqualTo("13055550100");
        assertThat(Phones.normalize("00505 8855 1234", "HN")).isEqualTo("50588551234");
        assertThat(Phones.normalize("50588551234", "NI")).isEqualTo("50588551234");   // ya trae su código: 11 dígitos
    }

    @Test
    void blankMeansNoPhone() {
        assertThat(Phones.normalize(null, "NI")).isNull();
        assertThat(Phones.normalize("   ", "NI")).isNull();
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> Phones.normalize("abc", "NI")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_PHONE");
        assertThatThrownBy(() -> Phones.normalize("123", "XX")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Phones.normalize("1".repeat(20), "NI")).isInstanceOf(ApiException.class);
    }
}
