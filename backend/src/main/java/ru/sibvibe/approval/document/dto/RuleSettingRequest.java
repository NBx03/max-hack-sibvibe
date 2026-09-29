package ru.sibvibe.approval.document.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.sibvibe.approval.rules.RuleEngine.Severity;

import java.util.List;

/**
 * Желаемое состояние правила компании. Пустой {@code sourceTitle} — источник шаблона; {@code minLength} и
 * {@code allowedValues} — только у проверок, где ожидаемое значение настраивается, иначе не передаются.
 */
public record RuleSettingRequest(
        @NotNull Boolean enabled,
        @NotNull Severity severity,
        @NotNull @Size(max = 2000) String description,
        @Size(max = 1000) String sourceTitle,
        @Size(max = 1000) String sourceRef,
        Integer minLength,
        @Size(max = 50) List<@Size(max = 500) String> allowedValues
) {
}
