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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.dto.ApproveJoinRequest;
import ru.sibvibe.approval.organization.dto.JoinByCodeRequest;
import ru.sibvibe.approval.organization.dto.JoinRequestResponse;
import ru.sibvibe.approval.organization.dto.JoinRequestView;
import ru.sibvibe.approval.organization.dto.MemberView;
import ru.sibvibe.approval.organization.service.JoinRequestService;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class JoinRequestController {

    private final JoinRequestService joinRequestService;

    public JoinRequestController(JoinRequestService joinRequestService) {
        this.joinRequestService = joinRequestService;
    }

    /** Заявку подаёт и отменяет реальная личность, а не демо-участник. */
    @PostMapping("/join-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinRequestResponse submit(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody JoinByCodeRequest request
    ) {
        return joinRequestService.submit(currentUser.authenticatedUser().id(), request.code());
    }

    @DeleteMapping("/join-requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable long id) {
        joinRequestService.cancel(currentUser.authenticatedUser().id(), id);
    }

    @GetMapping("/orgs/current/join-requests")
    public List<JoinRequestView> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String status
    ) {
        return joinRequestService.list(currentUser, status);
    }

    @PostMapping("/orgs/current/join-requests/{id}/approve")
    public MemberView approve(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long id,
            @Valid @RequestBody ApproveJoinRequest request
    ) {
        return joinRequestService.approve(currentUser, id, request.roleIds(), Boolean.TRUE.equals(request.isAdmin()));
    }

    @PostMapping("/orgs/current/join-requests/{id}/reject")
    public void reject(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable long id) {
        joinRequestService.reject(currentUser, id);
    }
}
