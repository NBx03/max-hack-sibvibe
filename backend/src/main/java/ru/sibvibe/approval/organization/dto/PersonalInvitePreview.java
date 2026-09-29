package ru.sibvibe.approval.organization.dto;

import ru.sibvibe.approval.common.dto.RoleRef;

import java.time.Instant;
import java.util.List;

/** Что увидит человек, открывший личную ссылку: компания и роли, которые он получит. */
public record PersonalInvitePreview(String orgName, List<RoleRef> roles, Instant expiresAt) {
    public PersonalInvitePreview {
        roles = List.copyOf(roles);
    }
}
