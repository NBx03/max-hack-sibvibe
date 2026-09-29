package ru.sibvibe.approval.approval.dto;

import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;

import java.util.List;

/**
 * Предпросмотр маршрута: полностью разрешённая цепочка с именами; ничего не сохраняется.
 * {@code carryOver} — кто уже одобрил прошлую версию, если документ с тех пор не менялся: их одобрение
 * перенесётся при отправке; {@code null} — переносить нечего. {@code previous} — маршрут, по которому документ шёл
 * в прошлой отправленной версии: конструктор заполняется им, а не шаблоном; {@code null} — документ ещё не
 * отправлялся. {@code stages} — шаблон вида документа: предзаполнение и признак «обязательный» у ролей.
 */
public record RoutePreviewResponse(List<Stage> stages, List<Problem> problems, CarryOver carryOver, PreviousRoute previous) {

    public RoutePreviewResponse {
        stages = List.copyOf(stages);
        problems = List.copyOf(problems);
    }

    public record Stage(int stageOrder, List<Participant> participants) {
        public Stage {
            participants = List.copyOf(participants);
        }
    }

    /** {@code resolution}: SELECT, AUTO, SKIPPED или AUTHOR_HOLDS_ROLE — см. контракт. */
    public record Participant(
            RoleRef role,
            boolean mandatory,
            List<UserRef> candidates,
            Long selectedUserId,
            String resolution
    ) {
        public Participant {
            candidates = List.copyOf(candidates);
        }
    }

    public record CarryOver(int fromVersionNo, List<Approval> approvals) {
        public CarryOver {
            approvals = List.copyOf(approvals);
        }
    }

    /** kind — APPROVAL или ENDORSEMENT: одобрение переносится только в шаг того же вида. */
    public record Approval(long roleId, long userId, String kind) {
    }

    /**
     * Маршрут прошлой отправки: этапы по порядку и утверждающий. Кто выбыл из компании и автосогласование автором
     * сюда не попадают — решения сбрасываются, остаются люди и порядок.
     */
    public record PreviousRoute(int versionNo, List<RouteStage> stages, Person endorser) {
        public PreviousRoute {
            stages = List.copyOf(stages);
        }
    }

    public record RouteStage(List<Person> participants) {
        public RouteStage {
            participants = List.copyOf(participants);
        }
    }

    public record Person(long userId, long roleId) {
    }

    /** {@code code}: ROUTE_ROLE_EMPTY или BLOCKING_ISSUES; пока список не пуст, отправить нельзя. */
    public record Problem(String code, String message) {
    }
}
