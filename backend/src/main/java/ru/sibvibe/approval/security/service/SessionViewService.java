package ru.sibvibe.approval.security.service;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.service.OrganizationSecurityService;
import ru.sibvibe.approval.security.dto.MeResponse;
import ru.sibvibe.approval.security.dto.SandboxResponse;

import java.util.List;

/** Формирует ответы сессии, не раскрывая MAX id или внутренние признаки личности. */
@Service
public class SessionViewService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionViewService.class);

    private final OrganizationSecurityService organizationService;
    private final boolean demoMode;

    public SessionViewService(
            OrganizationSecurityService organizationService,
            @Value("${app.demo-mode:false}") boolean demoMode
    ) {
        this.organizationService = organizationService;
        this.demoMode = demoMode;
    }

    @Transactional(readOnly = true)
    public MeResponse me(CurrentUser currentUser) {
        long authenticatedId = currentUser.authenticatedUser().id();
        MeResponse.Membership membership = organizationService.findRealMembership(authenticatedId)
                .map(value -> new MeResponse.Membership(
                        value.orgId(), value.orgName(), roles(value.roles()), value.admin(), value.city(), value.timeZone()))
                .orElse(null);
        MeResponse.PendingJoinRequest pending = organizationService.findPendingJoin(authenticatedId)
                .map(value -> new MeResponse.PendingJoinRequest(value.id(), value.orgName(), value.createdAt()))
                .orElse(null);
        MeResponse.Sandbox sandbox = demoMode
                ? organizationService.findSandbox(authenticatedId)
                        .map(value -> new MeResponse.Sandbox(value.orgId(), value.orgName(), value.city(), value.timeZone()))
                        .orElse(null)
                : null;
        MeResponse.ActingAs actingAs = actingAs(currentUser);
        if (currentUser.startParam() != null) {
            // Только вид ссылки, без значения: токен личного приглашения — секрет (
            // проверяем, доходит ли параметр ссылки от клиента MAX до нас).
            LOGGER.debug("Запуск мини-приложения по ссылке: вид {}, компания {}, заявка {}",
                    startParamKind(currentUser.startParam()), membership != null, pending != null);
        }
        return new MeResponse(
                new UserRef(authenticatedId, currentUser.authenticatedUser().displayName()),
                membership, pending, demoMode, sandbox, actingAs, currentUser.startParam());
    }

    private static String startParamKind(String startParam) {
        int separator = startParam.indexOf('_');
        return separator > 0 && separator <= 3 ? startParam.substring(0, separator + 1) : "другой";
    }

    @Transactional(readOnly = true)
    public SandboxResponse sandbox(long ownerId) {
        return organizationService.findSandbox(ownerId)
                .map(this::sandbox)
                .orElseThrow(NotFoundException::new);
    }

    @Transactional
    public SandboxResponse resetSandbox(long ownerId, String timeZone) {
        return sandbox(organizationService.resetSandbox(ownerId, timeZone));
    }

    public boolean demoMode() {
        return demoMode;
    }

    private SandboxResponse sandbox(OrganizationSecurityService.SandboxInfo value) {
        return new SandboxResponse(value.orgId(), value.orgName(), value.users().stream()
                .map(user -> new SandboxResponse.SandboxUser(
                        user(user.user()), roles(user.roles()), user.admin()))
                .toList());
    }

    private UserRef user(OrganizationSecurityService.UserInfo value) {
        return new UserRef(value.id(), value.displayName());
    }

    private MeResponse.ActingAs actingAs(CurrentUser currentUser) {
        if (!currentUser.actingAsDemo()) {
            return null;
        }
        OrganizationSecurityService.SandboxUserInfo value = organizationService.requireSandboxActor(
                currentUser.authenticatedUser().id(), currentUser.userId());
        return new MeResponse.ActingAs(user(value.user()), roles(value.roles()), value.admin());
    }

    private List<RoleRef> roles(List<OrganizationSecurityService.RoleInfo> values) {
        return values.stream().map(value -> new RoleRef(value.id(), value.code(), value.name())).toList();
    }
}
