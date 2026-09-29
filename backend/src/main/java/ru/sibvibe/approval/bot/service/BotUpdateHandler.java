package ru.sibvibe.approval.bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.sibvibe.approval.bot.Notifier;
import ru.sibvibe.approval.document.DocumentLookup;
import ru.sibvibe.approval.organization.service.InviteLinks;
import ru.sibvibe.approval.organization.service.NotificationDirectoryService;
import ru.sibvibe.approval.organization.service.NotificationDirectoryService.Membership;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Обрабатывает одно входящее обновление MAX Bot API - общая логика для long polling
 * ({@link BotUpdatePoller}) и webhook ({@code BotWebhookController}), чтобы поведение бота не зависело
 * от режима приёма событий.
 *
 * {@code /start} - кнопка открытия мини-приложения. Любое другое сообщение 
 * сначала пробуется как поиск документа по названию среди видимых отправителю в его реальной компании
 * (не в демо-песочнице - у входящего сообщения нет и не может быть {@code X-Demo-Act-As}); не нашли
 * совпадений - «не нашёл». Поиск обещаем только тем, кому он доступен: участнику реальной компании.
 * Остальным (без компании, в том числе проверяющему в демо-песочнице) бот объясняет, что поиск - для
 * сотрудников, и предлагает мини-приложение: иначе на название приходило бы то же приветствие с тем же
 * обещанием, и бот выглядел бы зависшим.
 */
