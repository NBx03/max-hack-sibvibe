package ru.sibvibe.approval.organization.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RoleIdsRequest(@NotNull List<Long> roleIds) {
}
