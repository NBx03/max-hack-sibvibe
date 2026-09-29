package ru.sibvibe.approval.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Новая версия документа-формы: правка формы после возврата. content — содержимое целиком. */
public record CreateFormVersionRequest(
        @NotNull @Size(max = 100) Map<@NotBlank String, @Size(max = 10_000) String> content,
        boolean containsSensitive
) {
}
