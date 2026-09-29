package ru.sibvibe.approval.document.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.dto.CompanyRulesResponse;
import ru.sibvibe.approval.document.dto.RuleSettingRequest;
import ru.sibvibe.approval.document.service.CompanyRulesService;

/** Правила проверки компании: смотреть — участник, менять и сбрасывать к шаблону — администратор. */
@RestController
@RequestMapping("/api/v1")
public class CompanyRulesController {

    private final CompanyRulesService service;

    public CompanyRulesController(CompanyRulesService service) {
        this.service = service;
    }

    @GetMapping("/orgs/current/rules")
    public CompanyRulesResponse rules(@AuthenticationPrincipal CurrentUser currentUser) {
        return service.rules(currentUser);
    }

    @PutMapping("/orgs/current/rules/{ruleId}")
    public CompanyRulesResponse update(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long ruleId,
            @Valid @RequestBody RuleSettingRequest request
    ) {
        return service.update(currentUser, ruleId, request);
    }

    /** «Сбросить к типовому». */
    @DeleteMapping("/orgs/current/rules/{ruleId}")
    public CompanyRulesResponse reset(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable long ruleId) {
        return service.reset(currentUser, ruleId);
    }
}
