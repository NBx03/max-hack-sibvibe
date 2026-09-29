package ru.sibvibe.approval.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Город и часовой пояс компании: меняет администратор в разделе «Компания». */
public record CompanyLocationRequest(
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 64) String timeZone
) {
}
