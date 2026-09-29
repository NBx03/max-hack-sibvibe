package ru.sibvibe.approval.organization.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/** Генерация кода компании и токена личной ссылки криптографически стойким генератором. */
@Component
public class InviteSecrets {

    /** Без двусмысленных знаков: нет 0, 1, O, I, L — код вводят руками с экрана. */
    static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int MIN_TOKEN_BYTES = 16;

    private final SecureRandom random = new SecureRandom();
    private final int codeLength;
    private final int tokenBytes;

    public InviteSecrets(
            @Value("${app.onboarding.invite-code-length:8}") int codeLength,
            @Value("${app.onboarding.personal-link-token-bytes:24}") int tokenBytes
    ) {
        if (tokenBytes < MIN_TOKEN_BYTES) {
            throw new IllegalStateException("Токен личной ссылки должен быть не короче 128 бит");
        }
        if (codeLength < 6) {
            throw new IllegalStateException("Код компании слишком короткий");
        }
        this.codeLength = codeLength;
        this.tokenBytes = tokenBytes;
    }

    public String newCode() {
        StringBuilder code = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Base64url без «=»: подходит для параметра startapp (только A-Z a-z 0-9 _). */
    public String newToken() {
        byte[] bytes = new byte[tokenBytes];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Приводит введённый код к виду хранения; {@code null}, если это не может быть кодом. */
    public String normalizeCode(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (code.length() != codeLength) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0) {
                return null;
            }
        }
        return code;
    }
}
