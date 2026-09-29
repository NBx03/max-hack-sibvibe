package ru.sibvibe.approval.bot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import ru.sibvibe.approval.bot.adapter.MaxBotApiClient;

/**
 * В режиме webhook ({@code max.bot.update-mode=webhook}) подписывает бота на события по адресу
 * {@code <MAX_BOT_WEBHOOK_URL>/api/v1/bot/webhook}. Без подписки MAX не знает, куда слать обновления, и бот не
 * отвечает на {@code /start} и сообщения.
 *
 * Сбой подписки не мешает запуску бэкенда: повторный запуск или ручной {@code POST /subscriptions}
 * (docs/DEVELOPMENT.md, раздел «Бот») исправляет ситуацию.
 *
 * Без секрета подписка не выполняется: {@code BotWebhookController} без секрета всегда отвечает 401, такая
 * подписка принимала бы события, на которые бот не ответит. Это ошибка конфигурации, а не штатная деградация.
 */
public class BotWebhookSubscriber implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotWebhookSubscriber.class);
    private static final String WEBHOOK_PATH = "/api/v1/bot/webhook";

    private final MaxBotApiClient client;
    private final String webhookBaseUrl;
    private final String webhookSecret;
    private final boolean tokenConfigured;

    public BotWebhookSubscriber(
            MaxBotApiClient client, String webhookBaseUrl, String webhookSecret, boolean tokenConfigured
    ) {
        this.client = client;
        this.webhookBaseUrl = webhookBaseUrl;
        this.webhookSecret = webhookSecret;
        this.tokenConfigured = tokenConfigured;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!tokenConfigured) {
            LOGGER.warn("MAX_BOT_TOKEN не задан: подписка на webhook не выполнена");
            return;
        }
        if (webhookBaseUrl == null || webhookBaseUrl.isBlank()) {
            LOGGER.warn("MAX_BOT_WEBHOOK_URL не задан: подписку на webhook нужно оформить вручную "
                    + "(docs/DEVELOPMENT.md, раздел «Бот»)");
            return;
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            LOGGER.error("MAX_BOT_WEBHOOK_SECRET не задан: подписка на webhook не выполнена, "
                    + "иначе входящие события никто не сможет подтвердить (BotWebhookController)");
            return;
        }
        String url = webhookBaseUrl.replaceAll("/+$", "") + WEBHOOK_PATH;
        try {
            client.subscribe(url, webhookSecret);
            LOGGER.info("Бот подписан на приём событий webhook'ом: {}", url);
        } catch (RuntimeException exception) {
            LOGGER.error("Не удалось подписать бота на webhook {}; приложение продолжает работу", url, exception);
        }
    }
}
