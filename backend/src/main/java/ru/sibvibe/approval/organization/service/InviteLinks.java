package ru.sibvibe.approval.organization.service;

import org.springframework.stereotype.Component;

/**
 * Ссылки на мини-приложение: {@code https://max.ru/<бот>?startapp=<префикс>_<значение>}.
 * Бота создали организаторы, его ник проверен запросом {@code GET /me} (docs/DESIGN-DECISIONS.md).
 * Используется и приглашениями, и уведомлениями бота (модуль {@code bot} вызывает только сервисы
 * {@code organization} - ARCHITECTURE.md, раздел 2).
 */
@Component
public class InviteLinks {

    private static final String BOT_USERNAME = "t354_hakaton_max_bot";

    /** Префикс {@code c_} отличает код компании от личной ссылки ({@code p_}) в startParam. */
    public String orgCodeLink(String code) {
        return link("c_" + code);
    }

    public String personalLink(String token) {
        return link("p_" + token);
    }

    /** Префикс {@code d_} - карточка документа из уведомления бота (API_CONTRACTS.md). */
    public String documentLink(long documentId) {
        return link("d_" + documentId);
    }

    /**
     * Ссылка с готовым параметром запуска — бот повторяет её кнопкой, если человек пришёл по ссылке в чат, а не в
     * мини-приложение ({@code bot_started} с payload, LNK-1). Параметр проверяет вызывающий.
     */
    public String startLink(String payload) {
        return link(payload);
    }

    /** Экран «Заявки» администратора — кнопка уведомления о новой заявке. */
    public String joinRequestsLink() {
        return link("a_requests");
    }

    /** Без startParam - для ответов бота вне какого-либо конкретного контекста ({@code /start}, приветствие). */
    public String appLink() {
        return "https://max.ru/" + BOT_USERNAME;
    }

    private String link(String payload) {
        return appLink() + "?startapp=" + payload;
    }
}
