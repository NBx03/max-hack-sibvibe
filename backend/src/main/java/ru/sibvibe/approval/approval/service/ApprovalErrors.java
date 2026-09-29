package ru.sibvibe.approval.approval.service;

import org.springframework.http.HttpStatus;
import ru.sibvibe.approval.common.api.DomainException;

import java.util.Map;

/** Ошибки согласования: коды и статусы — из docs/API_CONTRACTS.md, тексты для пользователя. */
final class ApprovalErrors {

    private ApprovalErrors() {
    }

    static DomainException forbidden(String message) {
        return new DomainException("FORBIDDEN", HttpStatus.FORBIDDEN, message);
    }

    static DomainException validation(String message) {
        return new DomainException("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, message);
    }

    static DomainException commentRequired() {
        return new DomainException("COMMENT_REQUIRED", HttpStatus.BAD_REQUEST,
                "Укажите комментарий: без него вернуть или отклонить документ нельзя");
    }

    static DomainException invalidState(String message) {
        return new DomainException("INVALID_STATE", HttpStatus.CONFLICT, message);
    }

    static DomainException stepNotActive(String message) {
        return new DomainException("STEP_NOT_ACTIVE", HttpStatus.CONFLICT, message);
    }

    static DomainException blockingIssues(String message) {
        return new DomainException("BLOCKING_ISSUES", HttpStatus.CONFLICT, message);
    }

    static DomainException routeRoleEmpty(String roleName) {
        return new DomainException("ROUTE_ROLE_EMPTY", HttpStatus.CONFLICT,
                "В компании нет сотрудника с ролью «" + roleName + "» — попросите администратора назначить эту роль "
                        + "или сделать её необязательной", Map.of("role", roleName));
    }

    static DomainException selectionRequired(int stageOrder, String roleName) {
        return new DomainException("SELECTION_REQUIRED", HttpStatus.CONFLICT,
                "Выберите согласующего для роли «" + roleName + "»",
                Map.of("stageOrder", stageOrder, "role", roleName));
    }

    static DomainException mandatoryRoleMissing(String roleName) {
        return new DomainException("MANDATORY_ROLE_MISSING", HttpStatus.BAD_REQUEST,
                "«" + roleName + "» обязателен для этого вида документа — верните его в маршрут",
                Map.of("role", roleName));
    }

    static DomainException routeNotConfigured() {
        return new DomainException("INVALID_STATE", HttpStatus.CONFLICT,
                "Для этого типа документа маршрут согласования не настроен");
    }
}
