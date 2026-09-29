package ru.sibvibe.approval.approval.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.approval.dto.RoutePreviewResponse;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.service.DocumentState;
import ru.sibvibe.approval.document.service.DocumentStateService;
import ru.sibvibe.approval.document.service.DocumentStateService.DocumentSnapshot;

import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Предпросмотр маршрута перед отправкой: та же логика, что у отправки, но без сохранения. */
@Service
public class RoutePreviewService {

    private final DocumentStateService documents;
    private final RoutePlanner planner;
    private final CarriedApprovals carriedApprovals;
    private final ApprovalStepRepository steps;

    public RoutePreviewService(
            DocumentStateService documents,
            RoutePlanner planner,
            CarriedApprovals carriedApprovals,
            ApprovalStepRepository steps
    ) {
        this.documents = documents;
        this.planner = planner;
        this.carriedApprovals = carriedApprovals;
        this.steps = steps;
    }

    /** Последняя отправленная версия до текущей — её маршрут без решений; {@code null} — не отправлялся. */
    private RoutePreviewResponse.PreviousRoute previousRoute(long documentId, int currentVersionNo) {
        for (int versionNo = currentVersionNo - 1; versionNo >= 1; versionNo--) {
            List<ApprovalStep> previous = steps.findByDocumentIdAndVersionNoOrderByStageOrderAscIdAsc(documentId, versionNo);
            if (previous.isEmpty()) {
                continue;
            }
            Map<Integer, List<RoutePreviewResponse.Person>> byStage = new TreeMap<>();
            RoutePreviewResponse.Person endorser = null;
            for (ApprovalStep step : previous) {
                if (step.getAutoReason() == ApprovalStep.AutoReason.AUTHOR_HOLDS_ROLE
                        || step.getAutoReason() == ApprovalStep.AutoReason.MEMBER_REMOVED) {
                    continue;
                }
                RoutePreviewResponse.Person person = new RoutePreviewResponse.Person(step.getApproverId(), step.getRoleId());
                if (step.getKind() == ApprovalStep.Kind.ENDORSEMENT) {
                    endorser = person;
                } else {
                    byStage.computeIfAbsent(step.getStageOrder(), order -> new ArrayList<>()).add(person);
                }
            }
            List<RoutePreviewResponse.RouteStage> stages = byStage.values().stream()
                    .map(RoutePreviewResponse.RouteStage::new)
                    .toList();
            return new RoutePreviewResponse.PreviousRoute(versionNo, stages, endorser);
        }
        return null;
    }

    /**
     * Доступен автору черновика. Проблемы ({@code problems}) не бросаются исключением: экран показывает их
     * плашкой и делает кнопку «Отправить» неактивной.
     */
    @Transactional
    public RoutePreviewResponse preview(CurrentUser user, long documentId) {
        DocumentSnapshot document = documents.authorSnapshot(
                documentId, user, "Маршрут перед отправкой показывается только автору");
        if (document.status() != DocumentState.DRAFT) {
            throw ApprovalErrors.invalidState("Предпросмотр доступен только у черновика");
        }
        RouteResolver.ResolvedRoute route = planner.plan(document.orgId(), document.documentTypeId(), document.authorId());

        List<RoutePreviewResponse.Stage> stages = route.stages().stream()
                .map(stage -> new RoutePreviewResponse.Stage(stage.stageOrder(), stage.participants().stream()
                        .map(participant -> new RoutePreviewResponse.Participant(
                                participant.role(), participant.mandatory(), participant.candidates(),
                                participant.selectedUserId(), participant.resolution().name()))
                        .toList()))
                .toList();
        List<RoutePreviewResponse.Problem> problems = new ArrayList<>();
        route.problems().forEach(problem ->
                problems.add(new RoutePreviewResponse.Problem(problem.code(), problem.message())));
        documents.submissionBlocker(documentId, document.currentVersionNo()).ifPresent(reason ->
                problems.add(new RoutePreviewResponse.Problem("BLOCKING_ISSUES", reason.message())));
        RoutePreviewResponse.CarryOver carryOver = carriedApprovals.of(documentId, document.currentVersionNo())
                .map(carried -> new RoutePreviewResponse.CarryOver(carried.fromVersionNo(), carried.approvals().stream()
                        .map(key -> new RoutePreviewResponse.Approval(key.roleId(), key.userId(), key.kind().name()))
                        .toList()))
                .orElse(null);
        return new RoutePreviewResponse(stages, problems, carryOver, previousRoute(documentId, document.currentVersionNo()));
    }
}
