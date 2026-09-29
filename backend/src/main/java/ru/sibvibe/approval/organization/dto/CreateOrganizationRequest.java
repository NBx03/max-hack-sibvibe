package ru.sibvibe.approval.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ИНН необязательный и не проверяется: по контракту это только справочное поле. Город и часовой пояс —
 * по выбору на экране; не переданы (старый клиент) — Москва.
 */
public record CreateOrganizationRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String inn,
        @Size(max = 64) String city,
        @Size(max = 64) String timeZone
) {
    public CreateOrganizationRequest(String name, String inn) {
        this(name, inn, null, null);
    }
}
