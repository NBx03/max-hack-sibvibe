package ru.sibvibe.approval.organization.event;

/**
 * Подана заявка на вступление: администраторы компании получают уведомление.
 * Публикуется внутри транзакции: слушатель обязан быть {@code @TransactionalEventListener(phase = AFTER_COMMIT)},
 * иначе уведомление уйдёт по операции, которая затем откатится.
 */
public record JoinRequestSubmittedEvent(long orgId, long joinRequestId, long applicantUserId) {
}
