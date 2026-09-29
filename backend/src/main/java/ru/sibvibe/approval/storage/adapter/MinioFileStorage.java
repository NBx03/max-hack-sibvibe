package ru.sibvibe.approval.storage.adapter;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sibvibe.approval.storage.FileStorage;
import ru.sibvibe.approval.storage.FileStorageException;
import ru.sibvibe.approval.storage.StoredFileNotFoundException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Потоковое хранилище: входной поток закрывает вызывающий код, как и content из get.
 * Удаление допустимо только после проверки вызывающим кодом, что ссылок на файл больше нет.
 */
@RequiredArgsConstructor
public class MinioFileStorage implements FileStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinioFileStorage.class);

    private static final String FILE_NAME_METADATA = "filename-base64";
    private static final Pattern STORAGE_KEY = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final MinioClient client;
    private final String bucket;

    @Override
    public String put(InputStream content, String fileName, String contentType, long size) {
        if (content == null || fileName == null || fileName.isBlank() || size < 0
                || contentType == null || contentType.isBlank()
                || contentType.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw new IllegalArgumentException("Некорректные параметры файла");
        }
        String encodedName = Base64.getEncoder().encodeToString(fileName.getBytes(StandardCharsets.UTF_8));
        // Метаданные S3 ограничены 2 КиБ; оставляем место для имени заголовка.
        if (encodedName.length() > 1800) {
            throw new IllegalArgumentException("Слишком длинное имя файла");
        }
        String storageKey = UUID.randomUUID().toString();
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .stream(content, size, -1)
                    .contentType(contentType)
                    .userMetadata(Map.of(FILE_NAME_METADATA, encodedName))
                    .build());
            return storageKey;
        } catch (Exception exception) {
            // Причину SDK не передаём наружу: она может содержать URL и заголовки запроса.
            // В лог сервера — краткая причина, иначе на стенде не понять, почему файл не сохранился.
            LOGGER.warn("MinIO: не удалось сохранить файл {}: {}", storageKey, describe(exception));
            throw new FileStorageException("Не удалось сохранить файл");
        }
    }

    @Override
    public StoredFile get(String storageKey) {
        validateKey(storageKey);
        GetObjectResponse response = null;
        try {
            response = client.getObject(GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
            String encodedName = response.headers().get("x-amz-meta-" + FILE_NAME_METADATA);
            String contentType = response.headers().get("Content-Type");
            long size = Long.parseLong(response.headers().get("Content-Length"));
            if (encodedName == null || contentType == null || contentType.isBlank() || size < 0) {
                throw new IllegalArgumentException();
            }
            String fileName = new String(Base64.getDecoder().decode(encodedName), StandardCharsets.UTF_8);
            if (fileName.isBlank()) {
                throw new IllegalArgumentException();
            }
            // Метаданные и поток относятся к одному GET: отдельный HEAD не нужен.
            return new StoredFile(response, fileName, contentType, size);
        } catch (ErrorResponseException exception) {
            closeOnFailure(response);
            if (isMissingObject(exception)) {
                LOGGER.warn("MinIO: файла {} нет в хранилище", storageKey);
                throw new StoredFileNotFoundException();
            }
            LOGGER.warn("MinIO: не удалось получить файл {}: {}", storageKey, describe(exception));
            throw new FileStorageException("Не удалось получить файл");
        } catch (Exception exception) {
            closeOnFailure(response);
            LOGGER.warn("MinIO: не удалось получить файл {}: {}", storageKey, describe(exception));
            throw new FileStorageException("Не удалось получить файл");
        }
    }

    @Override
    public void delete(String storageKey) {
        validateKey(storageKey);
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(storageKey).build());
        } catch (ErrorResponseException exception) {
            // DELETE идемпотентен; отсутствие бакета или прав при этом остаётся ошибкой.
            if (!isMissingObject(exception)) {
                LOGGER.warn("MinIO: не удалось удалить файл {}: {}", storageKey, describe(exception));
                throw new FileStorageException("Не удалось удалить файл");
            }
        } catch (Exception exception) {
            LOGGER.warn("MinIO: не удалось удалить файл {}: {}", storageKey, describe(exception));
            throw new FileStorageException("Не удалось удалить файл");
        }
    }

    private static void validateKey(String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Некорректный ключ файла");
        }
    }

    /**
     * Причина для лога без трассы SDK: код ошибки S3 или классы исключения и его первопричины
     * с их сообщениями (обрыв сети, отказ в соединении). Заголовков запроса здесь нет.
     */
    static String describe(Exception exception) {
        if (exception instanceof ErrorResponseException response && response.errorResponse() != null) {
            return "S3 " + response.errorResponse().code() + " (" + response.errorResponse().message() + ")";
        }
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String summary = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return root == exception ? summary : summary + "; причина " + root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static boolean isMissingObject(ErrorResponseException exception) {
        return "NoSuchKey".equals(exception.errorResponse().code());
    }

    private static void closeOnFailure(GetObjectResponse response) {
        if (response != null) {
            try {
                response.close();
            } catch (IOException ignored) {
                // Ошибка закрытия не должна скрыть исходную ошибку или раскрыть детали SDK.
            }
        }
    }
}
