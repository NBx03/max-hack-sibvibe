package ru.sibvibe.approval.storage;

import java.io.InputStream;

/**
 * Порт файлового хранилища. Контракт между модулями:
 * "положить файл - получить ключ", "получить файл по ключу".
 *
 * Стейт-машина работает только с storage_key и не знает, что под портом -
 * MinIO, другое S3-совместимое хранилище или файловая система.
 * В базе хранится только ключ, бинарники в Postgres не попадают.
 */
public interface FileStorage {

    /**
     * @return storage_key для сохранения в Document
     */
    String put(InputStream content, String fileName, String contentType, long size);

    StoredFile get(String storageKey);

    void delete(String storageKey);

    record StoredFile(InputStream content, String fileName, String contentType, long size) {}
}
