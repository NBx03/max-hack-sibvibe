package ru.sibvibe.approval.document.service;

import ru.sibvibe.approval.organization.service.CompanyZoneService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.dto.DocumentCardResponse;
import ru.sibvibe.approval.document.dto.DocumentListResponse;
import ru.sibvibe.approval.document.dto.DocumentTypeResponse;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.entity.DocumentFile;
import ru.sibvibe.approval.document.entity.DocumentTypeField;
import ru.sibvibe.approval.document.entity.DocumentVersion;
import ru.sibvibe.approval.document.repository.DocumentFileRepository;
import ru.sibvibe.approval.document.repository.DocumentReadRepository;
import ru.sibvibe.approval.document.repository.DocumentRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeFieldRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeRepository;
import ru.sibvibe.approval.document.repository.DocumentVersionRepository;
import ru.sibvibe.approval.organization.service.OrganizationSecurityService;
import ru.sibvibe.approval.rules.RuleEngine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DocumentViewService {


    private static final TypeReference<List<DocumentCardResponse.FieldValue>> FIELD_LIST =
            new TypeReference<>() {};
    private static final TypeReference<List<DocumentCardResponse.ValidationIssueView>> ISSUE_LIST =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> CONTENT = new TypeReference<>() {};

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentFileRepository fileRepository;
    private final DocumentTypeRepository typeRepository;
    private final DocumentTypeFieldRepository fieldRepository;
    private final DocumentReadRepository readRepository;
    private final OrganizationSecurityService organizationService;
    private final DownloadTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final VersionChangesService versionChanges;
    private final CompanyZoneService companyZones;

    public DocumentViewService(
            DocumentRepository documentRepository,
            DocumentVersionRepository versionRepository,
            DocumentFileRepository fileRepository,
            DocumentTypeRepository typeRepository,
            DocumentTypeFieldRepository fieldRepository,
            DocumentReadRepository readRepository,
            OrganizationSecurityService organizationService,
            DownloadTokenService tokenService,
            ObjectMapper objectMapper,
            VersionChangesService versionChanges,
            CompanyZoneService companyZones
    ) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.fileRepository = fileRepository;
        this.typeRepository = typeRepository;
        this.fieldRepository = fieldRepository;
        this.readRepository = readRepository;
        this.organizationService = organizationService;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.versionChanges = versionChanges;
        this.companyZones = companyZones;
    }

    @Transactional(readOnly = true)
    public List<DocumentTypeResponse> documentTypes() {
        Map<Long, List<DocumentTypeField>> fields = fieldRepository.findAllByOrderByDocumentTypeIdAscPositionAscIdAsc()
                .stream()
                .collect(Collectors.groupingBy(
                        DocumentTypeField::getDocumentTypeId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return typeRepository.findAllByOrderById().stream()
                .map(type -> new DocumentTypeResponse(
                        type.getId(),
                        type.getCode(),
                        type.getName(),
                        type.isGeneric(),
                        fields.getOrDefault(type.getId(), List.of()).stream()
                                .map(field -> new DocumentTypeResponse.Field(
                                        field.getFieldName(),
                                        field.getFieldType().name(),
                                        field.getLabel(),
                                        field.getHint() == null ? "" : field.getHint()))
                                .toList()))
                .toList();
    }

    public DocumentListResponse list(
            CurrentUser currentUser,
            DocumentListTab tab,
            DisplayStatus status,
            Long typeId,
            Long authorId,
            LocalDate from,
            LocalDate to,
            String query,
            int page,
            int size
    ) {
        long orgId = requireOrganization(currentUser);
        // Границы периода — по часовому поясу компании.
        ZoneId zone = companyZones.zoneOf(orgId);
        Instant fromInstant = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        DocumentReadRepository.PageRows rows = readRepository.findDocuments(
                tab, orgId, currentUser.userId(), status, typeId, authorId,
                fromInstant, toExclusive, query, page, size);
        List<DocumentListResponse.Item> items = rows.items().stream()
                .map(row -> new DocumentListResponse.Item(
                        row.id(),
                        row.title(),
                        new DocumentListResponse.TypeRef(row.typeId(), row.typeName()),
                        row.status(),
                        row.displayStatus(),
                        new UserRef(row.authorId(), row.authorName()),
                        row.currentVersionNo(),
                        row.currentStage(),
                        row.updatedAt(),
                        tab == DocumentListTab.WAITING_ME ? row.waitingSince() : null,
                        row.recognizedKind() == null
                                ? null
                                : new DocumentListResponse.RecognizedKind(row.recognizedKind(), row.recognizedKindFromAi())))
                .toList();
        return new DocumentListResponse(items, page, size, rows.total());
    }

    @Transactional(readOnly = true)
    public DocumentCardResponse card(long documentId, CurrentUser currentUser) {
        long orgId = requireOrganization(currentUser);
        if (!readRepository.canRead(documentId, orgId, currentUser.userId())) {
            throw new NotFoundException();
        }
        Document document = documentRepository.findById(documentId).orElseThrow(NotFoundException::new);
        var type = typeRepository.findById(document.getDocumentTypeId()).orElseThrow();
        // Вид, который определил ИИ, — только если он не совпадает с выбранным. «Другой документ»
        // от ИИ — это «не уверен», а не другой вид: расхождения нет (у записки, выбранной
        // автором, предупреждение «ИИ определил: Другой документ» было ложным).
        var aiType = document.getAiTypeId() == null || document.getAiTypeId().equals(document.getDocumentTypeId())
                ? null
                : typeRepository.findById(document.getAiTypeId())
                        .filter(found -> !DocumentTypeGuessService.OTHER_DOCUMENT.equals(found.getCode()))
                        .orElse(null);
        List<DocumentVersion> versions = versionRepository.findByDocumentIdOrderByVersionNoAsc(documentId);
        Map<Long, List<DocumentFile>> files = fileRepository
                .findByVersionIdInOrderByVersionIdAscPositionAsc(
                        versions.stream().map(DocumentVersion::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        DocumentFile::getVersionId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        Set<Long> userIds = versions.stream()
                .map(DocumentVersion::getCreatedBy)
                .collect(Collectors.toSet());
        userIds.add(document.getAuthorId());
        Map<Long, OrganizationSecurityService.UserInfo> users = organizationService.findUsers(userIds);

        List<DocumentCardResponse.VersionView> versionViews = versions.stream()
                .map(version -> new DocumentCardResponse.VersionView(
                        version.getVersionNo(),
                        version.isContainsSensitive(),
                        version.getCreatedAt(),
                        user(users, version.getCreatedBy()),
                        fileViews(files.getOrDefault(version.getId(), List.of()), currentUser.userId()),
                        version.getWithdrawnAt(),
                        DocumentPersistenceService.isForm(version)
                                ? objectMapper.convertValue(version.getContent(), CONTENT)
                                : null,
                        version.getValidationIssues() == null
                                ? null
                                : objectMapper.convertValue(version.getValidationIssues(), ISSUE_LIST)))
                .toList();
        DocumentVersion current = versions.stream()
                .filter(version -> version.getVersionNo().equals(document.getCurrentVersionNo()))
                .findFirst()
                .orElseThrow();
        DocumentCardResponse.CheckResult check = type.isGeneric() ? null : check(current);
        List<DocumentReadRepository.StepRow> steps = readRepository.findSteps(documentId);
        DocumentCardResponse.RouteView route = route(document, steps);
        boolean author = document.getAuthorId().equals(currentUser.userId());
        boolean draft = document.getStatus() == Document.Status.DRAFT;
        // Правило «нельзя отправить» общее с самой отправкой (DocumentCheckRules): кнопка и сервер не расходятся.
        boolean submissionBlocked = DocumentCheckRules.blockReason(
                type.isGeneric(), current.getCheckStatus(), current.getValidationIssues()).isPresent();
        boolean editable = draft || document.getStatus() == Document.Status.RETURNED;
        boolean mainIsDocx = files.getOrDefault(current.getId(), List.of()).stream()
                .anyMatch(file -> file.getKind() == DocumentFile.Kind.MAIN && DocxCorrector.DOCX.equals(file.getMimeType()));
        DocumentCardResponse.Permissions permissions = new DocumentCardResponse.Permissions(
                author && draft && !type.isGeneric(),
                author && editable,
                author && draft && !submissionBlocked,
                // добавить согласующего можно до отправки: он выбирается в предпросмотре маршрута
                author && draft,
                author && editable && !type.isGeneric() && mainIsDocx,
                author && document.getStatus() == Document.Status.IN_APPROVAL);
        List<Long> activeStepIds = steps.stream()
                .filter(step -> step.versionNo() == document.getCurrentVersionNo())
                .filter(step -> step.approverId() == currentUser.userId())
                .filter(step -> "PENDING".equals(step.decision()))
                .filter(step -> step.activatedAt() != null)
                .filter(step -> document.getStatus() == Document.Status.IN_APPROVAL)
                .filter(step -> document.getCurrentStage() != null
                        && step.stageOrder() == document.getCurrentStage())
                .map(DocumentReadRepository.StepRow::id)
                .toList();

        return new DocumentCardResponse(
                document.getId(),
                document.getTitle(),
                new DocumentCardResponse.TypeRef(
                        type.getId(), type.getCode(), type.getName(), type.isGeneric(), document.isTypeAutoDetected(),
                        aiType == null ? null : aiType.getId(), aiType == null ? null : aiType.getName()),
                document.getStatus(),
                readRepository.displayStatus(documentId),
                document.getVisibility(),
                user(users, document.getAuthorId()),
                document.getCurrentVersionNo(),
                versionViews,
                check,
                route,
                permissions,
                activeStepIds,
                versionChanges.changes(documentId, document.getCurrentVersionNo()).orElse(null));
    }

    private List<DocumentCardResponse.FileRef> fileViews(
            List<DocumentFile> files,
            long userId
    ) {
        return files.stream()
                .sorted(Comparator.comparing(DocumentFile::getPosition))
                .map(file -> {
                    DownloadTokenService.DownloadLink link = tokenService.issue(file.getId(), userId);
                    return new DocumentCardResponse.FileRef(
                            file.getId(),
                            file.getKind(),
                            file.getFileName(),
                            file.getMimeType(),
                            file.getFileSize(),
                            link.url(),
                            link.expiresAt());
                })
                .toList();
    }

    private DocumentCardResponse.CheckResult check(DocumentVersion version) {
        JsonNode stored = version.getExtractedFields();
        List<DocumentCardResponse.FieldValue> fields = stored == null
                ? List.of()
                : objectMapper.convertValue(stored.path("fields"), FIELD_LIST);
        List<DocumentCardResponse.ValidationIssueView> issues = version.getValidationIssues() == null
                ? List.of()
                : objectMapper.convertValue(version.getValidationIssues(), ISSUE_LIST);
        boolean modelAvailable = stored != null && stored.path("modelAvailable").asBoolean(false);
        boolean textMissing = stored != null && stored.path("textMissing").asBoolean(false);
        return new DocumentCardResponse.CheckResult(
                version.getVersionNo(),
                version.getCheckStatus(),
                modelAvailable,
                version.getAiSummary(),
                fields,
                issues,
                textMissing);
    }

    private DocumentCardResponse.RouteView route(
            Document document,
            List<DocumentReadRepository.StepRow> rows
    ) {
        if (rows.isEmpty()) {
            return null;
        }
        List<DocumentReadRepository.StepRow> current = rows.stream()
                .filter(row -> row.versionNo() == document.getCurrentVersionNo())
                .toList();
        Map<Integer, List<DocumentReadRepository.StepRow>> byStage = current.stream()
                .collect(Collectors.groupingBy(
                        DocumentReadRepository.StepRow::stageOrder,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<DocumentCardResponse.StageView> stages = byStage.entrySet().stream()
                .map(entry -> new DocumentCardResponse.StageView(
                        entry.getKey(),
                        stageState(document, entry.getKey(), entry.getValue()),
                        entry.getValue().stream().map(this::step).toList()))
                .toList();
        List<DocumentCardResponse.StepView> history = rows.stream()
                .filter(row -> row.versionNo() < document.getCurrentVersionNo())
                .map(this::step)
                .toList();
        return new DocumentCardResponse.RouteView(document.getCurrentVersionNo(), stages, history);
    }

    private String stageState(
            Document document,
            int stage,
            Collection<DocumentReadRepository.StepRow> steps
    ) {
        if (steps.stream().noneMatch(step -> "PENDING".equals(step.decision()))) {
            return "DONE";
        }
        if (document.getStatus() == Document.Status.IN_APPROVAL
                && document.getCurrentStage() != null
                && document.getCurrentStage() == stage) {
            return "ACTIVE";
        }
        return "WAITING";
    }

    private DocumentCardResponse.StepView step(DocumentReadRepository.StepRow row) {
        return new DocumentCardResponse.StepView(
                row.id(),
                row.versionNo(),
                row.stageOrder(),
                new RoleRef(row.roleId(), row.roleCode(), row.roleName()),
                new UserRef(row.approverId(), row.approverName()),
                row.origin(),
                row.decision(),
                row.kind(),
                row.comment(),
                row.activatedAt(),
                row.decidedAt(),
                row.autoReason());
    }

    private UserRef user(Map<Long, OrganizationSecurityService.UserInfo> users, long userId) {
        OrganizationSecurityService.UserInfo user = users.get(userId);
        if (user == null) {
            throw new IllegalStateException("Пользователь документа не найден");
        }
        return new UserRef(user.id(), user.displayName());
    }

    private long requireOrganization(CurrentUser user) {
        if (user.orgId() == null || user.memberId() == null) {
            throw DocumentApiException.forbidden("Для работы с документами нужно состоять в компании");
        }
        return user.orgId();
    }
}
