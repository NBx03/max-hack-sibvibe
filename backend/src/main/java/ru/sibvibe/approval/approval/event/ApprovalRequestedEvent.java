package ru.sibvibe.approval.approval.event;

import java.util.Set;

/**
 * Этап стал активным: перечисленные согласующие получают уведомление «документ ждёт вашего решения».
 * Публикуется внутри транзакции: слушатель — только {@code @TransactionalEventListener(phase = AFTER_COMMIT)},
 * иначе уведомление уйдёт по операции, которая затем откатится.
 *
 * {@code documentTitle} — название на момент отправки, кладётся в событие самим {@code approval}
 *: слушатель в {@code bot} не должен ходить в {@code document} за именем,
 * это отдельный вызов ради одной строки текста.
 */
public record ApprovalRequestedEvent(
        long documentId,
        int versionNo,
        int stageOrder,
        Set<Long> approverUserIds,
        long authorUserId,
        String documentTitle,
        /** Активный этап — утверждение: текст «ждёт вашего утверждения», а не «решения». */
        boolean endorsement
) {
    public ApprovalRequestedEvent {
        approverUserIds = Set.copyOf(approverUserIds);
    }
}
