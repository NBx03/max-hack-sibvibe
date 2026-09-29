package ru.sibvibe.approval.organization.dto;

import jakarta.validation.constraints.NotNull;

/** Права администратора — флаг участника, а не роль. */
public record AdminFlagRequest(@NotNull Boolean isAdmin) {
}
