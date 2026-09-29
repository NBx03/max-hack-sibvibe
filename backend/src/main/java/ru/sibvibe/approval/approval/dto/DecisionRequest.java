package ru.sibvibe.approval.approval.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Решение согласующего по шагу. Для {@code RETURN} и {@code REJECT} комментарий обязателен.
 *
 * Длину комментария (до 2000 символов) проверяет сервис уже после обрезки пробелов по краям: ограничение
 * на самом поле сработало бы до неё, и строка из одних пробелов получила бы «слишком длинный» вместо
 * «комментарий обязателен».
 */
public record DecisionRequest(@NotNull Action decision, String comment) {

    public enum Action { APPROVE, RETURN, REJECT }
}
