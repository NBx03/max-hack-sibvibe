package ru.sibvibe.approval.document.dto;

/** Измеримый эффект согласования внутри текущей компании. */
public record OrganizationMetricsResponse(
        Double medianApprovalHours,
        Double returnRate,
        long documentsCount
) {
}
