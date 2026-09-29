package ru.sibvibe.approval.bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.bot.Notifier;
import ru.sibvibe.approval.document.DocumentLookup;
import ru.sibvibe.approval.organization.service.InviteLinks;
import ru.sibvibe.approval.organization.service.NotificationDirectoryService;
import ru.sibvibe.approval.organization.service.NotificationDirectoryService.Membership;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /start} - кнопка открытия мини-приложения. Любое другое сообщение - сначала
 * поиск документа по названию, иначе тот же ответ, что и раньше. Одинаковая логика для
 * long polling и webhook - см. {@link BotUpdateHandler}.
 */
class BotUpdateHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String APP_LINK = "https://max.ru/t354_hakaton_max_bot";
    private static final String DOCUMENT_LINK = "https://max.ru/t354_hakaton_max_bot?startapp=d_500";

    private final Notifier notifier = mock(Notifier.class);
    private final InviteLinks links = mock(InviteLinks.class);
    private final NotificationDirectoryService directory = mock(NotificationDirectoryService.class);
    private final DocumentLookup documents = mock(DocumentLookup.class);
    private final BotUpdateHandler handler = new BotUpdateHandler(notifier, links, directory, documents);

    @Test
    void botStartedRepliesToTheUserWhoPressedStart() throws Exception {
        when(links.appLink()).thenReturn(APP_LINK);
        JsonNode update = json("""
                { "update_type": "bot_started", "timestamp": 1, "chat_id": 55,
                  "user": { "user_id": 910000042, "first_name": "Иван", "is_bot": false } }
                """);

        handler.handle(update);

        verify(notifier).send(eq(910000042L), anyString(), anyString(), eq(APP_LINK));
    }

    @Test
    void botStartedWithOurPayloadRepliesWithTheSameLinkToTheMiniApp() throws Exception {
        String link = "https://max.ru/t354_hakaton_max_bot?startapp=c_KX7M2PQR";
        when(links.startLink("c_KX7M2PQR")).thenReturn(link);

        // Пришёл по ссылке в чат, а не в мини-приложение — кнопка несёт тот же параметр, а не приветствие
        handler.handle(json("""
                { "update_type": "bot_started", "payload": "c_KX7M2PQR", "user": { "user_id": 910000042 } }
                """));

        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000042L), text.capture(), eq("Вступить в компанию"), eq(link));
        assertThat(text.getValue()).contains("заявка");
        verify(links, never()).appLink();
    }

    @Test
    void typedStartWithADocumentPayloadOpensThatDocument() throws Exception {
        when(links.startLink("d_500")).thenReturn(DOCUMENT_LINK);

        handler.handle(message("/start d_500"));

        verify(notifier).send(eq(910000042L), anyString(), eq("Открыть документ"), eq(DOCUMENT_LINK));
    }

    @Test
    void inviteToSomeoneAlreadyInACompanyIsJustTheGreeting() throws Exception {
        when(links.appLink()).thenReturn(APP_LINK);
        when(directory.findRealMembershipByMaxUserId(910000042L)).thenReturn(Optional.of(new Membership(7L, 1L)));

        // Мини-приложение пустит участника на главную, а не к заявке — не обещаем заявку
        handler.handle(json("""
                { "update_type": "bot_started", "payload": "c_KX7M2PQR", "user": { "user_id": 910000042 } }
                """));
        handler.handle(message("/start p_token123"));

        verify(notifier, org.mockito.Mockito.times(2)).send(eq(910000042L), anyString(), anyString(), eq(APP_LINK));
        verify(links, never()).startLink(anyString());
    }

    @Test
    void inviteToSomeoneWithAPendingJoinRequestIsJustTheGreeting() throws Exception {
        // приложение покажет прежнюю заявку — новую не обещаем
        when(links.appLink()).thenReturn(APP_LINK);
        when(directory.hasPendingJoinRequest(910000042L)).thenReturn(true);

        handler.handle(json("""
                { "update_type": "bot_started", "payload": "c_KX7M2PQR", "user": { "user_id": 910000042 } }
                """));

        verify(notifier).send(eq(910000042L), anyString(), anyString(), eq(APP_LINK));
        verify(links, never()).startLink(anyString());
    }

    @Test
    void payloadLengthLimitIsFiveHundredAfterThePrefix() throws Exception {
        when(links.appLink()).thenReturn(APP_LINK);
        String fits = "d_" + "1".repeat(500);
        String tooLong = "d_" + "1".repeat(501);
        when(links.startLink(fits)).thenReturn(DOCUMENT_LINK);

        handler.handle(message("/start " + fits));
        handler.handle(message("/start " + tooLong));

        verify(notifier).send(eq(910000042L), anyString(), eq("Открыть документ"), eq(DOCUMENT_LINK));
        verify(links, never()).startLink(tooLong);
        verify(notifier).send(eq(910000042L), anyString(), anyString(), eq(APP_LINK));
    }

    @Test
    void foreignOrMalformedPayloadFallsBackToTheGreeting() throws Exception {
        when(links.appLink()).thenReturn(APP_LINK);
        when(directory.findRealMembershipByMaxUserId(910000042L)).thenReturn(Optional.empty());

        handler.handle(json("""
                { "update_type": "bot_started", "payload": "x_<script>", "user": { "user_id": 910000042 } }
                """));

        verify(notifier).send(eq(910000042L), anyString(), anyString(), eq(APP_LINK));
        verify(links, never()).startLink(anyString());
    }

    @Test
    void messageFromSomeoneOutsideAnyCompanyGetsTheOpenAppButton() throws Exception {
        when(links.appLink()).thenReturn(APP_LINK);
        when(directory.findRealMembershipByMaxUserId(910000042L)).thenReturn(Optional.empty());

        handler.handle(message("Привет"));

        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000042L), text.capture(), anyString(), eq(APP_LINK));
        verify(documents, never()).findByTitleFragment(anyLong(), anyLong(), anyString(), anyInt());
        // Не то же приветствие с обещанием поиска: иначе бот выглядит зависшим.
        assertThat(text.getValue()).contains("доступен сотрудникам компании").doesNotContain("пришлю его статус");
    }

    @Test
    void greetingPromisesTitleSearchOnlyToCompanyMembers() throws Exception {
        when(links.appLink()).thenReturn(APP_LINK);
        when(directory.findRealMembershipByMaxUserId(910000042L)).thenReturn(Optional.empty());
        when(directory.findRealMembershipByMaxUserId(910000043L)).thenReturn(Optional.of(new Membership(7L, 1L)));

        handler.handle(json("""
                { "update_type": "bot_started", "user": { "user_id": 910000042 } }
                """));
        handler.handle(json("""
                { "update_type": "bot_started", "user": { "user_id": 910000043 } }
                """));

        org.mockito.ArgumentCaptor<String> guest = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> member = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000042L), guest.capture(), anyString(), eq(APP_LINK));
        verify(notifier).send(eq(910000043L), member.capture(), anyString(), eq(APP_LINK));
        assertThat(guest.getValue()).doesNotContain("пришлю его статус").contains("демо-компанию");
        assertThat(member.getValue()).contains("пришлю его статус");
    }

    @Test
    void messageWithNoTitleMatchGetsTheOpenAppButton() throws Exception {
        when(links.appLink()).thenReturn(APP_LINK);
        when(directory.findRealMembershipByMaxUserId(910000042L)).thenReturn(Optional.of(new Membership(7L, 1L)));
        when(documents.findByTitleFragment(1L, 7L, "Договор аренды", 5)).thenReturn(List.of());

        handler.handle(message("Договор аренды"));

        verify(notifier).send(eq(910000042L), anyString(), anyString(), eq(APP_LINK));
    }

    @Test
    void messageMatchingExactlyOneDocumentRepliesWithStatusStageAndCardButton() throws Exception {
        when(directory.findRealMembershipByMaxUserId(910000042L)).thenReturn(Optional.of(new Membership(7L, 1L)));
        when(documents.findByTitleFragment(1L, 7L, "ноутбук", 5)).thenReturn(
                List.of(new DocumentLookup.Match(500L, "Закупка ноутбуков", "IN_APPROVAL", 2)));
        when(links.documentLink(500L)).thenReturn(DOCUMENT_LINK);

        handler.handle(message("ноутбук"));

        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000042L), text.capture(), anyString(), eq(DOCUMENT_LINK));
        assertThat(text.getValue()).contains("Закупка ноутбуков").contains("этап 2");
    }

    @Test
    void messageMatchingSeveralDocumentsListsThemAndOpensTheSearchInTheApp() throws Exception {
        // Кнопка несёт сам запрос (base64url от «заявление»), приложение откроет результаты поиска
        String searchLink = APP_LINK + "?startapp=s_0LfQsNGP0LLQu9C10L3QuNC1";
        when(links.startLink("s_0LfQsNGP0LLQu9C10L3QuNC1")).thenReturn(searchLink);
        when(directory.findRealMembershipByMaxUserId(910000042L)).thenReturn(Optional.of(new Membership(7L, 1L)));
        when(documents.findByTitleFragment(1L, 7L, "заявление", 5)).thenReturn(List.of(
                new DocumentLookup.Match(500L, "Заявление на отпуск", "APPROVED", null),
                new DocumentLookup.Match(501L, "Заявление на командировку", "DRAFT", null)));

        handler.handle(message("заявление"));

        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).send(eq(910000042L), text.capture(), eq("Показать все"), eq(searchLink));
        assertThat(text.getValue()).contains("Заявление на отпуск").contains("Заявление на командировку");
    }

    /**
     * Ручной {@code /start} (не нажатие «Начать» - тогда пришёл бы {@code bot_started}) приходит как
     * обычное сообщение. Без этой проверки участник компании получил бы «Не нашёл документ» на первое
     * же слово, с которого может начаться проверка бота.
     */
    @Test
    void slashCommandsTypedAsMessagesGetTheGreetingNotADocumentSearch() throws Exception {
        when(links.appLink()).thenReturn(APP_LINK);
        when(directory.findRealMembershipByMaxUserId(910000042L)).thenReturn(Optional.of(new Membership(7L, 1L)));

        handler.handle(message("/start"));

        verify(notifier).send(eq(910000042L), anyString(), anyString(), eq(APP_LINK));
        verify(documents, never()).findByTitleFragment(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void messagesFromBotsAreIgnoredToAvoidLoops() throws Exception {
        JsonNode update = json("""
                { "update_type": "message_created", "timestamp": 1,
                  "message": { "timestamp": 1, "recipient": { "chat_id": 55, "chat_type": "dialog" },
                    "body": { "mid": "1", "seq": 1, "text": "Ответ" },
                    "sender": { "user_id": 1, "first_name": "Бот", "is_bot": true } } }
                """);

        handler.handle(update);

        verify(notifier, never()).send(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void unknownUpdateTypesAreIgnored() throws Exception {
        JsonNode update = json("""
                { "update_type": "chat_title_changed", "timestamp": 1, "chat_id": 55,
                  "user": { "user_id": 910000042, "first_name": "Иван", "is_bot": false }, "title": "Новое" }
                """);

        handler.handle(update);

        verify(notifier, never()).send(anyLong(), anyString(), anyString(), anyString());
    }

    private JsonNode message(String text) throws Exception {
        return json("""
                { "update_type": "message_created", "timestamp": 1,
                  "message": { "timestamp": 1, "recipient": { "chat_id": 55, "chat_type": "dialog" },
                    "body": { "mid": "1", "seq": 1, "text": "%s" },
                    "sender": { "user_id": 910000042, "first_name": "Иван", "is_bot": false } } }
                """.formatted(text));
    }

    private JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }
}
