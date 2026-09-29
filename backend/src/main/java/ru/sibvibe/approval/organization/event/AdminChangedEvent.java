package ru.sibvibe.approval.organization.event;

/**
 * Изменились права администратора: выданы, сняты или администратора исключили. Администраторы
 * равноправны, поэтому злоупотребление должно быть заметным и обратимым: остальные администраторы и сам человек сразу
 * узнают, кто это сделал. Только для настоящих компаний, не для демо-песочниц.
 * Публикуется внутри транзакции: слушатель — только {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
public record AdminChangedEvent(long orgId, long actorUserId, long targetUserId, Kind kind) {

    public enum Kind { GRANTED, REVOKED, EXCLUDED }
}
