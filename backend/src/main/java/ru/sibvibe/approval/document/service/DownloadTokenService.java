package ru.sibvibe.approval.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import ru.sibvibe.approval.common.api.NotFoundException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

@Service
public class DownloadTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadTokenService.class);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;
    private final String miniAppUrl;
    private final Clock clock;

    public DownloadTokenService(
            @Value("${app.download.secret:}") String configuredSecret,
            @Value("${app.download.ttl-seconds:900}") long ttlSeconds,
            @Value("${max.mini-app.url:}") String miniAppUrl,
            Clock clock,
            Environment environment
    ) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("app.download.ttl-seconds должен быть положительным");
        }
        if (configuredSecret.isBlank()) {
            this.secret = new byte[32];
            new SecureRandom().nextBytes(this.secret);
            LOGGER.warn("DOWNLOAD_LINK_SECRET не задан: создан случайный ключ до перезапуска приложения");
        } else {
            if (configuredSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("DOWNLOAD_LINK_SECRET должен содержать не менее 32 байт");
            }
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.ttlSeconds = ttlSeconds;
        this.miniAppUrl = stripTrailingSlash(miniAppUrl);
        if (this.miniAppUrl.isBlank() && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            LOGGER.warn("MAX_MINI_APP_URL не задан: ссылки скачивания будут относительными");
        }
        this.clock = clock;
    }

    public DownloadLink issue(long fileId, long userId) {
        Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);
        String payload = userId + "." + expiresAt.getEpochSecond();
        String signature = ENCODER.encodeToString(sign(fileId, payload));
        String token = payload + "." + signature;
        String path = "/api/v1/files/" + fileId + "/content?token=" + token;
        return new DownloadLink(miniAppUrl + path, expiresAt);
    }

    public VerifiedToken verify(long fileId, String token) {
        if (token == null) {
            throw new NotFoundException();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new NotFoundException();
        }
        try {
            long userId = Long.parseLong(parts[0]);
            long expires = Long.parseLong(parts[1]);
            if (userId <= 0 || expires <= clock.instant().getEpochSecond()) {
                throw new NotFoundException();
            }
            byte[] supplied = DECODER.decode(parts[2]);
            byte[] expected = sign(fileId, parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, supplied)) {
                throw new NotFoundException();
            }
            return new VerifiedToken(userId, Instant.ofEpochSecond(expires));
        } catch (IllegalArgumentException exception) {
            throw new NotFoundException();
        }
    }

    private byte[] sign(long fileId, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal((fileId + "\n" + payload).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 недоступен", exception);
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public record DownloadLink(String url, Instant expiresAt) {}

    public record VerifiedToken(long userId, Instant expiresAt) {}
}
