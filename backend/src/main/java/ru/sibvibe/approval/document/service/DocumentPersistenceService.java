package ru.sibvibe.approval.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.document.dto.DocumentCardResponse;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.entity.DocumentFile;
import ru.sibvibe.approval.document.entity.DocumentVersion;
import ru.sibvibe.approval.document.repository.DocumentFileRepository;
import ru.sibvibe.approval.document.repository.DocumentRepository;
import ru.sibvibe.approval.document.repository.DocumentVersionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static ru.sibvibe.approval.document.service.DocumentFileValidator.MAX_ATTACHMENTS;
import static ru.sibvibe.approval.document.service.DocumentFileValidator.MAX_FILE_SIZE;
import static ru.sibvibe.approval.document.service.DocumentFileValidator.MAX_VERSION_SIZE;

@Service
public class DocumentPersistenceService {

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentFileRepository fileRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DocumentPersistenceService(
            DocumentRepository documentRepository,
            DocumentVersionRepository versionRepository,
            DocumentFileRepository fileRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.fileRepository = fileRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CreatedVersion create(
            long orgId,
            long authorId,
            String title,
            Document.Visibility visibility,
            boolean containsSensitive,
            boolean typeAutoDetected,
            DocumentRuleService.PreparedCheck prepared,
            NewFile main,
            List<NewFile> attachments
    ) {
        return create(orgId, authorId, title, false, visibility, containsSensitive, typeAutoDetected, prepared, main,
                attachments);
    }

    /** titleAuto — название не вводил автор: дальше оно следует за заголовком документа ({@link #followSubject}). */
    @Transactional
    public CreatedVersion create(
            long orgId,
            long authorId,
            String title,
            boolean titleAuto,
            Document.Visibility visibility,
            boolean containsSensitive,
            boolean typeAutoDetected,
            DocumentRuleService.PreparedCheck prepared,
            NewFile main,
            List<NewFile> attachments
    ) {
        validateNewDocumentFiles(main, attachments);
        CreatedVersion created = insert(
                orgId, authorId, title, visibility, containsSensitive, typeAutoDetected, prepared, main, attachments);
        markTitleAuto(created.documentId(), titleAuto);
        return created;
    }

    /** Документ-форма: версия хранит содержимое формы в content, файлов нет. */
    @Transactional
    public CreatedVersion createForm(
            long orgId,
            long authorId,
            String title,
            Document.Visibility visibility,
            boolean containsSensitive,
            DocumentRuleService.PreparedCheck prepared
    ) {
        return createForm(orgId, authorId, title, false, visibility, containsSensitive, prepared);
    }

    @Transactional
    public CreatedVersion createForm(
            long orgId,
            long authorId,
            String title,
            boolean titleAuto,
            Document.Visibility visibility,
            boolean containsSensitive,
            DocumentRuleService.PreparedCheck prepared
    ) {
        CreatedVersion created = insert(orgId, authorId, title, visibility, containsSensitive, false, prepared, null, List.of());
        markTitleAuto(created.documentId(), titleAuto);
        return created;
    }

    /** Вид, который определил ИИ: расхождение с выбранным автором видят автор и согласующие. */
    @Transactional
    public void recordAiType(long documentId, Long aiTypeId) {
        if (aiTypeId != null) {
            documentRepository.findById(documentId).ifPresent(document -> document.setAiTypeId(aiTypeId));
        }
    }

    private void markTitleAuto(long documentId, boolean titleAuto) {
        if (titleAuto) {
            documentRepository.findById(documentId).ifPresent(document -> document.setTitleAuto(true));
        }
    }

    /**
     * Название следует за заголовком документа: автор исправил «Заголовок» — в списках документ
     * называется уже по-новому. Только если название не вводил сам автор; пустой заголовок название не стирает.
     */
    static void followSubject(Document document, DocumentRuleService.CheckData result) {
        if (!document.isTitleAuto() || result == null) {
            return;
        }
        result.fields().stream()
                .filter(field -> "subject".equals(field.name()))
                .map(DocumentCardResponse.FieldValue::value)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> DocumentCommandService.truncateTitle(value.strip()))
                .findFirst()
                .ifPresent(document::setTitle);
    }

