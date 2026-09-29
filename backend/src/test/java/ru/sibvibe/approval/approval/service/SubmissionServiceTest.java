package ru.sibvibe.approval.approval.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import ru.sibvibe.approval.approval.dto.SubmitRequest;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.event.ApprovalRequestedEvent;
import ru.sibvibe.approval.approval.event.DocumentDecidedEvent;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.service.DocumentCheckRules;
import ru.sibvibe.approval.document.service.DocumentState;
import ru.sibvibe.approval.document.service.DocumentStateService;
import ru.sibvibe.approval.document.service.DocumentStateService.DocumentSnapshot;
import ru.sibvibe.approval.organization.service.RoleService;
import ru.sibvibe.approval.organization.service.RouteTemplateService.TemplateRow;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Отправка: проверки до записи, состав шагов, активный этап и автосогласование. */
class SubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final long DOC = 1;
    private static final long ORG = 10;
    private static final long AUTHOR = 100;
    private static final long TYPE = 5;
    private static final String TITLE = "Служебная записка о закупке ноутбуков";

    private static final UserRef AUTHOR_REF = new UserRef(AUTHOR, "Автор");
    private static final UserRef BOB = new UserRef(2, "Боб");
    private static final UserRef CAROL = new UserRef(3, "Карол");
    private static final UserRef DAVE = new UserRef(4, "Дейв");
    private static final UserRef EVE = new UserRef(5, "Ева");
    private static final RoleRef HEAD = new RoleRef(20, "DEPARTMENT_HEAD", "Руководитель отдела");
    private static final RoleRef LAWYER = new RoleRef(21, "LAWYER", "Юрист");
    private static final RoleRef DIRECTOR = new RoleRef(22, "DIRECTOR", "Директор");
    private static final Map<Long, RoleRef> ROLES = Map.of(20L, HEAD, 21L, LAWYER, 22L, DIRECTOR);

    private final DocumentStateService documents = mock(DocumentStateService.class);
    private final RoutePlanner planner = mock(RoutePlanner.class);
    private final RoleService roles = mock(RoleService.class);
    private final ApprovalStepRepository steps = mock(ApprovalStepRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CarriedApprovals carried = mock(CarriedApprovals.class);
    private final SubmissionService service = new SubmissionService(
            documents, planner, roles, steps, events, carried, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void onlyDraftCanBeSubmittedAndNothingIsWrittenOtherwise() {
        givenDocument(DocumentState.IN_APPROVAL);

        assertThatThrownBy(() -> service.submit(author(), DOC, empty()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_STATE");
                    assertThat(e.httpStatus().value()).isEqualTo(409);
                });
        verify(steps, never()).saveAll(any());
    }

    @Test
    void blockingIssuesStopTheSubmission() {
        givenDocument(DocumentState.DRAFT);
        when(documents.submissionBlocker(DOC, 3)).thenReturn(Optional.of(DocumentCheckRules.Reason.BLOCKER_ISSUES));

        assertThatThrownBy(() -> service.submit(author(), DOC, empty()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("BLOCKING_ISSUES"));
        verify(steps, never()).saveAll(any());
    }

    @Test
    void mandatoryRoleWithNoHolderBlocksSubmissionWithTheRoleName() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 22, true)), Map.of());

        assertThatThrownBy(() -> service.submit(author(), DOC, empty()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("ROUTE_ROLE_EMPTY");
                    assertThat(e.details()).containsEntry("role", "Директор");
                });
        verify(steps, never()).saveAll(any());
    }

    @Test
    void severalCandidatesRequireAChoice() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, true)), Map.of(21L, List.of(BOB, CAROL)));

        assertThatThrownBy(() -> service.submit(author(), DOC, empty()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("SELECTION_REQUIRED");
                    assertThat(e.httpStatus().value()).isEqualTo(409);
                });
        verify(steps, never()).saveAll(any());
    }

    @Test
    void choiceMustBeOneOfTheCandidatesAndOnlyWhereAChoiceIsNeeded() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, true), row(2, 22, true)),
                Map.of(21L, List.of(BOB, CAROL), 22L, List.of(BOB)));

        // человек не из кандидатов и несуществующая пара «этап + роль» выглядят как «не найдено»
        assertThatThrownBy(() -> service.submit(author(), DOC, choices(choice(1, 21, 999))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.submit(author(), DOC, choices(choice(7, 21, 2))))
                .isInstanceOf(NotFoundException.class);
        // там, где кандидат один, выбирать нечего
        assertThatThrownBy(() -> service.submit(author(), DOC, choices(choice(1, 21, 2), choice(2, 22, 2))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        // один участник — один выбор
        assertThatThrownBy(() -> service.submit(author(), DOC, choices(choice(1, 21, 2), choice(1, 21, 3))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        verify(steps, never()).saveAll(any());
    }

    @Test
    void sequentialRouteActivatesOnlyTheFirstStageAndNotifiesItsApprovers() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, true), row(2, 22, true)),
                Map.of(21L, List.of(BOB, CAROL), 22L, List.of(CAROL)));

        service.submit(author(), DOC, choices(choice(1, 21, 2)));

        List<ApprovalStep> saved = savedSteps();
        assertThat(saved).hasSize(2);
        ApprovalStep first = saved.get(0);
        assertThat(first.getStageOrder()).isEqualTo(1);
        assertThat(first.getApproverId()).isEqualTo(BOB.id());
        assertThat(first.getDecision()).isEqualTo(ApprovalStep.Decision.PENDING);
        assertThat(first.getActivatedAt()).isEqualTo(NOW);
        assertThat(first.getVersionNo()).isEqualTo(3);
        assertThat(first.getOrigin()).isEqualTo(ApprovalStep.Origin.TEMPLATE);
        ApprovalStep second = saved.get(1);
        assertThat(second.getStageOrder()).isEqualTo(2);
        assertThat(second.getActivatedAt()).as("этап ещё не начался").isNull();

        verify(documents).moveToApproval(DOC, 1);
        verify(documents, never()).approve(anyLong());
        ApprovalRequestedEvent event = event(ApprovalRequestedEvent.class);
        assertThat(event.stageOrder()).isEqualTo(1);
        assertThat(event.approverUserIds()).containsExactly(BOB.id());
        assertThat(event.authorUserId()).isEqualTo(AUTHOR);
        assertThat(event.documentTitle()).isEqualTo(TITLE);
    }

    @Test
    void firstStageWithoutAnyoneIsSkippedAndTheNextOneBecomesActive() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 20, false), row(2, 22, true)), Map.of(22L, List.of(CAROL)));

        service.submit(author(), DOC, empty());

        // Пустых этапов в маршруте нет: пропущенный этап шаблона не оставляет дыры в нумерации.
        assertThat(savedSteps()).singleElement().satisfies(step -> {
            assertThat(step.getStageOrder()).isEqualTo(1);
            assertThat(step.getActivatedAt()).isEqualTo(NOW);
        });
        verify(documents).moveToApproval(DOC, 1);
    }

    @Test
    void authorHoldingTheOnlyMandatoryRoleApprovesItAutomaticallyAndTheDocumentIsApprovedAtOnce() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 20, false), row(2, 22, true)), Map.of(22L, List.of(AUTHOR_REF)));

        service.submit(author(), DOC, empty());

        assertThat(savedSteps()).singleElement().satisfies(step -> {
            assertThat(step.getApproverId()).isEqualTo(AUTHOR);
            assertThat(step.getDecision()).isEqualTo(ApprovalStep.Decision.APPROVED);
            assertThat(step.getAutoReason()).isEqualTo(ApprovalStep.AutoReason.AUTHOR_HOLDS_ROLE);
            assertThat(step.getDecidedAt()).isEqualTo(NOW);
            assertThat(step.getActivatedAt()).isEqualTo(NOW);
        });
        var order = inOrder(documents);
        order.verify(documents).moveToApproval(DOC, null);
        order.verify(documents).approve(DOC);
        DocumentDecidedEvent event = event(DocumentDecidedEvent.class);
        assertThat(event.outcome()).isEqualTo(DocumentDecidedEvent.Outcome.APPROVED);
        assertThat(event.deciderUserId()).isNull();
        assertThat(event.documentTitle()).isEqualTo(TITLE);
    }

    @Test
    void authorStepInALaterStageDoesNotDelayTheFirstRealApproval() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, true), row(2, 22, true)),
                Map.of(21L, List.of(BOB), 22L, List.of(AUTHOR_REF)));

        service.submit(author(), DOC, empty());

        List<ApprovalStep> saved = savedSteps();
        assertThat(saved).hasSize(2);
        assertThat(saved).filteredOn(step -> step.getApproverId() == BOB.id())
                .singleElement().extracting(ApprovalStep::getDecision).isEqualTo(ApprovalStep.Decision.PENDING);
        assertThat(saved).filteredOn(step -> step.getApproverId() == AUTHOR)
                .singleElement().extracting(ApprovalStep::getDecision).isEqualTo(ApprovalStep.Decision.APPROVED);
        verify(documents).moveToApproval(DOC, 1);
        verify(documents, never()).approve(anyLong());
    }

    @Test
    void addedApproverBecomesAnExtraStepInTheirRoleAndTheRouteOnlyGrows() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, true), row(2, 22, true)),
                Map.of(21L, List.of(BOB), 22L, List.of(CAROL)));
        when(roles.carriersOf(ORG, 21)).thenReturn(List.of(BOB, DAVE));

        service.submit(author(), DOC, new SubmitRequest(null, List.of(choice(1, 21, DAVE.id()))));

        List<ApprovalStep> saved = savedSteps();
        assertThat(saved).hasSize(3);
        assertThat(saved).filteredOn(step -> step.getOrigin() == ApprovalStep.Origin.ADDED_BY_AUTHOR)
                .singleElement().satisfies(extra -> {
                    assertThat(extra.getApproverId()).isEqualTo(DAVE.id());
                    assertThat(extra.getRoleId()).isEqualTo(21L);
                    assertThat(extra.getStageOrder()).isEqualTo(1);
                    assertThat(extra.getDecision()).isEqualTo(ApprovalStep.Decision.PENDING);
                    assertThat(extra.getActivatedAt()).isEqualTo(NOW);
                });
        // шаблонные шаги на месте: обязательного участника убрать нельзя
        assertThat(saved).filteredOn(step -> step.getOrigin() == ApprovalStep.Origin.TEMPLATE).hasSize(2);
        assertThat(event(ApprovalRequestedEvent.class).approverUserIds()).containsExactlyInAnyOrder(BOB.id(), DAVE.id());
    }

    @Test
    void invalidExtraApproversAreRejected() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, true)), Map.of(21L, List.of(BOB)));
        when(roles.carriersOf(ORG, 21)).thenReturn(List.of(BOB, CAROL, AUTHOR_REF));

        // не автор и не тот, кто не носит роль
        assertThatThrownBy(() -> service.submit(author(), DOC, extras(choice(1, 21, AUTHOR))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> service.submit(author(), DOC, extras(choice(1, 21, 999))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("INVALID_STATE"));
        // этап вне шаблона и чужая роль
        assertThatThrownBy(() -> service.submit(author(), DOC, extras(choice(9, 21, 2))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.submit(author(), DOC, extras(choice(1, 777, 2))))
                .isInstanceOf(NotFoundException.class);
        // один и тот же дважды
        assertThatThrownBy(() -> service.submit(author(), DOC, extras(choice(1, 21, 3), choice(1, 21, 3))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        verify(steps, never()).saveAll(any());
    }

    @Test
    void extraApproverThatIsAlreadyInTheRouteIsRejectedInsteadOfDoublingTheStep() {
        givenDocument(DocumentState.DRAFT);
        // 21 — подставляется автоматически (Боб единственный), 22 — несколько кандидатов, автор выбирает Карол
        givenRoute(List.of(row(1, 21, true), row(1, 22, true)),
                Map.of(21L, List.of(BOB), 22L, List.of(BOB, CAROL)));
        when(roles.carriersOf(ORG, 21)).thenReturn(List.of(BOB, CAROL));
        when(roles.carriersOf(ORG, 22)).thenReturn(List.of(BOB, CAROL));

        // тот, кого подставили автоматически
        assertThatThrownBy(() -> service.submit(author(), DOC,
                new SubmitRequest(List.of(choice(1, 22, 3)), List.of(choice(1, 21, 2)))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        // тот, кого автор выбрал в этой же отправке
        assertThatThrownBy(() -> service.submit(author(), DOC,
                new SubmitRequest(List.of(choice(1, 22, 3)), List.of(choice(1, 22, 3)))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        verify(steps, never()).saveAll(any());
    }

    @Test
    void samePersonInAnotherRoleIsStillADuplicate() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, true), row(1, 22, true)),
                Map.of(21L, List.of(BOB), 22L, List.of(BOB, CAROL)));
        when(roles.carriersOf(ORG, 21)).thenReturn(List.of(BOB, CAROL));
        when(roles.carriersOf(ORG, 22)).thenReturn(List.of(BOB, CAROL));

        // Элемент маршрута — человек. Карол уже выбрана в роли 22 — в роли 21 её не добавить.
        assertThatThrownBy(() -> service.submit(author(), DOC, new SubmitRequest(List.of(choice(1, 22, 3)),
                List.of(choice(1, 21, 3)))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        verify(steps, never()).saveAll(any());
    }

    @Test
    void approverAddedAsANewStageGetsTheirOwnStageAndTemplateStagesShift() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 20, true), row(2, 22, true)),
                Map.of(20L, List.of(BOB), 22L, List.of(CAROL)));
        when(roles.carriersOf(ORG, 21)).thenReturn(List.of(DAVE, EVE));

        // Юрист Дейв — между этапами 1 и 2, Юрист Ева — последней, после директора
        service.submit(author(), DOC, new SubmitRequest(null, List.of(
                new SubmitRequest.Choice(2, 21L, DAVE.id(), true),
                new SubmitRequest.Choice(3, 21L, EVE.id(), true))));

        List<ApprovalStep> saved = savedSteps();
        assertThat(saved).extracting(ApprovalStep::getStageOrder, ApprovalStep::getRoleId, ApprovalStep::getApproverId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, 20L, BOB.id()),
                        org.assertj.core.groups.Tuple.tuple(2, 21L, DAVE.id()),
                        org.assertj.core.groups.Tuple.tuple(3, 22L, CAROL.id()),
                        org.assertj.core.groups.Tuple.tuple(4, 21L, EVE.id()));
        verify(documents).moveToApproval(DOC, 1);
    }

    @Test
    void newStageMayOnlyStandBeforeATemplateStageOrLast() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 22, true)), Map.of(22L, List.of(CAROL)));
        when(roles.carriersOf(ORG, 21)).thenReturn(List.of(BOB));

        assertThatThrownBy(() -> service.submit(author(), DOC, extras(new SubmitRequest.Choice(3, 21L, BOB.id(), true))))
                .isInstanceOf(NotFoundException.class);
        // «вместе с этапом» после последнего — не этап шаблона
        assertThatThrownBy(() -> service.submit(author(), DOC, extras(new SubmitRequest.Choice(2, 21L, BOB.id(), false))))
                .isInstanceOf(NotFoundException.class);
        verify(steps, never()).saveAll(any());
    }

    @Test
    void approvalsOfAnUnchangedPreviousVersionAreCarriedOverAndOthersWait() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, true), row(2, 22, true)),
                Map.of(21L, List.of(BOB), 22L, List.of(CAROL)));
        when(carried.of(DOC, 3)).thenReturn(Optional.of(new CarriedApprovals.Carried(2,
                Set.of(new CarriedApprovals.Key(21L, BOB.id(), ApprovalStep.Kind.APPROVAL)))));

        service.submit(author(), DOC, empty());

        List<ApprovalStep> saved = savedSteps();
        assertThat(saved).filteredOn(step -> step.getRoleId() == 21L).singleElement().satisfies(step -> {
            assertThat(step.getDecision()).isEqualTo(ApprovalStep.Decision.APPROVED);
            assertThat(step.getAutoReason()).isEqualTo(ApprovalStep.AutoReason.CARRIED_OVER);
        });
        assertThat(saved).filteredOn(step -> step.getRoleId() == 22L).singleElement()
                .satisfies(step -> assertThat(step.getDecision()).isEqualTo(ApprovalStep.Decision.PENDING));
        // этап 1 уже одобрен — активен сразу этап 2
        verify(documents).moveToApproval(DOC, 2);
        assertThat(event(ApprovalRequestedEvent.class).approverUserIds()).containsExactly(CAROL.id());
    }

    @Test
    void missingBodyMeansNoSelections() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 22, true)), Map.of(22L, List.of(CAROL)));

        service.submit(author(), DOC, new SubmitRequest(null, null));

        verify(documents).moveToApproval(eq(DOC), anyInt());
    }

    // ---------- маршрут из конструктора ----------

    @Test
    void authorMayReorderStagesMoveTheMandatoryAndDropTheOptional() {
        givenDocument(DocumentState.DRAFT);
        // Шаблон: руководитель (необязательный) → директор (обязательный)
        givenRoute(List.of(row(1, 20, false), row(2, 22, true)),
                Map.of(20L, List.of(BOB), 22L, List.of(CAROL)));
        when(roles.carriersOf(ORG, 21)).thenReturn(List.of(DAVE));

        // Автор убрал руководителя, поставил директора первым, юриста — вторым этапом
        service.submit(author(), DOC, route(List.of(List.of(p(CAROL, 22)), List.of(p(DAVE, 21))), null));

        assertThat(savedSteps()).extracting(ApprovalStep::getStageOrder, ApprovalStep::getApproverId, ApprovalStep::getOrigin)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, CAROL.id(), ApprovalStep.Origin.TEMPLATE),
                        org.assertj.core.groups.Tuple.tuple(2, DAVE.id(), ApprovalStep.Origin.ADDED_BY_AUTHOR));
        verify(documents).moveToApproval(DOC, 1);
    }

    @Test
    void mandatoryRoleCannotBeRemoved() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 20, false), row(2, 22, true)),
                Map.of(20L, List.of(BOB), 22L, List.of(CAROL)));

        assertThatThrownBy(() -> service.submit(author(), DOC, route(List.of(List.of(p(BOB, 20))), null)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("MANDATORY_ROLE_MISSING"));
        verify(steps, never()).saveAll(any());
    }

    @Test
    void emptyStagesAndRepeatedPeopleAreRejected() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 22, true)), Map.of(22L, List.of(CAROL)));
        when(roles.carriersOf(ORG, 21)).thenReturn(List.of(CAROL, DAVE));

        assertThatThrownBy(() -> service.submit(author(), DOC, route(List.of(List.of(p(CAROL, 22)), List.of()), null)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getMessage()).contains("Этап 2 пустой"));
        assertThatThrownBy(() -> service.submit(author(), DOC,
                route(List.of(List.of(p(CAROL, 22)), List.of(p(CAROL, 21))), null)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getMessage()).contains("Карол"));
        // и в роли, которой у человека нет, — «состав изменился, обновите экран»
        assertThatThrownBy(() -> service.submit(author(), DOC, route(List.of(List.of(p(BOB, 22))), null)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getMessage()).contains("обновите экран"));
        verify(steps, never()).saveAll(any());
    }

    @Test
    void endorserIsTheLastSeparateStepOfKindEndorsement() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, false), row(2, 22, true)),
                Map.of(21L, List.of(BOB), 22L, List.of(CAROL)));

        service.submit(author(), DOC, route(List.of(List.of(p(BOB, 21))), p(CAROL, 22)));

        assertThat(savedSteps()).extracting(ApprovalStep::getStageOrder, ApprovalStep::getApproverId, ApprovalStep::getKind)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, BOB.id(), ApprovalStep.Kind.APPROVAL),
                        org.assertj.core.groups.Tuple.tuple(2, CAROL.id(), ApprovalStep.Kind.ENDORSEMENT));
    }

    @Test
    void authorStepStandsAfterTheApprovalStagesAndBeforeTheEndorsement() {
        // Шаг автора — отдельным этапом после согласующих, утверждение — следующим номером
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, false), row(2, 20, true)),
                Map.of(21L, List.of(BOB), 20L, List.of(AUTHOR_REF), 22L, List.of(CAROL)));

        service.submit(author(), DOC, route(List.of(List.of(p(BOB, 21))), p(CAROL, 22)));

        assertThat(savedSteps()).extracting(ApprovalStep::getStageOrder, ApprovalStep::getApproverId, ApprovalStep::getKind)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, BOB.id(), ApprovalStep.Kind.APPROVAL),
                        org.assertj.core.groups.Tuple.tuple(2, AUTHOR, ApprovalStep.Kind.APPROVAL),
                        org.assertj.core.groups.Tuple.tuple(3, CAROL.id(), ApprovalStep.Kind.ENDORSEMENT));
        verify(documents).moveToApproval(DOC, 1);
    }

    @Test
    void authorHoldingTwoMandatoryRolesAloneIsInTheRouteOnce() {
        // D6, одна строка автора, а не по шагу на каждую роль
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, true), row(2, 22, true), row(3, 20, false)),
                Map.of(21L, List.of(AUTHOR_REF), 22L, List.of(AUTHOR_REF), 20L, List.of(BOB)));

        service.submit(author(), DOC, route(List.of(List.of(p(BOB, 20))), null));

        assertThat(savedSteps()).filteredOn(step -> step.getApproverId() == AUTHOR).singleElement().satisfies(step -> {
            assertThat(step.getAutoReason()).isEqualTo(ApprovalStep.AutoReason.AUTHOR_HOLDS_ROLE);
            assertThat(step.getStageOrder()).isEqualTo(2);
        });
    }

    @Test
    void endorsementIsNotCarriedIntoAnApprovalOfTheSamePerson() {
        // и в обратную сторону — утвердивший прошлую версию согласует новую сам
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, false)), Map.of(21L, List.of(BOB)));
        when(carried.of(DOC, 3)).thenReturn(Optional.of(new CarriedApprovals.Carried(2,
                Set.of(new CarriedApprovals.Key(21L, BOB.id(), ApprovalStep.Kind.ENDORSEMENT)))));

        service.submit(author(), DOC, route(List.of(List.of(p(BOB, 21))), null));

        assertThat(savedSteps()).singleElement()
                .satisfies(step -> assertThat(step.getDecision()).isEqualTo(ApprovalStep.Decision.PENDING));
    }

    @Test
    void approvalIsNotCarriedIntoTheEndorsementOfTheSamePerson() {
        //: Боб согласовал прошлую версию, теперь он утверждающий — должен утвердить сам
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 21, false)), Map.of(21L, List.of(BOB)));
        when(carried.of(DOC, 3)).thenReturn(Optional.of(new CarriedApprovals.Carried(2,
                Set.of(new CarriedApprovals.Key(21L, BOB.id(), ApprovalStep.Kind.APPROVAL)))));

        service.submit(author(), DOC, route(List.of(), p(BOB, 21)));

        assertThat(savedSteps()).singleElement().satisfies(step -> {
            assertThat(step.getKind()).isEqualTo(ApprovalStep.Kind.ENDORSEMENT);
            assertThat(step.getDecision()).isEqualTo(ApprovalStep.Decision.PENDING);
        });
        verify(documents, never()).approve(anyLong());
    }

    @Test
    void nobodyLeftToDecideWithACarriedEndorsementIsEndorsed() {
        // ветка «решать некому», утверждение перенесено с прошлой версии
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 22, false)), Map.of(22L, List.of(CAROL)));
        when(carried.of(DOC, 3)).thenReturn(Optional.of(new CarriedApprovals.Carried(2,
                Set.of(new CarriedApprovals.Key(22L, CAROL.id(), ApprovalStep.Kind.ENDORSEMENT)))));

        service.submit(author(), DOC, route(List.of(), p(CAROL, 22)));

        verify(documents).approve(DOC);
        assertThat(event(DocumentDecidedEvent.class).endorsed()).isTrue();
    }

    @Test
    void emptyRouteIsAllowedOnlyWhenNobodyElseCanApprove() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 22, false)), Map.of(22L, List.of(CAROL)));

        when(roles.anyCarrierExcept(ORG, AUTHOR)).thenReturn(true);
        assertThatThrownBy(() -> service.submit(author(), DOC, route(List.of(), null)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getMessage()).contains("хотя бы одного"));

        when(roles.anyCarrierExcept(ORG, AUTHOR)).thenReturn(false);
        service.submit(author(), DOC, route(List.of(), null));
        verify(documents).approve(DOC);
    }

    @Test
    void bothFormatsAtOnceAreRejected() {
        givenDocument(DocumentState.DRAFT);
        givenRoute(List.of(row(1, 22, true)), Map.of(22L, List.of(CAROL)));

        assertThatThrownBy(() -> service.submit(author(), DOC, new SubmitRequest(List.of(choice(1, 22, 3)), null,
                List.of(new SubmitRequest.Stage(List.of(p(CAROL, 22)))), null)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    }

    // ---------- вспомогательное ----------

    private void givenDocument(DocumentState status) {
        when(documents.lockForAuthor(eq(DOC), any(), any())).thenReturn(new DocumentSnapshot(
                DOC, ORG, AUTHOR, TYPE, status, 3, null, TITLE));
        when(documents.submissionBlocker(DOC, 3)).thenReturn(Optional.empty());
    }

    private void givenRoute(List<TemplateRow> template, Map<Long, List<UserRef>> carriers) {
        when(planner.plan(ORG, TYPE, AUTHOR))
                .thenReturn(new RouteResolver().resolve(template, ROLES, carriers, AUTHOR));
        // Проверка маршрута спрашивает роли и носителей у всех, кто в нём стоит, — отдаём справочник теста.
        when(roles.rolesById(eq(ORG), any())).thenAnswer(invocation -> {
            java.util.Collection<Long> ids = invocation.getArgument(1);
            return ROLES.entrySet().stream().filter(entry -> ids.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        });
        carriers.forEach((roleId, users) -> when(roles.carriersOf(ORG, roleId)).thenReturn(users));
    }

    @SuppressWarnings("unchecked")
    private List<ApprovalStep> savedSteps() {
        ArgumentCaptor<List<ApprovalStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(steps).saveAll(captor.capture());
        return captor.getValue();
    }

    private <T> T event(Class<T> type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(type);
        return type.cast(captor.getValue());
    }

    private static TemplateRow row(int stage, long roleId, boolean mandatory) {
        return new TemplateRow(stage, roleId, mandatory);
    }

    private static SubmitRequest.Choice choice(int stage, long roleId, long userId) {
        return new SubmitRequest.Choice(stage, roleId, userId);
    }

    private static SubmitRequest.Participant p(UserRef user, long roleId) {
        return new SubmitRequest.Participant(user.id(), roleId);
    }

    private static SubmitRequest route(List<List<SubmitRequest.Participant>> stages, SubmitRequest.Participant endorser) {
        return new SubmitRequest(null, null, stages.stream().map(SubmitRequest.Stage::new).toList(), endorser);
    }

    private static SubmitRequest empty() {
        return new SubmitRequest(List.of(), List.of());
    }

    private static SubmitRequest choices(SubmitRequest.Choice... selections) {
        return new SubmitRequest(List.of(selections), List.of());
    }

    private static SubmitRequest extras(SubmitRequest.Choice... extras) {
        return new SubmitRequest(List.of(), List.of(extras));
    }

    private static CurrentUser author() {
        var identity = new CurrentUser.UserIdentity(AUTHOR, "Автор");
        return new CurrentUser(identity, identity, 50L, ORG, Set.of(), false, null);
    }
}
