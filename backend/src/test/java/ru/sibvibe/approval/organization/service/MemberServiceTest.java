package ru.sibvibe.approval.organization.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.PendingStepsGuard;
import ru.sibvibe.approval.organization.entity.MemberRole;
import ru.sibvibe.approval.organization.entity.Organization;
import ru.sibvibe.approval.organization.event.AdminChangedEvent;
import ru.sibvibe.approval.organization.entity.OrganizationMember;
import ru.sibvibe.approval.organization.entity.Role;
import ru.sibvibe.approval.organization.repository.JoinRequestRepository;
import ru.sibvibe.approval.organization.repository.MemberRoleRepository;
import ru.sibvibe.approval.organization.repository.OrganizationMemberRepository;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;
import ru.sibvibe.approval.organization.repository.RoleRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Обязательные проверки docs/DESIGN-DECISIONS.md для участников: №6 (последний админ), №7 (незавершённые шаги), №3, самоназначение ролей. */
class MemberServiceTest {

    private static final long ORG = 1;
    private static final long ADMIN_USER = 10;
    private static final long TARGET_USER = 50;
    private static final long MEMBER_ID = 500;

    private final OrganizationMemberRepository memberRepository = mock(OrganizationMemberRepository.class);
    private final MemberRoleRepository memberRoleRepository = mock(MemberRoleRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final JoinRequestRepository joinRequestRepository = mock(JoinRequestRepository.class);
    private final PendingStepsGuard guard = mock(PendingStepsGuard.class);
    private final MemberViewAssembler assembler = mock(MemberViewAssembler.class);
    private final org.springframework.context.ApplicationEventPublisher events =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private final MemberService service = new MemberService(memberRepository, memberRoleRepository, roleRepository,
            organizationRepository, joinRequestRepository, guard, assembler,
            Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneOffset.UTC), events);

