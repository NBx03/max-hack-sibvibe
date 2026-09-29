package ru.sibvibe.approval.approval.service;

import org.springframework.stereotype.Service;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.document.service.DemoDocumentSeedService;
import ru.sibvibe.approval.organization.DemoSandboxContentSeeder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Наполняет песочницу документами и историей согласования в одной транзакции с её созданием. */
@Service
public class DemoSandboxContentService implements DemoSandboxContentSeeder {

    private final DemoDocumentSeedService documents;
    private final ApprovalStepRepository steps;

    public DemoSandboxContentService(DemoDocumentSeedService documents, ApprovalStepRepository steps) {
        this.documents = documents;
        this.steps = steps;
    }

    @Override
    public void seed(Seed seed) {
        DemoDocumentSeedService.SeededDocuments ids =
                documents.seed(seed.orgId(), seed.authorId(), seed.createdAt());
        List<ApprovalStep> result = new ArrayList<>();

        result.addAll(route(ids.inApprovalId(), seed, Scenario.IN_APPROVAL));
        result.addAll(route(ids.approvedId(), seed, Scenario.APPROVED));
        result.addAll(route(ids.returnedId(), seed, Scenario.RETURNED));
        result.addAll(route(ids.rejectedId(), seed, Scenario.REJECTED));
        steps.saveAll(result);
    }

    private List<ApprovalStep> route(long documentId, Seed seed, Scenario scenario) {
        Instant created = seed.createdAt().minusSeconds(scenario.stepAgeSeconds);
        return List.of(
                step(documentId, seed.lawyerId(), seed.lawyerRoleId(), 1,
                        scenario.lawyer, created, created, decidedAt(scenario.lawyer, created),
                        scenario == Scenario.RETURNED ? "Уточните обоснование закупки" : null),
                step(documentId, seed.accountantId(), seed.accountantRoleId(), 1,
                        scenario.accountant, created, created,
                        decidedAt(scenario.accountant, created),
                        scenario == Scenario.REJECTED ? "Закупка не входит в утверждённый бюджет" : null),
                // Директор — утверждающий: так демонстрация сразу показывает «Утверждение» и статус «Утверждён».
                endorse(step(documentId, seed.directorId(), seed.directorRoleId(), 2,
                        scenario.director, created,
                        scenario == Scenario.APPROVED ? created.plusSeconds(1800) : null,
                        decidedAt(scenario.director, created.plusSeconds(1800)), null))
        );
    }

    private static ApprovalStep endorse(ApprovalStep step) {
        step.setKind(ApprovalStep.Kind.ENDORSEMENT);
        return step;
    }

    private ApprovalStep step(
            long documentId,
            long approverId,
            long roleId,
            int stage,
            ApprovalStep.Decision decision,
            Instant createdAt,
            Instant activatedAt,
            Instant decidedAt,
            String comment
    ) {
        ApprovalStep step = new ApprovalStep();
        step.setDocumentId(documentId);
        step.setVersionNo(1);
        step.setApproverId(approverId);
        step.setRoleId(roleId);
        step.setStageOrder(stage);
        step.setOrigin(ApprovalStep.Origin.TEMPLATE);
        step.setDecision(decision);
        step.setComment(comment);
        step.setCreatedAt(createdAt);
        step.setActivatedAt(activatedAt);
        step.setDecidedAt(decidedAt);
        return step;
    }

    private Instant decidedAt(ApprovalStep.Decision decision, Instant value) {
        return switch (decision) {
            case APPROVED, RETURNED, REJECTED -> value.plusSeconds(900);
            case PENDING, SKIPPED -> null;
        };
    }

    private enum Scenario {
        IN_APPROVAL(ApprovalStep.Decision.PENDING, ApprovalStep.Decision.PENDING,
                ApprovalStep.Decision.PENDING, 12_600),
        APPROVED(ApprovalStep.Decision.APPROVED, ApprovalStep.Decision.APPROVED,
                ApprovalStep.Decision.APPROVED, 9_900),
        RETURNED(ApprovalStep.Decision.RETURNED, ApprovalStep.Decision.SKIPPED,
                ApprovalStep.Decision.SKIPPED, 6_300),
        REJECTED(ApprovalStep.Decision.SKIPPED, ApprovalStep.Decision.REJECTED,
                ApprovalStep.Decision.SKIPPED, 2700);

        private final ApprovalStep.Decision lawyer;
        private final ApprovalStep.Decision accountant;
        private final ApprovalStep.Decision director;
        private final long stepAgeSeconds;

        Scenario(
                ApprovalStep.Decision lawyer,
                ApprovalStep.Decision accountant,
                ApprovalStep.Decision director,
                long stepAgeSeconds
        ) {
            this.lawyer = lawyer;
            this.accountant = accountant;
            this.director = director;
            this.stepAgeSeconds = stepAgeSeconds;
        }
    }
}
