package ru.sibvibe.approval.document.service;

import ru.sibvibe.approval.organization.service.CompanyZoneService;
import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.repository.DocumentReadRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentMetricsServiceTest {

    private final DocumentReadRepository repository = mock(DocumentReadRepository.class);
    private final CompanyZoneService companyZones = mock(CompanyZoneService.class);
    private final DocumentMetricsService service = new DocumentMetricsService(repository, companyZones);

    @org.junit.jupiter.api.BeforeEach
    void moscowByDefault() {
        when(companyZones.zoneOf(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.time.ZoneId.of("Europe/Moscow"));
    }

    /** Границы периода — по поясу компании: у новосибирской день начинается на 4 часа раньше московского. */
    @Test
    void periodBoundsFollowTheCompanyTimeZone() {
        when(companyZones.zoneOf(7L)).thenReturn(java.time.ZoneId.of("Asia/Novosibirsk"));
        when(repository.metrics(7L, Instant.parse("2026-08-31T17:00:00Z"), Instant.parse("2026-09-01T17:00:00Z")))
                .thenReturn(new DocumentReadRepository.MetricsRow(1.0, 0.0, 1));

        assertThat(service.metrics(user(7L, 9L), java.time.LocalDate.of(2026, 9, 1), java.time.LocalDate.of(2026, 9, 1)).documentsCount())
                .isEqualTo(1);
    }

    @Test
    void usesInclusiveMoscowDatesAndReturnsRepositoryValues() {
        CurrentUser user = user(7L, 9L);
        Instant from = Instant.parse("2026-08-31T21:00:00Z");
        Instant toExclusive = Instant.parse("2026-09-02T21:00:00Z");
        when(repository.metrics(7L, from, toExclusive))
                .thenReturn(new DocumentReadRepository.MetricsRow(4.5, 0.25, 8));

        var result = service.metrics(user, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"));

        assertThat(result.medianApprovalHours()).isEqualTo(4.5);
        assertThat(result.returnRate()).isEqualTo(0.25);
        assertThat(result.documentsCount()).isEqualTo(8);
        verify(repository).metrics(7L, from, toExclusive);
    }

    @Test
    void rejectsInvalidPeriodBeforeQuery() {
        assertThatThrownBy(() -> service.metrics(
                user(7L, 9L), LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-01")))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.httpStatus().value()).isEqualTo(400);
                });
        verifyNoInteractions(repository);
    }

    @Test
    void requiresActiveCompanyMembership() {
        assertThatThrownBy(() -> service.metrics(user(null, null), null, null))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("FORBIDDEN");
                    assertThat(error.httpStatus().value()).isEqualTo(403);
                });
        verifyNoInteractions(repository);
    }

    private CurrentUser user(Long orgId, Long memberId) {
        CurrentUser.UserIdentity identity = new CurrentUser.UserIdentity(1, "Пользователь");
        return new CurrentUser(identity, identity, memberId, orgId, Set.of(), false, null);
    }
}
