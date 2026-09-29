package ru.sibvibe.approval.organization.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Принятие заявки: роли назначаются в том же запросе. */
public record ApproveJoinRequest(@NotNull List<Long> roleIds, Boolean isAdmin) {
}
