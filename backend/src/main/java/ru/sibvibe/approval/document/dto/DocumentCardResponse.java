package ru.sibvibe.approval.document.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.entity.DocumentFile;
import ru.sibvibe.approval.document.service.DisplayStatus;
import ru.sibvibe.approval.document.service.VersionChangesService;
import ru.sibvibe.approval.rules.RuleEngine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DocumentCardResponse(
        long id,
        String title,
        TypeRef type,
        Document.Status status,
        /** Как статус видит человек: «На утверждении», «Утверждён». Показывать — его, а не status. */
        DisplayStatus displayStatus,
        Document.Visibility visibility,
        UserRef author,
        int currentVersionNo,
        List<VersionView> versions,
        CheckResult check,
        RouteView route,
        Permissions permissions,
        List<Long> myActiveStepIds,
        /** Что изменилось в текущей версии по сравнению с предыдущей; {@code null} у первой версии. */
        VersionChangesService.Changes changes
) {
    /** autoDetected — тип выбрала модель по тексту при загрузке («Определить автоматически»). */
    /** code — по нему экран узнаёт «Другой документ» (GENERIC): вид вне шаблонов компании. */
    /**
     * aiTypeId, aiTypeName — вид, который определил ИИ, если он не совпадает с выбранным: автор
     * видит подсказку, согласующие — «Вид выбран автором · ИИ определил: …». null — совпадает или ИИ вид не определял.
     */
    public record TypeRef(long id, String code, String name, boolean isGeneric, boolean autoDetected,
                          Long aiTypeId, String aiTypeName) {}

    /**
     * withdrawnAt — автор отозвал эту версию с согласования; {@code null} — не отзывалась.
     * content — содержимое документа-формы, заполненного в приложении; {@code null} у версии с файлом.
     * issues — замечания проверки этой версии, снимок на момент проверки; {@code null} — версия не проверялась.
     * Нужны, чтобы у прошлой версии было видно, с чем её отправляли.
     */
    public record VersionView(
            int versionNo,
            boolean containsSensitive,
            Instant createdAt,
            UserRef createdBy,
            List<FileRef> files,
            Instant withdrawnAt,
            Map<String, String> content,
            List<ValidationIssueView> issues
    ) {}

    public record FileRef(
            long id,
            DocumentFile.Kind kind,
            String fileName,
            String mimeType,
            long size,
            String downloadUrl,
            Instant downloadExpiresAt
    ) {}

    /** textMissing — в основном файле нет текста (скан): ИИ его не читал, поля вводятся вручную. */
    public record CheckResult(
            int versionNo,
            String status,
            boolean modelAvailable,
            String summary,
            List<FieldValue> fields,
            List<ValidationIssueView> issues,
            boolean textMissing
    ) {}

    /**
     * source MODEL — значение нашла модель в тексте файла (quote — откуда), MANUAL — ввёл автор.
     * fileValue — что было в файле, если автор заменил найденное значение вручную, не меняя файл:
     * согласующий видит и то и другое, ручная правка не прячется.
     */
    public record FieldValue(
            String name,
            String value,
            String source,
            String quote,
            Integer page,
            String fileValue
    ) {
        // Сохранённые поля читаются из JSON версии: создатель для Jackson — канонический,
        // конструктор ниже — для кода, где ручной правки не было.
        @JsonCreator
        public FieldValue {
        }

        public FieldValue(String name, String value, String source, String quote, Integer page) {
            this(name, value, source, quote, page, null);
        }
    }

    public record ValidationIssueView(
            long ruleId,
            String fieldName,
            String message,
            RuleEngine.Severity severity,
            RuleEngine.RuleKind kind,
            String sourceTitle,
            String sourceUrl,
            String sourceRef,
            String quote,
            Integer page
    ) {}

    public record RouteView(
            int versionNo,
            List<StageView> stages,
            List<StepView> history
    ) {}

    public record StageView(int stageOrder, String state, List<StepView> steps) {}

    public record StepView(
            long id,
            int versionNo,
            int stageOrder,
            RoleRef role,
            UserRef approver,
            String origin,
            String decision,
            /** APPROVAL — согласование, ENDORSEMENT — утверждение (последний шаг, D4). */
            String kind,
            String comment,
            Instant activatedAt,
            Instant decidedAt,
            String autoReason
    ) {}

    /**
     * canCorrectFile — исправленные поля можно вписать прямо в основной файл (DOCX) новой версией:
     * автор, черновик или возвращённый документ, основной файл — DOCX.
     */
    public record Permissions(
            boolean canEditFields,
            boolean canUploadVersion,
            boolean canSubmit,
            boolean canAddApprover,
            boolean canCorrectFile,
            boolean canWithdraw
    ) {}
}
