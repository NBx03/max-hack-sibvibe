package ru.sibvibe.approval.approval.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.document.service.DemoDocumentSeedService;
import ru.sibvibe.approval.organization.DemoSandboxContentSeeder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoSandboxContentServiceTest {

    private final DemoDocumentSeedService documents = mock(DemoDocumentSeedService.class);
    private final ApprovalStepRepository steps = mock(ApprovalStepRepository.class);
    private final DemoSandboxContentService service = new DemoSandboxContentService(documents, steps);

    @Test
    void createsConsistentHistoryForEveryNonDraftDemoDocument() {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        var seed = new DemoSandboxContentSeeder.Seed(
                10, 20, 21, 22, 23, 31, 32, 33, now);
        when(documents.seed(10, 20, now))
                .thenReturn(new DemoDocumentSeedService.SeededDocuments(100, 101, 102, 103, 104));

        service.seed(seed);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ApprovalStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(steps).saveAll(captor.capture());
        List<ApprovalStep> saved = captor.getValue();
        assertThat(saved).hasSize(12);

        assertRoute(saved, 101, ApprovalStep.Decision.PENDING, ApprovalStep.Decision.PENDING,
                ApprovalStep.Decision.PENDING, false);
        assertRoute(saved, 102, ApprovalStep.Decision.APPROVED, ApprovalStep.Decision.APPROVED,
                ApprovalStep.Decision.APPROVED, true);
        assertRoute(saved, 103, ApprovalStep.Decision.RETURNED, ApprovalStep.Decision.SKIPPED,
                ApprovalStep.Decision.SKIPPED, false);
        assertRoute(saved, 104, ApprovalStep.Decision.SKIPPED, ApprovalStep.Decision.REJECTED,
                ApprovalStep.Decision.SKIPPED, false);
    }

    private void assertRoute(
            List<ApprovalStep> saved,
            long documentId,
            ApprovalStep.Decision lawyer,
            ApprovalStep.Decision accountant,
            ApprovalStep.Decision director,
            boolean directorActivated
    ) {
        List<ApprovalStep> route = saved.stream()
                .filter(step -> step.getDocumentId() == documentId)
                .toList();
        assertThat(route).extracting(ApprovalStep::getStageOrder).containsExactly(1, 1, 2);
        assertThat(route).extracting(ApprovalStep::getDecision)
                .containsExactly(lawyer, accountant, director);
        assertThat(route.get(0).getActivatedAt()).isNotNull();
        assertThat(route.get(1).getActivatedAt()).isNotNull();
        assertThat(route.get(2).getActivatedAt() != null).isEqualTo(directorActivated);
        assertThat(route).allSatisfy(step -> {
            assertThat(step.getVersionNo()).isEqualTo(1);
            assertThat(step.getOrigin()).isEqualTo(ApprovalStep.Origin.TEMPLATE);
        });
    }
}
