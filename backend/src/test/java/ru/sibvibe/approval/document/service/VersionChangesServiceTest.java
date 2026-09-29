package ru.sibvibe.approval.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.entity.DocumentFile;
import ru.sibvibe.approval.document.entity.DocumentVersion;
import ru.sibvibe.approval.document.repository.DocumentFileRepository;
import ru.sibvibe.approval.document.repository.DocumentRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeFieldRepository;
import ru.sibvibe.approval.document.repository.DocumentVersionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * «Не изменился ли документ»: ошибка здесь означает чужое согласие на изменённом тексте, поэтому
 * каждый вид изменения проверяется отдельно.
 */
class VersionChangesServiceTest {

    private static final long DOC = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final DocumentFileRepository files = mock(DocumentFileRepository.class);
    private final DocumentTypeFieldRepository fields = mock(DocumentTypeFieldRepository.class);
    private final VersionChangesService service = new VersionChangesService(documents, versions, files, fields, MAPPER);

    VersionChangesServiceTest() {
        Document document = new Document();
        document.setId(DOC);
        document.setDocumentTypeId(5L);
        when(documents.findById(DOC)).thenReturn(Optional.of(document));
        when(fields.findByDocumentTypeIdOrderByPositionAscIdAsc(5L)).thenReturn(List.of());
    }

    @Test
    void identicalFilesAndFieldsAreTheSameDocument() {
        givenVersion(1, List.of(file(DocumentFile.Kind.MAIN, "key-main")), "Директору");
        givenVersion(2, List.of(file(DocumentFile.Kind.MAIN, "key-main")), "Директору");

        assertThat(service.sameAsPrevious(DOC, 2)).isTrue();
    }

    @Test
    void replacedMainFileIsAChange() {
        givenVersion(1, List.of(file(DocumentFile.Kind.MAIN, "key-old")), "Директору");
        givenVersion(2, List.of(file(DocumentFile.Kind.MAIN, "key-new")), "Директору");

        assertThat(service.sameAsPrevious(DOC, 2)).isFalse();
        assertThat(service.changes(DOC, 2).orElseThrow().files()).singleElement()
                .extracting(VersionChangesService.FileChange::change).isEqualTo(VersionChangesService.ChangeKind.REPLACED);
    }

    @Test
    void addedAndRemovedAttachmentsAreChanges() {
        givenVersion(1, List.of(file(DocumentFile.Kind.MAIN, "main"), file(DocumentFile.Kind.ATTACHMENT, "old")), "Директору");
        givenVersion(2, List.of(file(DocumentFile.Kind.MAIN, "main"), file(DocumentFile.Kind.ATTACHMENT, "new")), "Директору");

        assertThat(service.sameAsPrevious(DOC, 2)).isFalse();
        assertThat(service.changes(DOC, 2).orElseThrow().files())
                .extracting(VersionChangesService.FileChange::change)
                .containsExactlyInAnyOrder(VersionChangesService.ChangeKind.ADDED, VersionChangesService.ChangeKind.REMOVED);
    }

    @Test
    void changedFieldValueIsAChangeButSpacesAtTheEdgesAreNot() {
        givenVersion(1, List.of(file(DocumentFile.Kind.MAIN, "main")), "Директору");
        givenVersion(2, List.of(file(DocumentFile.Kind.MAIN, "main")), " Директору ");
        // Пробелы по краям согласующий не видит — это тот же документ.
        assertThat(service.sameAsPrevious(DOC, 2)).isTrue();

        givenVersion(3, List.of(file(DocumentFile.Kind.MAIN, "main")), "Генеральному директору");
        assertThat(service.sameAsPrevious(DOC, 3)).isFalse();
        assertThat(service.changes(DOC, 3).orElseThrow().fields()).singleElement().satisfies(change -> {
            assertThat(change.before()).isEqualTo("Директору");
            assertThat(change.after()).isEqualTo("Генеральному директору");
        });
    }

    @Test
    void documentWithoutFilesComparesByFieldsAndFormContent() {
        givenVersion(1, List.of(), "Директору");
        givenVersion(2, List.of(), "Директору");
        assertThat(service.sameAsPrevious(DOC, 2)).isTrue();

        // Изменился текст документа-формы — это изменение, даже если поля те же.
        DocumentVersion edited = givenVersion(3, List.of(), "Директору");
        ObjectNode content = MAPPER.createObjectNode().put("body", "Другой текст");
        edited.setContent(content);
        assertThat(service.sameAsPrevious(DOC, 3)).isFalse();
        assertThat(service.changes(DOC, 3).orElseThrow().contentChanged()).isTrue();
    }

    @Test
    void firstVersionHasNothingToCompareWith() {
        givenVersion(1, List.of(file(DocumentFile.Kind.MAIN, "main")), "Директору");

        assertThat(service.changes(DOC, 1)).isEmpty();
        assertThat(service.sameAsPrevious(DOC, 1)).isFalse();
    }

    private DocumentVersion givenVersion(int versionNo, List<DocumentFile> versionFiles, String addressee) {
        DocumentVersion version = new DocumentVersion();
        version.setId(100L + versionNo);
        version.setDocumentId(DOC);
        version.setVersionNo(versionNo);
        ObjectNode stored = MAPPER.createObjectNode();
        stored.put("modelAvailable", true);
        stored.putArray("fields").addObject()
                .put("name", "addressee").put("value", addressee).put("source", "MODEL");
        version.setExtractedFields(stored);
        when(versions.findByDocumentIdAndVersionNo(DOC, versionNo)).thenReturn(Optional.of(version));
        List<DocumentFile> copies = new ArrayList<>();
        for (DocumentFile source : versionFiles) {
            DocumentFile copy = new DocumentFile();
            copy.setVersionId(version.getId());
            copy.setKind(source.getKind());
            copy.setStorageKey(source.getStorageKey());
            copy.setFileName(source.getFileName());
            copies.add(copy);
        }
        when(files.findByVersionIdOrderByPosition(version.getId())).thenReturn(copies);
        return version;
    }

    private static DocumentFile file(DocumentFile.Kind kind, String key) {
        DocumentFile file = new DocumentFile();
        file.setKind(kind);
        file.setStorageKey(key);
        file.setFileName(key + ".docx");
        return file;
    }
}
