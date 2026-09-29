package ru.sibvibe.approval.document.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.dto.OrganizationMetricsResponse;
import ru.sibvibe.approval.document.service.DocumentMetricsService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/orgs/current")
public class DocumentMetricsController {

    private final DocumentMetricsService service;

    public DocumentMetricsController(DocumentMetricsService service) {
        this.service = service;
    }

    @GetMapping("/metrics")
    public OrganizationMetricsResponse metrics(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return service.metrics(currentUser, from, to);
    }
}
