package ru.sibvibe.approval.document.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.dto.CompanyRulesResponse;
import ru.sibvibe.approval.document.dto.RuleSettingRequest;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.entity.DocumentTypeField;
import ru.sibvibe.approval.document.repository.DocumentTypeFieldRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeRepository;
import ru.sibvibe.approval.rules.RuleEngine.CheckType;
import ru.sibvibe.approval.rules.service.RuleCatalogService;
import ru.sibvibe.approval.rules.service.RuleCatalogService.CatalogRule;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Раздел «Компания» → «Правила проверки»: смотреть — любой участник, менять — администратор своей
 * компании. Правила — шаблон сервиса с настройками компании ({@link RuleCatalogService}); здесь к ним добавляются
 * типы документов и подписи полей, которыми владеет модуль {@code document}.
 */
@Service
public class CompanyRulesService {

    private static final String GENERIC = "GENERIC";

    private final RuleCatalogService catalog;
    private final DocumentTypeRepository typeRepository;
    private final DocumentTypeFieldRepository fieldRepository;

    public CompanyRulesService(
            RuleCatalogService catalog,
            DocumentTypeRepository typeRepository,
            DocumentTypeFieldRepository fieldRepository
    ) {
        this.catalog = catalog;
        this.typeRepository = typeRepository;
        this.fieldRepository = fieldRepository;
    }

