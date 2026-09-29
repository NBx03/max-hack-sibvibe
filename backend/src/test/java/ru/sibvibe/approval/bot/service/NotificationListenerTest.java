package ru.sibvibe.approval.bot.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.sibvibe.approval.approval.event.ApprovalRequestedEvent;
import ru.sibvibe.approval.approval.event.DocumentDecidedEvent;
import ru.sibvibe.approval.approval.event.DocumentWithdrawnEvent;
import ru.sibvibe.approval.approval.event.StuckStepReminderEvent;
import ru.sibvibe.approval.bot.Notifier;
import ru.sibvibe.approval.organization.event.AdminChangedEvent;
import ru.sibvibe.approval.organization.event.JoinRequestDecidedEvent;
import ru.sibvibe.approval.organization.event.JoinRequestSubmittedEvent;
import ru.sibvibe.approval.organization.event.MemberJoinedByLinkEvent;
import ru.sibvibe.approval.organization.service.InviteLinks;
import ru.sibvibe.approval.organization.service.NotificationDirectoryService;
import ru.sibvibe.approval.organization.service.NotificationDirectoryService.Recipient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Доменные события {@code organization} и {@code approval} превращаются в уведомления MAX
 * (ARCHITECTURE.md, «Уведомления»). Проверяется маршрутизация получателей, а не HTTP - его закрывает
 * {@link ru.sibvibe.approval.bot.adapter.MaxBotApiNotifier}.
 */
class NotificationListenerTest {

    private final Notifier notifier = mock(Notifier.class);
    private final NotificationDirectoryService directory = mock(NotificationDirectoryService.class);
    private final InviteLinks links = mock(InviteLinks.class);
    private final NotificationListener listener = new NotificationListener(notifier, directory, links);

    @Test
    void joinRequestSubmittedNotifiesEveryActiveAdmin() {
        when(directory.find(77L)).thenReturn(Optional.of(new Recipient(910000077L, "Иван Заявитель")));
        when(directory.activeAdmins(1L)).thenReturn(List.of(
                new Recipient(910000001L, "Админ Один"), new Recipient(910000002L, "Админ Два")));
        String requestsLink = "https://max.ru/t354_hakaton_max_bot?startapp=a_requests";
        when(links.joinRequestsLink()).thenReturn(requestsLink);

        listener.on(new JoinRequestSubmittedEvent(1L, 300L, 77L));

        // Кнопка ведёт сразу на «Заявки», а не на главную
        verify(notifier).send(eq(910000001L), contains("Иван Заявитель"), eq("Открыть заявки"), eq(requestsLink));
        verify(notifier).send(eq(910000002L), contains("Иван Заявитель"), eq("Открыть заявки"), eq(requestsLink));
    }

    @Test
    void joinRequestSubmittedFallsBackToGenericNameWhenApplicantUnknown() {
        when(directory.find(77L)).thenReturn(Optional.empty());
        when(directory.activeAdmins(1L)).thenReturn(List.of(new Recipient(910000001L, "Админ")));

        listener.on(new JoinRequestSubmittedEvent(1L, 300L, 77L));

        verify(notifier).send(eq(910000001L), anyString(), anyString(), any());
    }

    @Test
    void joinRequestDecidedDoesNothingIfApplicantIsGone() {
        when(directory.find(77L)).thenReturn(Optional.empty());

        listener.on(new JoinRequestDecidedEvent(1L, 300L, 77L, true));

        verifyNoInteractions(notifier);
    }

    @Test
    void joinRequestApprovedAndRejectedProduceDifferentText() {
        when(directory.find(77L)).thenReturn(Optional.of(new Recipient(910000077L, "Заявитель")));

        listener.on(new JoinRequestDecidedEvent(1L, 300L, 77L, true));
        listener.on(new JoinRequestDecidedEvent(1L, 301L, 77L, false));

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(notifier, times(2)).send(eq(910000077L), texts.capture(), anyString(), any());
        assertThat(texts.getAllValues().get(0)).contains("одобрена");
        assertThat(texts.getAllValues().get(1)).contains("отклонена");
    }

    /** Сняли права с администратора: сообщение остальным администраторам и ему самому, тому, кто снял, — нет. */
    @Test
    void adminRightsChangeIsSeenByOtherAdminsAndTheTarget() {
        when(directory.find(5L)).thenReturn(Optional.of(new Recipient(910000005L, "Иван Первый")));
        when(directory.find(6L)).thenReturn(Optional.of(new Recipient(910000006L, "Пётр Второй")));
        when(directory.activeAdmins(1L)).thenReturn(List.of(
                new Recipient(910000005L, "Иван Первый"), new Recipient(910000007L, "Анна Третья")));

        listener.on(new AdminChangedEvent(1L, 5L, 6L, AdminChangedEvent.Kind.REVOKED));

        verify(notifier).send(eq(910000007L),
                eq("Изменение прав: Пётр Второй больше не администратор компании. Кто изменил: Иван Первый."),
                anyString(), any());
        verify(notifier).send(eq(910000006L),
                eq("У вас больше нет прав администратора компании. Кто изменил: Иван Первый."), anyString(), any());
        verify(notifier, never()).send(eq(910000005L), anyString(), anyString(), any());
    }

