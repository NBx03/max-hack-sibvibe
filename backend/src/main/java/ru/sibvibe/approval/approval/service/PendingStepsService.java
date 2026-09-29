package ru.sibvibe.approval.approval.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.event.ApproverRemovedEvent;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.document.service.DocumentState;
import ru.sibvibe.approval.document.service.DocumentStateService;
import ru.sibvibe.approval.document.service.DocumentStateService.DocumentSnapshot;
import ru.sibvibe.approval.organization.PendingStepsGuard;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Исключили согласующего или сняли с него роль — документы, где он ещё не решил, возвращаются авторам
 * ({@link PendingStepsGuard}). Его шаги получают {@code SKIPPED} с отметкой
 * {@code MEMBER_REMOVED} — в истории видно, почему документ вернулся; остальные ожидающие шаги версии —
 * {@code SKIPPED}, как при обычном возврате.
 */
@Service
public class PendingStepsService implements PendingStepsGuard {

    private final ApprovalStepRepository stepRepository;
    private final DocumentStateService documents;
    private final ApplicationEventPublisher events;

    public PendingStepsService(
            ApprovalStepRepository stepRepository, DocumentStateService documents, ApplicationEventPublisher events
    ) {
        this.stepRepository = stepRepository;
        this.documents = documents;
        this.events = events;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int releaseSteps(long orgId, long userId, Collection<Long> roleIds) {
        if (roleIds != null && roleIds.isEmpty()) {
            return 0;
        }
        // По возрастанию id документа: несколько одновременных исключений блокируют документы в одном порядке.
        List<Long> documentIds = stepRepository.findDocumentIdsWithDecision(
                userId, ApprovalStep.Decision.PENDING, roleIds == null, roleIds == null ? List.of(-1L) : roleIds);
        int returned = 0;
        for (Long documentId : documentIds) {
            if (returnDocument(orgId, documentId, userId, roleIds)) {
                returned++;
            }
        }
        return returned;
    }

    private boolean returnDocument(long orgId, long documentId, long userId, Collection<Long> roleIds) {
        DocumentSnapshot document = documents.lockIfInOrg(documentId, orgId).orElse(null);
        if (document == null || document.status() != DocumentState.IN_APPROVAL) {
            return false;
        }
        List<ApprovalStep> versionSteps = stepRepository
                .findByDocumentIdAndVersionNoOrderByStageOrderAscIdAsc(documentId, document.currentVersionNo());
        // Шаги читаются заново под блокировкой документа: пока ждали очередь, человек мог успеть решить.
        List<ApprovalStep> removed = versionSteps.stream()
                .filter(step -> step.getApproverId() == userId
                        && step.getDecision() == ApprovalStep.Decision.PENDING
                        && (roleIds == null || roleIds.contains(step.getRoleId())))
                .toList();
        if (removed.isEmpty()) {
            return false;
        }
        Set<Long> waiting = versionSteps.stream()
                .filter(step -> step.getDecision() == ApprovalStep.Decision.PENDING && step.getActivatedAt() != null)
                .map(ApprovalStep::getApproverId)
                .filter(approver -> approver != userId)
                .collect(Collectors.toSet());
        versionSteps.stream()
                .filter(step -> step.getDecision() == ApprovalStep.Decision.PENDING)
                .forEach(step -> step.setDecision(ApprovalStep.Decision.SKIPPED));
        removed.forEach(step -> step.setAutoReason(ApprovalStep.AutoReason.MEMBER_REMOVED));
        documents.returnForRevision(documentId);
        events.publishEvent(new ApproverRemovedEvent(
                documentId, document.title(), document.authorId(), userId, roleIds != null, waiting));
        return true;
    }
}
