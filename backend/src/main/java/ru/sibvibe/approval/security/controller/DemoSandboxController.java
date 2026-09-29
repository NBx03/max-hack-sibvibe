package ru.sibvibe.approval.security.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import ru.sibvibe.approval.security.dto.SandboxRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.security.dto.SandboxResponse;
import ru.sibvibe.approval.security.service.SessionViewService;

@RestController
@RequestMapping("/api/v1/demo/sandbox")
public class DemoSandboxController {

    private final SessionViewService sessionViewService;

    public DemoSandboxController(SessionViewService sessionViewService) {
        this.sessionViewService = sessionViewService;
    }

    @GetMapping
    public SandboxResponse get(@AuthenticationPrincipal CurrentUser currentUser) {
        requireDemoMode();
        return sessionViewService.sandbox(currentUser.authenticatedUser().id());
    }

    @PostMapping
    public SandboxResponse reset(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody(required = false) SandboxRequest request
    ) {
        requireDemoMode();
        // Пояс устройства проверяющего: «сегодня» в демо-компании — по его часам. Нет тела — Москва.
        return sessionViewService.resetSandbox(currentUser.authenticatedUser().id(), request == null ? null : request.timeZone());
    }

    private void requireDemoMode() {
        if (!sessionViewService.demoMode()) {
            throw new NotFoundException();
        }
    }
}
