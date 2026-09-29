package ru.sibvibe.approval.bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import ru.sibvibe.approval.bot.adapter.MaxBotApiClient;

import java.time.Duration;

/**
 * Long polling {@code GET /updates} для локальной разработки ({@code max.bot.update-mode=polling}, по
 * умолчанию). Документация MAX прямо называет long polling непригодным для продакшена и говорит, что
 * одновременно с webhook он не работает - поэтому бин создаётся только в одном из двух режимов
 * ({@code bot.config.BotConfiguration}, {@code @ConditionalOnProperty}).
 *
 * Отдельный демон-поток: обновления держат HTTP-соединение до {@code POLL_TIMEOUT_SECONDS}, что плохо
 * сочетается с пулом {@code @Scheduled} на одном потоке. Ошибка сети не останавливает поток - логируется,
 * короткая пауза, повтор; недоступность Bot API не должна ломать сценарий (docs/DESIGN-DECISIONS.md, решение E).
 */
public class BotUpdatePoller implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotUpdatePoller.class);
    private static final int POLL_TIMEOUT_SECONDS = 25;
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(5);

    private final MaxBotApiClient client;
    private final BotUpdateHandler handler;
    private final boolean tokenConfigured;

    private volatile Thread thread;
    private volatile boolean running;

    public BotUpdatePoller(MaxBotApiClient client, BotUpdateHandler handler, boolean tokenConfigured) {
        this.client = client;
        this.handler = handler;
        this.tokenConfigured = tokenConfigured;
    }

    @Override
    public void start() {
        if (!tokenConfigured) {
            // Без токена все вызовы получат 401 - не начинаем бесполезный цикл (см. NoopNotifier).
            LOGGER.warn("MAX_BOT_TOKEN не задан: приём обновлений MAX Bot API (polling) не запущен");
            return;
        }
        running = true;
        thread = new Thread(this::loop, "max-bot-poller");
        thread.setDaemon(true);
        thread.start();
        LOGGER.info("Приём обновлений MAX Bot API запущен (long polling)");
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        Long marker = null;
        while (running) {
            try {
                MaxBotApiClient.UpdatesPage page = client.getUpdates(marker, POLL_TIMEOUT_SECONDS);
                for (JsonNode update : page.updates()) {
                    try {
                        handler.handle(update);
                    } catch (RuntimeException exception) {
                        LOGGER.warn("Ошибка обработки обновления бота: {}", exception.getMessage());
                    }
                }
                if (page.marker() != null) {
                    marker = page.marker();
                }
            } catch (RuntimeException exception) {
                if (!running) {
                    break;
                }
                LOGGER.warn("Не удалось получить обновления MAX Bot API (polling): {}", exception.getMessage());
                sleep(ERROR_BACKOFF);
            }
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