    /** main {@code null} — документ-форма: содержимое версии — поля, переданные автором. */
    private CreatedVersion insert(
            long orgId,
            long authorId,
            String title,
            Document.Visibility visibility,
            boolean containsSensitive,
            boolean typeAutoDetected,
            DocumentRuleService.PreparedCheck prepared,
            NewFile main,
            List<NewFile> attachments
    ) {
        Instant now = clock.instant();
        Document document = new Document();
        document.setDocumentTypeId(prepared.type().getId());
        document.setTypeAutoDetected(typeAutoDetected);
        document.setAuthorId(authorId);
        document.setOrgId(orgId);
        document.setTitle(title.trim());
        document.setStatus(Document.Status.DRAFT);
        document.setCurrentVersionNo(1);
        document.setVisibility(visibility);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        document = documentRepository.save(document);

        String checkToken = checkToken(prepared);
        DocumentVersion version = newVersion(
                document.getId(), 1, containsSensitive, authorId, now, prepared, checkToken);
        if (main == null) {
            version.setContent(objectMapper.valueToTree(prepared.suppliedFields()));
        }
        version = versionRepository.save(version);
        if (main != null) {
            List<DocumentFile> files = new ArrayList<>();
            files.add(newFile(version.getId(), DocumentFile.Kind.MAIN, 0, main, now));
            for (int index = 0; index < attachments.size(); index++) {
                files.add(newFile(version.getId(), DocumentFile.Kind.ATTACHMENT, index + 1, attachments.get(index), now));
            }
            fileRepository.saveAll(files);
        }
        return new CreatedVersion(document.getId(), version.getId(), 1, checkToken);
    }

    /**
     * Новая версия документа-формы: содержимое формы целиком, файлов нет. Можно только у формы:
     * документ с файлом исправляют новым файлом или правкой полей, а не заменой на форму.
     */
    @Transactional
    public CreatedVersion createFormVersion(
            long documentId,
            long orgId,
            long authorId,
            int expectedCurrentVersion,
            boolean containsSensitive,
            DocumentRuleService.PreparedCheck prepared
    ) {
        Document document = lockForNewVersion(documentId, orgId, authorId, expectedCurrentVersion, prepared);
        DocumentVersion current = versionRepository
                .findByDocumentIdAndVersionNo(documentId, document.getCurrentVersionNo())
                .orElseThrow();
        if (!isForm(current)) {
            throw DocumentApiException.validation("Документ загружен файлом — исправьте поля или замените файл");
        }
        int versionNo = document.getCurrentVersionNo() + 1;
        Instant now = clock.instant();
        String checkToken = checkToken(prepared);
        DocumentVersion version = newVersion(
                documentId, versionNo, containsSensitive, authorId, now, prepared, checkToken);
        version.setContent(objectMapper.valueToTree(prepared.suppliedFields()));
        version = versionRepository.save(version);
        becomeDraft(document, versionNo, now);
        return new CreatedVersion(documentId, version.getId(), versionNo, checkToken);
    }