    @Transactional(readOnly = true)
    public CompanyRulesResponse rules(CurrentUser user) {
        long orgId = requireMember(user);
        Map<Long, List<CatalogRule>> byType = catalog.all(orgId).stream()
                .collect(Collectors.groupingBy(CatalogRule::documentTypeId));
        List<DocumentTypeField> fields = fieldRepository.findAllByOrderByDocumentTypeIdAscPositionAscIdAsc();
        Map<String, String> labels = fields.stream()
                .collect(Collectors.toMap(field -> field.getDocumentTypeId() + ":" + field.getFieldName(),
                        DocumentTypeField::getLabel, (left, right) -> left));
        // Правила — в порядке полей документа, как на экране проверки, а не в порядке номеров в базе.
        Map<String, Integer> positions = new java.util.HashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            DocumentTypeField field = fields.get(index);
            positions.putIfAbsent(field.getDocumentTypeId() + ":" + field.getFieldName(), index);
        }
        List<CompanyRulesResponse.TypeRules> types = typeRepository.findAllByOrderById().stream()
                .filter(type -> byType.containsKey(type.getId()))
                // Как на экране загрузки: «Другой документ» — последним.
                .sorted(Comparator.comparing((DocumentType type) -> GENERIC.equals(type.getCode()))
                        .thenComparing(DocumentType::getId))
                .map(type -> new CompanyRulesResponse.TypeRules(type.getId(), type.getCode(), type.getName(),
                        byType.get(type.getId()).stream()
                                .sorted(Comparator.comparing((CatalogRule rule) -> positions.getOrDefault(
                                        type.getId() + ":" + rule.fieldName(), Integer.MAX_VALUE))
                                        .thenComparing(rule -> rule.check().ordinal())
                                        .thenComparing(CatalogRule::id))
                                .map(rule -> view(rule, labels.getOrDefault(type.getId() + ":" + rule.fieldName(),
                                        rule.fieldName())))
                                .toList()))
                .toList();
        return new CompanyRulesResponse(user.admin(), types);
    }

    @Transactional
    public CompanyRulesResponse update(CurrentUser user, long ruleId, RuleSettingRequest request) {
        long orgId = requireAdmin(user);
        catalog.update(orgId, ruleId, user.userId(), new RuleCatalogService.Change(
                request.enabled(), request.severity(), request.description(), request.sourceTitle(),
                request.sourceRef(), request.minLength(), request.allowedValues()));
        return rules(user);
    }

    @Transactional
    public CompanyRulesResponse reset(CurrentUser user, long ruleId) {
        long orgId = requireAdmin(user);
        catalog.reset(orgId, ruleId);
        return rules(user);
    }

    /**
     * Демо-песочница получает свои «локальные акты Демо-компании» настройкой, а не шаблоном: так в демонстрации
     * сразу видно, как компания подстраивает правила под себя («Изменено компанией»), а у настоящей компании
     * остаётся нейтральный шаблон сервиса.
     */
    @Transactional
    public void applyDemoSettings(long orgId, long userId) {
        Map<String, DocumentType> types = typeRepository.findByCodeIn(Set.of("OFFICIAL_MEMO", "VACATION_REQUEST", "BUSINESS_TRIP_REQUEST"))
                .stream().collect(Collectors.toMap(DocumentType::getCode, type -> type));
        demoSource(orgId, userId, types.get("OFFICIAL_MEMO"), DEMO_MEMO_POLICY, Map.of(
                "reg_number", "п. 2.5",
                "signer_position", "п. 2.7"));
        demoSource(orgId, userId, types.get("VACATION_REQUEST"), DEMO_VACATION_POLICY, Map.of(
                "employee_name", "п. 3.1",
                "vacation_type", "п. 3.2",
                "start_date", "п. 3.3"));
        // Заявка на командировку: четвёртый тип добавлен одними данными — и настраивается так же.
        demoSource(orgId, userId, types.get("BUSINESS_TRIP_REQUEST"), DEMO_TRIP_POLICY, Map.of(
                "employee_name", "п. 2.1",
                "destination", "п. 2.2",
                "purpose", "п. 2.3",
                "start_date", "п. 2.4"));
    }

    static final String DEMO_MEMO_POLICY =
            "Инструкция по делопроизводству Демо-компании (вымышленный локальный акт на основе ГОСТ Р 7.0.97-2016)";
    static final String DEMO_VACATION_POLICY =
            "Положение об оформлении отпусков Демо-компании (вымышленный локальный акт)";
    static final String DEMO_TRIP_POLICY =
            "Положение о служебных командировках Демо-компании (вымышленный локальный акт)";

    private void demoSource(long orgId, long userId, DocumentType type, String title, Map<String, String> refs) {
        if (type == null) {
            return;
        }
        for (CatalogRule rule : catalog.forType(orgId, type.getId())) {
            String ref = refs.get(rule.fieldName());
            if (ref == null || rule.locked()) {
                continue;
            }
            catalog.update(orgId, rule.id(), userId, new RuleCatalogService.Change(
                    true, rule.severity(), rule.description(), title, ref, null, null));
        }
    }

    private CompanyRulesResponse.RuleView view(CatalogRule rule, String fieldLabel) {
        RuleCatalogService.Template template = rule.template();
        return new CompanyRulesResponse.RuleView(
                rule.id(),
                rule.fieldName(),
                fieldLabel,
                rule.check(),
                rule.kind(),
                rule.enabled(),
                rule.severity(),
                rule.description(),
                rule.sourceTitle(),
                rule.sourceUrl(),
                rule.sourceRef(),
                minLength(rule.check(), rule.expected()),
                allowedValues(rule.check(), rule.expected()),
                rule.customized(),
                rule.locked(),
                new CompanyRulesResponse.Template(
                        template.kind(),
                        template.severity(),
                        template.description(),
                        template.sourceTitle(),
                        template.sourceRef(),
                        minLength(rule.check(), template.expected()),
                        allowedValues(rule.check(), template.expected())));
    }

    private static Integer minLength(CheckType check, String expected) {
        if (check != CheckType.MIN_LENGTH || expected == null) {
            return null;
        }
        try {
            return Integer.parseInt(expected.strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<String> allowedValues(CheckType check, String expected) {
        return check == CheckType.ONE_OF ? catalog.allowedValues(expected) : null;
    }

    private static long requireMember(CurrentUser user) {
        if (user.orgId() == null || user.memberId() == null) {
            throw DocumentApiException.forbidden("Правила проверки видны сотрудникам компании");
        }
        return user.orgId();
    }

    private static long requireAdmin(CurrentUser user) {
        long orgId = requireMember(user);
        if (!user.admin()) {
            throw new DomainException("NOT_ADMIN", HttpStatus.FORBIDDEN,
                    "Менять правила проверки может только администратор компании");
        }
        return orgId;
    }
}
