package ru.sibvibe.approval.approval.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.document.service.VersionChangesService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Какие одобрения прошлой версии остаются в силе. */
class CarriedApprovalsTest {

    private final VersionChangesService changes = mock(VersionChangesService.class);
    private final ApprovalStepRepository steps = mock(ApprovalStepRepository.class);
    private final CarriedApprovals carried = new CarriedApprovals(changes, steps);

    @Test
    void changedDocumentCarriesNothingAndDoesNotEvenLookAtOldSteps() {
        when(changes.sameAsPrevious(1L, 2)).thenReturn(false);

        assertThat(carried.of(1L, 2)).isEmpty();
        verify(steps, never()).findByDocumentIdAndVersionNoOrderByStageOrderAscIdAsc(anyLong(), anyInt());
    }

    @Test
    void unchangedDocumentCarriesOnlyRealApprovals() {
        when(changes.sameAsPrevious(1L, 2)).thenReturn(true);
        when(steps.findByDocumentIdAndVersionNoOrderByStageOrderAscIdAsc(1L, 1)).thenReturn(List.of(
                step(21L, 3L, ApprovalStep.Decision.APPROVED, null),
                step(22L, 4L, ApprovalStep.Decision.RETURNED, null),
                step(23L, 5L, ApprovalStep.Decision.SKIPPED, null),
                step(24L, 9L, ApprovalStep.Decision.APPROVED, ApprovalStep.AutoReason.AUTHOR_HOLDS_ROLE)));

        CarriedApprovals.Carried result = carried.of(1L, 2).orElseThrow();

        assertThat(result.fromVersionNo()).isEqualTo(1);
        assertThat(result.approvals()).containsExactly(new CarriedApprovals.Key(21L, 3L, ApprovalStep.Kind.APPROVAL));
        // вернувший документ решает заново, автосогласование автора при отправке получится само
        assertThat(result.covers(22L, 4L, ApprovalStep.Kind.APPROVAL)).isFalse();
        assertThat(result.covers(24L, 9L, ApprovalStep.Kind.APPROVAL)).isFalse();
        //: «согласовал» не становится «утвердил»
        assertThat(result.covers(21L, 3L, ApprovalStep.Kind.ENDORSEMENT)).isFalse();
    }

    @Test
    void firstVersionHasNothingToCarry() {
        assertThat(carried.of(1L, 1)).isEmpty();
        verify(changes, never()).sameAsPrevious(anyLong(), anyInt());
    }

    private static ApprovalStep step(long roleId, long userId, ApprovalStep.Decision decision, ApprovalStep.AutoReason reason) {
        ApprovalStep step = new ApprovalStep();
        step.setRoleId(roleId);
        step.setApproverId(userId);
        step.setDecision(decision);
        step.setAutoReason(reason);
        return step;
    }
}
