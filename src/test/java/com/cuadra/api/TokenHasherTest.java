package com.cuadra.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.cuadra.api.security.TokenHasher;
import org.junit.jupiter.api.Test;

class TokenHasherTest {

    @Test
    void tokensAreLongAndUnique() {
        String a = TokenHasher.newToken();
        assertThat(a).hasSizeGreaterThanOrEqualTo(43).isNotEqualTo(TokenHasher.newToken());
    }

    @Test
    void hashIsStableSha256Hex() {
        assertThat(TokenHasher.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void codesAvoidAmbiguousCharacters() {
        for (int i = 0; i < 200; i++) assertThat(TokenHasher.newCode(8)).hasSize(8).doesNotContainAnyWhitespaces().matches("[A-HJKMNP-Z2-9]{8}");
    }
}
