package ru.sibvibe.approval.organization.dto;

import jakarta.validation.constraints.NotNull;

/** Сделать роль в шаблоне вида документа обязательной или нет. */
public record RouteMandatoryRequest(@NotNull Boolean mandatory) {
}
