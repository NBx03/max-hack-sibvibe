package ru.sibvibe.approval.common.api;

import org.springframework.http.HttpStatus;

/** Ресурс отсутствует или недоступен в текущей конфигурации. */
public class NotFoundException extends DomainException {
    public NotFoundException() {
        super("NOT_FOUND", HttpStatus.NOT_FOUND, "Ресурс не найден");
    }
}
