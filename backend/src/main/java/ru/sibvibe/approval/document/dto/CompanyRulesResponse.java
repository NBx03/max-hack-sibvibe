package ru.sibvibe.approval.document.dto;

import ru.sibvibe.approval.rules.RuleEngine.CheckType;
import ru.sibvibe.approval.rules.RuleEngine.RuleKind;
import ru.sibvibe.approval.rules.RuleEngine.Severity;

import java.util.List;

/**
 * Правила проверки компании по типам документов (раздел «Компания» → «Правила проверки»).
 * {@code canEdit} — текущий участник администратор: остальные видят правила, но не меняют.
 */
public record CompanyRulesResponse(boolean canEdit, List<TypeRules> types) {

    public record TypeRules(long documentTypeId, String code, String name, List<RuleView> rules) {
    }

    /**
     * Правило, как оно действует в компании. {@code minLength} — только у MIN_LENGTH, {@code allowedValues} — только
     * у ONE_OF: только их ожидаемое значение настраивается. {@code locked} — закон, не меняется. {@code template} —
     * значения шаблона сервиса, к которым ведёт «Сбросить к типовому».
     */
    public record RuleView(
            long id,
            String fieldName,
            String fieldLabel,
            CheckType check,
            RuleKind kind,
            boolean enabled,
            Severity severity,
            String description,
            String sourceTitle,
            String sourceUrl,
            String sourceRef,
            Integer minLength,
            List<String> allowedValues,
            boolean customized,
            boolean locked,
            Template template
    ) {
    }

    public record Template(
            RuleKind kind,
            Severity severity,
            String description,
            String sourceTitle,
            String sourceRef,
            Integer minLength,
            List<String> allowedValues
    ) {
    }
}