    @Transactional
    public CreatedVersion createVersion(
            long documentId,
            long orgId,
            long authorId,
            int expectedCurrentVersion,
            boolean containsSensitive,
            List<Long> keepFileIds,
            DocumentRuleService.PreparedCheck prepared,
            NewFile newMain,
            List<NewFile> newAttachments
    ) {
        Document document = lockForNewVersion(documentId, orgId, authorId, expectedCurrentVersion, prepared);

        DocumentVersion current = versionRepository
                .findByDocumentIdAndVersionNo(documentId, document.getCurrentVersionNo())
                .orElseThrow();
        List<DocumentFile> currentFiles = fileRepository.findByVersionIdOrderByPosition(current.getId());
        Set<Long> requestedIds = new LinkedHashSet<>(keepFileIds);
        if (requestedIds.size() != keepFileIds.size()) {
            throw DocumentApiException.validation("Некорректный список сохраняемых файлов");
        }
        List<DocumentFile> kept = currentFiles.stream()
                .filter(file -> requestedIds.contains(file.getId()))
                .toList();
        if (kept.size() != requestedIds.size()) {
            throw DocumentApiException.validation("Некорректный список сохраняемых файлов");
        }
        long keptMain = kept.stream().filter(file -> file.getKind() == DocumentFile.Kind.MAIN).count();
        // У документов демо-песочницы файлов нет вовсе (DemoDocumentSeedService): новая версия без файла —
        // та же, что и у них, иначе возвращённый демо-документ нельзя было бы ни исправить, ни отправить заново.
        boolean fileless = currentFiles.isEmpty() && newMain == null && newAttachments.isEmpty();
        if (!fileless && ((newMain == null && keptMain != 1) || (newMain != null && keptMain != 0))) {
            throw DocumentApiException.validation("Для версии должен быть указан один основной файл");
        }
        validateVersionFiles(kept, newMain, newAttachments);

        int versionNo = document.getCurrentVersionNo() + 1;
        Instant now = clock.instant();
        String checkToken = checkToken(prepared);
        DocumentVersion draft = newVersion(documentId, versionNo, containsSensitive, authorId, now, prepared, checkToken);
        if (newMain == null && isForm(current)) {
            // Защита формы: если новая версия записки всё же пришла сюда, а не в createFormVersion,
            // содержимое переносится вместе с переданными полями — иначе форма стала бы «документом без файла».
            draft.setContent(mergedContent(current, prepared));
        }
        DocumentVersion version = versionRepository.save(draft);
        List<DocumentFile> copies = new ArrayList<>();
        for (DocumentFile source : kept) {
            NewFile reused = new NewFile(
                    source.getStorageKey(), source.getFileName(), source.getMimeType(), source.getFileSize());
            copies.add(newFile(version.getId(), source.getKind(), source.getPosition(), reused, now));
        }
        if (newMain != null) {
            copies.add(newFile(version.getId(), DocumentFile.Kind.MAIN, 0, newMain, now));
        }
        // Новые приложения — после оставленных: позиции оставленных сохраняются, порядок в карточке не прыгает.
        int nextPosition = kept.stream().mapToInt(DocumentFile::getPosition).max().orElse(0) + 1;
        for (NewFile attachment : newAttachments) {
            copies.add(newFile(version.getId(), DocumentFile.Kind.ATTACHMENT, nextPosition++, attachment, now));
        }
        fileRepository.saveAll(copies);

        // Основной файл тот же — поля прежней версии, в том числе ручные правки, переносятся, а правила
        // перепроверяют их без модели: файл не изменился, перечитывать нечего, а перечитывание стёрло бы
        // правки автора. Сменился признак чувствительности или прежняя проверка
        // не выполнилась — обычная проверка.
        DocumentRuleService.CheckData previousFields = newMain == null
                && !containsSensitive
                && !current.isContainsSensitive()
                && ("CHECKED".equals(current.getCheckStatus()) || fileless)
                ? readCheckData(current)
                : null;
        if (previousFields != null) {
            // Основной файл не менялся: проверенная сводка относится к тому же тексту.
            version.setAiSummary(current.getAiSummary());
        }

        becomeDraft(document, versionNo, now);
        return new CreatedVersion(documentId, version.getId(), versionNo, checkToken, previousFields);
    }

    /** Документ под блокировкой, в котором автор может создать новую версию. */
    private Document lockForNewVersion(
            long documentId,
            long orgId,
            long authorId,
            int expectedCurrentVersion,
            DocumentRuleService.PreparedCheck prepared
    ) {
        Document document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(NotFoundException::new);
        requireAuthor(document, orgId, authorId);
        if (document.getCurrentVersionNo() != expectedCurrentVersion) {
            throw DocumentApiException.invalidState("Документ уже был изменён");
        }
        if (document.getStatus() != Document.Status.DRAFT
                && document.getStatus() != Document.Status.RETURNED) {
            throw DocumentApiException.invalidState("В текущем статусе нельзя создать новую версию");
        }
        if (!document.getDocumentTypeId().equals(prepared.type().getId())) {
            throw DocumentApiException.validation("Тип документа не совпадает");
        }
        return document;
    }

    /**
     * Удалить черновик, который ни разу не отправлялся (у отправленного есть история — её не стираем). Строки
     * версий и файлов удаляются, сами файлы в хранилище остаются: они неизменяемы и в MVP не удаляются (docs/DESIGN-DECISIONS.md).
     */
    @Transactional
    public void deleteDraft(long documentId, long orgId, long authorId, java.util.function.BooleanSupplier everSubmitted) {
        Document document = documentRepository.findByIdForUpdate(documentId).orElseThrow(NotFoundException::new);
        requireAuthor(document, orgId, authorId);
        if (document.getStatus() != Document.Status.DRAFT || everSubmitted.getAsBoolean()) {
            throw DocumentApiException.invalidState("Удалить можно только черновик, который ещё не отправлялся");
        }
        List<DocumentVersion> versions = versionRepository.findByDocumentIdOrderByVersionNoAsc(documentId);
        fileRepository.deleteAll(fileRepository.findByVersionIdInOrderByVersionIdAscPositionAsc(
                versions.stream().map(DocumentVersion::getId).toList()));
        fileRepository.flush();
        versionRepository.deleteAll(versions);
        versionRepository.flush();
        documentRepository.delete(document);
    }

