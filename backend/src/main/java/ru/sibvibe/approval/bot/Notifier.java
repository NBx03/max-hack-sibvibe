package ru.sibvibe.approval.bot;

/**
 * Единственная точка выхода к MAX Bot API для отправки уведомлений (ARCHITECTURE.md, «Порты»).
 * {@code organization} и {@code approval} этот интерфейс не вызывают напрямую — они публикуют доменные
 * события, на которые подписан {@code bot} (ARCHITECTURE.md, «Уведомления»).
 *
 * Реализация не пробрасывает исключения наружу: недоступность Bot API не должна ломать действие
 * пользователя, только логируется (docs/DESIGN-DECISIONS.md, решение E; ARCHITECTURE.md, «Уведомления»).
 */
public interface Notifier {

    /**
     * @param maxUserId   получатель - идентификатор пользователя в MAX
     * @param text        текст уведомления, по-русски
     * @param buttonLabel текст кнопки; {@code null}, если кнопки нет
     * @param buttonUrl   ссылка на мини-приложение для кнопки; {@code null}, если кнопки нет
     */
    void send(long maxUserId, String text, String buttonLabel, String buttonUrl);
}
