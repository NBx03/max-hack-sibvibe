package ru.sibvibe.approval.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.document.dto.DocumentCardResponse;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.entity.DocumentFile;
import ru.sibvibe.approval.document.entity.DocumentTypeField;
import ru.sibvibe.approval.document.entity.DocumentVersion;
import ru.sibvibe.approval.document.repository.DocumentFileRepository;
import ru.sibvibe.approval.document.repository.DocumentRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeFieldRepository;
import ru.sibvibe.approval.document.repository.DocumentVersionRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Что изменилось в версии по сравнению с предыдущей: файлы и значения полей.
 *
 * Нужно в двух местах. Согласующему, который уже одобрял прошлую версию, карточка показывает только
 * разницу — перечитывать весь документ заново не нужно. А если разницы нет вовсе (автор отозвал документ,
 * чтобы добавить согласующего, или его вернули из-за маршрута), прежние одобрения переносятся на новую
 * версию: одобрение относится к содержимому, а оно то же самое.
 *
 * Содержимое версии — это её файлы (по ключу в хранилище: файлы неизменяемы, один ключ — одни байты) и
 * значения полей, которые видят согласующие. Название и видимость — свойства документа, а не версии.
 */
@Service
public class VersionChangesService {

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentFileRepository fileRepository;
    private final DocumentTypeFieldRepository fieldRepository;
    private final ObjectMapper objectMapper;

    public VersionChangesService(
            DocumentRepository documentRepository,
            DocumentVersionRepository versionRepository,
            DocumentFileRepository fileRepository,
            DocumentTypeFieldRepository fieldRepository,
            ObjectMapper objectMapper
    ) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.fileRepository = fileRepository;
        this.fieldRepository = fieldRepository;
        this.objectMapper = objectMapper;
    }

    /** Разница версии {@code versionNo} с предыдущей; пусто — это первая версия. */
    @Transactional(readOnly = true)
    public Optional<Changes> changes(long documentId, int versionNo) {
        if (versionNo <= 1) {
            return Optional.empty();
        }
        Optional<DocumentVersion> current = versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo);
        Optional<DocumentVersion> previous = versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo - 1);
        if (current.isEmpty() || previous.isEmpty()) {
            return Optional.empty();
        }
        Document document = documentRepository.findById(documentId).orElseThrow();
        Map<String, String> labels = fieldRepository
                .findByDocumentTypeIdOrderByPositionAscIdAsc(document.getDocumentTypeId()).stream()
                .collect(Collectors.toMap(DocumentTypeField::getFieldName, DocumentTypeField::getLabel,
                        (left, right) -> left, LinkedHashMap::new));
        return Optional.of(new Changes(
                versionNo - 1,
                fileChanges(files(previous.get()), files(current.get())),
                fieldChanges(values(previous.get()), values(current.get()), labels),
                // Содержимое документа-формы (docs/DESIGN-DECISIONS.md): пока его пишет только демо, но как только появится
                // правка формы, изменённый текст не должен считаться «тем же документом».
                !Objects.equals(previous.get().getContent(), current.get().getContent())));
    }

    /**
     * Содержимое версии совпадает с предыдущей: те же файлы, те же значения полей и то же содержимое формы.
     * Признак «чувствительные данные» не сравнивается сознательно: он меняет только то, уходит ли текст в модель,
     * а не то, что видит и одобряет согласующий.
     */
    @Transactional(readOnly = true)
    public boolean sameAsPrevious(long documentId, int versionNo) {
        return changes(documentId, versionNo).map(Changes::isEmpty).orElse(false);
    }

    private List<DocumentFile> files(DocumentVersion version) {
        return fileRepository.findByVersionIdOrderByPosition(version.getId());
    }

    private Map<String, String> values(DocumentVersion version) {
        if (version.getExtractedFields() == null || version.getExtractedFields().isNull()) {
            return Map.of();
        }
        DocumentPersistenceService.StoredFields stored = objectMapper.convertValue(
                version.getExtractedFields(), DocumentPersistenceService.StoredFields.class);
        Map<String, String> result = new LinkedHashMap<>();
        if (stored.fields() != null) {
            for (DocumentCardResponse.FieldValue field : stored.fields()) {
                result.put(field.name(), normalized(field.value()));
            }
        }
        return result;
    }

    private static List<FileChange> fileChanges(List<DocumentFile> before, List<DocumentFile> after) {
        List<FileChange> result = new ArrayList<>();
        Optional<DocumentFile> mainBefore = main(before);
        Optional<DocumentFile> mainAfter = main(after);
        if (mainAfter.isPresent() && !Objects.equals(
                mainBefore.map(DocumentFile::getStorageKey).orElse(null), mainAfter.get().getStorageKey())) {
            result.add(new FileChange(ChangeKind.REPLACED, DocumentFile.Kind.MAIN, mainAfter.get().getFileName()));
        }
        Map<String, DocumentFile> attachmentsBefore = attachments(before);
        Map<String, DocumentFile> attachmentsAfter = attachments(after);
        attachmentsAfter.forEach((key, file) -> {
            if (!attachmentsBefore.containsKey(key)) {
                result.add(new FileChange(ChangeKind.ADDED, DocumentFile.Kind.ATTACHMENT, file.getFileName()));
            }
        });
        attachmentsBefore.forEach((key, file) -> {
            if (!attachmentsAfter.containsKey(key)) {
                result.add(new FileChange(ChangeKind.REMOVED, DocumentFile.Kind.ATTACHMENT, file.getFileName()));
            }
        });
        return result;
    }

    private static Optional<DocumentFile> main(List<DocumentFile> files) {
        return files.stream().filter(file -> file.getKind() == DocumentFile.Kind.MAIN).findFirst();
    }

    private static Map<String, DocumentFile> attachments(List<DocumentFile> files) {
        return files.stream()
                .filter(file -> file.getKind() == DocumentFile.Kind.ATTACHMENT)
                .collect(Collectors.toMap(DocumentFile::getStorageKey, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
    }

    private static List<FieldChange> fieldChanges(
            Map<String, String> before, Map<String, String> after, Map<String, String> labels
    ) {
        Set<String> names = new LinkedHashSet<>(labels.keySet());
        names.addAll(before.keySet());
        names.addAll(after.keySet());
        List<FieldChange> result = new ArrayList<>();
        for (String name : names) {
            String old = before.getOrDefault(name, "");
            String now = after.getOrDefault(name, "");
            if (!old.equals(now)) {
                result.add(new FieldChange(name, labels.getOrDefault(name, name), blankToNull(old), blankToNull(now)));
            }
        }
        return result;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }

    private static String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    public enum ChangeKind { ADDED, REMOVED, REPLACED }

    /** fileName — имя нового файла (для REPLACED и ADDED) или убранного (REMOVED). */
    public record FileChange(ChangeKind change, DocumentFile.Kind kind, String fileName) {
    }

    /** before/after — {@code null}, если поле было или стало пустым. */
    public record FieldChange(String name, String label, String before, String after) {
    }

    /** contentChanged — изменилось содержимое документа-формы ({@code document_version.content}). */
    public record Changes(int comparedToVersionNo, List<FileChange> files, List<FieldChange> fields, boolean contentChanged) {

        public boolean isEmpty() {
            return files.isEmpty() && fields.isEmpty() && !contentChanged;
        }
    }
}
