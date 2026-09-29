package ru.sibvibe.approval.document.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpdateFieldsRequest(
        @NotNull @Size(max = 100) Map<@NotNull String, @Size(max = 10_000) String> fields
) {
}
