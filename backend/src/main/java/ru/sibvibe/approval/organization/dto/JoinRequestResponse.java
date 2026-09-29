package ru.sibvibe.approval.organization.dto;

import java.time.Instant;

public record JoinRequestResponse(long id, String orgName, String status, Instant createdAt) {
}
