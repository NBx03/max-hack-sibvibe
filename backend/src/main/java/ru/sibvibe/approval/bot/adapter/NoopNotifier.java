package ru.sibvibe.approval.bot.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sibvibe.approval.bot.Notifier;

/**
 * {@link Notifier} без обращений к MAX Bot API: только пишет в лог. Используется, пока
 * {@code MAX_BOT_TOKEN} не задан (например, локально без токена организаторов) или пока
 * {@code max.bot.enabled=false} (интеграционные тесты с фиктивным токеном, временное отключение
 * на стенде) - основной сценарий не должен ломаться из-за их отсутствия.
 */
public class NoopNotifier implements Notifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoopNotifier.class);

    @Override
    public void send(long maxUserId, String text, String buttonLabel, String buttonUrl) {
        LOGGER.info("Уведомления MAX отключены, не отправлено: пользователь {}, текст «{}»", maxUserId, text);
    }
}
