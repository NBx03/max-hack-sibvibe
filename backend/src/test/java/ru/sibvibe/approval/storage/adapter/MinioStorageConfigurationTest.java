package ru.sibvibe.approval.storage.adapter;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.sibvibe.approval.storage.FileStorage;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MinioStorageConfigurationTest {

    @Test
    void createsStorageFromExistingPropertiesWithoutConnectingToServer() {
        new ApplicationContextRunner()
                .withUserConfiguration(MinioStorageConfiguration.class)
                .withPropertyValues(
                        "minio.endpoint=http://127.0.0.1:1",
                        "minio.access-key=" + UUID.randomUUID(),
                        "minio.secret-key=" + UUID.randomUUID(),
                        "minio.bucket=storage-test")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MinioClient.class).hasSingleBean(FileStorage.class);
                    assertThat(context.getBean(FileStorage.class)).isInstanceOf(MinioFileStorage.class);
                });
    }
}
