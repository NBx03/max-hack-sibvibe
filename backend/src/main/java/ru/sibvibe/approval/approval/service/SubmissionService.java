package ru.sibvibe.approval.approval.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.approval.dto.SubmitRequest;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.event.ApprovalRequestedEvent;
import ru.sibvibe.approval.approval.event.DocumentDecidedEvent;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.service.DocumentState;
import ru.sibvibe.approval.document.service.DocumentStateService;
import ru.sibvibe.approval.document.service.DocumentStateService.DocumentSnapshot;
import ru.sibvibe.approval.organization.service.RoleService;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Отправка документа на согласование.
 *
 * Всё выполняется одной транзакцией под блокировкой строки документа (ARCHITECTURE.md, «Конкурентность»):
 * повторная отправка или новая версия одновременно с ней дождутся своей очереди и получат 409.
 *
 * Маршрут составляет автор (конструктор, D3): шаблон вида документа только предзаполняет его. Сервер проверяет
 * три вещи — все обязательные роли на месте, один человек один раз, нет пустых этапов — и прежние правила: автор
 * не согласует свой документ, пока есть кто-то ещё; каждый — активный носитель роли, под которой стоит. Прежний
 * формат запроса (выбор кандидатов и добавленные к шаблону) переводится в тот же маршрут и проходит те же проверки.
 * Последний шаг может быть утверждением: один утверждающий, отдельным этапом после всех.
 *
 * Шаги создаются сразу для всех этапов версии; активируется первый этап, где есть кому решать. Если решать некому
 * вообще (все шаги автосогласованы), документ становится «Согласован» тут же.
 */
@Service
public class SubmissionService {

    private final DocumentStateService documents;
    private final RoutePlanner planner;
    private final RoleService roles;
    private final ApprovalStepRepository steps;
    private final ApplicationEventPublisher events;
    private final CarriedApprovals carriedApprovals;
    private final Clock clock;

    public SubmissionService(
            DocumentStateService documents,
            RoutePlanner planner,
            RoleService roles,
            ApprovalStepRepository steps,
            ApplicationEventPublisher events,
            CarriedApprovals carriedApprovals,
            Clock clock
    ) {
        this.documents = documents;
        this.planner = planner;
        this.roles = roles;
        this.steps = steps;
        this.events = events;
        this.carriedApprovals = carriedApprovals;
        this.clock = clock;
    }

