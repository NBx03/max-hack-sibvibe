package ru.sibvibe.approval.common.api;

import org.springframework.http.HttpStatus;

/** Запрошенный демо-персонаж не принадлежит песочнице вызывающего. */
public class DemoActAsForbiddenException extends DomainException {
    public DemoActAsForbiddenException() {
        super(
                "DEMO_ACT_AS_FORBIDDEN",
                HttpStatus.FORBIDDEN,
                "Нельзя действовать от имени этого пользователя");
    }
}
