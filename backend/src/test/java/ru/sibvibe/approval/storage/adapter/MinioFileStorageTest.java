package ru.sibvibe.approval.storage.adapter;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import ru.sibvibe.approval.storage.FileStorage;
import ru.sibvibe.approval.storage.FileStorageException;
import ru.sibvibe.approval.storage.StoredFileNotFoundException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MinioFileStorageTest {

    private static final String BUCKET = "storage-test";
    private static final String KEY = "73925a30-c0be-4bc7-8463-01b823e6373d";
    private final MinioClient client = mock(MinioClient.class);
    private final FileStorage storage = new MinioFileStorage(client, BUCKET);

    @Test
    void putUsesIndependentKeysAndPreservesUnicodeNameAsMetadata() throws Exception {
        String name = "../../папка\\тестовый документ.pdf";
        byte[] bytes = {1, 2, 3};
        InputStream content = spy(new ByteArrayInputStream(bytes));

        String first = storage.put(content, name, "application/pdf", bytes.length);
        String second = storage.put(new ByteArrayInputStream(bytes), name, "application/pdf", bytes.length);

        assertThat(first).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(second).isNotEqualTo(first);
        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client, times(2)).putObject(args.capture());
        PutObjectArgs firstArgs = args.getAllValues().getFirst();
        assertThat(firstArgs.object()).isEqualTo(first);
        assertThat(firstArgs.bucket()).isEqualTo(BUCKET);
        assertThat(firstArgs.contentType()).isEqualTo("application/pdf");
        assertThat(firstArgs.objectSize()).isEqualTo(bytes.length);
        assertThat(firstArgs.genHeaders().get("x-amz-meta-filename-base64")).containsExactly(encode(name));
        assertThat(firstArgs.stream().readAllBytes()).containsExactly(bytes);
        verify(content, never()).close();
    }

    @Test
    void getReturnsMetadataAndTransfersStreamOwnershipToCaller() throws Exception {
        String name = "тестовый документ.pdf";
        InputStream content = spy(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(response(name, "3", content));

        FileStorage.StoredFile file = storage.get(KEY);

        assertThat(file.fileName()).isEqualTo(name);
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.size()).isEqualTo(3);
        verify(content, never()).close();
        try (InputStream downloaded = file.content()) {
            assertThat(downloaded.readAllBytes()).containsExactly(1, 2, 3);
        }
        verify(content).close();
        ArgumentCaptor<GetObjectArgs> args = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(client).getObject(args.capture());
        assertThat(args.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(args.getValue().object()).isEqualTo(KEY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../file", "folder/file", "folder\\file", "https://example.invalid/file",
            "%2e%2e%2ffile", "73925a30-c0be-4bc7-8463-01b823e6373d/other"})
    void invalidKeysNeverReachMinio(String key) {
        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void invalidUploadParametersNeverReachMinio() {
        InputStream content = InputStream.nullInputStream();
        assertThatThrownBy(() -> storage.put(null, "test.pdf", "application/pdf", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(content, null, "application/pdf", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(content, " ", "application/pdf", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(content, "test.pdf", "application/pdf", -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(content, "test.pdf", "application/pdf\r\nX-Test: value", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put(content, "a".repeat(1801), "application/pdf", 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void zeroLengthFileIsSupported() throws Exception {
        storage.put(InputStream.nullInputStream(), "empty.pdf", "application/pdf", 0);
        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(args.capture());
        assertThat(args.getValue().objectSize()).isZero();
        when(client.getObject(any(GetObjectArgs.class)))
                .thenReturn(response("empty.pdf", "0", InputStream.nullInputStream()));
        FileStorage.StoredFile file = storage.get(KEY);
        try (InputStream content = file.content()) {
            assertThat(file.size()).isZero();
            assertThat(content.read()).isEqualTo(-1);
        }
    }

    @Test
    void missingObjectHasProviderIndependentException() throws Exception {
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(error("NoSuchKey"));
        assertThatThrownBy(() -> storage.get(KEY))
                .isExactlyInstanceOf(StoredFileNotFoundException.class)
                .hasMessage("Файл не найден").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NoSuchBucket", "AccessDenied", "InternalError"})
    void infrastructureErrorsAreNotReportedAsMissingFiles(String code) throws Exception {
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(error(code));
        doThrow(error(code)).when(client).removeObject(any(RemoveObjectArgs.class));
        assertThatThrownBy(() -> storage.get(KEY)).isExactlyInstanceOf(FileStorageException.class)
                .hasMessage("Не удалось получить файл").hasNoCause();
        assertThatThrownBy(() -> storage.delete(KEY)).isExactlyInstanceOf(FileStorageException.class)
                .hasMessage("Не удалось удалить файл").hasNoCause();
    }

    @Test
    void transportErrorsDoNotExposeProviderDetails() throws Exception {
        IOException failure = new IOException("http://storage.internal.invalid/private-object");
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(failure);
        when(client.getObject(any(GetObjectArgs.class))).thenThrow(failure);
        doThrow(failure).when(client).removeObject(any(RemoveObjectArgs.class));
        assertThatThrownBy(() -> storage.put(InputStream.nullInputStream(), "empty.pdf", "application/pdf", 0))
                .isExactlyInstanceOf(FileStorageException.class).hasMessage("Не удалось сохранить файл").hasNoCause();
        assertThatThrownBy(() -> storage.get(KEY)).isExactlyInstanceOf(FileStorageException.class)
                .hasMessage("Не удалось получить файл").hasNoCause();
        assertThatThrownBy(() -> storage.delete(KEY)).isExactlyInstanceOf(FileStorageException.class)
                .hasMessage("Не удалось удалить файл").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "invalid", ""})
    void invalidSizeClosesDownloadStream(String size) throws Exception {
        InputStream content = spy(InputStream.nullInputStream());
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(response("test.pdf", size, content));
        assertThatThrownBy(() -> storage.get(KEY)).isExactlyInstanceOf(FileStorageException.class).hasNoCause();
        verify(content).close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"%%%"})
    void missingOrInvalidNameMetadataClosesDownloadStream(String encodedName) throws Exception {
        InputStream content = spy(InputStream.nullInputStream());
        Headers.Builder headers = new Headers.Builder().add("Content-Length", "0").add("Content-Type", "application/pdf");
        if (encodedName != null) {
            headers.add("x-amz-meta-filename-base64", encodedName);
        }
        when(client.getObject(any(GetObjectArgs.class)))
                .thenReturn(new GetObjectResponse(headers.build(), BUCKET, null, KEY, content));
        assertThatThrownBy(() -> storage.get(KEY)).isExactlyInstanceOf(FileStorageException.class).hasNoCause();
        verify(content).close();
    }

    @Test
    void deleteUsesConfiguredBucketAndIsIdempotent() throws Exception {
        storage.delete(KEY);
        ArgumentCaptor<RemoveObjectArgs> args = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client).removeObject(args.capture());
        assertThat(args.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(args.getValue().object()).isEqualTo(KEY);
        doThrow(error("NoSuchKey")).when(client).removeObject(any(RemoveObjectArgs.class));
        assertThatCode(() -> storage.delete(KEY)).doesNotThrowAnyException();
    }

    private static GetObjectResponse response(String name, String size, InputStream content) {
        Headers headers = new Headers.Builder()
                .add("x-amz-meta-filename-base64", encode(name))
                .add("Content-Type", "application/pdf")
                .add("Content-Length", size)
                .build();
        return new GetObjectResponse(headers, BUCKET, null, KEY, content);
    }

    private static String encode(String name) {
        return Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void describesFailureForServerLogWithoutSdkTrace() {
        assertThat(MinioFileStorage.describe(error("AccessDenied")))
                .isEqualTo("S3 AccessDenied (Тестовая ошибка)");
        assertThat(MinioFileStorage.describe(new IOException("запрос прерван",
                new java.net.ConnectException("Connection refused"))))
                .isEqualTo("IOException: запрос прерван; причина ConnectException: Connection refused");
    }

    private static ErrorResponseException error(String code) {
        return new ErrorResponseException(
                new ErrorResponse(code, "Тестовая ошибка", BUCKET, KEY, null, null, null), null, null);
    }
}