    @Transactional
    public void submit(CurrentUser user, long documentId, SubmitRequest request) {
        DocumentSnapshot document = documents.lockForAuthor(
                documentId, user, "Отправить документ на согласование может только автор");
        if (document.status() != DocumentState.DRAFT) {
            throw ApprovalErrors.invalidState("Отправить можно только черновик");
        }
        documents.submissionBlocker(documentId, document.currentVersionNo()).ifPresent(reason -> {
            throw ApprovalErrors.blockingIssues(reason.message());
        });

        RouteResolver.ResolvedRoute template = planner.plan(document.orgId(), document.documentTypeId(), document.authorId());
        template.problems().stream()
                .filter(problem -> problem.code().equals("ROUTE_ROLE_EMPTY"))
                .findFirst()
                .ifPresent(problem -> {
                    throw ApprovalErrors.routeRoleEmpty(problem.role().name());
                });

        if (request.hasExplicitRoute() && (!request.selectionsOrEmpty().isEmpty() || !request.extraApproversOrEmpty().isEmpty())) {
            throw ApprovalErrors.validation("Маршрут передан в двух форматах сразу");
        }
        Draft draft = request.hasExplicitRoute() ? explicitDraft(request) : legacyDraft(document, template, request);
        Validated route = validate(document, template, draft);

        Instant now = clock.instant();
        List<ApprovalStep> planned = buildSteps(document, template, route, now);
        carryOverApprovals(document, planned, now);
        OptionalInt firstStage = planned.stream()
                .filter(step -> step.getDecision() == ApprovalStep.Decision.PENDING)
                .mapToInt(ApprovalStep::getStageOrder)
                .min();
        firstStage.ifPresent(stage -> planned.stream()
                .filter(step -> step.getDecision() == ApprovalStep.Decision.PENDING && step.getStageOrder() == stage)
                .forEach(step -> step.setActivatedAt(now)));
        steps.saveAll(planned);

        if (firstStage.isPresent()) {
            documents.moveToApproval(documentId, firstStage.getAsInt());
            Set<Long> approvers = planned.stream()
                    .filter(step -> step.getDecision() == ApprovalStep.Decision.PENDING
                            && step.getStageOrder() == firstStage.getAsInt())
                    .map(ApprovalStep::getApproverId)
                    .collect(Collectors.toSet());
            boolean endorsement = planned.stream().anyMatch(step -> step.getStageOrder() == firstStage.getAsInt()
                    && step.getKind() == ApprovalStep.Kind.ENDORSEMENT);
            events.publishEvent(new ApprovalRequestedEvent(documentId, document.currentVersionNo(),
                    firstStage.getAsInt(), approvers, document.authorId(), document.title(), endorsement));
        } else {
            // Решать некому: обязательные роли только у автора, остальных нет. Согласуется сразу.
            documents.moveToApproval(documentId, null);
            documents.approve(documentId);
            events.publishEvent(new DocumentDecidedEvent(documentId, document.currentVersionNo(), document.authorId(),
                    DocumentDecidedEvent.Outcome.APPROVED, null, document.title(), ApprovalStep.endorsedIn(planned)));
        }
    }

    // ---------- Маршрут из запроса ----------

    private static Draft explicitDraft(SubmitRequest request) {
        List<List<Entry>> stages = request.stagesOrEmpty().stream()
                .map(stage -> stage.participants().stream()
                        .map(participant -> new Entry(participant.userId(), participant.roleId()))
                        .toList())
                .toList();
        Entry endorser = request.endorser() == null
                ? null
                : new Entry(request.endorser().userId(), request.endorser().roleId());
        return new Draft(stages, endorser);
    }

    /**
     * Прежний формат: этапы шаблона с выбранными кандидатами и добавленные к ним — вместе с этапом или отдельным
     * этапом перед этапом шаблона (или последним). Правила выбора прежние: выбор только там, где кандидатов
     * несколько, и только из кандидатов; любая ссылка мимо шаблона — «не найдено».
     */
    private Draft legacyDraft(DocumentSnapshot document, RouteResolver.ResolvedRoute template, SubmitRequest request) {
        Map<StageRole, Long> chosen = validateSelections(template, request.selectionsOrEmpty());
        Set<Integer> stageOrders = template.stages().stream().map(RouteResolver.Stage::stageOrder).collect(Collectors.toSet());
        int afterLast = afterLastStage(template);
        for (SubmitRequest.Choice extra : request.extraApproversOrEmpty()) {
            boolean placeExists = stageOrders.contains(extra.stageOrder())
                    || (extra.isNewStage() && extra.stageOrder() == afterLast);
            if (!placeExists) {
                throw new NotFoundException();
            }
        }

        List<List<Entry>> stages = new ArrayList<>();
        for (RouteResolver.Stage stage : template.stages()) {
            addNewStage(stages, request.extraApproversOrEmpty(), stage.stageOrder());
            List<Entry> entries = new ArrayList<>();
            for (RouteResolver.Participant participant : stage.participants()) {
                Long userId = switch (participant.resolution()) {
                    case AUTO -> participant.selectedUserId();
                    case SELECT -> chosen.get(new StageRole(stage.stageOrder(), participant.role().id()));
                    case SKIPPED, AUTHOR_HOLDS_ROLE -> null;
                };
                if (userId != null) {
                    entries.add(new Entry(userId, participant.role().id()));
                }
            }
            request.extraApproversOrEmpty().stream()
                    .filter(extra -> !extra.isNewStage() && extra.stageOrder() == stage.stageOrder())
                    .forEach(extra -> entries.add(new Entry(extra.userId(), extra.roleId())));
            if (!entries.isEmpty()) {
                stages.add(entries);
            }
        }
        addNewStage(stages, request.extraApproversOrEmpty(), afterLast);
        return new Draft(stages, null);
    }