    /** Версия документа-формы: содержимое заполнено в приложении, а не загружено файлом. */
    static boolean isForm(DocumentVersion version) {
        return version.getContent() != null && version.getContent().isObject();
    }

    /** Форма версии с поверх вписанными полями из запроса. */
    private static ObjectNode mergedContent(DocumentVersion version, DocumentRuleService.PreparedCheck prepared) {
        ObjectNode content = version.getContent().deepCopy();
        prepared.suppliedFields().forEach((name, value) -> content.put(name, value == null ? "" : value));
        return content;
    }

    private static void becomeDraft(Document document, int versionNo, Instant now) {
        document.setCurrentVersionNo(versionNo);
        document.setCurrentStage(null);
        document.setStatus(Document.Status.DRAFT);
        document.setUpdatedAt(now);
    }

    @Transactional
    public FieldsUpdateAttempt beginFieldsUpdate(
            long documentId,
            int versionNo,
            long orgId,
            long authorId,
            DocumentRuleService.PreparedCheck prepared
    ) {
        Document document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(NotFoundException::new);
        requireAuthor(document, orgId, authorId);
        if (document.getStatus() != Document.Status.DRAFT
                || document.getCurrentVersionNo() != versionNo) {
            throw DocumentApiException.invalidState("Поля можно менять только у текущего черновика");
        }
        if (!document.getDocumentTypeId().equals(prepared.type().getId()) || prepared.type().isGeneric()) {
            throw DocumentApiException.validation("У этого документа нет проверяемых полей");
        }
        DocumentVersion version = versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(NotFoundException::new);
        DocumentRuleService.CheckData previous = readCheckData(version);
        if (isForm(version)) {
            // У документа-формы поля и есть содержимое: правка поля — правка самой формы.
            version.setContent(mergedContent(version, prepared));
        }
        String checkToken = UUID.randomUUID().toString();
        writePending(version, prepared, checkToken);
        document.setUpdatedAt(clock.instant());
        return new FieldsUpdateAttempt(new CheckAttempt(version.getId(), checkToken), previous);
    }

    /**
     * «Проверить заново»: сохранённые поля проверяются по текущим правилам компании, без модели.
     *
     * Всё выполняется в одной транзакции под блокировкой документа, без промежуточного состояния «ожидание»: иначе
     * параллельный запрос прочитал бы пустые поля как прежние, а ошибка правил оставила бы версию {@code FAILED}
     * без полей. Внешних вызовов нет, поэтому блокировку можно держать на время проверки.
     *
     * У {@code RETURNED} возвращённая версия остаётся в истории; перепроверяется новая версия-черновик с теми же
     * файлами, полями и содержимым формы.
     *
     * @return номер проверенной версии
     */
    @Transactional
    public int recheck(
            long documentId,
            int versionNo,
            long orgId,
            long authorId,
            long documentTypeId,
            java.util.function.UnaryOperator<DocumentRuleService.CheckData> rules
    ) {
        Document document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(NotFoundException::new);
        requireAuthor(document, orgId, authorId);
        if (document.getCurrentVersionNo() != versionNo) {
            throw DocumentApiException.invalidState("Документ уже был изменён — обновите экран");
        }
        if (document.getStatus() != Document.Status.DRAFT && document.getStatus() != Document.Status.RETURNED) {
            throw DocumentApiException.invalidState("Проверить заново можно черновик или возвращённый документ");
        }
        if (!document.getDocumentTypeId().equals(documentTypeId)) {
            throw DocumentApiException.invalidState("Документ уже был изменён — обновите экран");
        }
        DocumentVersion version = versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(NotFoundException::new);
        JsonNode stored = version.getExtractedFields();
        if (stored != null && stored.path("checkToken").isTextual()) {
            throw DocumentApiException.invalidState("Проверка ещё идёт — подождите несколько секунд");
        }
        DocumentRuleService.CheckData result = rules.apply(readCheckData(version));
        if (result == null) {
            throw DocumentApiException.validation("У этого документа нет проверяемых полей");
        }
        DocumentVersion target = version;
        Instant now = clock.instant();
        if (document.getStatus() == Document.Status.RETURNED) {
            target = new DocumentVersion();
            target.setDocumentId(documentId);
            target.setVersionNo(versionNo + 1);
            target.setContainsSensitive(version.isContainsSensitive());
            target.setContent(version.getContent() == null ? null : version.getContent().deepCopy());
            // Тот же файл — та же проверенная сводка: модель здесь не вызывается.
            target.setAiSummary(version.getAiSummary());
            target.setCreatedBy(authorId);
            target.setCreatedAt(now);
            target = versionRepository.save(target);
            List<DocumentFile> copies = new ArrayList<>();
            for (DocumentFile source : fileRepository.findByVersionIdOrderByPosition(version.getId())) {
                NewFile reused = new NewFile(
                        source.getStorageKey(), source.getFileName(), source.getMimeType(), source.getFileSize());
                copies.add(newFile(target.getId(), source.getKind(), source.getPosition(), reused, now));
            }
            fileRepository.saveAll(copies);
            document.setCurrentVersionNo(versionNo + 1);
            document.setCurrentStage(null);
            document.setStatus(Document.Status.DRAFT);
        }
        target.setExtractedFields(fieldsJson(result, null));
        target.setValidationIssues(objectMapper.valueToTree(result.issues()));
        target.setCheckStatus("CHECKED");
        followSubject(document, result);
        document.setUpdatedAt(now);
        return target.getVersionNo();
    }

