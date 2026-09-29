package ru.sibvibe.approval.organization.dto;

/** Всё, что видит человек до одобрения заявки, — название компании. */
public record InvitePreviewResponse(String orgName) {
}
