package ru.sibvibe.approval.document.service;

import org.springframework.stereotype.Service;
import ru.sibvibe.approval.ai.DocumentExtractor;
import ru.sibvibe.approval.ai.service.DocumentExtractionService;
import ru.sibvibe.approval.document.entity.DocumentFile;
import ru.sibvibe.approval.document.entity.DocumentTypeField;
import ru.sibvibe.approval.document.repository.DocumentFileRepository;
import ru.sibvibe.approval.storage.FileStorage;

import java.io.IOException;
import java.io.InputStream;

@Service
public class DocumentAnalysisService {

    private final DocumentFileRepository fileRepository;
    private final FileStorage fileStorage;
    private final DocumentExtractionService extractionService;
    private final DocumentRuleService ruleService;

    public DocumentAnalysisService(
            DocumentFileRepository fileRepository,
            FileStorage fileStorage,
            DocumentExtractionService extractionService,
            DocumentRuleService ruleService
    ) {
        this.fileRepository = fileRepository;
        this.fileStorage = fileStorage;
        this.extractionService = extractionService;
        this.ruleService = ruleService;
    }

    public AnalysisResult analyze(
            long versionId,
            boolean containsSensitive,
            DocumentRuleService.PreparedCheck prepared
    ) {
        if (containsSensitive) {
            return new AnalysisResult(ruleService.execute(prepared), null);
        }
        DocumentFile main = fileRepository.findByVersionIdOrderByPosition(versionId).stream()
                .filter(file -> file.getKind() == DocumentFile.Kind.MAIN)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("У версии нет основного файла"));
        FileStorage.StoredFile stored = fileStorage.get(main.getStorageKey());
        InputStream content = stored.content();
        try (content) {
            DocumentExtractionService.ExtractionOutcome outcome = extractionService.extract(
                    content,
                    stored.contentType(),
                    prepared.type().getCode(),
                    prepared.schema().stream().map(this::fieldSpec).toList());
            return new AnalysisResult(ruleService.execute(prepared, outcome.fields()), outcome.summary());
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось закрыть поток файла", exception);
        }
    }

    public record AnalysisResult(DocumentRuleService.CheckData check, String summary) {}

    private DocumentExtractor.FieldSpec fieldSpec(DocumentTypeField field) {
        return new DocumentExtractor.FieldSpec(
                field.getFieldName(),
                DocumentExtractor.FieldType.valueOf(field.getFieldType().name()),
                field.getHint());
    }
}
