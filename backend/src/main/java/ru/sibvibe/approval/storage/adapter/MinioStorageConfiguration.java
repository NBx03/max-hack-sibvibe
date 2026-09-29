package ru.sibvibe.approval.storage.adapter;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.sibvibe.approval.storage.FileStorage;

@Configuration(proxyBeanMethods = false)
public class MinioStorageConfiguration {

    @Bean
    MinioClient minioClient(@Value("${minio.endpoint}") String endpoint,
                            @Value("${minio.access-key}") String accessKey,
                            @Value("${minio.secret-key}") String secretKey) {
        try {
            return MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
        } catch (IllegalArgumentException exception) {
            // Исключение SDK может содержать значение настройки, включая адрес сервера.
            throw new IllegalStateException("Некорректная конфигурация файлового хранилища");
        }
    }

    @Bean
    FileStorage fileStorage(MinioClient minioClient, @Value("${minio.bucket}") String bucket) {
        // Бакет создаёт minio-init в compose.yaml, запуск приложения не меняет инфраструктуру.
        return new MinioFileStorage(minioClient, bucket);
    }
}
