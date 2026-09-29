package ru.sibvibe.approval.bot.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.bot.adapter.MaxBotApiClient;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Автоподписка на webhook при старте (только {@code max.bot.update-mode=webhook}, docs/DESIGN-DECISIONS.md,
 * «Long polling у MAX Bot API официально не годится для продакшена»).
 */
class BotWebhookSubscriberTest {

    private final MaxBotApiClient client = mock(MaxBotApiClient.class);

    @Test
    void doesNothingWithoutToken() {
        BotWebhookSubscriber subscriber = new BotWebhookSubscriber(
                client, "https://documentics.ru", "secret", false);

        subscriber.run(null);

        verifyNoInteractions(client);
    }

    @Test
    void doesNothingWithoutWebhookUrl() {
        BotWebhookSubscriber subscriber = new BotWebhookSubscriber(client, "", "secret", true);

        subscriber.run(null);

        verifyNoInteractions(client);
    }

    @Test
    void subscribesToWebhookPathAppendedToConfiguredUrl() {
        BotWebhookSubscriber subscriber = new BotWebhookSubscriber(
                client, "https://documentics.ru/", "webhook-secret", true);

        subscriber.run(null);

        verify(client).subscribe(eq("https://documentics.ru/api/v1/bot/webhook"), eq("webhook-secret"));
    }

    @Test
    void subscribeFailureDoesNotPropagate() {
        doThrow(new RuntimeException("Bot API недоступен"))
                .when(client).subscribe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        BotWebhookSubscriber subscriber = new BotWebhookSubscriber(
                client, "https://documentics.ru", "webhook-secret", true);

        subscriber.run(null);

        verify(client).subscribe(eq("https://documentics.ru/api/v1/bot/webhook"), eq("webhook-secret"));
    }

    /**
     * Без секрета не подписываемся вовсе - подписка без него была бы
     * бесполезна, потому что {@code BotWebhookController} и так отвечает 401 без секрета (fail closed).
     */
    @Test
    void doesNothingWithoutWebhookSecret() {
        BotWebhookSubscriber subscriber = new BotWebhookSubscriber(client, "https://documentics.ru", "", true);

        subscriber.run(null);

        verifyNoInteractions(client);
    }

    @Test
    void doesNothingWithNullWebhookSecret() {
        BotWebhookSubscriber subscriber = new BotWebhookSubscriber(client, "https://documentics.ru", null, true);

        subscriber.run(null);

        verifyNoInteractions(client);
    }
}
