package ru.sibvibe.approval.organization.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InviteSecretsTest {

    private final InviteSecrets secrets = new InviteSecrets(8, 24);

    @Test
    void codeHasEightCharactersWithoutAmbiguousLetters() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String code = secrets.newCode();
            assertThat(code).hasSize(8);
            assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}");
            assertThat(code).doesNotContain("0", "1", "O", "I", "L");
            seen.add(code);
        }
        assertThat(seen).hasSize(2_000);
    }

    @Test
    void tokenIsAtLeast128BitsAndSafeForStartAppPayload() {
        String token = secrets.newToken();

        assertThat(token).matches("[A-Za-z0-9_-]+");
        assertThat(Base64.getUrlDecoder().decode(token).length * 8).isGreaterThanOrEqualTo(128);
        // payload startapp — не длиннее 512 символов вместе с префиксом «p_»
        assertThat(token.length() + 2).isLessThanOrEqualTo(512);
        assertThat(secrets.newToken()).isNotEqualTo(token);
    }

    @Test
    void tokenShorterThan128BitsIsRefusedAtStartup() {
        assertThatThrownBy(() -> new InviteSecrets(8, 15)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enteredCodeIsNormalizedButAmbiguousOrWrongLengthIsRejected() {
        assertThat(secrets.normalizeCode("  abcd2345 ")).isEqualTo("ABCD2345");
        assertThat(secrets.normalizeCode("ABCD234")).isNull();
        assertThat(secrets.normalizeCode("ABCD23456")).isNull();
        assertThat(secrets.normalizeCode("ABCD23O5")).isNull();
        assertThat(secrets.normalizeCode("ABCD2315")).isNull();
        assertThat(secrets.normalizeCode(null)).isNull();
        assertThat(secrets.normalizeCode("")).isNull();
    }
}
