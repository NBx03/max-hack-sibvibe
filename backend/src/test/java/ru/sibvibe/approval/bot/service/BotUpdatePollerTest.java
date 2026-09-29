package ru.sibvibe.approval.bot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.sibvibe.approval.bot.adapter.MaxBotApiClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Long polling {@code GET /updates}: без токена цикл не должен даже начинаться (иначе каждый вызов
 * получал бы 401 впустую), а полученные обновления должны доходить до {@link BotUpdateHandler}.
 */
class BotUpdatePollerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void doesNotStartTheLoopWithoutAToken() {
        MaxBotApiClient client = mock(MaxBotApiClient.class);
        BotUpdateHandler handler = mock(BotUpdateHandler.class);
        BotUpdatePoller poller = new BotUpdatePoller(client, handler, false);

        poller.start();

        assertThat(poller.isRunning()).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    @Timeout(10)
    void dispatchesReceivedUpdatesToTheHandlerAndStopsCleanly() throws Exception {
        MaxBotApiClient client = mock(MaxBotApiClient.class);
        BotUpdateHandler handler = mock(BotUpdateHandler.class);
        CountDownLatch dispatched = new CountDownLatch(1);
        var update = MAPPER.readTree("""
                { "update_type": "bot_started", "timestamp": 1, "chat_id": 1,
                  "user": { "user_id": 42, "first_name": "Иван", "is_bot": false } }
                """);

        when(client.getUpdates(any(), anyInt()))
                .thenReturn(new MaxBotApiClient.UpdatesPage(java.util.List.of(update), 100L))
                .thenAnswer(invocation -> {
                    throw new RuntimeException("MAX Bot API недоступен - должно быть поймано и залогировано");
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            dispatched.countDown();
            return null;
        }).when(handler).handle(update);

        BotUpdatePoller poller = new BotUpdatePoller(client, handler, true);
        poller.start();
        try {
            assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
            verify(handler).handle(update);
        } finally {
            poller.stop();
        }
        assertThat(poller.isRunning()).isFalse();
    }
}
