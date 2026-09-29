package ru.sibvibe.approval.organization.event;

/**
 * Человек вступил по личной ссылке без заявки: администраторы получают уведомление
 * с кнопкой «Исключить». Ссылку могли переслать не тому, поэтому уведомление обязательно.
 * Публикуется внутри транзакции: слушатель — только {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
public record MemberJoinedByLinkEvent(long orgId, long memberId, long userId, long invitedByUserId) {
}
