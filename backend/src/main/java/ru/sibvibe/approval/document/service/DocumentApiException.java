package ru.sibvibe.approval.document.service;

import org.springframework.http.HttpStatus;
import ru.sibvibe.approval.common.api.DomainException;

import java.util.Map;

public final class DocumentApiException extends DomainException {

    private DocumentApiException(String code, HttpStatus status, String message) {
        super(code, status, message);
    }

    private DocumentApiException(String code, HttpStatus status, String message, Map<String, Object> details) {
        super(code, status, message, details);
    }

    /**
     * Та же ошибка, но про приложение: {@code details.file = ATTACHMENT} и имя файла. Коды FILE_* у основного
     * файла экран показывает у его зоны и убирает файл — ошибку приложения он покажет у списка приложений.
     */
    public DocumentApiException aboutAttachment(String fileName) {
        return new DocumentApiException(code(), httpStatus(), getMessage(), Map.of("file", "ATTACHMENT", "fileName", fileName));
    }

    /** Та же ошибка, но про все файлы версии вместе (30 МБ): {@code details.file = VERSION} — основной файл не виноват. */
    public DocumentApiException aboutVersion() {
        return new DocumentApiException(code(), httpStatus(), getMessage(), Map.of("file", "VERSION"));
    }

    public static DocumentApiException validation(String message) {
        return new DocumentApiException("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, message);
    }

    public static DocumentApiException invalidState(String message) {
        return new DocumentApiException("INVALID_STATE", HttpStatus.CONFLICT, message);
    }

    public static DocumentApiException fileTypeNotAllowed(String message) {
        return new DocumentApiException("FILE_TYPE_NOT_ALLOWED", HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Содержимое основного файла не читается: пустой, битый PDF, опасный DOCX. Отдельный код, чтобы экран показал ошибку
     * у зоны файла и убрал его, как у FILE_TYPE_NOT_ALLOWED и FILE_TOO_LARGE.
     */
    public static DocumentApiException fileInvalid(String message) {
        return new DocumentApiException("FILE_INVALID", HttpStatus.BAD_REQUEST, message);
    }

    public static DocumentApiException fileTooLarge(String message) {
        return new DocumentApiException("FILE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    public static DocumentApiException forbidden(String message) {
        return new DocumentApiException("FORBIDDEN", HttpStatus.FORBIDDEN, message);
    }
}
