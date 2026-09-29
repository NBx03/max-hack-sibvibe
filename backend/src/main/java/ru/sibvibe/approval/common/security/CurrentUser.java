package ru.sibvibe.approval.common.security;

import java.util.Set;

/** Проверенная реальная личность и эффективный пользователь текущего запроса. */
public record CurrentUser(
        UserIdentity authenticatedUser,
        UserIdentity effectiveUser,
        Long memberId,
        Long orgId,
        Set<Long> roleIds,
        boolean admin,
        String startParam
) {
    public CurrentUser {
        roleIds = Set.copyOf(roleIds);
    }

    public long userId() {
        return effectiveUser.id();
    }

    public boolean actingAsDemo() {
        return authenticatedUser.id() != effectiveUser.id();
    }

    public record UserIdentity(long id, String displayName) {
    }
}
