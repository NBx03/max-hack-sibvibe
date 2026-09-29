package ru.sibvibe.approval.ai.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import ru.sibvibe.approval.ai.DocumentExtractor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class GigaChatDocumentExtractorTest {

    private final ObjectProvider<ChatClient.Builder> builders = mock(ObjectProvider.class);
    private final GigaChatDocumentExtractor extractor = new GigaChatDocumentExtractor(
            builders, new ObjectMapper(), false, "gigachat", "", 1);
    private final List<DocumentExtractor.FieldSpec> schema = List.of(
            new DocumentExtractor.FieldSpec("date", DocumentExtractor.FieldType.DATE, "Дата"));

    @AfterEach
    void close() {
        extractor.closeExecutor();
    }

    @Test
    void disabledAiNeverObtainsChatClient() {
        var result = extractor.extract(new DocumentExtractor.ExtractionRequest("text", "TYPE", schema));

        assertThat(result.available()).isFalse();
        verify(builders, never()).getIfAvailable();
    }

    @Test
    void noneProviderNeverObtainsChatClient() {
        var none = new GigaChatDocumentExtractor(
                builders, new ObjectMapper(), true, "none", "secret", 1);
        try {
            var result = none.extract(new DocumentExtractor.ExtractionRequest("text", "TYPE", schema));

            assertThat(result.available()).isFalse();
            verify(builders, never()).getIfAvailable();
        } finally {
            none.closeExecutor();
        }
    }

    @Test
    void missingCredentialsNeverObtainsChatClient() {
        var withoutKey = new GigaChatDocumentExtractor(
                builders, new ObjectMapper(), true, "gigachat", " ", 1);
        try {
            var result = withoutKey.extract(new DocumentExtractor.ExtractionRequest("text", "TYPE", schema));

            assertThat(result.available()).isFalse();
            verify(builders, never()).getIfAvailable();
        } finally {
            withoutKey.closeExecutor();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutReturnsUnavailableInsteadOfEscaping() throws Exception {
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(provider.getIfAvailable()).thenReturn(builder);
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(invocation -> {
                    Thread.sleep(5_000);
                    return "{}";
                });
        var timed = new GigaChatDocumentExtractor(
                provider, new ObjectMapper(), true, "gigachat", "secret", 1);
        try {
            var result = timed.extract(new DocumentExtractor.ExtractionRequest("text", "TYPE", schema));

            assertThat(result.available()).isFalse();
        } finally {
            timed.closeExecutor();
        }
    }

    /** Случайный некорректный JSON — один повтор, а не потеря всех полей. */
    @Test
    @SuppressWarnings("unchecked")
    void invalidJsonIsRetriedOnceAndThenGivesUp() {
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        when(provider.getIfAvailable()).thenReturn(builder);
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("{\"fields\":[{\"fieldName\":\"date\",\"value\":\"ООО \"Ромашка\"\"}]}")
                .thenReturn("{\"fields\":[{\"fieldName\":\"date\",\"value\":\"01.10.2026\"}]}");
        var retried = new GigaChatDocumentExtractor(provider, new ObjectMapper(), true, "gigachat", "secret", 5);
        try {
            var result = retried.extract(new DocumentExtractor.ExtractionRequest("text", "TYPE", schema));

            assertThat(result.available()).isTrue();
            assertThat(result.fields().get("date").value()).isEqualTo("01.10.2026");
        } finally {
            retried.closeExecutor();
        }

        when(builder.build().prompt().system(anyString()).user(anyString()).call().content()).thenReturn("не JSON");
        var failing = new GigaChatDocumentExtractor(provider, new ObjectMapper(), true, "gigachat", "secret", 5);
        try {
            assertThat(failing.extract(new DocumentExtractor.ExtractionRequest("text", "TYPE", schema)).available())
                    .as("после второй неудачи — «модель недоступна», без бесконечных повторов").isFalse();
        } finally {
            failing.closeExecutor();
        }
    }

    @Test
    void acceptsMarkdownUnknownAndMissingFieldsButRejectsInvalidKnownValues() {
        var enabled = new GigaChatDocumentExtractor(
                builders, new ObjectMapper(), true, "gigachat", "secret", 1);
        try {
            var valid = enabled.parse(
                    "{\"fields\":[{\"fieldName\":\"date\",\"value\":\"2026-09-22\",\"quote\":\"22.09.2026\",\"page\":1}]}",
                    schema);
            var unknown = enabled.parse(
                    "{\"fields\":[{\"fieldName\":\"unknown\",\"value\":42}],\"extra\":true}",
                    schema);
            var markdown = enabled.parse("```json\n{\"fields\":[]}\n```", schema);
            var invalid = enabled.parse(
                    "{\"fields\":[{\"fieldName\":\"date\",\"value\":42}]}", schema);
            var duplicate = enabled.parse(
                    "{\"fields\":[{\"fieldName\":\"date\",\"value\":null},"
                            + "{\"fieldName\":\"date\",\"value\":null}]}", schema);

            assertThat(valid.available()).isTrue();
            assertThat(valid.fields()).containsKey("date");
            assertThat(unknown.available()).isTrue();
            assertThat(unknown.fields().get("date").value()).isNull();
            assertThat(markdown.available()).isTrue();
            assertThat(markdown.fields().get("date").value()).isNull();
            assertThat(invalid.available()).isFalse();
            assertThat(duplicate.available()).isFalse();
        } finally {
            enabled.closeExecutor();
        }
    }

    /**
     * Сводка — отдельный вызов, ответ простым текстом, не JSON: кавычки
     * внутри сводки — например, в названии компании — не должны ничего ломать, потому что ломать нечего.
     */
    @Test
    void processesPlainTextSummaryAndTreatsNoAndBlankAsNoSummary() {
        var enabled = new GigaChatDocumentExtractor(
                builders, new ObjectMapper(), true, "gigachat", "secret", 1);
        try {
            var valid = enabled.processSummary("Отпуск начинается 22.09.2026.");
            var quoted = enabled.processSummary("\"ООО \"Демо-компания\" подала заявку.\"");
            var no = enabled.processSummary("НЕТ");
            var blank = enabled.processSummary("   ");
            var fenced = enabled.processSummary("```\nОтпуск начинается 22.09.2026.\n```");
            var tooLong = enabled.processSummary("x".repeat(2_001));

            assertThat(valid.available()).isTrue();
            assertThat(valid.summary()).isEqualTo("Отпуск начинается 22.09.2026.");
            assertThat(quoted.available()).isTrue();
            assertThat(quoted.summary()).isEqualTo("ООО \"Демо-компания\" подала заявку.");
            assertThat(no.available()).isTrue();
            assertThat(no.summary()).isNull();
            assertThat(blank.available()).isTrue();
            assertThat(blank.summary()).isNull();
            assertThat(fenced.available()).isTrue();
            assertThat(fenced.summary()).isEqualTo("Отпуск начинается 22.09.2026.");
            assertThat(tooLong.available()).isFalse();
        } finally {
            enabled.closeExecutor();
        }
    }

    /**
     * модель иногда дописывает маркер «НЕТ» ПОСЛЕ настоящей сводки
     * (документы 07 и 10 — те же строки, дословно). Обычное строчное «нет» внутри фразы
     * при этом не должно пострадать.
     */
    @Test
    void stripsTrailingNoSummaryMarkerAppendedAfterRealSummary() {
        var enabled = new GigaChatDocumentExtractor(
                builders, new ObjectMapper(), true, "gigachat", "secret", 1);
        try {
            var doc07 = enabled.processSummary("ООО «Демо-компания», ИНН 001234567, подаёт заявку на получение "
                    + "меры поддержки от центра поддержки предпринимательства. Генеральным директором является "
                    + "В.П. Орлов. Дата подачи заявки - 15.09.2026. НЕТ");
            var doc10 = enabled.processSummary("Генеральный директор В.П. Орлов подал заявку от 15.09.2026 года "
                    + "на получение консультационной поддержки по вопросам бухгалтерского учёта в центре поддержки "
                    + "предпринимательства. НЕТ");
            var withPeriodAfterMarker = enabled.processSummary("Отпуск начинается 22.09.2026. НЕТ.");
            var lowercaseNoInsideSentence = enabled.processSummary("Свободных средств на счёте нет.");

            assertThat(doc07.available()).isTrue();
            assertThat(doc07.summary()).isEqualTo("ООО «Демо-компания», ИНН 001234567, подаёт заявку на получение "
                    + "меры поддержки от центра поддержки предпринимательства. Генеральным директором является "
                    + "В.П. Орлов. Дата подачи заявки - 15.09.2026.");
            assertThat(doc10.available()).isTrue();
            assertThat(doc10.summary()).isEqualTo("Генеральный директор В.П. Орлов подал заявку от 15.09.2026 года "
                    + "на получение консультационной поддержки по вопросам бухгалтерского учёта в центре поддержки "
                    + "предпринимательства.");
            assertThat(withPeriodAfterMarker.summary()).isEqualTo("Отпуск начинается 22.09.2026.");
            // Строчное «нет» в конце обычного предложения — часть содержания, не маркер, трогать нельзя.
            assertThat(lowercaseNoInsideSentence.summary()).isEqualTo("Свободных средств на счёте нет.");
        } finally {
            enabled.closeExecutor();
        }
    }

    /**
     * повторяем только временный сбой — 429 и 5xx (даже если Spring AI
     * завернул их в {@code NonTransientAiException}, называя временными только 5xx) и обрыв соединения.
     * 401/400 и другие ошибки клиента — не временные, вебсервер не «поправится сам», повтор их не изменит.
     */
    @Test
    void classifiesOnlyTransientErrorsAsRetryable() {
        assertThat(GigaChatDocumentExtractor.isRetryable(
                new ExecutionException(new NonTransientAiException("429 - {\"message\":\"Too Many Requests\"}"))))
                .as("429 — временно, хотя Spring AI называет исключение NonTransient").isTrue();
        assertThat(GigaChatDocumentExtractor.isRetryable(
                new ExecutionException(new org.springframework.ai.retry.TransientAiException("500 - {\"message\":\"Internal Server Error\"}"))))
                .isTrue();
        assertThat(GigaChatDocumentExtractor.isRetryable(
                new ExecutionException(new ResourceAccessException("Request was interrupted", new IOException()))))
                .as("обрыв соединения — временная ошибка ввода-вывода").isTrue();
        assertThat(GigaChatDocumentExtractor.isRetryable(
                new ExecutionException(new NonTransientAiException("401 - {\"message\":\"Unauthorized\"}"))))
                .as("401 — токен неверный или просрочен, повтор не поможет").isFalse();
        assertThat(GigaChatDocumentExtractor.isRetryable(
                new ExecutionException(new NonTransientAiException("400 - {\"message\":\"Bad Request\"}"))))
                .as("400 — ошибка запроса, повтор не поможет").isFalse();
    }
}
