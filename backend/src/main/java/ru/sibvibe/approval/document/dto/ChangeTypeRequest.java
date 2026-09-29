package ru.sibvibe.approval.document.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Новый тип черновика — если модель определила вид неверно. */
public record ChangeTypeRequest(@NotNull @Positive Long documentTypeId) {
}
