package ru.sibvibe.approval.bot.adapter;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Кнопка уведомлений - тип {@code open_app}, а не {@code link}: проверяем,
 * что готовая ссылка от {@link ru.sibvibe.approval.organization.service.InviteLinks} правильно
 * разбирается обратно на ник бота и payload.
 */
class MaxBotApiNotifierTest {

    private final MaxBotApiClient client = mock(MaxBotApiClient.class);
    private final MaxBotApiNotifier notifier = new MaxBotApiNotifier(client);

    @Test
    void urlWithStartParamBecomesOpenAppButtonWithPayload() {
        notifier.send(910000011L, "Документ ждёт вашего решения.", "Открыть документ",
                "https://max.ru/t354_hakaton_max_bot?startapp=d_500");

        ArgumentCaptor<List<MaxBotApiClient.OpenAppButtonRequest>> buttons = ArgumentCaptor.forClass(List.class);
        verify(client).sendMessage(eq(910000011L), eq("Документ ждёт вашего решения."), buttons.capture());
        assertThat(buttons.getValue()).containsExactly(
                new MaxBotApiClient.OpenAppButtonRequest("open_app", "Открыть документ", "t354_hakaton_max_bot", "d_500"));
    }

    @Test
    void urlWithoutStartParamBecomesOpenAppButtonWithNullPayload() {
        notifier.send(910000001L, "Здравствуйте!", "Открыть мини-приложение", "https://max.ru/t354_hakaton_max_bot");

        ArgumentCaptor<List<MaxBotApiClient.OpenAppButtonRequest>> buttons = ArgumentCaptor.forClass(List.class);
        verify(client).sendMessage(eq(910000001L), eq("Здравствуйте!"), buttons.capture());
        assertThat(buttons.getValue()).containsExactly(
                new MaxBotApiClient.OpenAppButtonRequest("open_app", "Открыть мини-приложение", "t354_hakaton_max_bot", null));
    }

    @Test
    void blankButtonUrlSendsNoButtons() {
        notifier.send(910000001L, "Здравствуйте!", null, null);

        verify(client).sendMessage(eq(910000001L), eq("Здравствуйте!"), eq(List.of()));
    }

    @Test
    void httpFailureIsLoggedNotThrown() {
        doThrow(new RuntimeException("boom")).when(client).sendMessage(eq(910000001L), eq("Текст"), any());

        assertThatCode(() -> notifier.send(910000001L, "Текст", null, null)).doesNotThrowAnyException();
    }

    /**
     * Синтетические участники демо-песочницы получают отрицательный {@code maxUserId}
     * (OrganizationSecurityService.nextSyntheticMaxUserId) - реального аккаунта MAX у них нет, вызов
     * {@code sendMessage} для них заведомо провалился бы.
     */
    @Test
    void negativeOrZeroMaxUserIdIsSkippedWithoutCallingTheApi() {
        notifier.send(-123L, "Текст", "Открыть", "https://max.ru/t354_hakaton_max_bot");
        notifier.send(0L, "Текст", "Открыть", "https://max.ru/t354_hakaton_max_bot");

        verifyNoInteractions(client);
    }

    @Test
    void positiveMaxUserIdIsSentAsUsual() {
        notifier.send(1L, "Текст", null, null);

        verify(client).sendMessage(eq(1L), eq("Текст"), eq(List.of()));
    }
}
