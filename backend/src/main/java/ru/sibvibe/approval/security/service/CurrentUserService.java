package ru.sibvibe.approval.security.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.sibvibe.approval.common.api.DemoActAsForbiddenException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.service.OrganizationSecurityService;
import ru.sibvibe.approval.security.MaxIdentityVerifier;

import java.util.Set;
import java.util.stream.Collectors;

/** Создаёт контекст запроса только из проверенной MAX-личности и данных БД. */
@Service
public class CurrentUserService {

    private final OrganizationSecurityService organizationService;
    private final boolean demoMode;

    public CurrentUserService(
            OrganizationSecurityService organizationService,
            @Value("${app.demo-mode:false}") boolean demoMode
    ) {
        this.organizationService = organizationService;
        this.demoMode = demoMode;
    }

    public CurrentUser resolve(MaxIdentityVerifier.VerifiedIdentity identity, String demoActAsHeader) {
        long maxUserId = parsePositive(identity.maxUserId());
        OrganizationSecurityService.UserInfo authenticated = organizationService.findOrCreateUser(
                maxUserId, identity.displayName());
        CurrentUser.UserIdentity authenticatedIdentity = new CurrentUser.UserIdentity(
                authenticated.id(), authenticated.displayName());

        if (demoActAsHeader != null) {
            if (!demoMode) {
                throw new DemoActAsForbiddenException();
            }
            long actorId = parseDemoActorId(demoActAsHeader);
            OrganizationSecurityService.SandboxUserInfo actor =
                    organizationService.requireSandboxActor(authenticated.id(), actorId);
            return new CurrentUser(
                    authenticatedIdentity,
                    new CurrentUser.UserIdentity(actor.user().id(), actor.user().displayName()),
                    actor.memberId(), actor.orgId(), roleIds(actor.roles()), actor.admin(), identity.startParam());
        }

        return organizationService.findRealMembership(authenticated.id())
                .map(membership -> new CurrentUser(
                        authenticatedIdentity,
                        authenticatedIdentity,
                        membership.memberId(), membership.orgId(), roleIds(membership.roles()),
                        membership.admin(), identity.startParam()))
                .orElseGet(() -> new CurrentUser(
                        authenticatedIdentity, authenticatedIdentity, null, null, Set.of(), false,
                        identity.startParam()));
    }

    private Set<Long> roleIds(java.util.List<OrganizationSecurityService.RoleInfo> roles) {
        return roles.stream().map(OrganizationSecurityService.RoleInfo::id).collect(Collectors.toUnmodifiableSet());
    }

    private long parsePositive(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("MAX user id must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid MAX user id", exception);
        }
    }

    private long parseDemoActorId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new DemoActAsForbiddenException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new DemoActAsForbiddenException();
        }
    }
}
