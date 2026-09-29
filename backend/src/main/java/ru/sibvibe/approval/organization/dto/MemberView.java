package ru.sibvibe.approval.organization.dto;

import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;

import java.time.Instant;
import java.util.List;

/** Участник компании: ответ approve, PUT .../roles, PUT .../admin и элемент списка участников. */
public record MemberView(
        long memberId,
        UserRef user,
        String status,
        List<RoleRef> roles,
        boolean isAdmin,
        Instant joinedAt
) {
    public MemberView {
        roles = List.copyOf(roles);
    }
}
