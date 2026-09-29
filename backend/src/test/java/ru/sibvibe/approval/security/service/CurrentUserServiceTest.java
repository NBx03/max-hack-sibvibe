package ru.sibvibe.approval.security.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.common.api.DemoActAsForbiddenException;
import ru.sibvibe.approval.organization.service.OrganizationSecurityService;
import ru.sibvibe.approval.security.MaxIdentityVerifier;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentUserServiceTest {

    private final OrganizationSecurityService organizationService = mock(OrganizationSecurityService.class);
    private final MaxIdentityVerifier.VerifiedIdentity identity =
            new MaxIdentityVerifier.VerifiedIdentity("700", "Проверяющий", 1_800_000_000L, "c_CODE1234");

    @Test
    void authenticatedUserWithoutOrganizationRemainsAuthenticatedForOnboarding() {
        when(organizationService.findOrCreateUser(700, "Проверяющий"))
                .thenReturn(new OrganizationSecurityService.UserInfo(7, "Проверяющий"));
        when(organizationService.findRealMembership(7)).thenReturn(Optional.empty());

        var current = new CurrentUserService(organizationService, true).resolve(identity, null);

        assertThat(current.authenticatedUser().id()).isEqualTo(7);
        assertThat(current.userId()).isEqualTo(7);
        assertThat(current.orgId()).isNull();
        assertThat(current.memberId()).isNull();
        assertThat(current.roleIds()).isEmpty();
        assertThat(current.startParam()).isEqualTo("c_CODE1234");
        assertThat(current.actingAsDemo()).isFalse();
        verify(organizationService, never()).requireSandboxActor(7, 7);
    }

    @Test
    void switchesBetweenCharactersOnlyThroughOwnerScopedLookup() {
        when(organizationService.findOrCreateUser(700, "Проверяющий"))
                .thenReturn(new OrganizationSecurityService.UserInfo(7, "Проверяющий"));
        when(organizationService.requireSandboxActor(7, 101)).thenReturn(actor(101, 201, 301));
        when(organizationService.requireSandboxActor(7, 102)).thenReturn(actor(102, 202, 302));
        CurrentUserService service = new CurrentUserService(organizationService, true);

        var first = service.resolve(identity, "101");
        var second = service.resolve(identity, "102");

        assertThat(first.userId()).isEqualTo(101);
        assertThat(first.memberId()).isEqualTo(201);
        assertThat(first.orgId()).isEqualTo(900);
        assertThat(first.roleIds()).containsExactly(301L);
        assertThat(second.userId()).isEqualTo(102);
        assertThat(second.roleIds()).containsExactly(302L);
        assertThat(first.authenticatedUser().id()).isEqualTo(7);
        assertThat(second.authenticatedUser().id()).isEqualTo(7);
    }

    @Test
    void twoAuthenticatedOwnersKeepIndependentContexts() {
        var secondIdentity = new MaxIdentityVerifier.VerifiedIdentity("800", "Второй", 1_800_000_000L, null);
        when(organizationService.findOrCreateUser(700, "Проверяющий"))
                .thenReturn(new OrganizationSecurityService.UserInfo(7, "Проверяющий"));
        when(organizationService.findOrCreateUser(800, "Второй"))
                .thenReturn(new OrganizationSecurityService.UserInfo(8, "Второй"));
        when(organizationService.requireSandboxActor(7, 101)).thenReturn(actor(101, 201, 301));
        when(organizationService.requireSandboxActor(8, 201)).thenReturn(actor(201, 401, 501));
        CurrentUserService service = new CurrentUserService(organizationService, true);

        var first = service.resolve(identity, "101");
        var second = service.resolve(secondIdentity, "201");

        assertThat(first.authenticatedUser().id()).isEqualTo(7);
        assertThat(first.userId()).isEqualTo(101);
        assertThat(second.authenticatedUser().id()).isEqualTo(8);
        assertThat(second.userId()).isEqualTo(201);
    }

    @Test
    void demoHeaderIsRejectedWhenDemoModeIsDisabled() {
        when(organizationService.findOrCreateUser(700, "Проверяющий"))
                .thenReturn(new OrganizationSecurityService.UserInfo(7, "Проверяющий"));

        assertThatThrownBy(() -> new CurrentUserService(organizationService, false).resolve(identity, "101"))
                .isInstanceOf(DemoActAsForbiddenException.class);
        verify(organizationService, never()).requireSandboxActor(7, 101);
    }

    @Test
    void foreignOrRealUserIsRejectedBySandboxLookup() {
        when(organizationService.findOrCreateUser(700, "Проверяющий"))
                .thenReturn(new OrganizationSecurityService.UserInfo(7, "Проверяющий"));
        when(organizationService.requireSandboxActor(7, 999)).thenThrow(new DemoActAsForbiddenException());

        assertThatThrownBy(() -> new CurrentUserService(organizationService, true).resolve(identity, "999"))
                .isInstanceOf(DemoActAsForbiddenException.class);
    }

    private OrganizationSecurityService.SandboxUserInfo actor(long userId, long memberId, long roleId) {
        return new OrganizationSecurityService.SandboxUserInfo(
                new OrganizationSecurityService.UserInfo(userId, "Персонаж " + userId),
                memberId, 900, List.of(new OrganizationSecurityService.RoleInfo(roleId, "ROLE", "Роль")), false);
    }
}
