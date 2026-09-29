package ru.sibvibe.approval.organization.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.dto.InviteCodeResponse;
import ru.sibvibe.approval.organization.dto.InvitePreviewResponse;
import ru.sibvibe.approval.organization.dto.PersonalInvitePreview;
import ru.sibvibe.approval.organization.dto.PersonalInviteView;
import ru.sibvibe.approval.organization.dto.RoleIdsRequest;
import ru.sibvibe.approval.organization.service.InviteService;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @GetMapping("/orgs/current/invite-code")
    public InviteCodeResponse code(@AuthenticationPrincipal CurrentUser currentUser) {
        return inviteService.currentCode(currentUser);
    }

    @PostMapping("/orgs/current/invite-code/regenerate")
    public InviteCodeResponse regenerate(@AuthenticationPrincipal CurrentUser currentUser) {
        return inviteService.regenerate(currentUser);
    }

    /** Человек видит только название компании; попытки считаются на реальную личность. */
    @GetMapping("/invites/code/{code}")
    public InvitePreviewResponse previewCode(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable String code
    ) {
        return inviteService.preview(currentUser.authenticatedUser().id(), code);
    }

    @PostMapping("/orgs/current/personal-invites")
    @ResponseStatus(HttpStatus.CREATED)
    public PersonalInviteView createPersonal(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody RoleIdsRequest request
    ) {
        return inviteService.createPersonal(currentUser, request.roleIds());
    }

    @GetMapping("/orgs/current/personal-invites")
    public List<PersonalInviteView> personal(@AuthenticationPrincipal CurrentUser currentUser) {
        return inviteService.listPersonal(currentUser);
    }

    @DeleteMapping("/orgs/current/personal-invites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokePersonal(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable long id) {
        inviteService.revokePersonal(currentUser, id);
    }

    @GetMapping("/invites/personal/{token}")
    public PersonalInvitePreview previewPersonal(@PathVariable String token) {
        return inviteService.previewPersonal(token);
    }
}
