package ru.sibvibe.approval.approval.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.approval.service.RouteResolver.Resolution;
import ru.sibvibe.approval.approval.service.RouteResolver.ResolvedRoute;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.organization.service.RouteTemplateService.TemplateRow;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Правила ARCHITECTURE.md, раздел 5: кто и когда становится согласующим. */
class RouteResolverTest {

    private static final long AUTHOR = 1;
    private static final UserRef AUTHOR_REF = new UserRef(AUTHOR, "Автор");
    private static final UserRef BOB = new UserRef(2, "Боб");
    private static final UserRef CAROL = new UserRef(3, "Карол");

    private static final RoleRef DIRECTOR = new RoleRef(10, "DIRECTOR", "Директор");
    private static final RoleRef LAWYER = new RoleRef(11, "LAWYER", "Юрист");
    private static final RoleRef ACCOUNTANT = new RoleRef(12, "ACCOUNTANT", "Бухгалтер");
    private static final Map<Long, RoleRef> ROLES = Map.of(10L, DIRECTOR, 11L, LAWYER, 12L, ACCOUNTANT);

    private final RouteResolver resolver = new RouteResolver();

    @Test
    void severalCandidatesMustBeChosenByTheAuthor() {
        ResolvedRoute route = resolve(List.of(row(1, 10, true)), Map.of(10L, List.of(BOB, CAROL)));

        RouteResolver.Participant participant = only(route);
        assertThat(participant.resolution()).isEqualTo(Resolution.SELECT);
        assertThat(participant.candidates()).containsExactly(BOB, CAROL);
        assertThat(participant.selectedUserId()).isNull();
        assertThat(route.problems()).isEmpty();
    }

    @Test
    void singleCandidateIsSubstitutedAutomatically() {
        ResolvedRoute route = resolve(List.of(row(1, 10, true)), Map.of(10L, List.of(BOB)));

        assertThat(only(route).resolution()).isEqualTo(Resolution.AUTO);
        assertThat(only(route).selectedUserId()).isEqualTo(BOB.id());
    }

    @Test
    void authorIsExcludedFromCandidatesWhenSomeoneElseHoldsTheRole() {
        ResolvedRoute route = resolve(List.of(row(1, 10, true)), Map.of(10L, List.of(AUTHOR_REF, BOB)));

        RouteResolver.Participant participant = only(route);
        assertThat(participant.candidates()).containsExactly(BOB);
        assertThat(participant.resolution()).isEqualTo(Resolution.AUTO);
    }

    @Test
    void optionalParticipantWithoutCandidatesIsSkippedEvenIfTheAuthorHoldsTheRole() {
        ResolvedRoute route = resolve(List.of(row(1, 11, false)), Map.of(11L, List.of(AUTHOR_REF)));

        assertThat(only(route).resolution()).isEqualTo(Resolution.SKIPPED);
        assertThat(route.problems()).isEmpty();
    }

    @Test
    void mandatoryRoleHeldOnlyByTheAuthorIsApprovedByTheAuthorAutomatically() {
        ResolvedRoute route = resolve(List.of(row(1, 10, true)), Map.of(10L, List.of(AUTHOR_REF)));

        assertThat(only(route).resolution()).isEqualTo(Resolution.AUTHOR_HOLDS_ROLE);
        assertThat(route.problems()).isEmpty();
    }

    @Test
    void mandatoryRoleHeldByNobodyIsAProblemWithTheRoleName() {
        ResolvedRoute route = resolve(List.of(row(1, 12, true)), Map.of());

        assertThat(route.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("ROUTE_ROLE_EMPTY");
            assertThat(problem.message()).startsWith("В компании нет сотрудника с ролью «Бухгалтер» — попросите администратора");
            assertThat(problem.role()).isEqualTo(ACCOUNTANT);
        });
        assertThat(only(route).candidates()).isEmpty();
    }

    @Test
    void stagesAreOrderedAndParallelParticipantsShareAStage() {
        ResolvedRoute route = resolve(
                List.of(row(2, 10, true), row(1, 11, false), row(1, 12, false)),
                Map.of(10L, List.of(BOB), 11L, List.of(CAROL), 12L, List.of(CAROL)));

        assertThat(route.stages()).extracting(RouteResolver.Stage::stageOrder).containsExactly(1, 2);
        assertThat(route.stages().getFirst().participants()).extracting(p -> p.role().code())
                .containsExactly("LAWYER", "ACCOUNTANT");
    }

    @Test
    void onePersonHoldingTwoRolesOfOneStageAppearsInBothParticipants() {
        ResolvedRoute route = resolve(List.of(row(1, 11, false), row(1, 12, false)),
                Map.of(11L, List.of(BOB), 12L, List.of(BOB)));

        // решение адресуется шагу, поэтому у Боба будет два шага — по одному на роль
        assertThat(route.stages().getFirst().participants()).extracting(RouteResolver.Participant::selectedUserId)
                .containsExactly(BOB.id(), BOB.id());
    }

    @Test
    void routeWithNoOneToDecideResolvesEverythingToSkippedOrAuthorApproval() {
        ResolvedRoute route = resolve(List.of(row(1, 11, false), row(2, 10, true)),
                Map.of(10L, List.of(AUTHOR_REF)));

        assertThat(route.stages()).flatExtracting(RouteResolver.Stage::participants)
                .extracting(RouteResolver.Participant::resolution)
                .containsExactly(Resolution.SKIPPED, Resolution.AUTHOR_HOLDS_ROLE);
    }

    @Test
    void roleMissingFromTheCompanyIsACorruptTemplateNotAUserError() {
        assertThatThrownBy(() -> resolver.resolve(List.of(row(1, 99, true)), ROLES, Map.of(), AUTHOR))
                .isInstanceOf(IllegalStateException.class);
    }

    private ResolvedRoute resolve(List<TemplateRow> template, Map<Long, List<UserRef>> carriers) {
        return resolver.resolve(template, ROLES, carriers, AUTHOR);
    }

    private static TemplateRow row(int stage, long roleId, boolean mandatory) {
        return new TemplateRow(stage, roleId, mandatory);
    }

    private static RouteResolver.Participant only(ResolvedRoute route) {
        assertThat(route.stages()).hasSize(1);
        assertThat(route.stages().getFirst().participants()).hasSize(1);
        return route.stages().getFirst().participants().getFirst();
    }
}
