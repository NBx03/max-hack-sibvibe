package ru.sibvibe.approval.document.dto;

import java.util.List;

/**
 * Итог «Исправить в файле»: карточка с новой версией и какие поля вписаны в файл. Поля,
 * которые вписать не удалось, — с причиной: их человек исправляет в самом документе.
 */
public record FileCorrectionResponse(
        DocumentCardResponse card,
        List<String> applied,
        List<NotApplied> notApplied
) {
    public record NotApplied(String field, String message) {}
}
