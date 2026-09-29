package ru.sibvibe.approval.rules.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.rules.RuleEngine.CheckType;
import ru.sibvibe.approval.rules.RuleEngine.RuleKind;
import ru.sibvibe.approval.rules.RuleEngine.Severity;
import ru.sibvibe.approval.rules.entity.OrganizationRuleSetting;
import ru.sibvibe.approval.rules.entity.RequirementRule;
import ru.sibvibe.approval.rules.repository.OrganizationRuleSettingRepository;
import ru.sibvibe.approval.rules.repository.RequirementRuleRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Правила проверки компании: шаблон сервиса ({@code requirement_rule}) плюс отличия компании
 * ({@code organization_rule_setting}).
 *
 * <p>Что компания может менять, решено так, чтобы настройка не ломала саму проверку и не выдавала себя за закон:
 * <ul>
 *   <li>проверять или нет, важность, текст замечания, свой источник и пункт — у любого правила, кроме закона;</li>
 *   <li>ожидаемое значение — только у «Не короче» (число) и «Одно из значений» (список). Шаблоны формата
 *       (регулярные выражения) и формат даты не открываем: человек не должен писать regex, а ошибка в нём молча
 *       ломала бы проверку;</li>
 *   <li>правило вида {@code LEGAL} не меняется вовсе: закон одинаков для всех компаний;</li>
 *   <li>новые правила и поля не создаются — это данные шаблона, а не настройка компании.</li>
 * </ul>
 * Правило продукта, которому компания указала свой источник, становится правилом компании ({@code INTERNAL_POLICY}):
 * основание теперь — её документ, а не наше допущение.
 */
@Service
public class RuleCatalogService {

    public static final int MAX_DESCRIPTION = 1000;
    public static final int MAX_SOURCE_TITLE = 300;
    public static final int MAX_SOURCE_REF = 200;
    public static final int MAX_MIN_LENGTH = 5000;
    public static final int MAX_ALLOWED_VALUES = 20;
    public static final int MAX_ALLOWED_VALUE_LENGTH = 100;

