package ru.sibvibe.approval.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinByCodeRequest(@NotBlank @Size(max = 64) String code) {
}
