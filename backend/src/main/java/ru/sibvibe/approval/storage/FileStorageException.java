package ru.sibvibe.approval.storage;

/** Ошибка хранилища без адресов, содержимого файлов и деталей провайдера. */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }
}
