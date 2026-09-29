package ru.sibvibe.approval.organization.service;

import org.springframework.http.HttpStatus;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.api.NotFoundException;

import java.util.Map;

/** Ошибки подключения компании: коды и статусы — из docs/API_CONTRACTS.md, тексты для пользователя. */
final class OrganizationErrors {

    private OrganizationErrors() {
    }

    static DomainException forbidden() {
        return new DomainException("FORBIDDEN", HttpStatus.FORBIDDEN, "Недостаточно прав");
    }

    static DomainException forbidden(String message) {
        return new DomainException("FORBIDDEN", HttpStatus.FORBIDDEN, message);
    }

    static DomainException notAdmin() {
        return new DomainException("NOT_ADMIN", HttpStatus.FORBIDDEN,
                "Действие доступно только администратору компании");
    }

    static NotFoundException notFound() {
        return new NotFoundException();
    }

    static DomainException validation(String message) {
        return new DomainException("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, message);
    }

    static DomainException alreadyMember() {
        return new DomainException("ALREADY_MEMBER", HttpStatus.CONFLICT, "Вы уже состоите в компании");
    }

    static DomainException joinRequestExists() {
        return new DomainException("JOIN_REQUEST_EXISTS", HttpStatus.CONFLICT,
                "У вас уже есть заявка на рассмотрении");
    }

    static DomainException invalidState(String message) {
        return new DomainException("INVALID_STATE", HttpStatus.CONFLICT, message);
    }

    static DomainException inviteNotFound() {
        return new DomainException("INVITE_NOT_FOUND", HttpStatus.NOT_FOUND, "Код не найден");
    }

    static DomainException inviteUsed() {
        return new DomainException("INVITE_USED", HttpStatus.CONFLICT, "Ссылка уже использована");
    }

    static DomainException inviteExpired() {
        return new DomainException("INVITE_EXPIRED", HttpStatus.CONFLICT, "Срок действия ссылки истёк");
    }

    static DomainException inviteRevoked() {
        return new DomainException("INVITE_REVOKED", HttpStatus.CONFLICT, "Ссылка отозвана");
    }

    static DomainException lastAdmin(String message) {
        return new DomainException("LAST_ADMIN", HttpStatus.CONFLICT, message);
    }

    static DomainException tooManyAttempts(long retryAfterSeconds) {
        return new DomainException("TOO_MANY_ATTEMPTS", HttpStatus.TOO_MANY_REQUESTS,
                "Слишком много попыток ввода кода. Попробуйте позже",
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}
