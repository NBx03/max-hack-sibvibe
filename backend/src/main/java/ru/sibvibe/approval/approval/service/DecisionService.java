package ru.sibvibe.approval.approval.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.approval.dto.DecisionRequest;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.event.ApprovalRequestedEvent;
import ru.sibvibe.approval.approval.event.DocumentDecidedEvent;
import ru.sibvibe.approval.approval.event.DocumentWithdrawnEvent;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.service.DocumentState;
import ru.sibvibe.approval.document.service.DocumentStateService;
import ru.sibvibe.approval.document.service.DocumentStateService.DocumentSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Решение согласующего по шагу: согласовать, вернуть, отклонить. Решает только человек (docs/DESIGN-DECISIONS.md).
 *
 * <ul>
 *   <li>Шаг ищется среди шагов самого пользователя; чужой шаг — 404.</li>
 *   <li>Документ блокируется до чтения шага: два одновременных решения идут по очереди.</li>
 *   <li>Решить можно только шаг в {@code PENDING} активного этапа текущей версии — иначе 409
 *       {@code STEP_NOT_ACTIVE}. Повтор того же решения тем же человеком — успех без изменений.</li>
 *   <li>Этап закрыт, когда одобрили все его шаги (AND); кворума «M из N» в MVP нет.</li>
 *   <li>Возврат и отклонение fail-fast: остальные ожидающие шаги версии становятся {@code SKIPPED}, а не
 *       удаляются, чтобы история осталась честной.</li>
 * </ul>
 */
@Service
public class DecisionService {

    /** Предел длины комментария после обрезки пробелов по краям (контракт: до 2000 символов). */
    static final int MAX_COMMENT_LENGTH = 2000;

    private final DocumentStateService documents;
    private final ApprovalStepRepository steps;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DecisionService(
            DocumentStateService documents,
            ApprovalStepRepository steps,
            ApplicationEventPublisher events,
            Clock clock
    ) {
        this.documents = documents;
        this.steps = steps;
        this.events = events;
        this.clock = clock;
    }

    /** @return id документа, по которому принято (или повторено) решение */
    @Transactional
    public long decide(CurrentUser user, long stepId, DecisionRequest request) {
        DecisionRequest.Action action = request.decision();
        String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment().strip();
        if (action != DecisionRequest.Action.APPROVE && comment == null) {
            throw ApprovalErrors.commentRequired();
        }
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw ApprovalErrors.validation("Комментарий не длиннее " + MAX_COMMENT_LENGTH + " символов");
        }
        if (user.orgId() == null) {
            throw ApprovalErrors.forbidden("Для работы с документами нужно состоять в компании");
        }

        // Только число: строку шага читаем уже под блокировкой документа, иначе получим устаревшее состояние.
        long documentId = steps.findDocumentIdOfStep(stepId, user.userId()).orElseThrow(NotFoundException::new);
        DocumentSnapshot document = documents.lockInOrg(documentId, user.orgId());
        ApprovalStep step = steps.findById(stepId).orElseThrow(NotFoundException::new);
        if (!step.getApproverId().equals(user.userId())) {
            throw new NotFoundException();
        }

        ApprovalStep.Decision wanted = decisionOf(action);
        if (step.getDecision() == wanted) {
            return documentId;
        }
        if (step.getDecision() != ApprovalStep.Decision.PENDING) {
            throw ApprovalErrors.stepNotActive(closedStepMessage(step, document));
        }
        if (document.status() != DocumentState.IN_APPROVAL
                || step.getVersionNo() != document.currentVersionNo()
                || document.currentStage() == null
                || step.getStageOrder().intValue() != document.currentStage()
                || step.getActivatedAt() == null) {
            throw ApprovalErrors.stepNotActive("Этот этап согласования сейчас не активен");
        }

        Instant now = clock.instant();
        List<ApprovalStep> versionSteps = steps
                .findByDocumentIdAndVersionNoOrderByStageOrderAscIdAsc(documentId, document.currentVersionNo());
        step.setDecision(wanted);
        step.setComment(comment);
        step.setDecidedAt(now);

