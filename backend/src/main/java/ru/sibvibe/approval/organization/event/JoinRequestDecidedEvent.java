package ru.sibvibe.approval.organization.event;

/**
 * По заявке принято решение: заявитель получает уведомление.
 * Публикуется внутри транзакции: слушатель — только {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
public record JoinRequestDecidedEvent(long orgId, long joinRequestId, long applicantUserId, boolean approved) {
}
