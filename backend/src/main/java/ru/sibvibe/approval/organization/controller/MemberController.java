package ru.sibvibe.approval.organization.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.dto.AdminFlagRequest;
import ru.sibvibe.approval.organization.dto.MemberChangeView;
import ru.sibvibe.approval.organization.dto.MemberView;
import ru.sibvibe.approval.organization.dto.RoleIdsRequest;
import ru.sibvibe.approval.organization.service.MemberService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orgs/current/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    public List<MemberView> list(@AuthenticationPrincipal CurrentUser currentUser) {
        return memberService.list(currentUser);
    }

    @PutMapping("/{memberId}/roles")
    public MemberChangeView setRoles(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long memberId,
            @Valid @RequestBody RoleIdsRequest request
    ) {
        return memberService.setRoles(currentUser, memberId, request.roleIds());
    }

    @PutMapping("/{memberId}/admin")
    public MemberView setAdmin(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long memberId,
            @Valid @RequestBody AdminFlagRequest request
    ) {
        return memberService.setAdmin(currentUser, memberId, request.isAdmin());
    }

    @PostMapping("/{memberId}/disable")
    public MemberChangeView disable(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable long memberId) {
        return memberService.disable(currentUser, memberId);
    }
}
