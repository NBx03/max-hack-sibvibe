package ru.sibvibe.approval.organization.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.entity.JoinRequest;
import ru.sibvibe.approval.organization.repository.AppUserRepository;
import ru.sibvibe.approval.organization.repository.JoinRequestRepository;
import ru.sibvibe.approval.organization.repository.OrganizationMemberRepository;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Проверки заявок: администратор управляет только своей компанией (№3), чужая заявка не раскрывается. */
class JoinRequestServiceTest {

    private static final long ORG = 1;
    private static final long ADMIN_USER = 10;
    private static final long APPLICANT = 77;
    private static final long REQUEST_ID = 300;

    private final JoinRequestRepository joinRequestRepository = mock(JoinRequestRepository.class);
    private final MemberService memberService = mock(MemberService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final JoinRequestService service = new JoinRequestService(
            joinRequestRepository,
            mock(AppUserRepository.class),
            mock(OrganizationRepository.class),
            mock(OrganizationMemberRepository.class),
            memberService,
            mock(InviteService.class),
            mock(MemberViewAssembler.class),
            events,
            Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void administratorOfAnotherCompanyCannotApproveOrReject() {
        when(joinRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request(999, APPLICANT)));

        assertThatThrownBy(() -> service.approve(admin(), REQUEST_ID, List.of(5L), false))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> service.reject(admin(), REQUEST_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void decidedRequestCannotBeDecidedAgain() {
        JoinRequest decided = request(ORG, APPLICANT);
        decided.setStatus(JoinRequest.Status.APPROVED);
        when(joinRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(decided));

        assertThatThrownBy(() -> service.approve(admin(), REQUEST_ID, List.of(5L), false))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_STATE");
                    assertThat(e.httpStatus().value()).isEqualTo(409);
                });
        assertThatThrownBy(() -> service.reject(admin(), REQUEST_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("INVALID_STATE"));
    }

    @Test
    void approvalWithoutRolesAndWithoutAdminRightsIsRefused() {
        when(joinRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request(ORG, APPLICANT)));
        when(memberService.requireRolesInOrg(anyLong(), any())).thenReturn(Set.of());

        assertThatThrownBy(() -> service.approve(admin(), REQUEST_ID, List.of(), false))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void rejectionRecordsDeciderAndNotifiesApplicant() {
        JoinRequest pending = request(ORG, APPLICANT);
        when(joinRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));

        service.reject(admin(), REQUEST_ID);

        assertThat(pending.getStatus()).isEqualTo(JoinRequest.Status.REJECTED);
        assertThat(pending.getDecidedBy()).isEqualTo(ADMIN_USER);
        assertThat(pending.getDecidedAt()).isNotNull();
        verify(events).publishEvent(any(ru.sibvibe.approval.organization.event.JoinRequestDecidedEvent.class));
    }

    @Test
    void onlyTheApplicantCanCancelAndOnlyWhilePending() {
        JoinRequest pending = request(ORG, APPLICANT);
        when(joinRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.cancel(123, REQUEST_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThat(pending.getStatus()).isEqualTo(JoinRequest.Status.PENDING);

        service.cancel(APPLICANT, REQUEST_ID);
        assertThat(pending.getStatus()).isEqualTo(JoinRequest.Status.CANCELLED);

        assertThatThrownBy(() -> service.cancel(APPLICANT, REQUEST_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("INVALID_STATE"));
    }

    @Test
    void unknownStatusFilterIsAValidationError() {
        assertThatThrownBy(() -> service.list(admin(), "WHATEVER"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    }

    private JoinRequest request(long orgId, long userId) {
        JoinRequest request = new JoinRequest();
        request.setId(REQUEST_ID);
        request.setOrgId(orgId);
        request.setUserId(userId);
        request.setInviteId(1L);
        request.setStatus(JoinRequest.Status.PENDING);
        request.setCreatedAt(Instant.parse("2026-09-20T09:00:00Z"));
        return request;
    }

    private CurrentUser admin() {
        var identity = new CurrentUser.UserIdentity(ADMIN_USER, "Админ");
        return new CurrentUser(identity, identity, 900L, ORG, Set.of(), true, null);
    }
}
