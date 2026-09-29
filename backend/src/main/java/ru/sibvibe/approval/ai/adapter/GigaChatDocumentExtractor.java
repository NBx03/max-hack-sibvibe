package ru.sibvibe.approval.ai.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import ru.sibvibe.approval.ai.DocumentExtractor;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GigaChatDocumentExtractor implements DocumentExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(GigaChatDocumentExtractor.class);
    private static final int MAX_VALUE_LENGTH = 10_000;
    private static final int MAX_QUOTE_LENGTH = 2_000;
    /** Попыток на один документ: повтор — только если ответ не разобран, и в пределах того же тайм-аута. */
    static final int MAX_ATTEMPTS = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(700);
    /** Формат сообщения RetryUtils: {@code "%s - %s"} со статусом первым — "429 - {...}". */
    private static final Pattern HTTP_STATUS_PREFIX = Pattern.compile("^(\\d{3})\\s*-");
    private static final int MAX_SUMMARY_LENGTH = 2_000;
    /**
     * Маркер «нет сводки», если он дописан ПОСЛЕ настоящего текста:
     * заглавными, отдельным словом, в конце ответа, с точкой или без. Без учёта регистра ловил бы и обычное
     * «нет» в конце обычного предложения — а оно всегда строчными.
     */
    private static final Pattern TRAILING_NO_SUMMARY_MARKER = Pattern.compile("(?:^|\\s)НЕТ\\.?\\s*$");
    private static final String SYSTEM_PROMPT = """
            Ты извлекаешь структурированные поля из текста документа.
            Не проверяй правила, не оценивай документ и не добавляй сведения, которых нет в тексте.
            Верни только JSON без markdown строго вида:
            {"fields":[{"fieldName":"имя","value":"значение или null","quote":"дословная цитата или null","page":1}]}
            Для каждого поля схемы верни ровно один объект. fieldName не изменяй.
            value — точная копия значения из текста: не переводи слова в числа, не меняй формат даты,
            кавычки, регистр и другие символы. Метки <PERSON_1>, <INN_1>, <SNILS_1>, <PASSPORT_1>,
            <PHONE_1> и аналогичные — замаскированные значения. Если поле содержит такую метку,
            верни метку в value и quote без изменений: приложение восстановит исходное значение.
            Извлекай значение только в значении поля, описанном его hint. Если именно такого поля
            в тексте нет, верни null: не подставляй похожее значение из другого места документа.
            В частности, не используй дату документа как другую дату и не преобразуй количество
            прописью в число. Строка «Синтетический тестовый документ…» — служебная пометка,
            она не является содержанием документа и не должна попадать ни в одно поле.
            quote должна быть дословным фрагментом переданного текста. Если поле не найдено,
            value, quote и page должны быть null.
            """;
    private static final String SUMMARY_SYSTEM_PROMPT = """
            Ты пишешь краткую сводку документа для человека, который будет его согласовывать.
            Не проверяй правила, не оценивай документ, не давай рекомендаций и не выражай мнение —
            только перескажи суть в 2-3 предложениях: что просит документ, какие в нём суммы и сроки
            и кого он касается. Людей называй в именительном падеже или по должности («Кузнецов Игорь
            Олегович», не «Кузнецова Игоря Олеговича»), даже если в тексте имя стоит в другом падеже.
            Используй исключительно факты, дословно присутствующие в тексте.
            Числа и даты пиши в том же виде, что они записаны в тексте: если дата цифрами
            (01.10.2026) — оставь цифрами, если словом (1 октября 2026 года) — оставь словом.
            Не переводи одно в другое и не округляй. Ничего не досчитывай и не
            предполагай: если каких-то деталей в тексте нет, просто не упоминай их — не заполняй
            пробелы правдоподобными на вид значениями.
            Метки <PERSON_1>, <INN_1>, <SNILS_1>, <PASSPORT_1>, <PHONE_1> и аналогичные — замаскированные
            значения; переноси их в сводку как есть, не расшифровывая и не убирая.
            Строка «Синтетический тестовый документ…» — служебная пометка, не пересказывай её.
            Ответь простым текстом: без кавычек вокруг всего ответа, без markdown, без JSON — только
            сама сводка. Если осмысленную сводку составить не из чего, ответь одним словом: НЕТ.
            Слово НЕТ — только вместо сводки целиком, никогда не дописывай его после текста сводки.
            """;

    private final ObjectProvider<ChatClient.Builder> builderProvider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String provider;
    private final String credentials;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GigaChatDocumentExtractor(
            ObjectProvider<ChatClient.Builder> builderProvider,
            ObjectMapper objectMapper,
            @Value("${app.ai.enabled:true}") boolean enabled,
            @Value("${app.ai.provider:gigachat}") String provider,
            @Value("${spring.ai.gigachat.auth.bearer.api-key:}") String credentials,
            @Value("${app.ai.timeout-seconds:30}") long timeoutSeconds
    ) {
        this.builderProvider = builderProvider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.provider = provider;
        this.credentials = credentials;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        if (!enabled || !"gigachat".equalsIgnoreCase(provider)
                || credentials == null || credentials.isBlank()
                || request == null || request.schema() == null || request.schema().isEmpty()) {
            return unavailable();
        }
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        if (builder == null) {
            return unavailable();
        }
        // Изредка модель отвечает некорректным JSON (на одном и том же документе то да, то нет),
        // и тогда пропадали все поля — автор видел «распознавание недоступно». Один повтор в пределах того же
        // тайм-аута: общее время ожидания не растёт, а случайный сбой формата больше не ломает проверку.
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int attempt = 1; ; attempt++) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                LOGGER.warn("Истёк тайм-аут извлечения полей GigaChat");
                return unavailable();
            }
            Future<String> call = executor.submit(() -> builder.build().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildUserPrompt(request))
                    .call()
                    .content());
            try {
                ExtractionResult parsed = parse(call.get(left, TimeUnit.NANOSECONDS), request.schema());
                if (parsed.available() || attempt >= MAX_ATTEMPTS) {
                    return parsed;
                }
                LOGGER.info("Повторяем извлечение полей: ответ GigaChat не разобран");
            } catch (TimeoutException exception) {
                call.cancel(true);
                LOGGER.warn("Истёк тайм-аут извлечения полей GigaChat");
                return unavailable();
            } catch (InterruptedException exception) {
                call.cancel(true);
                Thread.currentThread().interrupt();
                return unavailable();
            } catch (ExecutionException | RuntimeException exception) {
                LOGGER.warn("GigaChat недоступен при извлечении полей: {}", exception.getClass().getSimpleName());
                // Только временный сбой (429, 5xx, обрыв соединения) стоит повторять — 401/400 и другие ошибки
                // клиента повтор не исправит, только потратит бюджет.
                if (!isRetryable(exception) || attempt >= MAX_ATTEMPTS || !sleepBeforeRetry(deadline)) {
                    return unavailable();
                }
                LOGGER.info("Повторяем извлечение полей: временный сбой вызова GigaChat");
            }
        }
    }

    @Override
    public SummaryResult summarize(SummaryRequest request) {
        if (!enabled || !"gigachat".equalsIgnoreCase(provider)
                || credentials == null || credentials.isBlank()
                || request == null || request.maskedText() == null || request.maskedText().isBlank()) {
            return unavailableSummary();
        }
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        if (builder == null) {
            return unavailableSummary();
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int attempt = 1; ; attempt++) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                LOGGER.warn("Истёк тайм-аут сводки GigaChat");
                return unavailableSummary();
            }
            Future<String> call = executor.submit(() -> builder.build().prompt()
                    .system(SUMMARY_SYSTEM_PROMPT)
                    .user(request.maskedText())
                    .call()
                    .content());
            try {
                SummaryResult parsed = processSummary(call.get(left, TimeUnit.NANOSECONDS));
                if (parsed.available() || attempt >= MAX_ATTEMPTS) {
                    return parsed;
                }
                LOGGER.info("Повторяем сводку: ответ GigaChat не уложился в формат");
            } catch (TimeoutException exception) {
                call.cancel(true);
                LOGGER.warn("Истёк тайм-аут сводки GigaChat");
                return unavailableSummary();
            } catch (InterruptedException exception) {
                call.cancel(true);
                Thread.currentThread().interrupt();
                return unavailableSummary();
            } catch (ExecutionException | RuntimeException exception) {
                LOGGER.warn("GigaChat недоступен при сводке: {}", exception.getClass().getSimpleName());
                if (!isRetryable(exception) || attempt >= MAX_ATTEMPTS || !sleepBeforeRetry(deadline)) {
                    return unavailableSummary();
                }
                LOGGER.info("Повторяем сводку: временный сбой вызова GigaChat");
            }
        }
    }

    /**
     * 429 «Too Many Requests» и 5xx — сервер поправится сам; повтор оправдан. Spring AI заворачивает даже 429
     * в {@code NonTransientAiException} (временными считает только 5xx — источник: RetryUtils в spring-ai-retry),
     * поэтому смотрим на сам код статуса в сообщении, а не на класс исключения. 401/400 и другие ошибки клиента —
     * не временные, повтор их не исправит.
     */
    static boolean isRetryable(Exception exception) {
        Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
        if (cause == null) {
            return false;
        }
        Integer status = httpStatus(cause);
        if (status != null) {
            return status == 429 || (status >= 500 && status < 600);
        }
        return cause instanceof IOException || cause instanceof ResourceAccessException;
    }

    private static Integer httpStatus(Throwable exception) {
        String message = exception.getMessage();
        if (message == null) {
            return null;
        }
        Matcher matcher = HTTP_STATUS_PREFIX.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /**
     * Пауза перед повтором после ошибки вызова (в частности, 429 «Too Many Requests»): мгновенный повтор почти
     * наверняка получит тот же ответ. Не длиннее, чем осталось до тайм-аута.
     */
    private boolean sleepBeforeRetry(long deadline) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            return false;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(Math.min(left, RETRY_BACKOFF.toNanos()));
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Простой текст, не JSON: кавычки внутри сводки — например, в названии
     * компании «ООО "Демо-компания"» — ломали разбор JSON целиком; текст ломать нечему.
     */
    SummaryResult processSummary(String response) {
        if (response == null) {
            return unavailableSummary();
        }
        String text = stripMarkdownFence(response);
        // Модель иногда всё равно оборачивает ответ в кавычки, хотя мы просим простой текст без них.
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isBlank() || text.equalsIgnoreCase("нет") || text.equalsIgnoreCase("нет.")) {
            return new SummaryResult(null, true);
        }
        // Модель иногда дописывает маркер «нет сводки» ПОСЛЕ настоящей
        // сводки — «...предпринимательства. НЕТ». Маркер сравнивается с учётом регистра (только заглавными,
        // как просит промпт): обычное «нет» внутри фразы пишется строчными и не пострадает.
        text = TRAILING_NO_SUMMARY_MARKER.matcher(text).replaceFirst("").strip();
        if (text.isBlank()) {
            return new SummaryResult(null, true);
        }
        if (text.length() > MAX_SUMMARY_LENGTH) {
            // Слишком длинный ответ — модель не уложилась в формат, а не «сводки нет»: как некорректный JSON полей,
            // это повод повторить попытку, а не молча укоротить текст.
            return unavailableSummary();
        }
        return new SummaryResult(text, true);
    }

    private SummaryResult unavailableSummary() {
        return new SummaryResult(null, false);
    }

    @PostConstruct
    void reportDisabledModel() {
        if (enabled && "gigachat".equalsIgnoreCase(provider)
                && (credentials == null || credentials.isBlank())) {
            LOGGER.warn("GigaChat credentials не заданы: извлечение полей моделью отключено");
        }
    }

    private String buildUserPrompt(ExtractionRequest request) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentType", request.documentTypeCode());
        payload.put("schema", request.schema());
        payload.put("text", request.maskedText());
        return objectMapper.writeValueAsString(payload);
    }

    ExtractionResult parse(String response, List<FieldSpec> schema) {
        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(stripMarkdownFence(response));
            requireObject(root);
            JsonNode fieldsNode = root.get("fields");
            if (fieldsNode == null || !fieldsNode.isArray()) {
                return unavailable();
            }
            Map<String, FieldSpec> expected = new LinkedHashMap<>();
            schema.forEach(field -> expected.put(field.fieldName(), field));
            Map<String, ExtractedField> fields = new LinkedHashMap<>();
            for (JsonNode node : fieldsNode) {
                requireObject(node);
                String name = requiredText(node, "fieldName", 255);
                if (!expected.containsKey(name)) {
                    continue;
                }
                if (fields.containsKey(name)) {
                    return unavailable();
                }
                String value = nullableText(node, "value", MAX_VALUE_LENGTH);
                String quote = nullableText(node, "quote", MAX_QUOTE_LENGTH);
                Integer page = nullablePositiveInteger(node, "page");
                fields.put(name, new ExtractedField(value, quote, page));
            }
            for (String name : expected.keySet()) {
                fields.putIfAbsent(name, new ExtractedField(null, null, null));
            }
            return new ExtractionResult(Map.copyOf(fields), true, "gigachat");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            LOGGER.warn("GigaChat вернул некорректный JSON извлечения полей");
            return unavailable();
        }
    }

    private String stripMarkdownFence(String response) {
        if (response == null) {
            return null;
        }
        String stripped = response.strip();
        if (!stripped.startsWith("```") || !stripped.endsWith("```")) {
            return stripped;
        }
        int firstLineEnd = stripped.indexOf('\n');
        if (firstLineEnd < 0) {
            return stripped;
        }
        String opening = stripped.substring(0, firstLineEnd).strip();
        if (!"```".equals(opening) && !"```json".equalsIgnoreCase(opening)) {
            return stripped;
        }
        return stripped.substring(firstLineEnd + 1, stripped.length() - 3).strip();
    }

    private void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException();
        }
    }

    private String requiredText(JsonNode node, String name, int maxLength) {
        String value = nullableText(node, name, maxLength);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private String nullableText(JsonNode node, String name, int maxLength) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().length() > maxLength) {
            throw new IllegalArgumentException();
        }
        return value.textValue();
    }

    private Integer nullablePositiveInteger(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException();
        }
        return value.intValue();
    }

    private ExtractionResult unavailable() {
        return new ExtractionResult(Map.of(), false, "gigachat");
    }

    @PreDestroy
    void closeExecutor() {
        executor.shutdownNow();
    }
}