    @Test
    void memberJoinedByLinkNotifiesTheAdminWhoCreatedTheLink() {
        when(directory.find(5L)).thenReturn(Optional.of(new Recipient(910000005L, "Админ Пригласивший")));
        when(directory.find(9L)).thenReturn(Optional.of(new Recipient(910000009L, "Новый Сотрудник")));

        listener.on(new MemberJoinedByLinkEvent(1L, 42L, 9L, 5L));

        // Точный текст: без глагола с родом («вступил(а)») — пол человека неизвестен.
        verify(notifier).send(eq(910000005L),
                eq("По вашей личной ссылке в компании новый сотрудник: Новый Сотрудник."), anyString(), any());
    }

    @Test
    void memberJoinedByLinkDoesNothingIfInvitingAdminIsGone() {
        when(directory.find(5L)).thenReturn(Optional.empty());

        listener.on(new MemberJoinedByLinkEvent(1L, 42L, 9L, 5L));

        verifyNoInteractions(notifier);
    }

    @Test
    void withdrawnDocumentNotifiesExactlyThoseWhoseTurnHadComeWithDocumentLink() {
        when(directory.find(Set.of(11L, 12L))).thenReturn(Map.of(
                11L, new Recipient(910000011L, "Юрист"),
                12L, new Recipient(910000012L, "Бухгалтер")));
        when(links.documentLink(500L)).thenReturn("https://max.ru/t354_hakaton_max_bot?startapp=d_500");

        listener.on(new DocumentWithdrawnEvent(500L, "Закупка ноутбуков", Set.of(11L, 12L)));

        String documentUrl = "https://max.ru/t354_hakaton_max_bot?startapp=d_500";
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000011L), text.capture(), eq("Открыть документ"), eq(documentUrl));
        verify(notifier).send(eq(910000012L), anyString(), eq("Открыть документ"), eq(documentUrl));
        verify(notifier, times(2)).send(anyLong(), anyString(), anyString(), any());
        assertThat(text.getValue()).contains("«Закупка ноутбуков»").contains("отозвал").contains("больше не нужно");
    }

    @Test
    void withdrawnDocumentBeforeAnyoneWasAskedNotifiesNobody() {
        listener.on(new DocumentWithdrawnEvent(500L, "Закупка ноутбуков", Set.of()));

        verify(notifier, never()).send(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void approvalRequestedNotifiesEveryApproverWithDocumentLinkAndTitle() {
        when(directory.find(Set.of(11L, 12L))).thenReturn(Map.of(
                11L, new Recipient(910000011L, "Согласующий Один"),
                12L, new Recipient(910000012L, "Согласующий Два")));
        when(links.documentLink(500L)).thenReturn("https://max.ru/t354_hakaton_max_bot?startapp=d_500");

        listener.on(new ApprovalRequestedEvent(500L, 1, 1, Set.of(11L, 12L), 99L, "Закупка ноутбуков", false));

        String documentUrl = "https://max.ru/t354_hakaton_max_bot?startapp=d_500";
        verify(notifier).send(eq(910000011L), contains("Закупка ноутбуков"), anyString(), eq(documentUrl));
        verify(notifier).send(eq(910000012L), contains("Закупка ноутбуков"), anyString(), eq(documentUrl));
    }

    @Test
    void endorsementIsAskedAndReportedInItsOwnWords() {
        when(directory.find(Set.of(11L))).thenReturn(Map.of(11L, new Recipient(910000011L, "Директор")));
        when(directory.find(99L)).thenReturn(Optional.of(new Recipient(910000099L, "Автор")));
        when(directory.find(11L)).thenReturn(Optional.of(new Recipient(910000011L, "Директор")));
        when(links.documentLink(500L)).thenReturn("https://max.ru/t354_hakaton_max_bot?startapp=d_500");

        // Утверждающего просят утвердить, автору сообщают «утверждён», а не «согласован»
        listener.on(new ApprovalRequestedEvent(500L, 1, 2, Set.of(11L), 99L, "Закупка ноутбуков", true));
        listener.on(new DocumentDecidedEvent(500L, 1, 99L, DocumentDecidedEvent.Outcome.APPROVED, 11L, "Закупка ноутбуков", true));

        verify(notifier).send(eq(910000011L), contains("ждёт вашего утверждения"), anyString(), anyString());
        verify(notifier).send(eq(910000099L), contains("утверждён"), anyString(), anyString());
    }

    @Test
    void documentDecidedNotifiesAuthorWithTitleOutcomeTextAndDeciderName() {
        when(directory.find(99L)).thenReturn(Optional.of(new Recipient(910000099L, "Автор")));
        when(directory.find(11L)).thenReturn(Optional.of(new Recipient(910000011L, "Иванов")));
        when(links.documentLink(500L)).thenReturn("https://max.ru/t354_hakaton_max_bot?startapp=d_500");

        listener.on(new DocumentDecidedEvent(
                500L, 1, 99L, DocumentDecidedEvent.Outcome.RETURNED, 11L, "Закупка ноутбуков", false));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000099L), text.capture(), anyString(),
                eq("https://max.ru/t354_hakaton_max_bot?startapp=d_500"));
        assertThat(text.getValue()).contains("Закупка ноутбуков").contains("возвращён").contains("Иванов");
    }

    @Test
    void documentDecidedWithoutADeciderOmitsTheSuffix() {
        when(directory.find(99L)).thenReturn(Optional.of(new Recipient(910000099L, "Автор")));
        when(links.documentLink(500L)).thenReturn("https://max.ru/t354_hakaton_max_bot?startapp=d_500");

        listener.on(new DocumentDecidedEvent(
                500L, 1, 99L, DocumentDecidedEvent.Outcome.APPROVED, null, "Закупка ноутбуков", false));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000099L), text.capture(), anyString(), any());
        assertThat(text.getValue()).contains("Закупка ноутбуков").contains("согласован").doesNotContain("Решение:");
    }

    @Test
    void documentDecidedDoesNothingIfAuthorIsGone() {
        when(directory.find(99L)).thenReturn(Optional.empty());

        listener.on(new DocumentDecidedEvent(
                500L, 1, 99L, DocumentDecidedEvent.Outcome.APPROVED, null, "Закупка ноутбуков", false));

        verify(notifier, never()).send(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void stuckStepReminderNotifiesTheApproverWithDocumentLinkTitleAndDaysWaiting() {
        when(directory.find(11L)).thenReturn(Optional.of(new Recipient(910000011L, "Согласующий")));
        when(links.documentLink(500L)).thenReturn("https://max.ru/t354_hakaton_max_bot?startapp=d_500");

        listener.on(new StuckStepReminderEvent(500L, 1, 2, 11L, "Заявление на отпуск", 76, false));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000011L), text.capture(), anyString(),
                eq("https://max.ru/t354_hakaton_max_bot?startapp=d_500"));
        assertThat(text.getValue()).contains("Заявление на отпуск").contains("3 дня");
    }

    @Test
    void stuckStepReminderUsesHoursWhenUnderADay() {
        when(directory.find(11L)).thenReturn(Optional.of(new Recipient(910000011L, "Согласующий")));
        when(links.documentLink(500L)).thenReturn("https://max.ru/t354_hakaton_max_bot?startapp=d_500");

        listener.on(new StuckStepReminderEvent(500L, 1, 2, 11L, "Заявление на отпуск", 5, false));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000011L), text.capture(), anyString(), any());
        assertThat(text.getValue()).contains("5 часов").doesNotContain("дня").doesNotContain("дней");
    }

    @Test
    void stuckStepReminderPluralizesRussianDaysCorrectly() {
        when(directory.find(11L)).thenReturn(Optional.of(new Recipient(910000011L, "Согласующий")));
        when(links.documentLink(500L)).thenReturn("url");

        listener.on(new StuckStepReminderEvent(500L, 1, 1, 11L, "Документ", 24, false)); // 1 день
        listener.on(new StuckStepReminderEvent(501L, 1, 1, 11L, "Документ", 11 * 24, false)); // 11 дней

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier, times(2)).send(eq(910000011L), text.capture(), anyString(), any());
        assertThat(text.getAllValues().get(0)).contains("1 день");
        assertThat(text.getAllValues().get(1)).contains("11 дней");
    }

    @Test
    void stuckStepReminderToEndorserSaysApprovalAndNamesTheSection() {
        when(directory.find(11L)).thenReturn(Optional.of(new Recipient(910000011L, "Утверждающий")));
        when(links.documentLink(500L)).thenReturn("url");

        listener.on(new StuckStepReminderEvent(500L, 1, 3, 11L, "Служебная записка", 30, true));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000011L), text.capture(), anyString(), any());
        assertThat(text.getValue()).contains("ждёт вашего утверждения уже 1 день").contains("«На моём согласовании»");
    }

    @Test
    void stuckStepReminderDoesNothingIfApproverIsGone() {
        when(directory.find(11L)).thenReturn(Optional.empty());

        listener.on(new StuckStepReminderEvent(500L, 1, 2, 11L, "Заявление на отпуск", 76, false));

        verifyNoInteractions(notifier);
    }
}
