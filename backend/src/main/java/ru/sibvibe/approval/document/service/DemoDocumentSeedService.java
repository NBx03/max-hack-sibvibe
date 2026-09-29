package ru.sibvibe.approval.document.service;

import ru.sibvibe.approval.organization.service.CompanyZoneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.entity.DocumentVersion;
import ru.sibvibe.approval.document.repository.DocumentRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeRepository;
import ru.sibvibe.approval.document.repository.DocumentVersionRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Создаёт синтетические документы личной демо-песочницы без файлов и внешних вызовов. */
@Service
public class DemoDocumentSeedService {

    private static final String MEMO_TYPE = "OFFICIAL_MEMO";
    private static final DateTimeFormatter DEMO_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");

    private final DocumentTypeRepository typeRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentRuleService ruleService;
    private final ObjectMapper objectMapper;
    private final CompanyRulesService companyRules;
    private final CompanyZoneService companyZones;

    public DemoDocumentSeedService(
            DocumentTypeRepository typeRepository,
            DocumentRepository documentRepository,
            DocumentVersionRepository versionRepository,
            DocumentRuleService ruleService,
            ObjectMapper objectMapper,
            CompanyRulesService companyRules,
            CompanyZoneService companyZones
    ) {
        this.typeRepository = typeRepository;
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.ruleService = ruleService;
        this.objectMapper = objectMapper;
        this.companyRules = companyRules;
        this.companyZones = companyZones;
    }

    public SeededDocuments seed(long orgId, long authorId, Instant now) {
        // Сначала правила: демо-документы проверяются уже по правилам своей компании.
        companyRules.applyDemoSettings(orgId, authorId);
        DocumentType type = typeRepository.findByCodeIn(Set.of(MEMO_TYPE)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Не найден тип документа " + MEMO_TYPE));
        // Дата записки — «сегодня» демо-компании, по её часовому поясу: иначе правило даты сочло бы её будущей.
        Map<String, String> fields = memoFields(now, companyZones.zoneOf(orgId));
        DocumentRuleService.CheckData check = ruleService.execute(ruleService.prepare(orgId, type.getId(), fields));
        if (check == null || check.issues().stream()
                .anyMatch(issue -> issue.severity() == ru.sibvibe.approval.rules.RuleEngine.Severity.BLOCKER)) {
            throw new IllegalStateException("Справочные правила отклоняют чистый демо-документ");
        }

        long draft = create(type.getId(), orgId, authorId, "Черновик служебной записки",
                Document.Status.DRAFT, null, now.minusSeconds(5 * 3600L), now.minusSeconds(5 * 3600L), fields, check);
        long inApproval = create(type.getId(), orgId, authorId, "Служебная записка на согласовании",
                Document.Status.IN_APPROVAL, 1, now.minusSeconds(4 * 3600L), now.minusSeconds(3 * 3600L), fields, check);
        long approved = create(type.getId(), orgId, authorId, "Согласованная служебная записка",
                Document.Status.APPROVED, null, now.minusSeconds(3 * 3600L), now.minusSeconds(2 * 3600L), fields, check);
        long returned = create(type.getId(), orgId, authorId, "Служебная записка на доработке",
                Document.Status.RETURNED, null, now.minusSeconds(2 * 3600L), now.minusSeconds(3600L), fields, check);
        long rejected = create(type.getId(), orgId, authorId, "Отклонённая служебная записка",
                Document.Status.REJECTED, null, now.minusSeconds(3600L), now.minusSeconds(300), fields, check);
        return new SeededDocuments(draft, inApproval, approved, returned, rejected);
    }

    private long create(
            long typeId,
            long orgId,
            long authorId,
            String title,
            Document.Status status,
            Integer currentStage,
            Instant createdAt,
            Instant updatedAt,
            Map<String, String> fields,
            DocumentRuleService.CheckData check
    ) {
        Document document = new Document();
        document.setDocumentTypeId(typeId);
        document.setAuthorId(authorId);
        document.setOrgId(orgId);
        document.setTitle(title);
        document.setStatus(status);
        document.setCurrentStage(currentStage);
        document.setCurrentVersionNo(1);
        document.setVisibility(Document.Visibility.ORG);
        document.setCreatedAt(createdAt);
        document.setUpdatedAt(updatedAt);
        document = documentRepository.save(document);

        var extracted = objectMapper.createObjectNode();
        extracted.put("modelAvailable", check.modelAvailable());
        extracted.set("fields", objectMapper.valueToTree(check.fields()));

        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(document.getId());
        version.setVersionNo(1);
        version.setContainsSensitive(false);
        version.setContent(objectMapper.valueToTree(fields));
        version.setCreatedBy(authorId);
        version.setCreatedAt(createdAt);
        version.setExtractedFields(extracted);
        version.setValidationIssues(objectMapper.valueToTree(check.issues()));
        version.setCheckStatus("CHECKED");
        versionRepository.save(version);
        return document.getId();
    }

    private Map<String, String> memoFields(Instant now, ZoneId zone) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("addressee", "Генеральному директору ООО «Демо-компания» Орлову В.П.");
        fields.put("author_name", "А.С. Петрова");
        fields.put("doc_date", LocalDate.ofInstant(now, zone).format(DEMO_DATE));
        fields.put("reg_number", "СЗ-117");
        fields.put("subject", "О закупке ноутбуков для отдела продаж");
        fields.put("body", "Прошу согласовать закупку пяти ноутбуков для новых сотрудников отдела продаж. "
                + "Средства предусмотрены бюджетом подразделения на текущий квартал.");
        fields.put("signer_position", "Руководитель отдела продаж");
        fields.put("signer_name", "А.С. Петрова");
        return Map.copyOf(fields);
    }

    public record SeededDocuments(
            long draftId,
            long inApprovalId,
            long approvedId,
            long returnedId,
            long rejectedId
    ) {
    }
}
