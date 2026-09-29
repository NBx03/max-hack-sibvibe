package ru.sibvibe.approval.organization.service;

import ru.sibvibe.approval.common.security.CurrentUser;

/**
 * Проверки прав, которые сервисы делают до любой работы с данными.
 *
 * {@link CurrentUser} строится фильтром из базы на каждом запросе, поэтому снятая роль
 * или права администратора действуют сразу (docs/DESIGN-DECISIONS.md, проверка №8).
 */
final class OrganizationAccess {

    private OrganizationAccess() {
    }

    /** Любой активный участник компании: возвращает id компании. */
    static long requireMemberOrg(CurrentUser user) {
        if (user.orgId() == null) {
            throw OrganizationErrors.forbidden();
        }
        return user.orgId();
    }

    /**
     * Администратор компании: возвращает id компании. Дальше сервисы ищут заявки, роли и
     * участников только внутри этой компании: одного права ADMIN для этого недостаточно.
     */
    static long requireAdminOrg(CurrentUser user) {
        long orgId = requireMemberOrg(user);
        if (!user.admin()) {
            throw OrganizationErrors.notAdmin();
        }
        return orgId;
    }
}