    @Test
    void nonMemberIsForbiddenAndNonAdminGetsNotAdmin() {
        assertThatThrownBy(() -> service.list(user(null, false)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("FORBIDDEN"));
        assertThatThrownBy(() -> service.list(user(ORG, false)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_ADMIN"));
    }

    @Test
    void lastAdministratorCannotLoseAdminRights() {
        givenLockedOrganization();
        OrganizationMember member = member(TARGET_USER, true);
        when(memberRepository.findByIdAndOrgId(MEMBER_ID, ORG)).thenReturn(Optional.of(member));
        when(memberRepository.countByOrgIdAndStatusAndAdminTrue(ORG, OrganizationMember.Status.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.setAdmin(adminActor(), MEMBER_ID, false))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("LAST_ADMIN");
                    assertThat(e.httpStatus().value()).isEqualTo(409);
                });
        assertThat(member.isAdmin()).isTrue();
    }

    @Test
    void administratorRightsCanBeRemovedWhenAnotherAdministratorRemains() {
        givenLockedOrganization();
        OrganizationMember member = member(TARGET_USER, true);
        when(memberRepository.findByIdAndOrgId(MEMBER_ID, ORG)).thenReturn(Optional.of(member));
        when(memberRepository.countByOrgIdAndStatusAndAdminTrue(ORG, OrganizationMember.Status.ACTIVE)).thenReturn(2L);

        service.setAdmin(adminActor(), MEMBER_ID, false);

        assertThat(member.isAdmin()).isFalse();
    }

    @Test
    void lastAdministratorCannotBeDisabled() {
        givenLockedOrganization();
        OrganizationMember member = member(TARGET_USER, true);
        when(memberRepository.findByIdAndOrgIdForUpdate(MEMBER_ID, ORG)).thenReturn(Optional.of(member));
        when(memberRepository.countByOrgIdAndStatusAndAdminTrue(ORG, OrganizationMember.Status.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.disable(adminActor(), MEMBER_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("LAST_ADMIN"));
        assertThat(member.getStatus()).isEqualTo(OrganizationMember.Status.ACTIVE);
    }

    @Test
    void disablingIsImmediateAndReturnsDocumentsAwaitingTheMember() {
        givenLockedOrganization();
        OrganizationMember member = member(TARGET_USER, false);
        when(memberRepository.findByIdAndOrgIdForUpdate(MEMBER_ID, ORG)).thenReturn(Optional.of(member));

        service.disable(adminActor(), MEMBER_ID);

        assertThat(member.getStatus()).isEqualTo(OrganizationMember.Status.DISABLED);
        verify(guard).releaseSteps(ORG, TARGET_USER, null);
    }

    @Test
    void memberWithoutPendingStepsIsDisabled() {
        givenLockedOrganization();
        OrganizationMember member = member(TARGET_USER, false);
        when(memberRepository.findByIdAndOrgIdForUpdate(MEMBER_ID, ORG)).thenReturn(Optional.of(member));

        service.disable(adminActor(), MEMBER_ID);

        assertThat(member.getStatus()).isEqualTo(OrganizationMember.Status.DISABLED);
    }

    @Test
    void removingARoleReturnsDocumentsAwaitingTheMemberInThatRole() {
        givenMemberWithRole(5L);

        service.setRoles(adminActor(), MEMBER_ID, List.of());

        verify(memberRoleRepository).deleteByMemberIdAndRoleIdIn(MEMBER_ID, Set.of(5L));
        verify(guard).releaseSteps(ORG, TARGET_USER, Set.of(5L));
    }

    @Test
    void addingRolesDoesNotAskAboutPendingSteps() {
        givenMemberWithRole(5L);
        when(roleRepository.findByOrgIdAndIdIn(ORG, Set.of(5L, 6L))).thenReturn(List.of(role(5), role(6)));

        service.setRoles(adminActor(), MEMBER_ID, List.of(5L, 6L));

        verify(guard, never()).releaseSteps(anyLong(), anyLong(), any());
        verify(memberRoleRepository).save(any(MemberRole.class));
    }

    @Test
    void administratorChangesOwnRolesToo() {
        OrganizationMember self = member(ADMIN_USER, true);
        when(memberRepository.findByIdAndOrgIdForUpdate(MEMBER_ID, ORG)).thenReturn(Optional.of(self));
        when(roleRepository.findByOrgIdAndIdIn(ORG, Set.of(5L))).thenReturn(List.of(role(5L)));

        service.setRoles(adminActor(), MEMBER_ID, List.of(5L));

        verify(memberRoleRepository).save(org.mockito.ArgumentMatchers.any(MemberRole.class));
    }

    @Test
    void ordinaryMemberCannotChangeAnyRoles() {
        assertThatThrownBy(() -> service.setRoles(user(ORG, false), MEMBER_ID, List.of(5L)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_ADMIN"));
    }

    @Test
    void memberOfAnotherCompanyLooksNonexistent() {
        when(memberRepository.findByIdAndOrgIdForUpdate(MEMBER_ID, ORG)).thenReturn(Optional.empty());
        givenLockedOrganization();
        when(memberRepository.findByIdAndOrgId(MEMBER_ID, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setRoles(adminActor(), MEMBER_ID, List.of()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> service.setAdmin(adminActor(), MEMBER_ID, true))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> service.disable(adminActor(), MEMBER_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void roleOfAnotherCompanyLooksNonexistent() {
        givenMemberWithRole();
        when(roleRepository.findByOrgIdAndIdIn(ORG, Set.of(999L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.setRoles(adminActor(), MEMBER_ID, List.of(999L)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
    }

    private void givenLockedOrganization() {
        givenLockedOrganization(false);
    }

    private void givenLockedOrganization(boolean demo) {
        Organization org = new Organization();
        org.setId(ORG);
        org.setDemo(demo);
        when(organizationRepository.findByIdForUpdate(ORG)).thenReturn(Optional.of(org));
    }

    /** Действия над администраторами видят остальные администраторы; в демо — нет. */
    @Test
    void changesOfAdministratorRightsAreAnnouncedOutsideDemo() {
        givenLockedOrganization();
        OrganizationMember admin = member(TARGET_USER, true);
        when(memberRepository.findByIdAndOrgId(MEMBER_ID, ORG)).thenReturn(Optional.of(admin));
        when(memberRepository.findByIdAndOrgIdForUpdate(MEMBER_ID, ORG)).thenReturn(Optional.of(admin));
        when(memberRepository.countByOrgIdAndStatusAndAdminTrue(ORG, OrganizationMember.Status.ACTIVE)).thenReturn(2L);

        service.setAdmin(adminActor(), MEMBER_ID, false);
        service.setAdmin(adminActor(), MEMBER_ID, true);
        service.disable(adminActor(), MEMBER_ID);

        verify(events).publishEvent(new AdminChangedEvent(ORG, ADMIN_USER, TARGET_USER, AdminChangedEvent.Kind.REVOKED));
        verify(events).publishEvent(new AdminChangedEvent(ORG, ADMIN_USER, TARGET_USER, AdminChangedEvent.Kind.GRANTED));
        verify(events).publishEvent(new AdminChangedEvent(ORG, ADMIN_USER, TARGET_USER, AdminChangedEvent.Kind.EXCLUDED));
    }

    @Test
    void excludingAnOrdinaryMemberOrActingInDemoIsNotAnnounced() {
        givenLockedOrganization();
        OrganizationMember ordinary = member(TARGET_USER, false);
        when(memberRepository.findByIdAndOrgIdForUpdate(MEMBER_ID, ORG)).thenReturn(Optional.of(ordinary));
        service.disable(adminActor(), MEMBER_ID);

        givenLockedOrganization(true);
        OrganizationMember demoAdmin = member(TARGET_USER, true);
        when(memberRepository.findByIdAndOrgId(MEMBER_ID, ORG)).thenReturn(Optional.of(demoAdmin));
        when(memberRepository.countByOrgIdAndStatusAndAdminTrue(ORG, OrganizationMember.Status.ACTIVE)).thenReturn(2L);
        service.setAdmin(adminActor(), MEMBER_ID, false);

        verify(events, org.mockito.Mockito.never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
    }

    private void givenMemberWithRole(Long... roleIds) {
        OrganizationMember member = member(TARGET_USER, false);
        when(memberRepository.findByIdAndOrgIdForUpdate(MEMBER_ID, ORG)).thenReturn(Optional.of(member));
        when(memberRoleRepository.findByMemberId(MEMBER_ID)).thenReturn(java.util.Arrays.stream(roleIds).map(id -> {
            MemberRole link = new MemberRole();
            link.setMemberId(MEMBER_ID);
            link.setRoleId(id);
            return link;
        }).toList());
        if (roleIds.length > 0) {
            when(roleRepository.findByOrgIdAndIdIn(ORG, Set.of())).thenReturn(List.of());
        }
    }

    private OrganizationMember member(long userId, boolean admin) {
        OrganizationMember member = new OrganizationMember();
        member.setId(MEMBER_ID);
        member.setOrgId(ORG);
        member.setUserId(userId);
        member.setStatus(OrganizationMember.Status.ACTIVE);
        member.setAdmin(admin);
        return member;
    }

    private Role role(long id) {
        Role role = new Role();
        role.setId(id);
        role.setOrgId(ORG);
        return role;
    }

    private CurrentUser adminActor() {
        return user(ORG, true);
    }

    private CurrentUser user(Long orgId, boolean admin) {
        var identity = new CurrentUser.UserIdentity(ADMIN_USER, "Админ");
        return new CurrentUser(identity, identity, orgId == null ? null : 900L, orgId, Set.of(), admin, null);
    }
}
