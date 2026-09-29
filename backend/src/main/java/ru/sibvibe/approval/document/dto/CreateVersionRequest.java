package ru.sibvibe.approval.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CreateVersionRequest(
        @Size(max = 100) Map<@NotBlank String, @Size(max = 10_000) String> fields,
        boolean containsSensitive,
        @NotNull List<@Positive Long> keepFileIds
) {
}
