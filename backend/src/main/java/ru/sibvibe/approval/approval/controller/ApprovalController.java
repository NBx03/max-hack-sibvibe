package ru.sibvibe.approval.approval.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.approval.dto.DecisionRequest;
import ru.sibvibe.approval.approval.dto.RoutePreviewResponse;
import ru.sibvibe.approval.approval.dto.SubmitRequest;
import ru.sibvibe.approval.approval.service.DecisionService;
import ru.sibvibe.approval.approval.service.RoutePreviewService;
import ru.sibvibe.approval.approval.service.SubmissionService;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.dto.DocumentCardResponse;
import ru.sibvibe.approval.document.service.DocumentViewService;

/**
 * Согласование: предпросмотр маршрута, отправка, решение. Изменяющие запросы сначала фиксируются одной транзакцией,
 * а карточка читается уже после неё: ответ показывает то, что реально сохранено.
 */
@RestController
@RequestMapping("/api/v1")
public class ApprovalController {

    private final RoutePreviewService previewService;
    private final SubmissionService submissionService;
    private final DecisionService decisionService;
    private final DocumentViewService viewService;

    public ApprovalController(
            RoutePreviewService previewService,
            SubmissionService submissionService,
            DecisionService decisionService,
            DocumentViewService viewService
    ) {
        this.previewService = previewService;
        this.submissionService = submissionService;
        this.decisionService = decisionService;
        this.viewService = viewService;
    }

    @GetMapping("/documents/{id}/route-preview")
    public RoutePreviewResponse routePreview(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable long id) {
        return previewService.preview(currentUser, id);
    }

    @PostMapping("/documents/{id}/submit")
    public DocumentCardResponse submit(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long id,
            @Valid @RequestBody(required = false) SubmitRequest request
    ) {
        submissionService.submit(currentUser, id, request == null ? new SubmitRequest(null, null) : request);
        return viewService.card(id, currentUser);
    }

    /** Автор отзывает документ с согласования: документ возвращается к нему, маршрут начнётся заново. */
    @PostMapping("/documents/{id}/withdraw")
    public DocumentCardResponse withdraw(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable long id) {
        decisionService.withdraw(currentUser, id);
        return viewService.card(id, currentUser);
    }

    @PostMapping("/approval-steps/{stepId}/decision")
    public DocumentCardResponse decision(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long stepId,
            @Valid @RequestBody DecisionRequest request
    ) {
        long documentId = decisionService.decide(currentUser, stepId, request);
        return viewService.card(documentId, currentUser);
    }
}
