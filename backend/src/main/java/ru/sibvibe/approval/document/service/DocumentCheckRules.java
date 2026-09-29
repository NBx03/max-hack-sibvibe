package ru.sibvibe.approval.document.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Единое правило «версию нельзя отправить на согласование»: его используют карточка ({@code canSubmit}) и сама
 * отправка, чтобы кнопка и проверка на сервере не расходились.
 *
 * Тип без проверки (generic) отправляется всегда. У типа с проверкой версию нельзя отправить, если проверка
 * не выполнилась ({@code check_status != CHECKED}) или среди замечаний есть {@code BLOCKER}.
 */
public final class DocumentCheckRules {

    private DocumentCheckRules() {
    }

    public static Optional<Reason> blockReason(boolean generic, String checkStatus, JsonNode issues) {
        if (generic) {
            return Optional.empty();
        }
        if (!"CHECKED".equals(checkStatus)) {
            return Optional.of(Reason.CHECK_NOT_DONE);
        }
        if (issues != null && issues.isArray()) {
            for (JsonNode issue : issues) {
                if ("BLOCKER".equals(issue.path("severity").asText())) {
                    return Optional.of(Reason.BLOCKER_ISSUES);
                }
            }
        }
        return Optional.empty();
    }

    public enum Reason {
        CHECK_NOT_DONE("Проверка документа не выполнена: исправьте поля или загрузите новую версию"),
        BLOCKER_ISSUES("В документе есть замечания, которые нужно исправить перед отправкой");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }
}
