package ru.sibvibe.approval.security.dto;

import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;

import java.time.Instant;
import java.util.List;

public record MeResponse(
        UserRef user,
        Membership membership,
        PendingJoinRequest pendingJoinRequest,
        boolean demoMode,
        Sandbox sandbox,
        ActingAs actingAs,
        String startParam
) {
    /** city, timeZone — город и часовой пояс компании: по ним экран считает «сегодня» (дата в записке, периоды). */
    public record Membership(long orgId, String orgName, List<RoleRef> roles, boolean isAdmin, String city, String timeZone) {
        public Membership {
            roles = List.copyOf(roles);
        }
    }

    public record PendingJoinRequest(long id, String orgName, Instant createdAt) {
    }

    public record Sandbox(long orgId, String orgName, String city, String timeZone) {
    }

    public record ActingAs(UserRef user, List<RoleRef> roles, boolean isAdmin) {
        public ActingAs {
            roles = List.copyOf(roles);
        }
    }
}
