package ru.sibvibe.approval.organization.dto;

import ru.sibvibe.approval.common.dto.RoleRef;

import java.time.Instant;
import java.util.List;

/** Личная ссылка: предъявительский токен, вступит тот, кто откроет первым. */
public record PersonalInviteView(long id, String link, Instant expiresAt, List<RoleRef> roles) {
    public PersonalInviteView {
        roles = List.copyOf(roles);
    }
}
