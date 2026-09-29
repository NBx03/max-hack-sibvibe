package ru.sibvibe.approval.document.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.ai.service.DocumentExtractionService;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.repository.DocumentFileRepository;
import ru.sibvibe.approval.storage.FileStorage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAnalysisServiceTest {

    @Test
    void sensitiveDocumentNeverReadsFileOrInvokesModelPipeline() {
        DocumentFileRepository files = mock(DocumentFileRepository.class);
        FileStorage storage = mock(FileStorage.class);
        DocumentExtractionService extraction = mock(DocumentExtractionService.class);
        DocumentRuleService rules = mock(DocumentRuleService.class);
        DocumentType type = new DocumentType();
        type.setCode("MEMO");
        var prepared = new DocumentRuleService.PreparedCheck(type, List.of(), Map.of(), List.of(), Map.of(), java.time.ZoneId.of("Europe/Moscow"));
        var expected = new DocumentRuleService.CheckData(false, List.of(), List.of());
        when(rules.execute(prepared)).thenReturn(expected);
        var service = new DocumentAnalysisService(files, storage, extraction, rules);

        var result = service.analyze(10, true, prepared);

        assertThat(result.check()).isSameAs(expected);
        assertThat(result.summary()).isNull();
        verify(files, never()).findByVersionIdOrderByPosition(10L);
        verify(storage, never()).get(org.mockito.ArgumentMatchers.any());
        verify(extraction, never()).extract(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
