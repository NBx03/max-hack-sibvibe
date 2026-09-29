package ru.sibvibe.approval.bot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.sibvibe.approval.bot.service.BotUpdateHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Контроллер работает только при {@code update-mode=webhook}
 * ({@code @ConditionalOnProperty} на классе, здесь не проверяется - это забота Spring) и
 * <b>всегда</b> отказывает без совпадающего секрета - пустой {@code max.bot.webhook-secret}
 * означает «отказать всем», а не «пропустить проверку».
 */
class BotWebhookControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rejectsWhenSecretHeaderIsMissing() throws Exception {
        BotUpdateHandler handler = mock(BotUpdateHandler.class);
        BotWebhookController controller = new BotWebhookController(handler, "configured-secret");

        ResponseEntity<Void> response = controller.receive(null, update());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(handler, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsWhenSecretDoesNotMatch() throws Exception {
        BotUpdateHandler handler = mock(BotUpdateHandler.class);
        BotWebhookController controller = new BotWebhookController(handler, "configured-secret");

        ResponseEntity<Void> response = controller.receive("wrong-secret", update());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(handler, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    /**
     * Пустой ожидаемый секрет - неверная конфигурация, а не "проверка отключена": отказываем всем
     * (fail closed), иначе кто угодно мог бы прислать поддельный bot_started с чужим user_id.
     */
    @Test
    void rejectsEverythingWhenExpectedSecretIsBlank() throws Exception {
        BotUpdateHandler handler = mock(BotUpdateHandler.class);
        BotWebhookController controller = new BotWebhookController(handler, "");

        ResponseEntity<Void> response = controller.receive("anything", update());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(handler, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptsAndDispatchesWhenSecretMatches() throws Exception {
        BotUpdateHandler handler = mock(BotUpdateHandler.class);
        BotWebhookController controller = new BotWebhookController(handler, "configured-secret");
        JsonNode update = update();

        ResponseEntity<Void> response = controller.receive("configured-secret", update);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(handler).handle(update);
    }

    @Test
    void handlerFailureStillReturnsOk() throws Exception {
        BotUpdateHandler handler = mock(BotUpdateHandler.class);
        org.mockito.Mockito.doThrow(new RuntimeException("боту поплохело"))
                .when(handler).handle(org.mockito.ArgumentMatchers.any());
        BotWebhookController controller = new BotWebhookController(handler, "configured-secret");

        ResponseEntity<Void> response = controller.receive("configured-secret", update());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode update() throws Exception {
        return MAPPER.readTree("""
                { "update_type": "bot_started", "timestamp": 1, "chat_id": 1,
                  "user": { "user_id": 42, "first_name": "Иван", "is_bot": false } }
                """);
    }
}
