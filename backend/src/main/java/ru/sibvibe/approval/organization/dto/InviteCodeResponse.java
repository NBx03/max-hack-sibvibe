package ru.sibvibe.approval.organization.dto;

import java.time.Instant;

/** Код компании и ссылка вида {@code https://max.ru/<бот>?startapp=c_<код>}. */
public record InviteCodeResponse(String code, String link, Instant createdAt) {
}
