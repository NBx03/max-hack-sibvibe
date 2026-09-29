package ru.sibvibe.approval.document.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.dto.CreateDocumentRequest;
import ru.sibvibe.approval.document.dto.CreateVersionRequest;
import ru.sibvibe.approval.document.dto.DocumentCardResponse;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.repository.DocumentReadRepository;
import ru.sibvibe.approval.document.repository.DocumentRepository;
import ru.sibvibe.approval.storage.FileStorage;
import ru.sibvibe.approval.storage.FileStorageException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentCommandServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentReadRepository documentReads = mock(DocumentReadRepository.class);
    private final DocumentFileValidator fileValidator = mock(DocumentFileValidator.class);
    private final DocumentRuleService rules = mock(DocumentRuleService.class);
    private final DocumentAnalysisService analysis = mock(DocumentAnalysisService.class);
    private final DocumentPersistenceService persistence = mock(DocumentPersistenceService.class);
    private final DocumentViewService views = mock(DocumentViewService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final DocumentTypeGuessService typeGuess = mock(DocumentTypeGuessService.class);
    private final DocumentCommandService service =
            new DocumentCommandService(
                    documents, documentReads, fileValidator, rules, analysis, persistence, views, storage, typeGuess, new DocxCorrector());

    @Test
    void deletesNewObjectWhenMetadataTransactionFails() {
        MockMultipartFile multipart = new MockMultipartFile("main", "memo.pdf", "application/pdf", new byte[]{1});
        var validated = new DocumentFileValidator.ValidatedFile(new byte[]{1}, "memo.pdf", "application/pdf");
        var prepared = prepared();
        when(fileValidator.validateMain(multipart)).thenReturn(validated);
        when(rules.prepare(anyLong(), eq(5L), eq(Map.of()))).thenReturn(prepared);
        when(storage.put(any(), any(), any(), anyLong())).thenReturn("new-key");
        when(persistence.create(anyLong(), anyLong(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db unavailable"));

        assertThatThrownBy(() -> service.create(user(), request(), multipart, List.of()))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).delete("new-key");
        verify(views, never()).card(anyLong(), any());
    }

    /**: не подошло приложение — в хранилище не попадает ни один файл запроса. */
    @Test
    void invalidAttachmentStopsUploadBeforeAnyFileIsStored() {
        MockMultipartFile multipart = new MockMultipartFile("main", "memo.pdf", "application/pdf", new byte[]{1});
        MockMultipartFile attachment = new MockMultipartFile("attachments", "notes.txt", "text/plain", new byte[]{1});
        when(fileValidator.validateMain(multipart))
                .thenReturn(new DocumentFileValidator.ValidatedFile(new byte[]{1}, "memo.pdf", "application/pdf"));
        when(fileValidator.validateAttachment(attachment))
                .thenThrow(DocumentApiException.fileTypeNotAllowed("Приложение «notes.txt» не подходит"));

        assertThatThrownBy(() -> service.create(user(), request(), multipart, List.of(attachment)))
                .hasMessage("Приложение «notes.txt» не подходит");

        verify(storage, never()).put(any(), any(), any(), anyLong());
    }

    /**: хранилище отказало на втором приложении — основной файл и первое приложение удаляются. */
    @Test
    void failedAttachmentUploadRemovesFilesAlreadyStored() {
        MockMultipartFile multipart = new MockMultipartFile("main", "memo.pdf", "application/pdf", new byte[]{1});
        MockMultipartFile first = new MockMultipartFile("attachments", "a.xlsx", "", new byte[]{1});
        MockMultipartFile second = new MockMultipartFile("attachments", "b.xlsx", "", new byte[]{2});
        when(fileValidator.validateMain(multipart))
                .thenReturn(new DocumentFileValidator.ValidatedFile(new byte[]{1}, "memo.pdf", "application/pdf"));
        when(fileValidator.validateAttachment(first))
                .thenReturn(new DocumentFileValidator.ValidatedFile(new byte[]{1}, "a.xlsx", "application/xlsx"));
        when(fileValidator.validateAttachment(second))
                .thenReturn(new DocumentFileValidator.ValidatedFile(new byte[]{2}, "b.xlsx", "application/xlsx"));
        when(rules.prepare(anyLong(), eq(5L), eq(Map.of()))).thenReturn(prepared());
        when(storage.put(any(), eq("memo.pdf"), any(), anyLong())).thenReturn("main-key");
        when(storage.put(any(), eq("a.xlsx"), any(), anyLong())).thenReturn("a-key");
        when(storage.put(any(), eq("b.xlsx"), any(), anyLong())).thenThrow(new FileStorageException("storage unavailable"));

        assertThatThrownBy(() -> service.create(user(), request(), multipart, List.of(first, second)))
                .isInstanceOf(FileStorageException.class);

        verify(storage).delete("main-key");
        verify(storage).delete("a-key");
        verify(persistence, never()).create(anyLong(), anyLong(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    /**
     *: новая версия с приложением — не сохранилась в базе, значит новые файлы удаляются из хранилища. Часть без
     * имени и содержимого («файл не выбран») пропускается, пустой файл с именем уходит в валидатор.
     */
    @Test
    void newVersionAttachmentsAreRemovedWhenSavingFails() {
        Document document = document(7L);
        document.setDocumentTypeId(5L);
        document.setCurrentVersionNo(1);
        when(documents.findById(1L)).thenReturn(Optional.of(document));
        when(rules.prepare(anyLong(), eq(5L), any())).thenReturn(prepared());
        MockMultipartFile attachment = new MockMultipartFile("attachments", "b.xlsx", "", new byte[]{2});
        MockMultipartFile notSelected = new MockMultipartFile("attachments", "", "", new byte[0]);
        when(fileValidator.validateAttachment(attachment))
                .thenReturn(new DocumentFileValidator.ValidatedFile(new byte[]{2}, "b.xlsx", "application/xlsx"));
        when(storage.put(any(), eq("b.xlsx"), any(), anyLong())).thenReturn("b-key");
        when(persistence.createVersion(anyLong(), anyLong(), anyLong(), anyInt(), anyBoolean(), any(), any(), any(), any()))
                .thenThrow(DocumentApiException.fileTooLarge("Размер файлов версии превышает 30 МБ"));

        assertThatThrownBy(() -> service.createVersion(1L, user(), new CreateVersionRequest(Map.of(), false, List.of(3L)),
                null, List.of(attachment, notSelected)))
                .hasMessage("Размер файлов версии превышает 30 МБ");

        verify(fileValidator, never()).validateAttachment(notSelected);
        verify(persistence).createVersion(eq(1L), eq(10L), eq(7L), eq(1), eq(false), eq(List.of(3L)), any(), isNull(),
                eq(List.of(new DocumentPersistenceService.NewFile("b-key", "b.xlsx", "application/xlsx", 1))));
        verify(storage).delete("b-key");
    }

    @Test
    void namedEmptyAttachmentIsValidatedNotDropped() {
        MockMultipartFile multipart = new MockMultipartFile("main", "memo.pdf", "application/pdf", new byte[]{1});
        MockMultipartFile empty = new MockMultipartFile("attachments", "Смета.xlsx", "", new byte[0]);
        when(fileValidator.validateMain(multipart))
                .thenReturn(new DocumentFileValidator.ValidatedFile(new byte[]{1}, "memo.pdf", "application/pdf"));
        when(fileValidator.validateAttachment(empty))
                .thenThrow(DocumentApiException.validation("Приложение «Смета.xlsx» пустое"));

        assertThatThrownBy(() -> service.create(user(), request(), multipart, List.of(empty)))
                .hasMessage("Приложение «Смета.xlsx» пустое");
        verify(storage, never()).put(any(), any(), any(), anyLong());
    }

    @Test
    void storageFailureDoesNotCreateDatabaseMetadata() {
        MockMultipartFile multipart = new MockMultipartFile(
                "main", "memo.pdf", "application/pdf", new byte[]{1});
        var validated = new DocumentFileValidator.ValidatedFile(
                new byte[]{1}, "memo.pdf", "application/pdf");
        when(fileValidator.validateMain(multipart)).thenReturn(validated);
        when(rules.prepare(anyLong(), eq(5L), eq(Map.of()))).thenReturn(prepared());
        when(storage.put(any(), any(), any(), anyLong()))
                .thenThrow(new FileStorageException("storage unavailable"));

        assertThatThrownBy(() -> service.create(user(), request(), multipart, List.of()))
                .isInstanceOf(FileStorageException.class);

        verify(persistence, never()).create(anyLong(), anyLong(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    @Test
    void ruleFailureLeavesPersistedVersionMarkedFailedAndReturnsCard() {
        MockMultipartFile multipart = new MockMultipartFile("main", "memo.pdf", "application/pdf", new byte[]{1});
        var validated = new DocumentFileValidator.ValidatedFile(new byte[]{1}, "memo.pdf", "application/pdf");
        var prepared = prepared();
        var pending = new DocumentRuleService.CheckData(false, List.of(), List.of());
        when(fileValidator.validateMain(multipart)).thenReturn(validated);
        when(rules.prepare(anyLong(), eq(5L), eq(Map.of()))).thenReturn(prepared);
        when(rules.pending(prepared)).thenReturn(pending);
        when(analysis.analyze(2, false, prepared)).thenThrow(new IllegalArgumentException("broken rule"));
        when(storage.put(any(), any(), any(), anyLong())).thenReturn("new-key");
        when(persistence.create(anyLong(), anyLong(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new DocumentPersistenceService.CreatedVersion(1, 2, 1, "attempt-1"));
        when(views.card(1, user())).thenReturn(mock(DocumentCardResponse.class));

        service.create(user(), request(), multipart, List.of());

        verify(persistence).failCheck(
                1, new DocumentPersistenceService.CheckAttempt(2, "attempt-1"), pending, true);
        verify(storage, never()).delete("new-key");
    }

    @Test
    void autoTypeAndTitleComeFromModelAndAreMarkedAutoDetected() {
        MockMultipartFile multipart = new MockMultipartFile("main", "scan_17.pdf", "application/pdf", new byte[]{1});
        var validated = new DocumentFileValidator.ValidatedFile(new byte[]{1}, "scan_17.pdf", "application/pdf");
        var prepared = prepared();
        when(fileValidator.validateMain(multipart)).thenReturn(validated);
        when(typeGuess.guess(validated.content(), "application/pdf"))
                .thenReturn(new DocumentTypeGuessService.Guess(prepared.type(), "О закупке ноутбуков"));
        when(rules.prepare(anyLong(), eq(5L), isNull())).thenReturn(prepared);
        when(storage.put(any(), any(), any(), anyLong())).thenReturn("new-key");
        when(persistence.create(anyLong(), anyLong(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new DocumentPersistenceService.CreatedVersion(1, 2, 1, "attempt-1"));
        when(analysis.analyze(2, false, prepared)).thenReturn(new DocumentAnalysisService.AnalysisResult(
                new DocumentRuleService.CheckData(true, List.of(), List.of()), "Краткая сводка"));

        service.create(user(), new CreateDocumentRequest(null, " ", Document.Visibility.PRIVATE, false, null), multipart, List.of());

        verify(persistence).create(10L, 7L, "О закупке ноутбуков", true, Document.Visibility.PRIVATE, false, true,
                prepared, new DocumentPersistenceService.NewFile("new-key", "scan_17.pdf", "application/pdf", 1), List.of());
    }

    /**
     * Автор выбрал вид сам (например, «Другой документ» вместо записки) — ИИ всё равно определяет свой, и он
     * сохраняется: расхождение увидят автор и согласующие.
     */
    @Test
    void explicitTypeStillRecordsTheTypeTheModelSees() {
        MockMultipartFile multipart = new MockMultipartFile("main", "memo.pdf", "application/pdf", new byte[]{1});
        var validated = new DocumentFileValidator.ValidatedFile(new byte[]{1}, "memo.pdf", "application/pdf");
        var prepared = prepared();
        DocumentType memo = new DocumentType();
        memo.setId(9L);
        when(fileValidator.validateMain(multipart)).thenReturn(validated);
        when(typeGuess.guess(validated.content(), "application/pdf"))
                .thenReturn(new DocumentTypeGuessService.Guess(memo, "О закупке"));
        when(rules.prepare(anyLong(), eq(5L), any())).thenReturn(prepared);
        when(storage.put(any(), any(), any(), anyLong())).thenReturn("new-key");
        when(persistence.create(anyLong(), anyLong(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new DocumentPersistenceService.CreatedVersion(1, 2, 1, "attempt-1"));
        when(analysis.analyze(2, false, prepared)).thenReturn(new DocumentAnalysisService.AnalysisResult(
                new DocumentRuleService.CheckData(true, List.of(), List.of()), null));

        service.create(user(), request(), multipart, List.of());

        verify(persistence).recordAiType(1L, 9L);
    }

    @Test
    void sensitiveDocumentNeedsExplicitTypeBecauseTextNeverReachesModel() {
        MockMultipartFile multipart = new MockMultipartFile("main", "memo.pdf", "application/pdf", new byte[]{1});
        when(fileValidator.validateMain(multipart))
                .thenReturn(new DocumentFileValidator.ValidatedFile(new byte[]{1}, "memo.pdf", "application/pdf"));

        assertThatThrownBy(() -> service.create(user(),
                new CreateDocumentRequest(null, "Записка", Document.Visibility.PRIVATE, true, Map.of()), multipart, List.of()))
                .isInstanceOf(DocumentApiException.class)
                .satisfies(exception -> assertThat(((DomainException) exception).code()).isEqualTo("VALIDATION_FAILED"));
        verify(typeGuess, never()).guess(any(), any());
        verify(storage, never()).put(any(), any(), any(), anyLong());
    }

    @Test
    void titleFallsBackToFileNameWithoutModel() {
        assertThat(DocumentCommandService.titleOrFileName(null, "Приказ_о_премии.docx")).isEqualTo("Приказ о премии");
        assertThat(DocumentCommandService.titleOrFileName("  ", ".docx")).isEqualTo(".docx");
        assertThat(DocumentCommandService.titleOrFileName("О закупке", "x.pdf")).isEqualTo("О закупке");
        assertThat(DocumentCommandService.titleOrFileName(null, null)).isEqualTo("Документ");
    }

    @Test
    void hidesPrivateDocumentFromSameCompanyNonAuthor() {
        Document document = document(9L);
        when(documents.findById(1L)).thenReturn(Optional.of(document));
        when(documentReads.canRead(1L, 10L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.createVersion(
                1L, user(), new CreateVersionRequest(Map.of(), false, List.of()), null, List.of()))
                .isInstanceOf(NotFoundException.class)
                .satisfies(exception -> assertThat(((DomainException) exception).code())
                        .isEqualTo("NOT_FOUND"));
    }

    @Test
    void forbidsModificationWhenNonAuthorCanReadDocument() {
        Document document = document(9L);
        when(documents.findById(1L)).thenReturn(Optional.of(document));
        when(documentReads.canRead(1L, 10L, 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.createVersion(
                1L, user(), new CreateVersionRequest(Map.of(), false, List.of()), null, List.of()))
                .isInstanceOf(DocumentApiException.class)
                .satisfies(exception -> assertThat(((DomainException) exception).code())
                        .isEqualTo("FORBIDDEN"));
    }

    private CreateDocumentRequest request() {
        return new CreateDocumentRequest(5L, "Записка", Document.Visibility.PRIVATE, false, Map.of());
    }

    /** Ревью: «Проверить заново» — только правила по сохранённым полям, модель и файл не трогаются. */
    @Test
    void recheckRunsRulesOnStoredFieldsWithoutTheModel() {
        Document document = new Document();
        document.setId(1L);
        document.setOrgId(10L);
        document.setAuthorId(7L);
        document.setDocumentTypeId(5L);
        when(documents.findById(1L)).thenReturn(Optional.of(document));
        when(rules.prepare(10L, 5L, Map.of())).thenReturn(prepared());

        service.recheck(1L, 1, user());

        verify(persistence).recheck(eq(1L), eq(1), eq(10L), eq(7L), eq(5L), any());
        verify(analysis, never()).analyze(anyLong(), anyBoolean(), any());
        verify(storage, never()).get(any());
    }

    @Test
    void recheckOfAForeignCompanysDocumentIsNotFound() {
        Document foreign = new Document();
        foreign.setId(2L);
        foreign.setOrgId(99L);
        foreign.setAuthorId(7L);
        foreign.setDocumentTypeId(5L);
        when(documents.findById(2L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.recheck(2L, 1, user())).isInstanceOf(NotFoundException.class);
        verify(persistence, never()).recheck(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong(), anyLong(), anyLong(), any());
    }

    /**: название формы — указанное, иначе заголовок записки, иначе название типа. */
    @Test
    void formTitleFallsBackToSubjectThenTypeName() {
        DocumentType type = prepared().type();
        var withSubject = new DocumentRuleService.PreparedCheck(type, List.of(), Map.of("subject", " О закупке "), List.of(),
                Map.of(), java.time.ZoneId.of("Europe/Moscow"));

        assertThat(DocumentCommandService.formTitle(" Моя записка ", withSubject)).isEqualTo("Моя записка");
        assertThat(DocumentCommandService.formTitle(null, withSubject)).isEqualTo("О закупке");
        assertThat(DocumentCommandService.formTitle("", prepared())).isEqualTo("Записка");
    }

    /** «Другой документ» (GENERIC) с  не is_generic, но формой он не бывает. */
    @Test
    void formOfOtherDocumentIsRejectedBeforeSaving() {
        DocumentType other = prepared().type();
        other.setCode("GENERIC");
        when(rules.prepare(10L, 5L, Map.of()))
                .thenReturn(new DocumentRuleService.PreparedCheck(other, List.of(), Map.of(), List.of(), Map.of(), java.time.ZoneId.of("Europe/Moscow")));

        assertThatThrownBy(() -> service.createForm(user(), new ru.sibvibe.approval.document.dto.CreateFormDocumentRequest(
                5L, null, Document.Visibility.PRIVATE, false, Map.of())))
                .isInstanceOf(DocumentApiException.class);
        verify(persistence, never()).createForm(anyLong(), anyLong(), any(), anyBoolean(), any(), anyBoolean(), any());
    }

    private CurrentUser user() {
        var identity = new CurrentUser.UserIdentity(7, "Автор");
        return new CurrentUser(identity, identity, 8L, 10L, Set.of(), false, null);
    }

    private Document document(long authorId) {
        Document document = new Document();
        document.setId(1L);
        document.setOrgId(10L);
        document.setAuthorId(authorId);
        return document;
    }

    private DocumentRuleService.PreparedCheck prepared() {
        DocumentType type = new DocumentType();
        type.setId(5L);
        type.setCode("MEMO");
        type.setName("Записка");
        return new DocumentRuleService.PreparedCheck(type, List.of(), Map.of(), List.of(), Map.of(), java.time.ZoneId.of("Europe/Moscow"));
    }
}