    private static void addNewStage(List<List<Entry>> stages, List<SubmitRequest.Choice> extras, int before) {
        List<Entry> added = extras.stream()
                .filter(extra -> extra.isNewStage() && extra.stageOrder() == before)
                .map(extra -> new Entry(extra.userId(), extra.roleId()))
                .toList();
        if (!added.isEmpty()) {
            stages.add(added);
        }
    }

    private Map<StageRole, Long> validateSelections(RouteResolver.ResolvedRoute route, List<SubmitRequest.Choice> selections) {
        Map<StageRole, RouteResolver.Participant> participants = new HashMap<>();
        route.stages().forEach(stage -> stage.participants().forEach(
                participant -> participants.put(new StageRole(stage.stageOrder(), participant.role().id()), participant)));

        Map<StageRole, Long> chosen = new HashMap<>();
        for (SubmitRequest.Choice choice : selections) {
            StageRole key = new StageRole(choice.stageOrder(), choice.roleId());
            RouteResolver.Participant participant = participants.get(key);
            if (participant == null) {
                throw new NotFoundException();
            }
            if (participant.resolution() != RouteResolver.Resolution.SELECT) {
                throw ApprovalErrors.validation("Для роли «" + participant.role().name() + "» выбор не нужен");
            }
            if (participant.candidates().stream().noneMatch(candidate -> candidate.id() == choice.userId())) {
                throw new NotFoundException();
            }
            if (chosen.put(key, choice.userId()) != null) {
                throw ApprovalErrors.validation("Согласующий для роли «" + participant.role().name()
                        + "» указан дважды");
            }
        }
        for (RouteResolver.Stage stage : route.stages()) {
            for (RouteResolver.Participant participant : stage.participants()) {
                if (participant.resolution() == RouteResolver.Resolution.SELECT
                        && !chosen.containsKey(new StageRole(stage.stageOrder(), participant.role().id()))) {
                    throw ApprovalErrors.selectionRequired(stage.stageOrder(), participant.role().name());
                }
            }
        }
        return chosen;
    }

    // ---------- Проверка ----------