    /**
     * Смена типа черновика: модель могла определить вид неверно. Текущая версия
     * перепроверяется по схеме нового типа — новая попытка проверки со своим токеном.
     */
    @Transactional
    public RetypeAttempt beginRetype(
            long documentId,
            int versionNo,
            long orgId,
            long authorId,
            DocumentRuleService.PreparedCheck prepared
    ) {
        Document document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(NotFoundException::new);
        requireAuthor(document, orgId, authorId);
        if (document.getStatus() != Document.Status.DRAFT || document.getCurrentVersionNo() != versionNo) {
            throw DocumentApiException.invalidState("Тип можно сменить только у текущего черновика");
        }
        DocumentVersion version = versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(NotFoundException::new);
        if (isForm(version)) {
            // Тип выбирал сам автор, а поля формы — это поля её типа: у другого типа их схема другая.
            throw DocumentApiException.validation("У документа, заполненного в приложении, тип не меняется");
        }
        document.setDocumentTypeId(prepared.type().getId());
        document.setTypeAutoDetected(false);
        document.setUpdatedAt(clock.instant());
        String checkToken = UUID.randomUUID().toString();
        writePending(version, prepared, checkToken);
        return new RetypeAttempt(new CheckAttempt(version.getId(), checkToken), version.isContainsSensitive());
    }

    /**
     * Что нужно, чтобы вписать исправления в файл текущей версии: основной файл, приложения
     * (переходят в новую версию как есть) и значения полей, найденные в файле.
     */
    @Transactional(readOnly = true)
    public CorrectionSource correctionSource(long documentId, int versionNo, long orgId, long authorId) {
        Document document = documentRepository.findById(documentId).orElseThrow(NotFoundException::new);
        requireAuthor(document, orgId, authorId);
        if (document.getCurrentVersionNo() != versionNo) {
            throw DocumentApiException.invalidState("Документ уже был изменён — обновите экран");
        }
        if (document.getStatus() != Document.Status.DRAFT && document.getStatus() != Document.Status.RETURNED) {
            throw DocumentApiException.invalidState("Исправлять можно черновик или возвращённый документ");
        }
        DocumentVersion version = versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(NotFoundException::new);
        List<DocumentFile> files = fileRepository.findByVersionIdOrderByPosition(version.getId());
        DocumentFile main = files.stream()
                .filter(file -> file.getKind() == DocumentFile.Kind.MAIN)
                .findFirst()
                .orElseThrow(() -> DocumentApiException.validation("У документа нет основного файла"));
        List<Long> attachments = files.stream()
                .filter(file -> file.getKind() == DocumentFile.Kind.ATTACHMENT)
                .map(DocumentFile::getId)
                .toList();
        return new CorrectionSource(
                document.getDocumentTypeId(), version.isContainsSensitive(), main, attachments,
                readCheckData(version).fields());
    }

