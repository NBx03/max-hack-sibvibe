package ru.sibvibe.approval.approval.event;

/**
 * Шаг согласования застрял: согласующий не решил его дольше настроенного порога.
 * Публикует {@code approval.StuckStepReminderJob} внутри той же транзакции, что и атомарную отметку
 * шага напомненным - слушатель в {@code bot} только шлёт сообщение и не отвечает за то, чтобы не
 * напомнить дважды, это уже сделано на уровне {@code UPDATE}.
 *
 * {@code waitingHours} - готовое число часов ожидания на момент запуска планировщика, а не {@code Instant}:
 * текст напоминания строит {@code bot} (как и {@code documentTitle} - см. {@link ApprovalRequestedEvent}),
 * а не пересчитывает время сам по своим часам.
 */
public record StuckStepReminderEvent(
        long documentId,
        int versionNo,
        int stageOrder,
        long approverUserId,
        String documentTitle,
        long waitingHours,
        /** Шаг — утверждение: «ждёт вашего утверждения», а не «решения». */
        boolean endorsement
) {
}
