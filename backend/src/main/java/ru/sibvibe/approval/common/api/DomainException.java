package ru.sibvibe.approval.common.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Ошибка бизнес-операции с контрактным HTTP-ответом. */
public class DomainException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;
    private final Map<String, Object> details;

    public DomainException(String code, HttpStatus httpStatus, String message) {
        this(code, httpStatus, message, Map.of());
    }

    public DomainException(String code, HttpStatus httpStatus, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public Map<String, Object> details() {
        return details;
    }
}
