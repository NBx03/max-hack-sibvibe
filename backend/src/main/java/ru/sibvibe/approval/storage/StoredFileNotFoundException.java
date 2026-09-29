package ru.sibvibe.approval.storage;

/** Позволяет сервису отличить отсутствие файла от недоступности хранилища. */
public class StoredFileNotFoundException extends FileStorageException {

    public StoredFileNotFoundException() {
        super("Файл не найден");
    }
}
