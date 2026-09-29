package ru.sibvibe.approval.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RoleNameRequest(@NotBlank @Size(max = 100) String name) {
}
