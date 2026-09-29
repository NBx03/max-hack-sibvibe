package ru.sibvibe.approval.document.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.ai.DocumentExtractor;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.document.dto.DocumentCardResponse;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.entity.DocumentTypeField;
import ru.sibvibe.approval.document.repository.DocumentTypeFieldRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeRepository;
import ru.sibvibe.approval.organization.service.CompanyZoneService;
import ru.sibvibe.approval.rules.RuleEngine;
import ru.sibvibe.approval.rules.service.RuleCatalogService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DocumentRuleService {

    private static final int MAX_FIELDS = 100;
    private static final int MAX_FIELD_VALUE_LENGTH = 10_000;

    private final DocumentTypeRepository typeRepository;
    private final DocumentTypeFieldRepository fieldRepository;
    private final RuleCatalogService ruleCatalog;
    private final RuleEngine ruleEngine;
    private final CompanyZoneService companyZones;
    private final Clock clock;

    public DocumentRuleService(
            DocumentTypeRepository typeRepository,
            DocumentTypeFieldRepository fieldRepository,
            RuleCatalogService ruleCatalog,
            RuleEngine ruleEngine,
            CompanyZoneService companyZones,
            Clock clock
    ) {
        this.typeRepository = typeRepository;
        this.fieldRepository = fieldRepository;
        this.ruleCatalog = ruleCatalog;
        this.ruleEngine = ruleEngine;
        this.companyZones = companyZones;
        this.clock = clock;
    }

    /**
     * Схема полей и правила, которые действуют в компании {@code orgId}: шаблон сервиса с её настройками.
     * Выключенное компанией правило не проверяется вовсе.
     */
    @Transactional(readOnly = true)
    public PreparedCheck prepare(long orgId, long documentTypeId, Map<String, String> suppliedFields) {
        DocumentType type = typeRepository.findById(documentTypeId).orElseThrow(NotFoundException::new);
        List<DocumentTypeField> schema = fieldRepository.findByDocumentTypeIdOrderByPositionAscIdAsc(documentTypeId);
        Map<String, String> fields = suppliedFields == null
                ? Map.of()
                : new LinkedHashMap<>(suppliedFields);
        if (fields.size() > MAX_FIELDS
                || fields.values().stream().anyMatch(value -> value != null
                        && value.length() > MAX_FIELD_VALUE_LENGTH)) {
            throw DocumentApiException.validation("Слишком много данных в полях документа");
        }
        Set<String> knownNames = schema.stream()
                .map(DocumentTypeField::getFieldName)
                .collect(Collectors.toSet());
        if (fields.keySet().stream().anyMatch(name -> name == null || !knownNames.contains(name))) {
            throw DocumentApiException.validation("Переданы неизвестные поля документа");
        }
        List<RuleEngine.RequirementRule> rules = ruleCatalog.forType(orgId, documentTypeId).stream()
                .filter(RuleCatalogService.CatalogRule::enabled)
                .map(rule -> new RuleEngine.RequirementRule(
                        rule.id(),
                        type.getCode(),
                        rule.fieldName(),
                        rule.description(),
                        rule.check(),
                        rule.expected(),
                        rule.kind(),
                        rule.severity(),
                        rule.sourceTitle(),
                        rule.sourceUrl(),
                        rule.sourceRef(),
                        rule.sourceCheckedAt()))
                .toList();
        return new PreparedCheck(
                type,
                List.copyOf(schema),
                Collections.unmodifiableMap(new LinkedHashMap<>(fields)),
                rules,
                Map.of(),
                companyZones.zoneOf(orgId));
    }

    public CheckData execute(PreparedCheck prepared) {
        return execute(prepared, new DocumentExtractor.ExtractionResult(Map.of(), false, "none"));
    }

    public CheckData execute(
            PreparedCheck prepared,
            DocumentExtractor.ExtractionResult extraction
    ) {
        if (prepared.type().isGeneric()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, RuleEngine.FieldLocation> locations = new LinkedHashMap<>();
        List<DocumentCardResponse.FieldValue> fields = prepared.schema().stream()
                .map(field -> fieldValue(prepared, extraction, values, locations, field))
                .toList();
        List<DocumentCardResponse.ValidationIssueView> issues = ruleEngine.validate(
                        values,
                        prepared.rules(),
                        new RuleEngine.ValidationContext(
                                LocalDate.ofInstant(clock.instant(), prepared.zone()), locations))
                .stream()
                .map(issue -> new DocumentCardResponse.ValidationIssueView(
                        issue.ruleId(),
                        issue.fieldName(),
                        issue.message(),
                        issue.severity(),
                        issue.kind(),
                        issue.sourceTitle(),
                        issue.sourceUrl(),
                        issue.sourceRef(),
                        issue.quote(),
                        issue.page()))
                .toList();
        return new CheckData(extraction.available(), fields, issues, extraction.textMissing());
    }

    public CheckData executeManualUpdate(PreparedCheck prepared, CheckData previous) {
        Map<String, DocumentCardResponse.FieldValue> previousByName = new LinkedHashMap<>();
        if (previous != null) {
            previous.fields().forEach(field -> previousByName.putIfAbsent(field.name(), field));
        }
        Map<String, String> manual = new LinkedHashMap<>();
        Map<String, DocumentExtractor.ExtractedField> extracted = new LinkedHashMap<>();
        for (DocumentTypeField schemaField : prepared.schema()) {
            String name = schemaField.getFieldName();
            DocumentCardResponse.FieldValue old = previousByName.get(name);
            boolean supplied = prepared.suppliedFields().containsKey(name);
            String value = supplied
                    ? prepared.suppliedFields().get(name)
                    : old == null ? null : old.value();
            // Пустое и отсутствующее — одно и то же: поле, которое открыли и не тронули, остаётся
            // «из текста документа», а не «указано вручную».
            boolean unchangedModel = old != null
                    && "MODEL".equals(old.source())
                    && Objects.equals(blankToNull(old.value()), blankToNull(value));
            if (unchangedModel) {
                extracted.put(name, new DocumentExtractor.ExtractedField(value, old.quote(), old.page()));
            } else {
                manual.put(name, value);
            }
        }
        // Пояс компании — из prepared: иначе «Сохранить и проверить» сверяло бы дату по Москве.
        // fileValues не переносим: «что в файле» у изменённых полей добавляет withFileValue ниже, из прошлой проверки.
        PreparedCheck merged = new PreparedCheck(
                prepared.type(), prepared.schema(), Collections.unmodifiableMap(manual), prepared.rules(),
                Map.of(), prepared.zone());
        CheckData result = execute(merged, new DocumentExtractor.ExtractionResult(
                Map.copyOf(extracted), previous != null && previous.modelAvailable(), "stored"));
        if (result == null) {
            return null;
        }
        // Ручная правка без изменения файла видна согласующему: рядом со значением — что в файле.
        List<DocumentCardResponse.FieldValue> fields = result.fields().stream()
                .map(field -> withFileValue(field, previousByName.get(field.name())))
                .toList();
        return new CheckData(result.modelAvailable(), fields, result.issues(),
                previous != null && previous.textMissing());
    }

    private static DocumentCardResponse.FieldValue withFileValue(
            DocumentCardResponse.FieldValue field,
            DocumentCardResponse.FieldValue old
    ) {
        if (!"MANUAL".equals(field.source()) || old == null) {
            return field;
        }
        // Пустая строка из одних пробелов от модели — тоже «не нашла», а не значение в файле.
        String inFile = old.fileValue() != null ? old.fileValue()
                : "MODEL".equals(old.source()) ? blankToNull(old.value()) : null;
        if (inFile == null && "MODEL".equals(old.source()) && field.value() != null && !field.value().isBlank()) {
            // Модель прочитала файл и этого поля в нём не нашла, а автор его заполнил: «в файле этого нет»
            // — пустая строка, в отличие от null («что в файле, неизвестно»).
            inFile = "";
        }
        if (inFile == null || Objects.equals(inFile, field.value())) {
            return field;
        }
        return new DocumentCardResponse.FieldValue(
                field.name(), field.value(), field.source(), field.quote(), field.page(), inFile);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public CheckData pending(PreparedCheck prepared) {
        if (prepared.type().isGeneric()) {
            return null;
        }
        return new CheckData(false, manualFields(prepared), List.of());
    }

    private List<DocumentCardResponse.FieldValue> manualFields(PreparedCheck prepared) {
        return prepared.schema().stream()
                .map(field -> new DocumentCardResponse.FieldValue(
                        field.getFieldName(),
                        prepared.suppliedFields().get(field.getFieldName()),
                        "MANUAL",
                        null,
                        null))
                .toList();
    }

    private DocumentCardResponse.FieldValue fieldValue(
            PreparedCheck prepared,
            DocumentExtractor.ExtractionResult extraction,
            Map<String, String> values,
            Map<String, RuleEngine.FieldLocation> locations,
            DocumentTypeField field
    ) {
        String name = field.getFieldName();
        if (prepared.suppliedFields().containsKey(name)) {
            String value = prepared.suppliedFields().get(name);
            values.put(name, value);
            return new DocumentCardResponse.FieldValue(
                    name, value, "MANUAL", null, null, prepared.fileValues().get(name));
        }
        DocumentExtractor.ExtractedField extracted = extraction.fields().get(name);
        String value = extracted == null ? null : extracted.value();
        values.put(name, value);
        if (extracted != null && extracted.quote() != null) {
            locations.put(name, new RuleEngine.FieldLocation(extracted.quote(), extracted.page()));
        }
        return new DocumentCardResponse.FieldValue(
                name,
                value,
                extraction.available() ? "MODEL" : "MANUAL",
                extracted == null ? null : extracted.quote(),
                extracted == null ? null : extracted.page());
    }

    /**
     * fileValues — что написано в файле для полей из suppliedFields, которые автор раньше заменил
     * вручную, не меняя файл: согласующий продолжает видеть расхождение.
     */
    public record PreparedCheck(
            DocumentType type,
            List<DocumentTypeField> schema,
            Map<String, String> suppliedFields,
            List<RuleEngine.RequirementRule> rules,
            Map<String, String> fileValues,
            /* Часовой пояс компании: «сегодня» для правила «не позже сегодняшнего дня». */
            ZoneId zone
    ) {
        public PreparedCheck withFileValues(Map<String, String> values) {
            return new PreparedCheck(type, schema, suppliedFields, rules, Map.copyOf(values), zone);
        }
    }

    /** textMissing — в файле не нашлось текста (скан): поля только вручную, и экран говорит почему. */
    public record CheckData(
            boolean modelAvailable,
            List<DocumentCardResponse.FieldValue> fields,
            List<DocumentCardResponse.ValidationIssueView> issues,
            boolean textMissing
    ) {
        public CheckData(
                boolean modelAvailable,
                List<DocumentCardResponse.FieldValue> fields,
                List<DocumentCardResponse.ValidationIssueView> issues
        ) {
            this(modelAvailable, fields, issues, false);
        }
    }
}
