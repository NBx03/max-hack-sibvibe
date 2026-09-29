package ru.sibvibe.approval.bot.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Тонкий HTTP-клиент MAX Bot API (https://github.com/max-messenger/api-schema, сверено 2026-09-22).
 * Токен передаётся заголовком {@code Authorization} без префикса «Bearer»; настраивается в
 * {@link ru.sibvibe.approval.bot.config.BotConfiguration}. Методы бросают исключение при ошибке HTTP,
 * обработку ошибки выбирает вызывающий код. Знаний о домене приложения в классе нет.
 *
 * Два {@link RestClient} с разными тайм-аутами: {@code restClient} — короткий (connect 5 с, read 10 с) для
 * {@code sendMessage}, {@code subscribe} и {@code GET /me}; {@code pollingRestClient} — длинный read timeout
 * только для {@code GET /updates}, где MAX держит соединение до 90 с. Общий клиент с длинным тайм-аутом делал
 * бы ответ пользователю зависимым от медленного Bot API.
 */
public class MaxBotApiClient {

    private final RestClient restClient;
    private final RestClient pollingRestClient;

    public MaxBotApiClient(RestClient restClient, RestClient pollingRestClient) {
        this.restClient = restClient;
        this.pollingRestClient = pollingRestClient;
    }

    /** Отправляет сообщение пользователю в личный диалог с ботом. */
    public void sendMessage(long userId, String text, List<OpenAppButtonRequest> buttons) {
        List<AttachmentRequest> attachments = buttons.isEmpty()
                ? null
                : List.of(new AttachmentRequest("inline_keyboard", new KeyboardPayload(List.of(buttons))));
        restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/messages").queryParam("user_id", userId).build())
                .body(new MessageBody(text, attachments))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Получение обновлений long polling'ом. Сервер держит соединение до {@code timeoutSeconds}
     * (не суитаблен для продакшена - документация MAX прямо об этом предупреждает, поэтому метод
     * используется только при {@code max.bot.update-mode=polling}).
     *
     * @param marker маркер предыдущей страницы; {@code null} для первого вызова
     */
    public UpdatesPage getUpdates(Long marker, int timeoutSeconds) {
        UpdatesPage page = pollingRestClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/updates")
                            .queryParam("timeout", timeoutSeconds)
                            .queryParam("types", "message_created,bot_started");
                    if (marker != null) {
                        uriBuilder.queryParam("marker", marker);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(UpdatesPage.class);
        return page == null ? new UpdatesPage(List.of(), null) : page;
    }

    /** Подписывает бота на приём событий webhook'ом (только на стенде, {@code update-mode=webhook}). */
    public void subscribe(String url, String secret) {
        restClient.post()
                .uri("/subscriptions")
                .body(new SubscribeRequest(url, List.of("message_created", "bot_started"),
                        secret == null || secret.isBlank() ? null : secret))
                .retrieve()
                .toBodilessEntity();
    }

    /** {@code GET /me} - используется только для ручной проверки токена (docs/DEVELOPMENT.md, раздел «Бот»), не в рантайме. */
    public String getMyUsername() {
        JsonNode node = restClient.get().uri("/me").retrieve().body(JsonNode.class);
        return node == null || node.get("username") == null ? null : node.get("username").asText(null);
    }

    /** Страница обновлений {@code GET /updates}: {@code updates} - как есть, разбор - в {@code bot.service}. */
    public record UpdatesPage(List<JsonNode> updates, Long marker) {
        public UpdatesPage {
            updates = updates == null ? List.of() : List.copyOf(updates);
        }
    }

    record MessageBody(String text, List<AttachmentRequest> attachments) {
    }

    record AttachmentRequest(String type, KeyboardPayload payload) {
    }

    record KeyboardPayload(List<List<OpenAppButtonRequest>> buttons) {
    }

    /**
     * Кнопка типа {@code open_app}: открывает мини-приложение сразу, без прыжка через страницу
     * max.ru (тип и поля сверены со схемой github.com/max-messenger/api-schema,
     * компонент {@code OpenAppButton}). Заменила прежнюю кнопку {@code link} с тем же URL.
     */
    public record OpenAppButtonRequest(String type, String text, @JsonProperty("web_app") String webApp, String payload) {
        public static OpenAppButtonRequest openApp(String label, String webApp, String payload) {
            return new OpenAppButtonRequest("open_app", label, webApp, payload);
        }
    }

    record SubscribeRequest(String url, @JsonProperty("update_types") List<String> updateTypes, String secret) {
    }
}
