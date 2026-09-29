package ru.sibvibe.approval.bot.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sibvibe.approval.bot.Notifier;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * {@link Notifier} через настоящий MAX Bot API. Ошибка HTTP-вызова не пробрасывается дальше:
 * уведомление не ушло - записали в лог, действие пользователя, вызвавшее уведомление, уже выполнено
 * и не откатывается (docs/DESIGN-DECISIONS.md, решение E; ARCHITECTURE.md, «Уведомления»).
 */
public class MaxBotApiNotifier implements Notifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaxBotApiNotifier.class);
    private static final String START_PARAM = "startapp";

    private final MaxBotApiClient client;

    public MaxBotApiNotifier(MaxBotApiClient client) {
        this.client = client;
    }

    @Override
    public void send(long maxUserId, String text, String buttonLabel, String buttonUrl) {
        if (maxUserId <= 0) {
            // Синтетический получатель демо-песочницы: у таких участников нет
            // настоящего аккаунта MAX - OrganizationSecurityService.nextSyntheticMaxUserId выдаёт им
            // только отрицательные id, настоящий MAX всегда возвращает положительный. Без этой проверки
            // каждая песочница каждого проверяющего слала бы в Bot API заведомо неудачные вызовы -
            // например, каждое утро по «застрявшим» шагам заранее посаженной служебной записки.
            LOGGER.debug("Уведомление MAX пропущено: получатель {} - синтетический пользователь демо-песочницы",
                    maxUserId);
            return;
        }
        List<MaxBotApiClient.OpenAppButtonRequest> buttons = buttonUrl == null || buttonUrl.isBlank()
                ? List.of()
                : List.of(openAppButton(buttonLabel, buttonUrl));
        try {
            client.sendMessage(maxUserId, text, buttons);
        } catch (RuntimeException exception) {
            LOGGER.warn("Не удалось отправить уведомление MAX пользователю {}: {}",
                    maxUserId, exception.getMessage());
        }
    }

    /**
     * {@link ru.sibvibe.approval.organization.service.InviteLinks} всегда отдаёт готовую ссылку вида
     * {@code https://max.ru/<бот>[?startapp=<payload>]} - той же самой, что уходит человеку при шаринге.
     * Кнопка {@code open_app} ссылку не принимает, только ник бота и сам payload по отдельности,
     * поэтому здесь их разбираем обратно, а не заводим для бота отдельную копию константы с ником.
     */
    private static MaxBotApiClient.OpenAppButtonRequest openAppButton(String label, String url) {
        URI uri = URI.create(url);
        String webApp = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
        String payload = uri.getQuery() == null ? null : Arrays.stream(uri.getQuery().split("&"))
                .filter(param -> param.startsWith(START_PARAM + "="))
                .map(param -> param.substring(START_PARAM.length() + 1))
                .findFirst()
                .orElse(null);
        return MaxBotApiClient.OpenAppButtonRequest.openApp(label, webApp, payload);
    }
}
