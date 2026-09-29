package ru.sibvibe.approval.approval.service;

import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.organization.service.RouteTemplateService.TemplateRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Разрешение шаблона маршрута в людей (docs/DESIGN-DECISIONS.md, «Маршрутизация согласования»; ARCHITECTURE.md, раздел 5).
 * Чистая логика без базы: всё, что нужно, приходит аргументами, поэтому каждое правило проверяется unit-тестом.
 *
 * Для каждого участника шаблона кандидаты — активные носители роли, кроме автора: собственный документ автор
 * не согласует, если есть кто-то ещё. Дальше:
 * <ul>
 *   <li>кандидатов нет, участник необязательный → {@link Resolution#SKIPPED};</li>
 *   <li>кандидатов нет, обязательный, роль есть у автора → {@link Resolution#AUTHOR_HOLDS_ROLE}: шаг сразу
 *       согласован с отметкой в истории, иначе маршрут маленькой компании был бы непроходим;</li>
 *   <li>кандидатов нет, обязательный, роли нет ни у кого → проблема {@code ROUTE_ROLE_EMPTY};</li>
 *   <li>один кандидат → {@link Resolution#AUTO}, несколько → {@link Resolution#SELECT}: выбирает автор.</li>
 * </ul>
 */
public final class RouteResolver {

    public ResolvedRoute resolve(
            List<TemplateRow> template,
            Map<Long, RoleRef> roles,
            Map<Long, List<UserRef>> carriersByRole,
            long authorId
    ) {
        Map<Integer, List<Participant>> byStage = new TreeMap<>();
        List<Problem> problems = new ArrayList<>();
        for (TemplateRow row : template) {
            RoleRef role = roles.get(row.roleId());
            if (role == null) {
                throw new IllegalStateException("Роль маршрута не найдена в компании: " + row.roleId());
            }
            List<UserRef> holders = carriersByRole.getOrDefault(row.roleId(), List.of());
            boolean authorHolds = holders.stream().anyMatch(user -> user.id() == authorId);
            List<UserRef> candidates = holders.stream().filter(user -> user.id() != authorId).toList();

            Participant participant;
            if (candidates.isEmpty()) {
                if (!row.mandatory()) {
                    participant = new Participant(role, false, List.of(), Resolution.SKIPPED, null);
                } else if (authorHolds) {
                    participant = new Participant(role, true, List.of(), Resolution.AUTHOR_HOLDS_ROLE, null);
                } else {
                    participant = new Participant(role, true, List.of(), Resolution.SELECT, null);
                    problems.add(new Problem("ROUTE_ROLE_EMPTY",
                            "В компании нет сотрудника с ролью «" + role.name() + "» — попросите администратора назначить эту роль "
                                    + "или сделать её необязательной", role));
                }
            } else if (candidates.size() == 1) {
                participant = new Participant(role, row.mandatory(), candidates, Resolution.AUTO,
                        candidates.getFirst().id());
            } else {
                participant = new Participant(role, row.mandatory(), candidates, Resolution.SELECT, null);
            }
            byStage.computeIfAbsent(row.stageOrder(), order -> new ArrayList<>()).add(participant);
        }
        List<Stage> stages = byStage.entrySet().stream()
                .map(entry -> new Stage(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new ResolvedRoute(stages, List.copyOf(problems));
    }

    public enum Resolution {
        /** Выбрать из кандидатов. */
        SELECT,
        /** Кандидат один — подставлен. */
        AUTO,
        /** Необязательный участник без кандидатов. */
        SKIPPED,
        /** Обязательный, но роль есть только у автора: шаг сразу согласован. */
        AUTHOR_HOLDS_ROLE
    }

    public record Participant(
            RoleRef role,
            boolean mandatory,
            List<UserRef> candidates,
            Resolution resolution,
            Long selectedUserId
    ) {
    }

    public record Stage(int stageOrder, List<Participant> participants) {
    }

    /** Проблема маршрута; {@code role} — роль, о которой речь. */
    public record Problem(String code, String message, RoleRef role) {
    }

    public record ResolvedRoute(List<Stage> stages, List<Problem> problems) {
    }
}
