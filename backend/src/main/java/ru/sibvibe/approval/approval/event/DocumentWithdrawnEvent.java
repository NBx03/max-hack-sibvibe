package ru.sibvibe.approval.approval.event;

import java.util.Set;

/**
 * Автор отозвал документ с согласования. {@code waitingApproverIds} — те, чья очередь
 * уже подошла: им бот сообщает, что решение больше не нужно. Будущим этапам сообщать нечего — они
 * о документе ещё не знали.
 */
public record DocumentWithdrawnEvent(long documentId, String documentTitle, Set<Long> waitingApproverIds) {

    public DocumentWithdrawnEvent {
        waitingApproverIds = Set.copyOf(waitingApproverIds);
    }
}
