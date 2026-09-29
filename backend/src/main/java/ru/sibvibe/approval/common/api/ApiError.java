package ru.sibvibe.approval.common.api;

import java.util.Map;

/** Единый формат ошибок REST API. */
public record ApiError(String code, String message, Map<String, Object> details) {
    public ApiError(String code, String message) {
        this(code, message, Map.of());
    }
}