    /**
     * Три правила D3 и прежние правила участника. Порядок проверок — от понятных человеку ошибок к «не найдено»:
     * пустой этап и повтор человека сообщаются текстом, ссылка на чужую роль — 404, на человека без этой роли — 409 «обновите экран» (контракт,
     * «Идентификаторы от клиента»).
     */
    private Validated validate(DocumentSnapshot document, RouteResolver.ResolvedRoute template, Draft draft) {
        for (int index = 0; index < draft.stages().size(); index++) {
            if (draft.stages().get(index).isEmpty()) {
                throw ApprovalErrors.validation("Этап " + (index + 1) + " пустой — добавьте согласующего или уберите этап");
            }
        }
        List<Entry> all = new ArrayList<>();
        draft.stages().forEach(all::addAll);
        if (draft.endorser() != null) {
            all.add(draft.endorser());
        }

        Map<Long, RoleRef> knownRoles = roles.rolesById(document.orgId(),
                all.stream().map(Entry::roleId).collect(Collectors.toSet()));
        Map<Long, Set<Long>> carriersByRole = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        for (Entry entry : all) {
            if (entry.userId() == document.authorId()) {
                throw ApprovalErrors.validation("Автор не согласует свой документ — уберите себя из маршрута");
            }
            if (!knownRoles.containsKey(entry.roleId())) {
                throw new NotFoundException();
            }
            Set<Long> carriers = carriersByRole.computeIfAbsent(entry.roleId(), roleId -> {
                List<UserRef> users = roles.carriersOf(document.orgId(), roleId);
                users.forEach(user -> names.put(user.id(), user.fullName()));
                return users.stream().map(UserRef::id).collect(Collectors.toSet());
            });
            if (!carriers.contains(entry.userId())) {
                // Роль своей компании, а человека с ней уже нет: сняли роль или исключили, пока был открыт конструктор.
                // Это не «не найдено», а устаревший экран: говорим, что сделать.
                throw ApprovalErrors.invalidState("Состав сотрудников изменился — обновите экран маршрута и проверьте согласующих");
            }
            if (!seen.add(entry.userId())) {
                throw ApprovalErrors.validation("«" + names.getOrDefault(entry.userId(), "Сотрудник")
                        + "» уже есть в маршруте — один человек согласует документ один раз");
            }
        }

        // Обязательная роль закрыта, если в маршруте где угодно есть её носитель: человек — один элемент маршрута.
        for (RouteResolver.Stage stage : template.stages()) {
            for (RouteResolver.Participant participant : stage.participants()) {
                boolean needsPerson = participant.mandatory()
                        && (participant.resolution() == RouteResolver.Resolution.AUTO
                        || participant.resolution() == RouteResolver.Resolution.SELECT);
                if (needsPerson && participant.candidates().stream().noneMatch(candidate -> seen.contains(candidate.id()))) {
                    throw ApprovalErrors.mandatoryRoleMissing(participant.role().name());
                }
            }
        }

        boolean authorApproves = template.stages().stream()
                .flatMap(stage -> stage.participants().stream())
                .anyMatch(participant -> participant.resolution() == RouteResolver.Resolution.AUTHOR_HOLDS_ROLE);
        if (all.isEmpty() && !authorApproves && roles.anyCarrierExcept(document.orgId(), document.authorId())) {
            throw ApprovalErrors.validation("Добавьте хотя бы одного согласующего");
        }
        return new Validated(draft.stages(), draft.endorser());
    }

    // ---------- Шаги ----------

    private List<ApprovalStep> buildSteps(
            DocumentSnapshot document,
            RouteResolver.ResolvedRoute template,
            Validated route,
            Instant now
    ) {
        TemplateClaims claims = new TemplateClaims(template);
        List<ApprovalStep> planned = new ArrayList<>();
        for (int index = 0; index < route.stages().size(); index++) {
            for (Entry entry : route.stages().get(index)) {
                planned.add(step(document, index + 1, entry.roleId(), entry.userId(), claims.origin(entry),
                        ApprovalStep.Kind.APPROVAL, now));
            }
        }
        // Обязательная роль, которая есть только у автора: шаг сразу согласован, с отметкой в истории (docs/DESIGN-DECISIONS.md, №10).
        // Отдельным этапом после согласующих и перед утверждением — там, где такая роль стоит в шаблоне; в этапе 1 он
        // выглядел бы так, будто автор появился в маршруте сам. Активация согласованный этап пропускает.
        int authorStage = route.stages().size() + 1;
        // Один шаг, даже если у автора несколько обязательных ролей, которых больше ни у кого нет: человек — один раз
        // в маршруте. Шаг хранит одну роль — первую по шаблону; остальные закрыты тем же
        // фактом — они есть у автора (validate их и не требует).
        Optional<RouteResolver.Participant> authorRole = template.stages().stream()
                .flatMap(stage -> stage.participants().stream())
                .filter(participant -> participant.resolution() == RouteResolver.Resolution.AUTHOR_HOLDS_ROLE)
                .findFirst();
        boolean authorStep = authorRole.isPresent();
        authorRole.ifPresent(participant -> {
            ApprovalStep step = step(document, authorStage, participant.role().id(), document.authorId(),
                    ApprovalStep.Origin.TEMPLATE, ApprovalStep.Kind.APPROVAL, now);
            step.setDecision(ApprovalStep.Decision.APPROVED);
            step.setAutoReason(ApprovalStep.AutoReason.AUTHOR_HOLDS_ROLE);
            step.setActivatedAt(now);
            step.setDecidedAt(now);
            planned.add(step);
        });
        if (route.endorser() != null) {
            planned.add(step(document, authorStep ? authorStage + 1 : authorStage, route.endorser().roleId(), route.endorser().userId(),
                    claims.origin(route.endorser()), ApprovalStep.Kind.ENDORSEMENT, now));
        }
        return planned;
    }

