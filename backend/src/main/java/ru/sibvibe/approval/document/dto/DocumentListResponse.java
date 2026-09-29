package ru.sibvibe.approval.document.dto;

import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.service.DisplayStatus;

import java.time.Instant;
import java.util.List;

public record DocumentListResponse(
        List<Item> items,
        int page,
        int size,
        long total
) {
    public record Item(
            long id,
            String title,
            TypeRef type,
            Document.Status status,
            /** Как статус видит человек: «На утверждении», «Утверждён». Показывать — его, а не status. */
            DisplayStatus displayStatus,
            UserRef author,
            int currentVersionNo,
            Integer currentStage,
            Instant updatedAt,
            Instant waitingSince,
            /** У «Другого документа» — вид, как его назвал сам документ («Договор аренды»); null — вид из шаблонов. */
            RecognizedKind recognizedKind
    ) {}

    public record TypeRef(long id, String name) {}

    /** fromAi — вид нашёл ИИ (значок ✦), false — автор исправил вручную. */
    public record RecognizedKind(String value, boolean fromAi) {}
}