@Component
public class BotUpdateHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotUpdateHandler.class);
    private static final String GREETING = "Здравствуйте! Здесь готовятся и согласуются документы компании.";
    private static final String GREETING_MEMBER = GREETING + " Напишите часть названия документа — пришлю его статус.";
    private static final String GREETING_GUEST = GREETING + " Откройте мини-приложение: создайте компанию, "
            + "вступите по коду или попробуйте демо-компанию.";
    private static final String MEMBERS_ONLY = "Статус документа по названию доступен сотрудникам компании. "
            + "Откройте мини-приложение: там можно создать компанию, вступить по коду или открыть демо-компанию.";
    private static final String OPEN_BUTTON = "Открыть мини-приложение";
    /** Наши параметры запуска: c_ — код компании, p_ — личное приглашение, d_ — документ (API_CONTRACTS.md). */
    private static final java.util.regex.Pattern PAYLOAD = java.util.regex.Pattern.compile("^(c|p|d)_[A-Za-z0-9_-]{1,500}$");
    // a_ (экран заявок) и s_ (поиск) бот сам кладёт в свои кнопки, а по ссылке в чат они не приходят.
    private static final String OPEN_DOCUMENT = "Открыть документ";
    private static final String NOT_FOUND = "Не нашёл документ с таким названием. "
            + "Напишите часть названия точнее или откройте мини-приложение.";

    /** Больше - неудобно читать одним сообщением в мессенджере, меньше - можно не найти нужный. */
    private static final int SEARCH_LIMIT = 5;

    // Показываемые статусы: «На утверждении» и «Утверждён» вычисляет DocumentReadRepository.DISPLAY_STATUS.
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "DRAFT", "черновик",
            "IN_APPROVAL", "на согласовании",
            "IN_ENDORSEMENT", "на утверждении",
            "ENDORSED", "утверждён",
            "APPROVED", "согласован",
            "RETURNED", "возвращён",
            "REJECTED", "отклонён");

    private final Notifier notifier;
    private final InviteLinks links;
    private final NotificationDirectoryService directory;
    private final DocumentLookup documents;

    public BotUpdateHandler(
            Notifier notifier,
            InviteLinks links,
            NotificationDirectoryService directory,
            DocumentLookup documents
    ) {
        this.notifier = notifier;
        this.links = links;
        this.directory = directory;
        this.documents = documents;
    }

    public void handle(JsonNode update) {
        if (update == null || update.isMissingNode()) {
            return;
        }
        String type = update.path("update_type").asText("");
        switch (type) {
            case "bot_started" -> {
                Long userId = userId(update.path("user"));
                if (userId != null) {
                    Optional<Membership> membership = membership(userId);
                    if (!openByPayload(userId, update.path("payload").asText(""), membership)) {
                        greet(userId, membership);
                    }
                }
            }
            case "message_created" -> handleMessage(update.path("message"));
            default -> {
                // Реакции на кнопки, системные события чата и т. д. - вне чек-листа задачи, игнорируем.
            }
        }
    }

    private void handleMessage(JsonNode message) {
        JsonNode sender = message.path("sender");
        if (sender.path("is_bot").asBoolean(false)) {
            return; // не отвечаем самому себе / другим ботам - иначе зацикливание
        }
        Long userId = userId(sender);
        if (userId == null) {
            return;
        }
        LOGGER.debug("Сообщение боту от пользователя {}", userId);
        String text = message.path("body").path("text").asText("").strip();
        // Слэш-команда (/start, /help, набранные вручную, а не нажатием "Начать" - тогда пришёл бы
        // bot_started) - это не название документа. Без проверки первый же ручной /start участника
        // компании получил бы «Не нашёл документ» вместо приветствия.
        Optional<Membership> membership = membership(userId);
        if (text.startsWith("/start ") && openByPayload(userId, text.substring("/start ".length()).strip(), membership)) {
            return;
        }
        if (text.isEmpty() || text.startsWith("/")) {
            greet(userId, membership);
        } else if (membership.isEmpty()) {
            notifier.send(userId, MEMBERS_ONLY, OPEN_BUTTON, links.appLink());
        } else {
            replyWithDocumentStatus(userId, membership.get(), text);
        }
    }

    /**
     * Человек пришёл по ссылке с параметром в чат с ботом, а не в мини-приложение (ссылка {@code ?start=}, первый
     * запуск бота, клиент MAX открыл чат) — бот отвечает кнопкой, которая открывает мини-приложение с тем же
     * параметром. Иначе человек нажал бы «Открыть» у приветствия и попал на приветственный экран.
     * Параметр — только наших видов и только разрешённые MAX символы; остальное — обычное приветствие. Приглашение
     * тому, кто уже в компании, — тоже приветствие: мини-приложение пустит его на главную, а не к заявке.
     */
    private boolean openByPayload(long userId, String payload, Optional<Membership> membership) {
        if (payload == null || !PAYLOAD.matcher(payload).matches()) {
            return false;
        }
        boolean invite = payload.startsWith("c_") || payload.startsWith("p_");
        if (invite && (membership.isPresent() || directory.hasPendingJoinRequest(userId))) {
            return false;
        }
        String link = links.startLink(payload);
        if (payload.startsWith("c_")) {
            notifier.send(userId, "Приглашение в компанию. Нажмите кнопку — откроется мини-приложение, и заявка "
                    + "на вступление отправится администратору сама.", "Вступить в компанию", link);
        } else if (payload.startsWith("p_")) {
            notifier.send(userId, "Личное приглашение в компанию. Нажмите кнопку и подтвердите вступление.",
                    "Открыть приглашение", link);
        } else {
            notifier.send(userId, "Документ по ссылке — откройте его в мини-приложении.", OPEN_DOCUMENT, link);
        }
        return true;
    }

    private Optional<Membership> membership(long maxUserId) {
        return directory.findRealMembershipByMaxUserId(maxUserId);
    }

    private void greet(long userId, Optional<Membership> membership) {
        notifier.send(userId, membership.isPresent() ? GREETING_MEMBER : GREETING_GUEST, OPEN_BUTTON, links.appLink());
    }

    private void replyWithDocumentStatus(long maxUserId, Membership membership, String text) {
        List<DocumentLookup.Match> matches = documents.findByTitleFragment(
                membership.orgId(), membership.userId(), text, SEARCH_LIMIT);
        if (matches.isEmpty()) {
            notifier.send(maxUserId, NOT_FOUND, OPEN_BUTTON, links.appLink());
        } else if (matches.size() == 1) {
            DocumentLookup.Match match = matches.get(0);
            notifier.send(maxUserId, statusText(match), OPEN_DOCUMENT, links.documentLink(match.id()));
        } else {
            // Несколько совпадений - список без кнопок (Notifier поддерживает только одну на сообщение),
            // с просьбой уточнить. Кнопка на каждый документ была бы удобнее, но это уже другой контракт
            // Notifier - за рамками задачи (просит кнопку только для точного совпадения).
            String listing = matches.stream()
                    .map(match -> "«" + match.title() + "» — " + statusLabel(match))
                    .collect(Collectors.joining("\n"));
            // Кнопка открывает в приложении результаты этого же поиска, а не главную. Запрос — в параметре
            // запуска (base64url: MAX пропускает только A-Z a-z 0-9 _); слишком длинный — просто приложение.
            String search = "s_" + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Запрос длиннее 100 символов приложение не ищет (400) — такую кнопку не даём.
            boolean fits = search.length() <= 500 && text.length() <= 100;
            notifier.send(maxUserId, "Нашёл несколько документов:\n" + listing
                            + (fits ? "\nВсе — по кнопке ниже." : "\nУточните название."),
                    fits ? "Показать все" : OPEN_BUTTON, fits ? links.startLink(search) : links.appLink());
        }
    }

    private String statusText(DocumentLookup.Match match) {
        return "«" + match.title() + "»: " + statusLabel(match) + ".";
    }

    private String statusLabel(DocumentLookup.Match match) {
        String label = STATUS_LABELS.getOrDefault(match.status(), match.status());
        if ("IN_APPROVAL".equals(match.status()) && match.currentStage() != null) {
            return label + ", этап " + match.currentStage();
        }
        return label;
    }

    private Long userId(JsonNode user) {
        JsonNode id = user.path("user_id");
        return id.isIntegralNumber() ? id.asLong() : null;
    }
}