        if (action == DecisionRequest.Action.APPROVE) {
            approve(document, step, versionSteps, now);
        } else {
            versionSteps.stream()
                    .filter(other -> other.getDecision() == ApprovalStep.Decision.PENDING)
                    .forEach(other -> other.setDecision(ApprovalStep.Decision.SKIPPED));
            if (action == DecisionRequest.Action.RETURN) {
                documents.returnForRevision(documentId);
                publish(document, DocumentDecidedEvent.Outcome.RETURNED, user.userId());
            } else {
                documents.reject(documentId);
                publish(document, DocumentDecidedEvent.Outcome.REJECTED, user.userId());
            }
        }
        return documentId;
    }

    /**
     * «Отозвать с согласования» — только автор и только пока документ на согласовании (почему:
     * заметил ошибку после отправки — раньше оставалось ждать, пока вернут). Ожидающие шаги версии
     * получают {@code SKIPPED}, как при возврате, документ — {@code RETURNED}.
     */
    @Transactional
    public void withdraw(CurrentUser user, long documentId) {
        DocumentSnapshot document = documents.lockForAuthor(documentId, user, "Отозвать документ может только автор");
        if (document.status() != DocumentState.IN_APPROVAL) {
            throw ApprovalErrors.invalidState("Документ уже не на согласовании");
        }
        List<ApprovalStep> versionSteps = steps
                .findByDocumentIdAndVersionNoOrderByStageOrderAscIdAsc(documentId, document.currentVersionNo());
        Set<Long> waiting = versionSteps.stream()
                .filter(step -> step.getDecision() == ApprovalStep.Decision.PENDING && step.getActivatedAt() != null)
                .map(ApprovalStep::getApproverId)
                .collect(Collectors.toSet());
        versionSteps.stream()
                .filter(step -> step.getDecision() == ApprovalStep.Decision.PENDING)
                .forEach(step -> step.setDecision(ApprovalStep.Decision.SKIPPED));
        documents.withdraw(documentId);
        events.publishEvent(new DocumentWithdrawnEvent(documentId, document.title(), waiting));
    }

    private void approve(DocumentSnapshot document, ApprovalStep step, List<ApprovalStep> versionSteps, Instant now) {
        boolean stageStillOpen = versionSteps.stream().anyMatch(other ->
                other.getStageOrder().equals(step.getStageOrder())
                        && other.getDecision() == ApprovalStep.Decision.PENDING);
        if (stageStillOpen) {
            return;
        }
        OptionalInt next = versionSteps.stream()
                .filter(other -> other.getDecision() == ApprovalStep.Decision.PENDING
                        && other.getStageOrder() > step.getStageOrder())
                .mapToInt(ApprovalStep::getStageOrder)
                .min();
        if (next.isPresent()) {
            Set<Long> approvers = versionSteps.stream()
                    .filter(other -> other.getDecision() == ApprovalStep.Decision.PENDING
                            && other.getStageOrder() == next.getAsInt())
                    .peek(other -> other.setActivatedAt(now))
                    .map(ApprovalStep::getApproverId)
                    .collect(Collectors.toSet());
            documents.advanceStage(document.id(), next.getAsInt());
            boolean endorsement = versionSteps.stream().anyMatch(other -> other.getStageOrder() == next.getAsInt()
                    && other.getKind() == ApprovalStep.Kind.ENDORSEMENT);
            events.publishEvent(new ApprovalRequestedEvent(document.id(), document.currentVersionNo(),
                    next.getAsInt(), approvers, document.authorId(), document.title(), endorsement));
        } else {
            documents.approve(document.id());
            events.publishEvent(new DocumentDecidedEvent(document.id(), document.currentVersionNo(), document.authorId(),
                    DocumentDecidedEvent.Outcome.APPROVED, step.getApproverId(), document.title(),
                    ApprovalStep.endorsedIn(versionSteps)));
        }
    }

    private void publish(DocumentSnapshot document, DocumentDecidedEvent.Outcome outcome, long deciderUserId) {
        events.publishEvent(new DocumentDecidedEvent(document.id(), document.currentVersionNo(),
                document.authorId(), outcome, deciderUserId, document.title(), false)); // возврат и отказ — не утверждение
    }

    private static ApprovalStep.Decision decisionOf(DecisionRequest.Action action) {
        return switch (action) {
            case APPROVE -> ApprovalStep.Decision.APPROVED;
            case RETURN -> ApprovalStep.Decision.RETURNED;
            case REJECT -> ApprovalStep.Decision.REJECTED;
        };
    }

    /** Шаг уже закрыт: причина понятна пользователю, особенно когда документ вернул или отклонил другой согласующий. */
    private static String closedStepMessage(ApprovalStep step, DocumentSnapshot document) {
        if (step.getDecision() == ApprovalStep.Decision.SKIPPED) {
            return switch (document.status()) {
                case RETURNED -> "Документ уже возвращён";
                case REJECTED -> "Документ уже отклонён";
                default -> "Этот шаг уже закрыт: документ изменился";
            };
        }
        return "Решение по этому шагу уже принято";
    }
}
