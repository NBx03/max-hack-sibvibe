package ru.sibvibe.approval.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ru.sibvibe.approval.document.entity.Document;

import java.util.Map;

/**
 * Документ-форма: содержимое заполняется прямо в приложении, файла нет. content — значения полей
 * схемы типа; они же и есть поля для правил, поэтому модель не нужна. Тип обязателен: определять его
 * по тексту незачем — человек сам выбрал, что заполняет. title пустой — по заголовку записки или типу.
 */
public record CreateFormDocumentRequest(
        @NotNull @Positive Long documentTypeId,
        @Size(max = 255) String title,
        @NotNull Document.Visibility visibility,
        boolean containsSensitive,
        @NotNull @Size(max = 100) Map<@NotBlank String, @Size(max = 10_000) String> content
) {
}
