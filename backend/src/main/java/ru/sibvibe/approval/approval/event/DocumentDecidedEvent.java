package ru.sibvibe.approval.approval.event;

/**
 * По документу принято итоговое решение: автор получает уведомление. {@code deciderUserId} пуст, если документ
 * согласован сразу при отправке (все шаги автосогласованы). Публикуется внутри транзакции: слушатель —
 * только {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 *
 * {@code documentTitle} — см. {@link ApprovalRequestedEvent}: название кладёт
 * {@code approval}, а не запрашивает слушатель в {@code bot}.
 */
public record DocumentDecidedEvent(
        long documentId,
        int versionNo,
        long authorUserId,
        Outcome outcome,
        Long deciderUserId,
        String documentTitle,
        /** Итог «Утверждён», а не «Согласован» — последним решал утверждающий. */
        boolean endorsed
) {
    public enum Outcome { APPROVED, RETURNED, REJECTED }
}
