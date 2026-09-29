package ru.sibvibe.approval.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.entity.DocumentVersion;
import ru.sibvibe.approval.document.repository.DocumentReadRepository;
import ru.sibvibe.approval.document.repository.DocumentRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeRepository;
import ru.sibvibe.approval.document.repository.DocumentVersionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Переходы по таблице «Состояния документа» и доступ автора (ARCHITECTURE.md, разделы 2 и 4). */
class DocumentStateServiceTest {

    private static final long DOC = 1;
    private static final long ORG = 10;
    private static final long AUTHOR = 100;
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final DocumentTypeRepository types = mock(DocumentTypeRepository.class);
    private final DocumentReadRepository reads = mock(DocumentReadRepository.class);
    private final DocumentStateService service = new DocumentStateService(
            documents, versions, types, reads, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void submissionMovesDraftToInApprovalWithTheActiveStage() {
        Document document = givenDocument(Document.Status.DRAFT);

        service.moveToApproval(DOC, 2);

        assertThat(document.getStatus()).isEqualTo(Document.Status.IN_APPROVAL);
        assertThat(document.getCurrentStage()).isEqualTo(2);
        assertThat(document.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void onlyDraftCanBeSubmitted() {
        for (Document.Status status : new Document.Status[]{
                Document.Status.IN_APPROVAL, Document.Status.APPROVED, Document.Status.RETURNED, Document.Status.REJECTED}) {
            givenDocument(status);
            assertThatThrownBy(() -> service.moveToApproval(DOC, 1)).isInstanceOfSatisfying(DomainException.class,
                    e -> assertThat(e.code()).isEqualTo("INVALID_STATE"));
        }
    }

    @Test
    void finalDecisionsCloseTheDocumentAndClearTheStage() {
        Document approved = givenDocument(Document.Status.IN_APPROVAL);
        approved.setCurrentStage(2);
        service.approve(DOC);
        assertThat(approved.getStatus()).isEqualTo(Document.Status.APPROVED);
        assertThat(approved.getCurrentStage()).isNull();

        Document returned = givenDocument(Document.Status.IN_APPROVAL);
        service.returnForRevision(DOC);
        assertThat(returned.getStatus()).isEqualTo(Document.Status.RETURNED);

        Document rejected = givenDocument(Document.Status.IN_APPROVAL);
        service.reject(DOC);
        assertThat(rejected.getStatus()).isEqualTo(Document.Status.REJECTED);
    }

    @Test
    void finalStatesAndDraftCannotBeDecided() {
        for (Document.Status status : new Document.Status[]{
                Document.Status.DRAFT, Document.Status.APPROVED, Document.Status.REJECTED, Document.Status.RETURNED}) {
            givenDocument(status);
            assertThatThrownBy(() -> service.approve(DOC)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> service.returnForRevision(DOC)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> service.reject(DOC)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> service.advanceStage(DOC, 2)).isInstanceOf(DomainException.class);
        }
    }

    @Test
    void submissionThenApprovalInOneTransactionIsAllowedForRoutesWithNoOneToDecide() {
        Document document = givenDocument(Document.Status.DRAFT);

        service.moveToApproval(DOC, null);
        service.approve(DOC);

        assertThat(document.getStatus()).isEqualTo(Document.Status.APPROVED);
    }

    @Test
    void authorGetsTheDocumentAndOthersDoNotLearnItExists() {
        Document document = givenDocument(Document.Status.DRAFT);
        when(documents.findById(DOC)).thenReturn(Optional.of(document));

        assertThat(service.lockForAuthor(DOC, user(AUTHOR, ORG), "нельзя").id()).isEqualTo(DOC);

        // сотрудник той же компании без доступа: как несуществующий (404), а не 403
        when(reads.canRead(DOC, ORG, 200)).thenReturn(false);
        assertThatThrownBy(() -> service.lockForAuthor(DOC, user(200, ORG), "нельзя"))
                .isInstanceOf(NotFoundException.class);
        // тот, кто документ видит, но не автор: 403
        when(reads.canRead(DOC, ORG, 201)).thenReturn(true);
        assertThatThrownBy(() -> service.lockForAuthor(DOC, user(201, ORG), "нельзя"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
        // другая компания и человек без компании
        assertThatThrownBy(() -> service.lockForAuthor(DOC, user(AUTHOR, 999), "нельзя"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.lockForAuthor(DOC, userWithoutCompany(), "нельзя"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void personWithoutCompanyGetsTheSameAnswerForExistingAndMissingDocuments() {
        // членство проверяется до поиска документа: по ответу не понять, есть ли документ с таким id
        for (long id : new long[]{DOC, 424242}) {
            assertThatThrownBy(() -> service.lockForAuthor(id, userWithoutCompany(), "нельзя"))
                    .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
            assertThatThrownBy(() -> service.authorSnapshot(id, userWithoutCompany(), "нельзя"))
                    .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
        }
        verifyNoInteractions(documents, reads);
    }

    @Test
    void documentStateMirrorsTheStatusesOfTheEntity() {
        // approval видит только DocumentState: добавили статус в таблицу документа — он обязан появиться и здесь
        assertThat(Arrays.stream(DocumentState.values()).map(Enum::name))
                .containsExactlyElementsOf(Arrays.stream(Document.Status.values()).map(Enum::name).toList());
        for (Document.Status status : Document.Status.values()) {
            assertThat(DocumentState.of(status).name()).isEqualTo(status.name());
        }
    }

    @Test
    void decisionLockRejectsADocumentOfAnotherCompany() {
        givenDocument(Document.Status.IN_APPROVAL);

        assertThat(service.lockInOrg(DOC, ORG).status()).isEqualTo(DocumentState.IN_APPROVAL);
        assertThatThrownBy(() -> service.lockInOrg(DOC, 999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void submissionBlockerFollowsTheSharedRule() throws Exception {
        Document document = givenDocument(Document.Status.DRAFT);
        when(documents.findById(DOC)).thenReturn(Optional.of(document));
        DocumentType typed = new DocumentType();
        typed.setGeneric(false);
        when(types.findById(5L)).thenReturn(Optional.of(typed));
        DocumentVersion version = new DocumentVersion();
        version.setCheckStatus("CHECKED");
        version.setValidationIssues(new ObjectMapper().readTree("[{\"severity\":\"BLOCKER\"}]"));
        when(versions.findByDocumentIdAndVersionNo(DOC, 1)).thenReturn(Optional.of(version));

        assertThat(service.submissionBlocker(DOC, 1)).contains(DocumentCheckRules.Reason.BLOCKER_ISSUES);

        version.setValidationIssues(new ObjectMapper().createArrayNode());
        assertThat(service.submissionBlocker(DOC, 1)).isEmpty();
    }

    private Document givenDocument(Document.Status status) {
        Document document = new Document();
        document.setId(DOC);
        document.setOrgId(ORG);
        document.setAuthorId(AUTHOR);
        document.setDocumentTypeId(5L);
        document.setStatus(status);
        document.setCurrentVersionNo(1);
        when(documents.findByIdForUpdate(DOC)).thenReturn(Optional.of(document));
        return document;
    }

    private CurrentUser user(long userId, long orgId) {
        var identity = new CurrentUser.UserIdentity(userId, "Пользователь");
        return new CurrentUser(identity, identity, 50L, orgId, Set.of(), false, null);
    }

    private CurrentUser userWithoutCompany() {
        var identity = new CurrentUser.UserIdentity(AUTHOR, "Пользователь");
        return new CurrentUser(identity, identity, null, null, Set.of(), false, null);
    }
}
