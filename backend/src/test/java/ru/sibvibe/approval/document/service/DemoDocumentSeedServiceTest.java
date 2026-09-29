package ru.sibvibe.approval.document.service;

import ru.sibvibe.approval.organization.service.CompanyZoneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.entity.DocumentVersion;
import ru.sibvibe.approval.document.repository.DocumentRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeRepository;
import ru.sibvibe.approval.document.repository.DocumentVersionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoDocumentSeedServiceTest {

    private final DocumentTypeRepository types = mock(DocumentTypeRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final DocumentRuleService rules = mock(DocumentRuleService.class);
    private final CompanyRulesService companyRules = mock(CompanyRulesService.class);
    private final DemoDocumentSeedService service =
            new DemoDocumentSeedService(types, documents, versions, rules, new ObjectMapper(), companyRules, moscow());

    private static CompanyZoneService moscow() {
        CompanyZoneService zones = mock(CompanyZoneService.class);
        when(zones.zoneOf(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.time.ZoneId.of("Europe/Moscow"));
        return zones;
    }

    @Test
    void createsOneCheckedMemoInEveryDocumentState() {
        DocumentType memo = new DocumentType();
        memo.setId(7L);
        memo.setCode("OFFICIAL_MEMO");
        when(types.findByCodeIn(Set.of("OFFICIAL_MEMO"))).thenReturn(List.of(memo));
        when(rules.prepare(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(mock(DocumentRuleService.PreparedCheck.class));
        when(rules.execute(any())).thenReturn(new DocumentRuleService.CheckData(false, List.of(), List.of()));
        AtomicLong ids = new AtomicLong(100);
        when(documents.save(any())).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId(ids.getAndIncrement());
            return document;
        });

        var result = service.seed(10, 20, Instant.parse("2026-09-22T00:00:00Z"));

        assertThat(result).isEqualTo(new DemoDocumentSeedService.SeededDocuments(100, 101, 102, 103, 104));
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documents, org.mockito.Mockito.times(5)).save(documentCaptor.capture());
        assertThat(documentCaptor.getAllValues()).extracting(Document::getStatus)
                .containsExactly(Document.Status.DRAFT, Document.Status.IN_APPROVAL,
                        Document.Status.APPROVED, Document.Status.RETURNED, Document.Status.REJECTED);
        assertThat(documentCaptor.getAllValues()).extracting(Document::getCurrentStage)
                .containsExactly(null, 1, null, null, null);
        assertThat(documentCaptor.getAllValues()).allSatisfy(document -> {
            assertThat(document.getOrgId()).isEqualTo(10);
            assertThat(document.getAuthorId()).isEqualTo(20);
            assertThat(document.getVisibility()).isEqualTo(Document.Visibility.ORG);
            assertThat(document.getUpdatedAt()).isAfterOrEqualTo(document.getCreatedAt());
        });

        ArgumentCaptor<DocumentVersion> versionCaptor = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versions, org.mockito.Mockito.times(5)).save(versionCaptor.capture());
        assertThat(versionCaptor.getAllValues()).allSatisfy(version -> {
            assertThat(version.getVersionNo()).isEqualTo(1);
            assertThat(version.getCheckStatus()).isEqualTo("CHECKED");
            assertThat(version.getContent()).isNotNull();
            assertThat(version.getExtractedFields().path("modelAvailable").asBoolean()).isFalse();
        });
    }
}
