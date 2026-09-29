package ru.sibvibe.approval.approval.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.organization.service.RoleService;
import ru.sibvibe.approval.organization.service.RouteTemplateService;
import ru.sibvibe.approval.organization.service.RouteTemplateService.TemplateRow;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Загружает шаблон маршрута компании и носителей ролей и разрешает их в людей. Одна и та же логика нужна
 * предпросмотру и отправке, поэтому она здесь: предпросмотр показывает ровно то, что сделает отправка.
 */
@Service
public class RoutePlanner {

    private final RouteTemplateService templates;
    private final RoleService roles;
    private final RouteResolver resolver = new RouteResolver();

    public RoutePlanner(RouteTemplateService templates, RoleService roles) {
        this.templates = templates;
        this.roles = roles;
    }

    /** Не только читает: если у компании для типа ещё нет маршрута, шаблон по умолчанию создаётся. */
    @Transactional
    public RouteResolver.ResolvedRoute plan(long orgId, long documentTypeId, long authorId) {
        List<TemplateRow> template = templates.templateFor(orgId, documentTypeId);
        if (template.isEmpty()) {
            throw ApprovalErrors.routeNotConfigured();
        }
        Set<Long> roleIds = new LinkedHashSet<>();
        template.forEach(row -> roleIds.add(row.roleId()));
        Map<Long, RoleRef> roleRefs = roles.rolesById(orgId, roleIds);
        Map<Long, List<UserRef>> carriers = new HashMap<>();
        for (Long roleId : roleIds) {
            carriers.put(roleId, roles.carriersOf(orgId, roleId));
        }
        return resolver.resolve(template, roleRefs, carriers, authorId);
    }
}
