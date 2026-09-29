package ru.sibvibe.approval.security.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.service.InviteService;
import ru.sibvibe.approval.security.dto.MeResponse;
import ru.sibvibe.approval.security.service.SessionViewService;

/**
 * Вступление по личной ссылке. Контракт отвечает здесь тем же, что и {@code GET /me}, а {@link MeResponse}
 * принадлежит security: organization не вправе его вызывать (ARCHITECTURE.md, раздел 2). Поэтому
 * эндпоинт живёт в security, а вся логика вступления — в {@link InviteService}.
 */
@RestController
@RequestMapping("/api/v1/invites/personal")
public class PersonalInviteAcceptController {

    private final InviteService inviteService;
    private final SessionViewService sessionViewService;

    public PersonalInviteAcceptController(InviteService inviteService, SessionViewService sessionViewService) {
        this.inviteService = inviteService;
        this.sessionViewService = sessionViewService;
    }

    /** Вступление — действие реальной личности; ответ читается уже после фиксации вступления. */
    @PostMapping("/{token}/accept")
    public MeResponse accept(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String token) {
        inviteService.acceptPersonal(currentUser.authenticatedUser().id(), token);
        return sessionViewService.me(currentUser);
    }
}