    @Transactional
    public boolean completeCheck(
            long documentId,
            CheckAttempt attempt,
            DocumentRuleService.CheckData result
    ) {
        return completeCheck(documentId, attempt, result, null, false);
    }

    /** Модельный анализ заменяет сводку; {@code null} явно удаляет прежнюю. */
    @Transactional
    public boolean completeCheck(
            long documentId,
            CheckAttempt attempt,
            DocumentRuleService.CheckData result,
            String summary
    ) {
        return completeCheck(documentId, attempt, result, summary, true);
    }

    private boolean completeCheck(
            long documentId,
            CheckAttempt attempt,
            DocumentRuleService.CheckData result,
            String summary,
            boolean replaceSummary
    ) {
        Document document = documentRepository.findByIdForUpdate(documentId).orElseThrow(NotFoundException::new);
        DocumentVersion version = currentAttempt(documentId, attempt);
        if (version == null) {
            return false;
        }
        followSubject(document, result);
        version.setExtractedFields(fieldsJson(result, null));
        version.setValidationIssues(objectMapper.valueToTree(result.issues()));
        version.setCheckStatus("CHECKED");
        if (replaceSummary) {
            version.setAiSummary(summary);
        }
        return true;
    }

    @Transactional
    public boolean failCheck(
            long documentId,
            CheckAttempt attempt,
            DocumentRuleService.CheckData pending
    ) {
        return failCheck(documentId, attempt, pending, false);
    }

    /** Ошибка модельного анализа удаляет сводку; ручная перепроверка сохраняет её. */
    @Transactional
    public boolean failCheck(
            long documentId,
            CheckAttempt attempt,
            DocumentRuleService.CheckData pending,
            boolean clearSummary
    ) {
        documentRepository.findByIdForUpdate(documentId).orElseThrow(NotFoundException::new);
        DocumentVersion version = currentAttempt(documentId, attempt);
        if (version == null) {
            return false;
        }
        version.setExtractedFields(fieldsJson(pending, null));
        version.setValidationIssues(objectMapper.createArrayNode());
        version.setCheckStatus("FAILED");
        if (clearSummary) {
            version.setAiSummary(null);
        }
        return true;
    }

    private DocumentVersion newVersion(
            long documentId,
            int versionNo,
            boolean containsSensitive,
            long authorId,
            Instant now,
            DocumentRuleService.PreparedCheck prepared,
            String checkToken
    ) {
        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(documentId);
        version.setVersionNo(versionNo);
        version.setContainsSensitive(containsSensitive);
        version.setCreatedBy(authorId);
        version.setCreatedAt(now);
        if (!prepared.type().isGeneric()) {
            writePending(version, prepared, checkToken);
        }
        return version;
    }

    private void writePending(
            DocumentVersion version,
            DocumentRuleService.PreparedCheck prepared,
            String checkToken
    ) {
        DocumentRuleService.CheckData pending = new DocumentRuleService.CheckData(
                false,
                prepared.schema().stream()
                        .map(field -> new DocumentCardResponse.FieldValue(
                                field.getFieldName(),
                                prepared.suppliedFields().get(field.getFieldName()),
                                "MANUAL",
                                null,
                                null))
                        .toList(),
                List.of());
        version.setExtractedFields(fieldsJson(pending, checkToken));
        version.setValidationIssues(objectMapper.createArrayNode());
        version.setCheckStatus("FAILED");
    }

    private JsonNode fieldsJson(DocumentRuleService.CheckData data, String checkToken) {
        return objectMapper.valueToTree(
                new StoredFields(data.modelAvailable(), data.fields(), checkToken, data.textMissing()));
    }

    private DocumentRuleService.CheckData readCheckData(DocumentVersion version) {
        if (version.getExtractedFields() == null || version.getExtractedFields().isNull()) {
            return new DocumentRuleService.CheckData(false, List.of(), List.of());
        }
        StoredFields stored = objectMapper.convertValue(version.getExtractedFields(), StoredFields.class);
        return new DocumentRuleService.CheckData(
                stored.modelAvailable(),
                stored.fields() == null ? List.of() : List.copyOf(stored.fields()),
                List.of(),
                stored.textMissing());
    }

