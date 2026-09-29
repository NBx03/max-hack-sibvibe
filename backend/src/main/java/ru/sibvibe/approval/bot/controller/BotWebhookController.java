package ru.sibvibe.approval.bot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.bot.service.BotUpdateHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Входящие события MAX Bot API в режиме webhook ({@code max.bot.update-mode=webhook}).
 * Вызывает сервер MAX, а не фронтенд, поэтому эндпоинт открыт в {@code SecurityConfiguration} без
 * {@code initData}; подлинность запроса проверяется секретом подписки {@code X-Max-Bot-Api-Secret}
 * ({@code SubscriptionRequestBody.secret} в схеме https://github.com/max-messenger/api-schema).
 *
 * Отвечает 200 всегда, кроме неверного секрета: MAX повторяет недоставленные обновления, и ошибка обработки
 * одного события не должна вызывать повторы.
 *
 * Контроллер создаётся только при {@code update-mode=webhook} и всегда требует непустой
 * {@code max.bot.webhook-secret}. Без проверки секрета можно было бы прислать поддельный {@code bot_started}
 * с чужим {@code user_id}, и бот писал бы произвольным людям. Секреты сравниваются через
 * {@link MessageDigest#isEqual}, чтобы не раскрывать совпавший префикс по времени ответа.
 */
@RestController
@RequestMapping("/api/v1/bot")
@ConditionalOnProperty(prefix = "max.bot", name = "update-mode", havingValue = "webhook")
public class BotWebhookController {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotWebhookController.class);
    private static final String SECRET_HEADER = "X-Max-Bot-Api-Secret";

    private final BotUpdateHandler handler;
    private final String expectedSecret;

    public BotWebhookController(
            BotUpdateHandler handler, @Value("${max.bot.webhook-secret:}") String expectedSecret
    ) {
        this.handler = handler;
        this.expectedSecret = expectedSecret;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestBody(required = false) JsonNode update
    ) {
        // Пустой ожидаемый секрет - это не "проверка отключена", а неверная конфигурация: отказываем
        // всем, а не пропускаем всех (fail closed). BotWebhookSubscriber с тем же условием не подписывает
        // бота вовсе, так что штатно секрет пустым в этом режиме быть не должно.
        if (expectedSecret.isBlank() || !secretMatches(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // Тип события без содержимого: по логу видно, доходят ли сообщения боту.
        // Вид параметра ссылки (c_/p_/d_/другой, без значения — токен личной ссылки секретный): по логам стенда видно,
        // доходит ли startapp-параметр до bot_started.
        LOGGER.debug("Webhook бота: событие {}, параметр {}", update == null ? "без тела" : update.path("update_type").asText("?"),
                update == null ? "нет" : payloadKind(update.path("payload").asText("")));
        try {
            handler.handle(update);
        } catch (RuntimeException exception) {
            LOGGER.warn("Ошибка обработки webhook-события бота", exception);
        }
        return ResponseEntity.ok().build();
    }

    private boolean secretMatches(String received) {
        if (received == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedSecret.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }

    static String payloadKind(String payload) {
        if (payload == null || payload.isEmpty()) {
            return "нет";
        }
        String prefix = payload.length() >= 2 ? payload.substring(0, 2) : payload;
        return switch (prefix) {
            case "c_", "p_", "d_" -> prefix;
            default -> "другой";
        };
    }
}
