package ru.sibvibe.approval.organization.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.sibvibe.approval.organization.entity.AppUser;
import ru.sibvibe.approval.organization.entity.Organization;
import ru.sibvibe.approval.organization.entity.OrganizationMember;
import ru.sibvibe.approval.organization.repository.AppUserRepository;
import ru.sibvibe.approval.organization.repository.JoinRequestRepository;
import ru.sibvibe.approval.organization.repository.MemberRoleRepository;
import ru.sibvibe.approval.organization.repository.OrganizationMemberRepository;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;
import ru.sibvibe.approval.organization.repository.RoleRepository;
import ru.sibvibe.approval.organization.DemoSandboxContentSeeder;
import ru.sibvibe.approval.common.api.DemoActAsForbiddenException;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrganizationSecurityServiceTest {

    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final OrganizationMemberRepository memberRepository = mock(OrganizationMemberRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final MemberRoleRepository memberRoleRepository = mock(MemberRoleRepository.class);
    private final JoinRequestRepository joinRequestRepository = mock(JoinRequestRepository.class);
    private final InviteService inviteService = mock(InviteService.class);
    private final RouteTemplateService routeTemplateService = mock(RouteTemplateService.class);
    private final DemoSandboxContentSeeder contentSeeder = mock(DemoSandboxContentSeeder.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final OrganizationSecurityService service = new OrganizationSecurityService(
            userRepository, organizationRepository, memberRepository, roleRepository, memberRoleRepository,
            joinRequestRepository, inviteService, routeTemplateService, contentSeeder,
            jdbcTemplate, Clock.systemUTC());

    @Test
    void doesNotWriteWhenExistingUsersDisplayNameIsUnchanged() {
        AppUser user = user(7, "Проверяющий");
        when(userRepository.findByMaxUserId(700L)).thenReturn(Optional.of(user));

        var result = service.findOrCreateUser(700, "Проверяющий");

        assertThat(result.id()).isEqualTo(7);
        assertThat(result.displayName()).isEqualTo("Проверяющий");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void updatesExistingUsersDisplayNameOnlyWhenItChanged() {
        AppUser user = user(7, "Старое имя");
        when(userRepository.findByMaxUserId(700L)).thenReturn(Optional.of(user));

        var result = service.findOrCreateUser(700, "Новое имя");

        assertThat(result.displayName()).isEqualTo("Новое имя");
        assertThat(user.getFullName()).isEqualTo("Новое имя");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void acceptsOnlyActiveDemoMemberOfOwnersCurrentSandbox() {
        Organization sandbox = sandbox(10, 70);
        OrganizationMember member = member(20, 10, 100);
        AppUser actor = user(100, "Свой персонаж");
        when(organizationRepository.findFirstByDemoOwnerIdAndDemoTrueOrderByIdDesc(70L))
                .thenReturn(Optional.of(sandbox));
        when(memberRepository.findByOrgIdAndUserIdAndStatusAndDemoTrue(
                10L, 100L, OrganizationMember.Status.ACTIVE)).thenReturn(Optional.of(member));
        when(userRepository.findById(100L)).thenReturn(Optional.of(actor));
        when(memberRoleRepository.findByMemberId(20L)).thenReturn(List.of());

        var result = service.requireSandboxActor(70, 100);

        assertThat(result.user().id()).isEqualTo(100);
        assertThat(result.orgId()).isEqualTo(10);
    }

    @Test
    void rejectsRealUserAndCharacterFromAnotherSandbox() {
        when(organizationRepository.findFirstByDemoOwnerIdAndDemoTrueOrderByIdDesc(70L))
                .thenReturn(Optional.of(sandbox(10, 70)));
        when(memberRepository.findByOrgIdAndUserIdAndStatusAndDemoTrue(
                10L, 500L, OrganizationMember.Status.ACTIVE)).thenReturn(Optional.empty());
        when(memberRepository.findByOrgIdAndUserIdAndStatusAndDemoTrue(
                10L, 600L, OrganizationMember.Status.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireSandboxActor(70, 500))
                .isInstanceOf(DemoActAsForbiddenException.class);
        assertThatThrownBy(() -> service.requireSandboxActor(70, 600))
                .isInstanceOf(DemoActAsForbiddenException.class);
    }

    private Organization sandbox(long id, long owner) {
        Organization value = new Organization();
        value.setId(id);
        value.setDemo(true);
        value.setDemoOwnerId(owner);
        value.setName("Песочница");
        return value;
    }

    private OrganizationMember member(long id, long orgId, long userId) {
        OrganizationMember value = new OrganizationMember();
        value.setId(id);
        value.setOrgId(orgId);
        value.setUserId(userId);
        value.setStatus(OrganizationMember.Status.ACTIVE);
        value.setDemo(true);
        return value;
    }

    private AppUser user(long id, String name) {
        AppUser value = new AppUser();
        value.setId(id);
        value.setFullName(name);
        return value;
    }
}
