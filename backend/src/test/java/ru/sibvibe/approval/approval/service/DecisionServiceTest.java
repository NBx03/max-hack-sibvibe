package ru.sibvibe.approval.approval.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import ru.sibvibe.approval.approval.dto.DecisionRequest;
import ru.sibvibe.approval.approval.dto.DecisionRequest.Action;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.entity.ApprovalStep.Decision;
import ru.sibvibe.approval.approval.event.ApprovalRequestedEvent;
import ru.sibvibe.approval.approval.event.DocumentDecidedEvent;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.service.DocumentState;
import ru.sibvibe.approval.document.service.DocumentStateService;
import ru.sibvibe.approval.document.service.DocumentStateService.DocumentSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Решения: этапы (AND), fail-fast с SKIPPED, идемпотентность и все отказы STEP_NOT_ACTIVE. */
class DecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final long DOC = 1;
    private static final long ORG = 10;
    private static final long AUTHOR = 100;
    private static final long ME = 7;
    private static final String TITLE = "Заявление на отпуск";

    private final DocumentStateService documents = mock(DocumentStateService.class);
    private final ApprovalStepRepository steps = mock(ApprovalStepRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DecisionService service = new DecisionService(
            documents, steps, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private final List<ApprovalStep> version = new ArrayList<>();

    @Test
    void approvalOfTheLastPendingStepOfTheLastStageApprovesTheDocument() {
        ApprovalStep mine = pending(1, 1, ME);
        givenInApproval(1, mine);

        service.decide(user(), 1, approve());

        assertThat(mine.getDecision()).isEqualTo(Decision.APPROVED);
        assertThat(mine.getDecidedAt()).isEqualTo(NOW);
        verify(documents).approve(DOC);
        DocumentDecidedEvent event = event(DocumentDecidedEvent.class);
        assertThat(event.outcome()).isEqualTo(DocumentDecidedEvent.Outcome.APPROVED);
        assertThat(event.deciderUserId()).isEqualTo(ME);
        assertThat(event.documentTitle()).isEqualTo(TITLE);
    }

    @Test
    void carriedOverEndorsementMakesTheOutcomeEndorsedEvenIfAnEarlierApproverDecidesLast() {
        // утверждение перенесено с прошлой версии, последним решает согласующий этапа 1
        ApprovalStep mine = pending(1, 1, ME);
        ApprovalStep endorsement = pending(2, 2, 9);
        endorsement.setKind(ApprovalStep.Kind.ENDORSEMENT);
        endorsement.setDecision(Decision.APPROVED);
        endorsement.setAutoReason(ApprovalStep.AutoReason.CARRIED_OVER);
        givenInApproval(1, mine, endorsement);

        service.decide(user(), 1, approve());

        verify(documents).approve(DOC);
        assertThat(event(DocumentDecidedEvent.class).endorsed()).isTrue();
    }

    @Test
    void nextStageBeingAnEndorsementIsFlaggedForTheNotification() {
        ApprovalStep mine = pending(1, 1, ME);
        ApprovalStep endorsement = pending(2, 2, 9);
        endorsement.setKind(ApprovalStep.Kind.ENDORSEMENT);
        givenInApproval(1, mine, endorsement);

        service.decide(user(), 1, approve());

        assertThat(event(ApprovalRequestedEvent.class).endorsement()).isTrue();
    }

    @Test
    void plainApprovalWithoutEndorsementIsNotEndorsed() {
        ApprovalStep mine = pending(1, 1, ME);
        givenInApproval(1, mine);

        service.decide(user(), 1, approve());

        assertThat(event(DocumentDecidedEvent.class).endorsed()).isFalse();
    }

    @Test
    void stageWithAnotherPendingParticipantStaysOpen() {
        ApprovalStep mine = pending(1, 1, ME);
        ApprovalStep parallel = pending(2, 1, 8);
        givenInApproval(1, mine, parallel);

        service.decide(user(), 1, approve());

        assertThat(mine.getDecision()).isEqualTo(Decision.APPROVED);
        assertThat(parallel.getDecision()).isEqualTo(Decision.PENDING);
        verify(documents, never()).advanceStage(anyLong(), anyInt());
        verify(documents, never()).approve(anyLong());
    }

    @Test
    void closingAStageActivatesTheNextOneAndNotifiesItsApprovers() {
        ApprovalStep mine = pending(1, 1, ME);
        ApprovalStep next = pending(2, 2, 8);
        givenInApproval(1, mine, next);

        service.decide(user(), 1, approve());

        assertThat(next.getActivatedAt()).isEqualTo(NOW);
        verify(documents).advanceStage(DOC, 2);
        ApprovalRequestedEvent event = event(ApprovalRequestedEvent.class);
        assertThat(event.stageOrder()).isEqualTo(2);
        assertThat(event.approverUserIds()).containsExactly(8L);
        assertThat(event.documentTitle()).isEqualTo(TITLE);
    }

    @Test
    void stagesWithNoPendingSteps_areSkippedWhenAdvancing() {
        ApprovalStep mine = pending(1, 1, ME);
        ApprovalStep autoApproved = pending(2, 2, AUTHOR);
        autoApproved.setDecision(Decision.APPROVED);
        ApprovalStep last = pending(3, 3, 9);
        givenInApproval(1, mine, autoApproved, last);

        service.decide(user(), 1, approve());

        verify(documents).advanceStage(DOC, 3);
        assertThat(last.getActivatedAt()).isEqualTo(NOW);
    }

    @Test
    void returnRequiresACommentAndSkipsEveryOtherPendingStepOfTheVersion() {
        ApprovalStep mine = pending(1, 1, ME);
        ApprovalStep sibling = pending(2, 1, 8);
        ApprovalStep later = pending(3, 2, 9);
        givenInApproval(1, mine, sibling, later);

        assertThatThrownBy(() -> service.decide(user(), 1, new DecisionRequest(Action.RETURN, "  ")))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("COMMENT_REQUIRED");
                    assertThat(e.httpStatus().value()).isEqualTo(400);
                });
        assertThat(mine.getDecision()).isEqualTo(Decision.PENDING);

        service.decide(user(), 1, new DecisionRequest(Action.RETURN, "  Уточните сумму "));

        assertThat(mine.getDecision()).isEqualTo(Decision.RETURNED);
        assertThat(mine.getComment()).isEqualTo("Уточните сумму");
        assertThat(sibling.getDecision()).isEqualTo(Decision.SKIPPED);
        assertThat(later.getDecision()).isEqualTo(Decision.SKIPPED);
        verify(documents).returnForRevision(DOC);
        DocumentDecidedEvent event = event(DocumentDecidedEvent.class);
        assertThat(event.outcome()).isEqualTo(DocumentDecidedEvent.Outcome.RETURNED);
        assertThat(event.deciderUserId()).isEqualTo(ME);
        assertThat(event.documentTitle()).isEqualTo(TITLE);
    }

    @Test
    void rejectionSkipsTheRestAndClosesTheDocument() {
        ApprovalStep mine = pending(1, 1, ME);
        ApprovalStep other = pending(2, 2, 8);
        givenInApproval(1, mine, other);

        assertThatThrownBy(() -> service.decide(user(), 1, new DecisionRequest(Action.REJECT, null)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("COMMENT_REQUIRED"));
        service.decide(user(), 1, new DecisionRequest(Action.REJECT, "Не по регламенту"));

        assertThat(mine.getDecision()).isEqualTo(Decision.REJECTED);
        assertThat(other.getDecision()).isEqualTo(Decision.SKIPPED);
        verify(documents).reject(DOC);
    }

    @Test
    void commentLengthIsCheckedAfterTrimmingAndABlankOneIsNotALongOne() {
        ApprovalStep mine = pending(1, 1, ME);
        givenInApproval(1, mine);
        String limit = "я".repeat(2000);

        // пробелы по краям не считаются: ровно 2000 полезных символов проходят
        service.decide(user(), 1, new DecisionRequest(Action.RETURN, " \t" + limit + "\n "));
        assertThat(mine.getComment()).isEqualTo(limit);

        ApprovalStep other = pending(1, 1, ME);
        givenInApproval(1, other);
        assertThatThrownBy(() -> service.decide(user(), 1, new DecisionRequest(Action.RETURN, limit + "я")))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.httpStatus().value()).isEqualTo(400);
                });
        // длинная строка из одних пробелов — это отсутствие комментария, а не «слишком длинный»
        assertThatThrownBy(() -> service.decide(user(), 1, new DecisionRequest(Action.RETURN, " ".repeat(5000))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("COMMENT_REQUIRED"));
        // и у согласования комментарий необязателен, но предел тот же
        assertThatThrownBy(() -> service.decide(user(), 1, new DecisionRequest(Action.APPROVE, limit + "я")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        assertThat(other.getDecision()).isEqualTo(Decision.PENDING);
    }

    @Test
    void repeatingTheSameDecisionIsHarmless() {
        ApprovalStep mine = pending(1, 1, ME);
        mine.setDecision(Decision.APPROVED);
        mine.setDecidedAt(NOW.minusSeconds(60));
        givenInApproval(1, mine);

        assertThat(service.decide(user(), 1, approve())).isEqualTo(DOC);

        assertThat(mine.getDecidedAt()).as("повтор ничего не меняет").isEqualTo(NOW.minusSeconds(60));
        verify(documents, never()).approve(anyLong());
        verify(documents, never()).advanceStage(anyLong(), anyInt());
    }

    @Test
    void stepSkippedByAnotherApproversReturnExplainsWhy() {
        ApprovalStep mine = pending(1, 1, ME);
        mine.setDecision(Decision.SKIPPED);
        givenDocument(DocumentState.RETURNED, null, mine);

        assertThatThrownBy(() -> service.decide(user(), 1, approve()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("STEP_NOT_ACTIVE");
                    assertThat(e.httpStatus().value()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("Документ уже возвращён");
                });
    }

    @Test
    void differentDecisionOnAClosedStepAndRejectedDocumentAreConflicts() {
        ApprovalStep mine = pending(1, 1, ME);
        mine.setDecision(Decision.APPROVED);
        givenInApproval(1, mine);
        assertThatThrownBy(() -> service.decide(user(), 1, new DecisionRequest(Action.RETURN, "нет")))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("STEP_NOT_ACTIVE"));

        ApprovalStep skipped = pending(1, 1, ME);
        skipped.setDecision(Decision.SKIPPED);
        givenDocument(DocumentState.REJECTED, null, skipped);
        assertThatThrownBy(() -> service.decide(user(), 1, approve()))
                .hasMessage("Документ уже отклонён");
    }

    @Test
    void stepOfAStageThatHasNotStartedIsNotActive() {
        ApprovalStep later = pending(1, 2, ME);
        givenInApproval(1, later);

        assertThatThrownBy(() -> service.decide(user(), 1, approve()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("STEP_NOT_ACTIVE"));
        assertThat(later.getDecision()).isEqualTo(Decision.PENDING);
    }

    @Test
    void stepOfAnOlderVersionOrOfANonActiveDocumentIsNotActive() {
        ApprovalStep old = pending(1, 1, ME);
        old.setVersionNo(1);
        givenDocument(DocumentState.IN_APPROVAL, 1, 2, old);
        assertThatThrownBy(() -> service.decide(user(), 1, approve()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("STEP_NOT_ACTIVE"));

        ApprovalStep draftStep = pending(1, 1, ME);
        givenDocument(DocumentState.DRAFT, null, draftStep);
        assertThatThrownBy(() -> service.decide(user(), 1, approve()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("STEP_NOT_ACTIVE"));
    }

    @Test
    void anotherUsersStepLooksNonexistent() {
        when(steps.findDocumentIdOfStep(1L, ME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide(user(), 1, approve())).isInstanceOf(NotFoundException.class);
        verify(documents, never()).lockInOrg(anyLong(), anyLong());
    }

    @Test
    void userWithoutACompanyIsForbidden() {
        var identity = new CurrentUser.UserIdentity(ME, "Я");
        var noCompany = new CurrentUser(identity, identity, null, null, Set.of(), false, null);

        assertThatThrownBy(() -> service.decide(noCompany, 1, approve()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
    }

    // ---------- вспомогательное ----------

    private void givenInApproval(int currentStage, ApprovalStep... all) {
        givenDocument(DocumentState.IN_APPROVAL, currentStage, 1, all);
    }

    private void givenDocument(DocumentState status, Integer currentStage, ApprovalStep... all) {
        givenDocument(status, currentStage, 1, all);
    }

    private void givenDocument(DocumentState status, Integer currentStage, int versionNo, ApprovalStep... all) {
        version.clear();
        version.addAll(List.of(all));
        ApprovalStep target = all[0];
        when(steps.findDocumentIdOfStep(1L, ME)).thenReturn(Optional.of(DOC));
        when(documents.lockInOrg(DOC, ORG)).thenReturn(
                new DocumentSnapshot(DOC, ORG, AUTHOR, 5, status, versionNo, currentStage, TITLE));
        when(steps.findById(1L)).thenReturn(Optional.of(target));
        when(steps.findByDocumentIdAndVersionNoOrderByStageOrderAscIdAsc(DOC, versionNo)).thenReturn(version);
    }

    private ApprovalStep pending(long id, int stage, long approver) {
        ApprovalStep step = new ApprovalStep();
        step.setId(id);
        step.setDocumentId(DOC);
        step.setVersionNo(1);
        step.setApproverId(approver);
        step.setRoleId(20L);
        step.setStageOrder(stage);
        step.setOrigin(ApprovalStep.Origin.TEMPLATE);
        step.setDecision(Decision.PENDING);
        step.setActivatedAt(NOW.minusSeconds(3600));
        step.setCreatedAt(NOW.minusSeconds(7200));
        return step;
    }

    private <T> T event(Class<T> type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(type);
        return type.cast(captor.getValue());
    }

    private static DecisionRequest approve() {
        return new DecisionRequest(Action.APPROVE, null);
    }

    private static CurrentUser user() {
        var identity = new CurrentUser.UserIdentity(ME, "Согласующий");
        return new CurrentUser(identity, identity, 50L, ORG, Set.of(), false, null);
    }
}