    private DocumentVersion currentAttempt(long documentId, CheckAttempt attempt) {
        DocumentVersion version = versionRepository.findById(attempt.versionId())
                .orElseThrow(NotFoundException::new);
        String storedToken = version.getExtractedFields() == null
                ? null
                : version.getExtractedFields().path("checkToken").textValue();
        if (!version.getDocumentId().equals(documentId) || !attempt.checkToken().equals(storedToken)) {
            return null;
        }
        return version;
    }

    private String checkToken(DocumentRuleService.PreparedCheck prepared) {
        return prepared.type().isGeneric() ? null : UUID.randomUUID().toString();
    }

    private DocumentFile newFile(
            long versionId,
            DocumentFile.Kind kind,
            int position,
            NewFile source,
            Instant now
    ) {
        DocumentFile file = new DocumentFile();
        file.setVersionId(versionId);
        file.setKind(kind);
        file.setPosition(position);
        file.setStorageKey(source.storageKey());
        file.setFileName(source.fileName());
        file.setMimeType(source.mimeType());
        file.setFileSize(source.size());
        file.setCreatedAt(now);
        return file;
    }

    private void requireAuthor(Document document, long orgId, long authorId) {
        if (!document.getOrgId().equals(orgId) || !document.getAuthorId().equals(authorId)) {
            throw new NotFoundException();
        }
    }

    static final String TOO_MANY_ATTACHMENTS = "В версии может быть не более 10 приложений";
    static final String VERSION_TOO_LARGE = "Размер файлов версии превышает 30 МБ";

    private void validateNewDocumentFiles(NewFile main, List<NewFile> attachments) {
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw DocumentApiException.validation(TOO_MANY_ATTACHMENTS);
        }
        long total = main.size() + attachments.stream().mapToLong(NewFile::size).sum();
        if (total > MAX_VERSION_SIZE) {
            throw DocumentApiException.fileTooLarge(VERSION_TOO_LARGE).aboutVersion();
        }
    }

    private void validateVersionFiles(List<DocumentFile> kept, NewFile newMain, List<NewFile> newAttachments) {
        long total = (newMain == null ? 0 : newMain.size()) + newAttachments.stream().mapToLong(NewFile::size).sum();
        int attachments = newAttachments.size();
        for (DocumentFile file : kept) {
            if (file.getFileSize() <= 0 || file.getFileSize() > MAX_FILE_SIZE) {
                throw DocumentApiException.validation("Некорректный сохраняемый файл");
            }
            total += file.getFileSize();
            if (file.getKind() == DocumentFile.Kind.ATTACHMENT) {
                attachments++;
            }
        }
        if (attachments > MAX_ATTACHMENTS) {
            throw DocumentApiException.validation(TOO_MANY_ATTACHMENTS);
        }
        if (total > MAX_VERSION_SIZE) {
            throw DocumentApiException.fileTooLarge(VERSION_TOO_LARGE).aboutVersion();
        }
    }

    public record NewFile(String storageKey, String fileName, String mimeType, long size) {}

    /** previousFields — поля прежней версии, если основной файл не менялся: их перепроверяют без модели. */
    public record CreatedVersion(
            long documentId,
            long versionId,
            int versionNo,
            String checkToken,
            DocumentRuleService.CheckData previousFields
    ) {
        public CreatedVersion(long documentId, long versionId, int versionNo, String checkToken) {
            this(documentId, versionId, versionNo, checkToken, null);
        }

        public CheckAttempt checkAttempt() {
            return checkToken == null ? null : new CheckAttempt(versionId, checkToken);
        }
    }

    public record CheckAttempt(long versionId, String checkToken) {}

    public record FieldsUpdateAttempt(CheckAttempt checkAttempt, DocumentRuleService.CheckData previous) {}

    public record RetypeAttempt(CheckAttempt checkAttempt, boolean containsSensitive) {}

    public record CorrectionSource(
            long documentTypeId,
            boolean containsSensitive,
            DocumentFile main,
            List<Long> attachmentIds,
            List<DocumentCardResponse.FieldValue> fields
    ) {}

    /** textMissing отсутствует в версиях, сохранённых до, — тогда false. */
    public record StoredFields(
            boolean modelAvailable,
            List<DocumentCardResponse.FieldValue> fields,
            String checkToken,
            boolean textMissing
    ) {
        public StoredFields(boolean modelAvailable, List<DocumentCardResponse.FieldValue> fields, String checkToken) {
            this(modelAvailable, fields, checkToken, false);
        }
    }
}