    /**
     * Кто в маршруте «из шаблона», а кто добавлен автором. Каждая роль шаблона даёт ровно одного человека: того, кого
     * подставил шаблон, или — если выбирать нужно было из нескольких — первого из её кандидатов в маршруте. Второй
     * кандидат той же роли — уже добавленный автором: шаблон просил одного юриста, а не двух.
     */
    private static final class TemplateClaims {

        private final List<RouteResolver.Participant> open = new ArrayList<>();

        TemplateClaims(RouteResolver.ResolvedRoute template) {
            template.stages().forEach(stage -> stage.participants().stream()
                    .filter(participant -> participant.resolution() == RouteResolver.Resolution.AUTO
                            || participant.resolution() == RouteResolver.Resolution.SELECT)
                    .forEach(open::add));
        }

        ApprovalStep.Origin origin(Entry entry) {
            for (RouteResolver.Participant participant : open) {
                boolean matches = participant.role().id() == entry.roleId()
                        && participant.candidates().stream().anyMatch(candidate -> candidate.id() == entry.userId());
                if (matches) {
                    open.remove(participant);
                    return ApprovalStep.Origin.TEMPLATE;
                }
            }
            return ApprovalStep.Origin.ADDED_BY_AUTHOR;
        }
    }

    /**
     * Одобрения прошлой версии, если документ с тех пор не менялся ({@link CarriedApprovals}): шаг сразу
     * согласован с отметкой {@code CARRIED_OVER}, видимой в истории. Изменился хоть один файл или поле —
     * согласуют все заново.
     */
    private void carryOverApprovals(DocumentSnapshot document, List<ApprovalStep> planned, Instant now) {
        carriedApprovals.of(document.id(), document.currentVersionNo()).ifPresent(carried -> planned.stream()
                .filter(step -> step.getDecision() == ApprovalStep.Decision.PENDING)
                .filter(step -> carried.covers(step.getRoleId(), step.getApproverId(), step.getKind()))
                .forEach(step -> {
                    step.setDecision(ApprovalStep.Decision.APPROVED);
                    step.setAutoReason(ApprovalStep.AutoReason.CARRIED_OVER);
                    step.setActivatedAt(now);
                    step.setDecidedAt(now);
                }));
    }

    private static int afterLastStage(RouteResolver.ResolvedRoute route) {
        return route.stages().stream().mapToInt(RouteResolver.Stage::stageOrder).max().orElse(0) + 1;
    }

    private ApprovalStep step(
            DocumentSnapshot document, int stageOrder, long roleId, long approverId,
            ApprovalStep.Origin origin, ApprovalStep.Kind kind, Instant now
    ) {
        ApprovalStep step = new ApprovalStep();
        step.setDocumentId(document.id());
        step.setVersionNo(document.currentVersionNo());
        step.setApproverId(approverId);
        step.setRoleId(roleId);
        step.setStageOrder(stageOrder);
        step.setOrigin(origin);
        step.setKind(kind);
        step.setDecision(ApprovalStep.Decision.PENDING);
        step.setCreatedAt(now);
        return step;
    }

    private record StageRole(int stageOrder, long roleId) {
    }

    /** Человек в маршруте и роль, под которой он согласует. */
    private record Entry(long userId, long roleId) {
    }

    private record Draft(List<List<Entry>> stages, Entry endorser) {
    }

    private record Validated(List<List<Entry>> stages, Entry endorser) {
    }
}
