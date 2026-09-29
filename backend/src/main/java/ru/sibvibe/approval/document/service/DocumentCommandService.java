package ru.sibvibe.approval.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.dto.CreateDocumentRequest;
import ru.sibvibe.approval.document.dto.CreateFormDocumentRequest;
import ru.sibvibe.approval.document.dto.CreateFormVersionRequest;
import ru.sibvibe.approval.document.dto.CreateVersionRequest;
import ru.sibvibe.approval.document.dto.DocumentCardResponse;
import ru.sibvibe.approval.document.dto.FileCorrectionResponse;
import ru.sibvibe.approval.document.dto.UpdateFieldsRequest;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.repository.DocumentReadRepository;
import ru.sibvibe.approval.document.repository.DocumentRepository;
import ru.sibvibe.approval.storage.FileStorage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class DocumentCommandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentCommandService.class);
    private static final int MAX_TITLE_LENGTH = 255;

    private final DocumentRepository documentRepository;
    private final DocumentReadRepository documentReadRepository;
    private final DocumentFileValidator fileValidator;
    private final DocumentRuleService ruleService;
    private final DocumentAnalysisService analysisService;
    private final DocumentPersistenceService persistenceService;
    private final DocumentViewService viewService;
    private final FileStorage fileStorage;
    private final DocumentTypeGuessService typeGuessService;
    private final DocxCorrector docxCorrector;

    public DocumentCommandService(
            DocumentRepository documentRepository,
            DocumentReadRepository documentReadRepository,
            DocumentFileValidator fileValidator,
            DocumentRuleService ruleService,
            DocumentAnalysisService analysisService,
            DocumentPersistenceService persistenceService,
            DocumentViewService viewService,
            FileStorage fileStorage,
            DocumentTypeGuessService typeGuessService,
            DocxCorrector docxCorrector
    ) {
        this.typeGuessService = typeGuessService;
        this.docxCorrector = docxCorrector;
        this.documentRepository = documentRepository;
        this.documentReadRepository = documentReadRepository;
        this.fileValidator = fileValidator;
        this.ruleService = ruleService;
        this.analysisService = analysisService;
        this.persistenceService = persistenceService;
        this.viewService = viewService;
        this.fileStorage = fileStorage;
    }

    public DocumentCardResponse create(
            CurrentUser currentUser,
            CreateDocumentRequest request,
            MultipartFile main,
            List<MultipartFile> attachments
    ) {
        Membership membership = requireMembership(currentUser);
        DocumentFileValidator.ValidatedFile validated = fileValidator.validateMain(main);
        List<DocumentFileValidator.ValidatedFile> validatedAttachments = validateAttachments(attachments);
        TypeChoice choice = chooseType(request, validated);
        DocumentRuleService.PreparedCheck prepared =
                ruleService.prepare(membership.orgId(), choice.typeId(), request.fields());
        DocumentPersistenceService.NewFile stored = upload(validated);
        List<DocumentPersistenceService.NewFile> storedAttachments = uploadAttachments(validatedAttachments, stored);
        DocumentPersistenceService.CreatedVersion created;
        try {
            created = persistenceService.create(
                    membership.orgId(),
                    currentUser.userId(),
                    choice.title(),
                    choice.titleAuto(),
                    request.visibility(),
                    request.containsSensitive(),
                    choice.autoDetected(),
                    prepared,
                    stored,
                    storedAttachments);
        } catch (RuntimeException exception) {
            compensate(stored, storedAttachments, exception);
            throw exception;
        }
        persistenceService.recordAiType(created.documentId(), choice.aiTypeId());
        runCheck(created.documentId(), created.checkAttempt(), request.containsSensitive(), prepared);
        return viewService.card(created.documentId(), currentUser);
    }

    /**
     * Документ-форма: содержимое заполнено в приложении, файла нет. Поля уже структурированы —
     * модель не нужна, правила проверяют их так же, как извлечённые из файла.
     */
    public DocumentCardResponse createForm(CurrentUser currentUser, CreateFormDocumentRequest request) {
        Membership membership = requireMembership(currentUser);
        DocumentRuleService.PreparedCheck prepared =
                prepareForm(membership.orgId(), request.documentTypeId(), request.content());
        DocumentPersistenceService.CreatedVersion created = persistenceService.createForm(
                membership.orgId(),
                currentUser.userId(),
                formTitle(request.title(), prepared),
                request.title() == null || request.title().isBlank(),
                request.visibility(),
                request.containsSensitive(),
                prepared);
        checkWithoutModel(created.documentId(), created.checkAttempt(), prepared, () -> ruleService.execute(prepared));
        return viewService.card(created.documentId(), currentUser);
    }

    /** Новая версия документа-формы: после возврата правится форма, а не файл. */
    public DocumentCardResponse createFormVersion(
            long documentId,
            CurrentUser currentUser,
            CreateFormVersionRequest request
    ) {
        Membership membership = requireMembership(currentUser);
        Document document = requireOwnedDocument(documentId, membership.orgId(), currentUser.userId());
        DocumentRuleService.PreparedCheck prepared =
                prepareForm(membership.orgId(), document.getDocumentTypeId(), request.content());
        DocumentPersistenceService.CreatedVersion created = persistenceService.createFormVersion(
                documentId,
                membership.orgId(),
                currentUser.userId(),
                document.getCurrentVersionNo(),
                request.containsSensitive(),
                prepared);
        checkWithoutModel(documentId, created.checkAttempt(), prepared, () -> ruleService.execute(prepared));
        return viewService.card(documentId, currentUser);
    }

    /**
     * Форма проверяется по правилам своей компании. «Другой документ» формой не бывает: его поля —
     * общие реквизиты для чужого файла, а не содержимое документа; старый тип без схемы — тем более.
     */
    private DocumentRuleService.PreparedCheck prepareForm(long orgId, long documentTypeId, Map<String, String> content) {
        DocumentRuleService.PreparedCheck prepared = ruleService.prepare(orgId, documentTypeId, content);
        if (prepared.type().isGeneric() || DocumentTypeGuessService.OTHER_DOCUMENT.equals(prepared.type().getCode())) {
            throw DocumentApiException.validation("Заполнить в приложении можно только документ с полями — «Другой документ» загрузите файлом");
        }
        return prepared;
    }

    /** Название формы: указанное автором, иначе заголовок записки, иначе название типа. */
    static String formTitle(String requested, DocumentRuleService.PreparedCheck prepared) {
        String title = requested == null ? "" : requested.strip();
        if (!title.isEmpty()) {
            return truncate(title);
        }
        String subject = prepared.suppliedFields().get("subject");
        return subject != null && !subject.isBlank() ? truncate(subject.strip()) : prepared.type().getName();
    }

    public DocumentCardResponse createVersion(
            long documentId,
            CurrentUser currentUser,
            CreateVersionRequest request,
            MultipartFile main,
            List<MultipartFile> attachments
    ) {
        Membership membership = requireMembership(currentUser);
        Document document = requireOwnedDocument(documentId, membership.orgId(), currentUser.userId());
        DocumentRuleService.PreparedCheck prepared =
                ruleService.prepare(membership.orgId(), document.getDocumentTypeId(), request.fields());
        DocumentFileValidator.ValidatedFile validatedMain = main == null || main.isEmpty()
                ? null
                : fileValidator.validateMain(main);
        List<DocumentFileValidator.ValidatedFile> validatedAttachments = validateAttachments(attachments);
        DocumentPersistenceService.NewFile stored = validatedMain == null ? null : upload(validatedMain);
        List<DocumentPersistenceService.NewFile> storedAttachments = uploadAttachments(validatedAttachments, stored);
        DocumentPersistenceService.CreatedVersion created;
        try {
            created = persistenceService.createVersion(
                    documentId,
                    membership.orgId(),
                    currentUser.userId(),
                    document.getCurrentVersionNo(),
                    request.containsSensitive(),
                    request.keepFileIds(),
                    prepared,
                    stored,
                    storedAttachments);
        } catch (RuntimeException exception) {
            compensate(stored, storedAttachments, exception);
            throw exception;
        }
        if (created.previousFields() != null) {
            runManualCheck(documentId, new DocumentPersistenceService.FieldsUpdateAttempt(
                    created.checkAttempt(), created.previousFields()), prepared);
        } else {
            runCheck(documentId, created.checkAttempt(), request.containsSensitive(), prepared);
        }
        return viewService.card(documentId, currentUser);
    }

    public DocumentCardResponse.CheckResult updateFields(
            long documentId,
            int versionNo,
            CurrentUser currentUser,
            UpdateFieldsRequest request
    ) {
        Membership membership = requireMembership(currentUser);
        Document document = requireOwnedDocument(documentId, membership.orgId(), currentUser.userId());
        DocumentRuleService.PreparedCheck prepared =
                ruleService.prepare(membership.orgId(), document.getDocumentTypeId(), request.fields());
        DocumentPersistenceService.FieldsUpdateAttempt update = persistenceService.beginFieldsUpdate(
                documentId,
                versionNo,
                membership.orgId(),
                currentUser.userId(),
                prepared);
        runManualCheck(documentId, update, prepared);
        return viewService.card(documentId, currentUser).check();
    }

    /**
     * «Проверить заново»: сохранённые поля — по текущим правилам компании. Модель не вызывается и файл
     * не перечитывается: изменились правила, а не документ. У возвращённого документа — новой версией-черновиком.
     */
    public DocumentCardResponse recheck(long documentId, int versionNo, CurrentUser currentUser) {
        Membership membership = requireMembership(currentUser);
        Document document = requireOwnedDocument(documentId, membership.orgId(), currentUser.userId());
        DocumentRuleService.PreparedCheck prepared =
                ruleService.prepare(membership.orgId(), document.getDocumentTypeId(), Map.of());
        if (prepared.type().isGeneric()) {
            throw DocumentApiException.validation("У этого документа нет проверяемых полей");
        }
        persistenceService.recheck(documentId, versionNo, membership.orgId(), currentUser.userId(),
                prepared.type().getId(), previous -> ruleService.executeManualUpdate(prepared, previous));
        return viewService.card(documentId, currentUser);
    }

    /** «Удалить черновик» — только автор и только черновик, который ещё не отправлялся. */
    public void deleteDraft(long documentId, CurrentUser currentUser) {
        Membership membership = requireMembership(currentUser);
        requireOwnedDocument(documentId, membership.orgId(), currentUser.userId());
        // «Отправлялся ли» — уже под блокировкой строки документа.
        persistenceService.deleteDraft(documentId, membership.orgId(), currentUser.userId(),
                () -> documentReadRepository.everSubmitted(documentId));
    }

    /** «Сменить тип» у черновика: вид, определённый моделью, оказался неверным. */
    public DocumentCardResponse changeType(long documentId, int versionNo, CurrentUser currentUser, long documentTypeId) {
        Membership membership = requireMembership(currentUser);
        requireOwnedDocument(documentId, membership.orgId(), currentUser.userId());
        DocumentRuleService.PreparedCheck prepared = ruleService.prepare(membership.orgId(), documentTypeId, null);
        DocumentPersistenceService.RetypeAttempt attempt = persistenceService.beginRetype(
                documentId, versionNo, membership.orgId(), currentUser.userId(), prepared);
        runCheck(documentId, attempt.checkAttempt(), attempt.containsSensitive(), prepared);
        return viewService.card(documentId, currentUser);
    }

    /**
     * «Исправить в файле»: исправленные значения полей вписываются в сам DOCX, и документ
     * получает новую версию, которая проверяется заново — как если бы автор поправил файл в Word
     * и загрузил его. Так согласующий получает исправленный файл, а не прежний файл плюс «исправленные поля».
     */
    public FileCorrectionResponse correctFile(
            long documentId,
            int versionNo,
            CurrentUser currentUser,
            UpdateFieldsRequest request
    ) {
        Membership membership = requireMembership(currentUser);
        requireOwnedDocument(documentId, membership.orgId(), currentUser.userId());
        DocumentPersistenceService.CorrectionSource source = persistenceService.correctionSource(
                documentId, versionNo, membership.orgId(), currentUser.userId());
        if (!DocxCorrector.DOCX.equals(source.main().getMimeType())) {
            throw DocumentApiException.validation("Вписать исправления можно только в документ Word (DOCX). "
                    + "PDF исправьте в исходном документе и загрузите его новой версией");
        }
        // Имена полей — только из схемы типа (та же проверка, что у ручной правки).
        ruleService.prepare(membership.orgId(), source.documentTypeId(), request.fields());

        Map<String, DocumentCardResponse.FieldValue> current = new LinkedHashMap<>();
        source.fields().forEach(field -> current.putIfAbsent(field.name(), field));
        Map<String, String> corrected = new LinkedHashMap<>();
        current.forEach((name, field) -> corrected.put(name, field.value()));
        List<DocxCorrector.Replacement> replacements = new ArrayList<>();
        request.fields().forEach((name, value) -> {
            DocumentCardResponse.FieldValue old = current.get(name);
            String inFile = old == null ? null : old.fileValue() != null ? old.fileValue() : old.value();
            String newValue = value == null ? "" : value.strip();
            if (!Objects.equals(inFile == null ? "" : inFile.strip(), newValue)) {
                replacements.add(new DocxCorrector.Replacement(name, inFile, newValue, old == null ? null : old.quote()));
                corrected.put(name, newValue);
            }
        });
        if (replacements.isEmpty()) {
            throw DocumentApiException.validation("Вы не изменили ни одного поля");
        }

        DocxCorrector.Result result = docxCorrector.apply(read(source.main().getStorageKey()), replacements);
        List<FileCorrectionResponse.NotApplied> notApplied = result.failed().entrySet().stream()
                .map(entry -> new FileCorrectionResponse.NotApplied(entry.getKey(), entry.getValue().message()))
                .toList();
        if (result.applied().isEmpty()) {
            throw DocumentApiException.validation("Не удалось вписать исправления в файл: " + notApplied.stream()
                    .map(FileCorrectionResponse.NotApplied::message)
                    .distinct()
                    .collect(Collectors.joining("; ")));
        }

        DocumentPersistenceService.NewFile stored = upload(new DocumentFileValidator.ValidatedFile(
                result.content(), source.main().getFileName(), DocxCorrector.DOCX));
        // Без чувствительных данных поля заново извлекает модель из нового файла — проверяется файл,
        // а не наши представления о нём. Но поля, которые автор раньше заменил вручную, не меняя файл,
        // и которые сейчас в файл не вписывались, модель прочитала бы по-старому — их переносим как есть,
        // вместе с тем, что в файле (иначе правка молча откатывалась). С чувствительными данными модель
        // не участвует: берём исправленные значения.
        DocumentRuleService.PreparedCheck prepared;
        if (source.containsSensitive()) {
            prepared = ruleService.prepare(membership.orgId(), source.documentTypeId(), corrected);
        } else {
            Map<String, String> carried = new LinkedHashMap<>();
            Map<String, String> carriedFileValues = new LinkedHashMap<>();
            current.forEach((name, field) -> {
                if (!result.applied().contains(name) && "MANUAL".equals(field.source()) && field.fileValue() != null) {
                    carried.put(name, field.value());
                    carriedFileValues.put(name, field.fileValue());
                }
            });
            prepared = ruleService.prepare(membership.orgId(), source.documentTypeId(), carried).withFileValues(carriedFileValues);
        }
        DocumentPersistenceService.CreatedVersion created;
        try {
            created = persistenceService.createVersion(
                    documentId,
                    membership.orgId(),
                    currentUser.userId(),
                    versionNo,
                    source.containsSensitive(),
                    source.attachmentIds(),
                    prepared,
                    stored,
                    List.of());
        } catch (RuntimeException exception) {
            compensate(stored.storageKey(), exception);
            throw exception;
        }
        runCheck(documentId, created.checkAttempt(), source.containsSensitive(), prepared);
        return new FileCorrectionResponse(viewService.card(documentId, currentUser), result.applied(), notApplied);
    }

    private byte[] read(String storageKey) {
        FileStorage.StoredFile stored = fileStorage.get(storageKey);
        try (InputStream content = stored.content()) {
            return content.readNBytes((int) DocumentFileValidator.MAX_FILE_SIZE + 1);
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать основной файл документа", exception);
        }
    }

    /**
     * Тип и название нового документа. Тип не указан — его выбирает модель по тексту; с
     * чувствительными данными текст в модель не уходит, поэтому тип тогда обязателен. Название
     * не указано — по содержанию документа, а без модели — из имени файла.
     */
    private TypeChoice chooseType(CreateDocumentRequest request, DocumentFileValidator.ValidatedFile file) {
        String title = request.title() == null ? "" : request.title().strip();
        // Название не ввёл автор — оно из документа или имени файла и дальше следует за заголовком документа.
        boolean titleAuto = title.isEmpty();
        if (request.documentTypeId() != null) {
            // Вид выбрал автор, но ИИ всё равно определяет свой (если текст можно отправить в модель): расхождение увидят
            // автор и согласующие — так в обход обязательных не уйти незаметно, выбрав «Другой документ».
            DocumentTypeGuessService.Guess guessed = request.containsSensitive()
                    ? null
                    : typeGuessService.guess(file.content(), file.mimeType());
            if (title.isEmpty()) {
                title = titleOrFileName(guessed == null ? null : guessed.title(), file.fileName());
            }
            return new TypeChoice(request.documentTypeId(), title, false, titleAuto,
                    guessed == null ? null : guessed.type().getId());
        }
        if (request.containsSensitive()) {
            throw DocumentApiException.validation("Выберите тип документа: с чувствительными данными документ "
                    + "не отправляется в ИИ, поэтому определить тип автоматически нельзя");
        }
        DocumentTypeGuessService.Guess guess = typeGuessService.guess(file.content(), file.mimeType());
        return new TypeChoice(guess.type().getId(),
                title.isEmpty() ? titleOrFileName(guess.title(), file.fileName()) : title, true, titleAuto, guess.type().getId());
    }

    static String titleOrFileName(String guessed, String fileName) {
        if (guessed != null && !guessed.isBlank()) {
            return truncate(guessed.strip());
        }
        String name = fileName == null ? "" : fileName.strip();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replace('_', ' ').strip();
        return name.isEmpty() ? "Документ" : truncate(name);
    }

    static String truncateTitle(String value) {
        return truncate(value);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH).strip();
    }

    private record TypeChoice(long typeId, String title, boolean autoDetected, boolean titleAuto, Long aiTypeId) {}

    private DocumentPersistenceService.NewFile upload(DocumentFileValidator.ValidatedFile file) {
        String key = fileStorage.put(
                new ByteArrayInputStream(file.content()),
                file.fileName(),
                file.mimeType(),
                file.size());
        return new DocumentPersistenceService.NewFile(
                key, file.fileName(), file.mimeType(), file.size());
    }

    /**
     * Приложения проверяются до загрузки в хранилище: не подошло одно — не загружается ни одно. Число — здесь же,
     * чтобы не читать лишние файлы; число вместе с оставленными приложениями и 30 МБ на версию — при сохранении.
     */
    private List<DocumentFileValidator.ValidatedFile> validateAttachments(List<MultipartFile> attachments) {
        if (attachments == null) {
            return List.of();
        }
        // Часть без имени и без содержимого — «файл не выбран» в форме; пустой файл с именем — ошибка валидатора.
        List<MultipartFile> present = attachments.stream()
                .filter(file -> file != null && !(file.isEmpty() && isBlank(file.getOriginalFilename())))
                .toList();
        if (present.size() > DocumentFileValidator.MAX_ATTACHMENTS) {
            throw DocumentApiException.validation(DocumentPersistenceService.TOO_MANY_ATTACHMENTS);
        }
        return present.stream().map(fileValidator::validateAttachment).toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Не загрузилось очередное приложение — уже загруженные файлы этого запроса удаляются. */
    private List<DocumentPersistenceService.NewFile> uploadAttachments(
            List<DocumentFileValidator.ValidatedFile> attachments,
            DocumentPersistenceService.NewFile main
    ) {
        List<DocumentPersistenceService.NewFile> stored = new ArrayList<>();
        try {
            for (DocumentFileValidator.ValidatedFile attachment : attachments) {
                stored.add(upload(attachment));
            }
        } catch (RuntimeException exception) {
            compensate(main, stored, exception);
            throw exception;
        }
        return stored;
    }

    private void runCheck(
            long documentId,
            DocumentPersistenceService.CheckAttempt attempt,
            boolean containsSensitive,
            DocumentRuleService.PreparedCheck prepared
    ) {
        if (prepared.type().isGeneric()) {
            return;
        }
        DocumentRuleService.CheckData pending = ruleService.pending(prepared);
        try {
            DocumentAnalysisService.AnalysisResult analysis =
                    analysisService.analyze(attempt.versionId(), containsSensitive, prepared);
            persistenceService.completeCheck(documentId, attempt, analysis.check(), analysis.summary());
        } catch (RuntimeException exception) {
            LOGGER.error("Не удалось проверить правила для версии {}", attempt.versionId(), exception);
            persistenceService.failCheck(documentId, attempt, pending, true);
        }
    }

    private void runManualCheck(
            long documentId,
            DocumentPersistenceService.FieldsUpdateAttempt update,
            DocumentRuleService.PreparedCheck prepared
    ) {
        checkWithoutModel(documentId, update.checkAttempt(), prepared,
                () -> ruleService.executeManualUpdate(prepared, update.previous()));
    }

    /** Проверка правилами по уже известным полям, без обращения к модели. */
    private void checkWithoutModel(
            long documentId,
            DocumentPersistenceService.CheckAttempt attempt,
            DocumentRuleService.PreparedCheck prepared,
            Supplier<DocumentRuleService.CheckData> rules
    ) {
        DocumentRuleService.CheckData pending = ruleService.pending(prepared);
        try {
            persistenceService.completeCheck(documentId, attempt, rules.get());
        } catch (RuntimeException exception) {
            LOGGER.error("Не удалось проверить правила для версии {}", attempt.versionId(), exception);
            persistenceService.failCheck(documentId, attempt, pending);
        }
    }

    private Document requireOwnedDocument(long documentId, long orgId, long userId) {
        Document document = documentRepository.findById(documentId).orElseThrow(NotFoundException::new);
        if (!document.getOrgId().equals(orgId)) {
            throw new NotFoundException();
        }
        if (!document.getAuthorId().equals(userId)) {
            if (!documentReadRepository.canRead(documentId, orgId, userId)) {
                throw new NotFoundException();
            }
            throw DocumentApiException.forbidden("Изменять документ может только автор");
        }
        return document;
    }

    private Membership requireMembership(CurrentUser user) {
        if (user.orgId() == null || user.memberId() == null) {
            throw DocumentApiException.forbidden("Для работы с документами нужно состоять в компании");
        }
        return new Membership(user.orgId());
    }

    private void compensate(
            DocumentPersistenceService.NewFile main,
            List<DocumentPersistenceService.NewFile> attachments,
            RuntimeException original
    ) {
        if (main != null) {
            compensate(main.storageKey(), original);
        }
        attachments.forEach(file -> compensate(file.storageKey(), original));
    }

    private void compensate(String storageKey, RuntimeException original) {
        try {
            fileStorage.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
            LOGGER.error("Не удалось удалить файл после ошибки сохранения метаданных", cleanupFailure);
        }
    }

    private record Membership(long orgId) {}
}
