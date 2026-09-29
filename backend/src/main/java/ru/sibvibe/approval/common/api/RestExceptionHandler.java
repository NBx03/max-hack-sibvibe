package ru.sibvibe.approval.common.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> domain(DomainException exception) {
        return ResponseEntity.status(exception.httpStatus())
                .body(new ApiError(exception.code(), exception.getMessage(), exception.details()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> fileTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("FILE_TOO_LARGE", "Файл или общий размер запроса слишком большой"));
    }

    /** Битый JSON, ошибки полей и параметры не того типа (например, нечисловой id в пути) — это 400, а не сбой сервера. */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class,
            TypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<ApiError> validationFailed() {
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_FAILED", "Проверьте правильность заполнения полей"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError("VALIDATION_FAILED", "Неподдерживаемый формат тела запроса"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        HttpStatusCode status = exception instanceof ErrorResponse errorResponse
                ? errorResponse.getStatusCode()
                : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            LOGGER.error("Непредвиденная ошибка при обработке REST-запроса", exception);
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ResponseEntity.status(status)
                    .body(new ApiError("NOT_FOUND", "Ресурс не найден"));
        }
        // Остальные 4xx Spring (405, 406…) — ошибка запроса, а не сбой сервиса:
        // «Сервис временно недоступен» на них уводил бы разбор не туда.
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return ResponseEntity.status(status)
                    .body(new ApiError("METHOD_NOT_ALLOWED", "Это действие по такому адресу не поддерживается"));
        }
        if (status.is4xxClientError()) {
            return ResponseEntity.status(status)
                    .body(new ApiError("BAD_REQUEST", "Некорректный запрос"));
        }
        return ResponseEntity.status(status)
                .body(new ApiError("INTERNAL_ERROR", "Сервис временно недоступен"));
    }
}
