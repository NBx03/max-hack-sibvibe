package ru.sibvibe.approval.security.dto;

import jakarta.validation.constraints.Size;

/** Создание демо-компании: часовой пояс устройства; не из России или не передан — Москва. */
public record SandboxRequest(@Size(max = 64) String timeZone) {
}
