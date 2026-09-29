package ru.sibvibe.approval.approval.event;

import java.util.Set;

/**
 * Согласующего исключили из компании или сняли с него роль, и документ, где он ещё не решил, вернулся автору
 *. Автору — что случилось и что делать, тем, чья очередь уже подошла, — что решение
 * больше не нужно. {@code roleRemoved}: {@code true} — снята роль, {@code false} — исключён из компании.
 */
public record ApproverRemovedEvent(
        long documentId,
        String documentTitle,
        long authorUserId,
        long removedUserId,
        boolean roleRemoved,
        Set<Long> waitingApproverIds
) {
    public ApproverRemovedEvent {
        waitingApproverIds = Set.copyOf(waitingApproverIds);
    }
}
