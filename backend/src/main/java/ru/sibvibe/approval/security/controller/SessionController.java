package ru.sibvibe.approval.security.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.security.dto.MeResponse;
import ru.sibvibe.approval.security.service.SessionViewService;

@RestController
@RequestMapping("/api/v1")
public class SessionController {

    private final SessionViewService sessionViewService;

    public SessionController(SessionViewService sessionViewService) {
        this.sessionViewService = sessionViewService;
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal CurrentUser currentUser) {
        return sessionViewService.me(currentUser);
    }
}
