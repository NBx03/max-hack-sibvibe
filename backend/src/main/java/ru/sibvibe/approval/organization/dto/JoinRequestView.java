package ru.sibvibe.approval.organization.dto;

import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;

import java.time.Instant;
import java.util.List;

/**
 * {@code previousRoles} — роли, с которыми человек уже был в компании и был исключён: экран принятия отмечает их
 * заранее, администратору остаётся «Принять». Пусто — человек новый.
 */
public record JoinRequestView(long id, UserRef user, String status, Instant createdAt, List<RoleRef> previousRoles) {
}
