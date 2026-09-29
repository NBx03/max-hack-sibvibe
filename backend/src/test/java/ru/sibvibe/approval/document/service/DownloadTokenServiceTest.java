package ru.sibvibe.approval.document.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.sibvibe.approval.common.api.NotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloadTokenServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-09-20T05:00:00Z");

    @Test
    void signsFileUserAndExpiry() {
        DownloadTokenService service = service(NOW);

        DownloadTokenService.DownloadLink link = service.issue(41, 7);
        String token = link.url().substring(link.url().indexOf("token=") + 6);

        assertThat(link.url()).startsWith("https://app.example/api/v1/files/41/content?token=");
        assertThat(link.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(service.verify(41, token).userId()).isEqualTo(7);
        assertThatThrownBy(() -> service.verify(42, token)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void rejectsExpiredAndTamperedTokens() {
        DownloadTokenService issuer = service(NOW);
        String token = issuer.issue(41, 7).url().split("token=", 2)[1];

        assertThatThrownBy(() -> service(NOW.plusSeconds(900)).verify(41, token))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> issuer.verify(41, token.substring(0, token.length() - 1) + "A"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> issuer.verify(41, "7.0.invalid"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void differentSecretCannotVerifyToken() {
        String token = service(NOW).issue(41, 7).url().split("token=", 2)[1];
        DownloadTokenService other = new DownloadTokenService(
                "abcdef0123456789abcdef0123456789", 900, "", Clock.fixed(NOW, ZoneOffset.UTC),
                new MockEnvironment().withProperty("spring.profiles.active", "dev"));

        assertThatThrownBy(() -> other.verify(41, token)).isInstanceOf(NotFoundException.class);
    }

    private DownloadTokenService service(Instant now) {
        return new DownloadTokenService(
                SECRET, 900, "https://app.example/", Clock.fixed(now, ZoneOffset.UTC),
                new MockEnvironment().withProperty("spring.profiles.active", "dev"));
    }
}