    private final RequirementRuleRepository ruleRepository;
    private final OrganizationRuleSettingRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RuleCatalogService(
            RequirementRuleRepository ruleRepository,
            OrganizationRuleSettingRepository settingRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.ruleRepository = ruleRepository;
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Правила типа документа, как они действуют в компании, в том числе выключенные. */
    @Transactional(readOnly = true)
    public List<CatalogRule> forType(long orgId, long documentTypeId) {
        Map<Long, OrganizationRuleSetting> settings = settings(orgId);
        return ruleRepository.findByDocumentTypeIdOrderById(documentTypeId).stream()
                .map(rule -> effective(rule, settings.get(rule.getId())))
                .toList();
    }

    /** Все правила компании — для экрана «Правила проверки». */
    @Transactional(readOnly = true)
    public List<CatalogRule> all(long orgId) {
        Map<Long, OrganizationRuleSetting> settings = settings(orgId);
        return ruleRepository.findAll().stream()
                .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
                .map(rule -> effective(rule, settings.get(rule.getId())))
                .toList();
    }

    /**
     * Сохраняет желаемое состояние правила. Хранится только отличие от шаблона: совпало с шаблоном целиком —
     * строка настройки удаляется, и правило снова «как в шаблоне».
     */
    @Transactional
    public CatalogRule update(long orgId, long ruleId, long userId, Change change) {
        RequirementRule rule = ruleRepository.findById(ruleId).orElseThrow(NotFoundException::new);
        if (rule.getKind() == RuleKind.LEGAL) {
            throw validation("Требование закона одинаково для всех компаний — выключить или изменить его нельзя");
        }
        if (change.severity() == null) {
            throw validation("Укажите важность замечания");
        }
        String description = requiredText(change.description(), MAX_DESCRIPTION, "Текст замечания");
        String sourceTitle = optionalText(change.sourceTitle(), MAX_SOURCE_TITLE, "Название документа-основания");
        String sourceRef = optionalText(change.sourceRef(), MAX_SOURCE_REF, "Пункт");
        // Пустое название — источник шаблона, что бы ни осталось в поле пункта: администратор
        // стёр название своего документа, а пункт в форме остался — это возврат к шаблону, а не ошибка.
        String expected = expected(rule, change);

        OrganizationRuleSetting setting = settingRepository.findByOrgIdAndRuleId(orgId, ruleId)
                .orElseGet(OrganizationRuleSetting::new);
        setting.setOrgId(orgId);
        setting.setRuleId(ruleId);
        setting.setEnabled(change.enabled());
        setting.setSeverity(change.severity() == rule.getSeverity() ? null : change.severity());
        setting.setDescription(description.equals(rule.getDescription()) ? null : description);
        // Свой источник — другое название или другой пункт; совпало с шаблоном целиком — источник шаблона.
        boolean ownSource = sourceTitle != null
                && (!sourceTitle.equals(rule.getSourceTitle()) || !Objects.equals(sourceRef, rule.getSourceRef()));
        setting.setSourceTitle(ownSource ? sourceTitle : null);
        setting.setSourceRef(ownSource ? sourceRef : null);
        setting.setExpected(Objects.equals(expected, rule.getExpected()) ? null : expected);
        setting.setUpdatedBy(userId);
        setting.setUpdatedAt(clock.instant());

        if (!isCustomized(setting)) {
            if (settingRepository.existsById(new OrganizationRuleSetting.Key(orgId, ruleId))) {
                settingRepository.deleteById(new OrganizationRuleSetting.Key(orgId, ruleId));
            }
            return effective(rule, null);
        }
        return effective(rule, settingRepository.save(setting));
    }

    /** «Сбросить к типовому»: настройка компании удаляется. */
    @Transactional
    public CatalogRule reset(long orgId, long ruleId) {
        RequirementRule rule = ruleRepository.findById(ruleId).orElseThrow(NotFoundException::new);
        OrganizationRuleSetting.Key key = new OrganizationRuleSetting.Key(orgId, ruleId);
        if (settingRepository.existsById(key)) {
            settingRepository.deleteById(key);
        }
        return effective(rule, null);
    }

    private Map<Long, OrganizationRuleSetting> settings(long orgId) {
        return settingRepository.findByOrgId(orgId).stream()
                .collect(Collectors.toMap(OrganizationRuleSetting::getRuleId, Function.identity()));
    }

    private static boolean isCustomized(OrganizationRuleSetting setting) {
        return !setting.isEnabled() || setting.getSeverity() != null || setting.getDescription() != null
                || setting.getSourceTitle() != null || setting.getExpected() != null;
    }

    private CatalogRule effective(RequirementRule rule, OrganizationRuleSetting setting) {
        Template template = new Template(rule.getKind(), rule.getSeverity(), rule.getDescription(),
                rule.getSourceTitle(), rule.getSourceUrl(), rule.getSourceRef(), rule.getExpected());
        if (setting == null) {
            return new CatalogRule(rule.getId(), rule.getDocumentTypeId(), rule.getFieldName(), rule.getCheckType(),
                    rule.getKind(), true, rule.getSeverity(), rule.getDescription(), rule.getSourceTitle(),
                    rule.getSourceUrl(), rule.getSourceRef(), rule.getExpected(), rule.getSourceCheckedAt(),
                    false, rule.getKind() == RuleKind.LEGAL, template);
        }
        boolean ownSource = setting.getSourceTitle() != null;
        RuleKind kind = ownSource && rule.getKind() == RuleKind.PRODUCT_RULE ? RuleKind.INTERNAL_POLICY : rule.getKind();
        return new CatalogRule(
                rule.getId(),
                rule.getDocumentTypeId(),
                rule.getFieldName(),
                rule.getCheckType(),
                kind,
                setting.isEnabled(),
                setting.getSeverity() != null ? setting.getSeverity() : rule.getSeverity(),
                setting.getDescription() != null ? setting.getDescription() : rule.getDescription(),
                ownSource ? setting.getSourceTitle() : rule.getSourceTitle(),
                ownSource ? null : rule.getSourceUrl(),
                ownSource ? setting.getSourceRef() : rule.getSourceRef(),
                setting.getExpected() != null ? setting.getExpected() : rule.getExpected(),
                rule.getSourceCheckedAt(),
                isCustomized(setting),
                rule.getKind() == RuleKind.LEGAL,
                template);
    }

    /** Ожидаемое значение в том же виде, что в шаблоне; у остальных проверок — как в шаблоне. */
    private String expected(RequirementRule rule, Change change) {
        if (rule.getCheckType() == CheckType.MIN_LENGTH) {
            if (change.minLength() == null) {
                return rule.getExpected();
            }
            if (change.minLength() < 1 || change.minLength() > MAX_MIN_LENGTH) {
                throw validation("Минимальная длина — от 1 до " + MAX_MIN_LENGTH + " символов");
            }
            return Integer.toString(change.minLength());
        }
        if (rule.getCheckType() == CheckType.ONE_OF) {
            if (change.allowedValues() == null) {
                return rule.getExpected();
            }
            Set<String> values = new LinkedHashSet<>();
            for (String value : change.allowedValues()) {
                String stripped = value == null ? "" : value.strip();
                if (stripped.isEmpty()) {
                    continue;
                }
                if (stripped.length() > MAX_ALLOWED_VALUE_LENGTH) {
                    throw validation("Допустимое значение — не длиннее " + MAX_ALLOWED_VALUE_LENGTH + " символов");
                }
                values.add(stripped);
            }
            if (values.isEmpty() || values.size() > MAX_ALLOWED_VALUES) {
                throw validation("Допустимых значений — от 1 до " + MAX_ALLOWED_VALUES);
            }
            if (Objects.equals(new ArrayList<>(values), allowedValues(rule.getExpected()))) {
                return rule.getExpected();
            }
            try {
                return objectMapper.writeValueAsString(new ArrayList<>(values));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(exception);
            }
        }
        if (change.minLength() != null || change.allowedValues() != null) {
            throw validation("У этой проверки ожидаемое значение не настраивается");
        }
        return rule.getExpected();
    }

    /** Значения «Одно из значений» из JSON-массива; пусто, если это другая проверка. */
    public List<String> allowedValues(String expected) {
        if (expected == null || expected.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(expected, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static String requiredText(String value, int max, String label) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            throw validation(label + " не может быть пустым");
        }
        if (stripped.length() > max) {
            throw validation(label + " — не длиннее " + max + " символов");
        }
        return stripped;
    }

    private static String optionalText(String value, int max, String label) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() > max) {
            throw validation(label + " — не длиннее " + max + " символов");
        }
        return stripped;
    }

    private static DomainException validation(String message) {
        return new DomainException("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Желаемое состояние правила от администратора. {@code minLength} — только у «Не короче», {@code allowedValues} —
     * только у «Одно из значений»; {@code null} — как в шаблоне. Пустой {@code sourceTitle} — источник шаблона.
     */
    public record Change(
            boolean enabled,
            Severity severity,
            String description,
            String sourceTitle,
            String sourceRef,
            Integer minLength,
            List<String> allowedValues
    ) {
    }

    /** Значения шаблона — чтобы экран показывал, что именно изменено, и мог сбросить. */
    public record Template(
            RuleKind kind,
            Severity severity,
            String description,
            String sourceTitle,
            String sourceUrl,
            String sourceRef,
            String expected
    ) {
    }

    /** Правило, как оно действует в компании. {@code locked} — закон: не меняется. */
    public record CatalogRule(
            long id,
            long documentTypeId,
            String fieldName,
            CheckType check,
            RuleKind kind,
            boolean enabled,
            Severity severity,
            String description,
            String sourceTitle,
            String sourceUrl,
            String sourceRef,
            String expected,
            java.time.Instant sourceCheckedAt,
            boolean customized,
            boolean locked,
            Template template
    ) {
    }
}
