package ru.sibvibe.approval.ai.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.sibvibe.approval.ai.DocumentClassifier;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Определение вида документа через GigaChat — те же настройки и деградация, что у извлечения полей. */
@Component
public class GigaChatDocumentClassifier implements DocumentClassifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(GigaChatDocumentClassifier.class);
    private static final int MAX_TITLE_LENGTH = 120;
    private static final String SYSTEM_PROMPT = """
            Ты определяешь вид документа по его тексту и предлагаешь короткое название для списка документов.
            Не оценивай документ и не добавляй сведения, которых нет в тексте.
            Верни только JSON без markdown строго вида: {"typeCode":"код или null","title":"название или null"}
            typeCode — один из кодов из options, только если документ точно относится к этому виду.
            Если не подходит ни один вид или ты не уверен — typeCode null.
            title — о чём документ, по его заголовку или содержанию, до 80 символов, по-русски, без кавычек
            вокруг и без вида документа в начале: «О закупке ноутбуков для отдела продаж», а не
            «Служебная записка о закупке». Метки вида <PERSON_1> в название не включай.
            Строка «Синтетический тестовый документ…» — служебная пометка, она не является содержанием.
            """;

    private final ObjectProvider<ChatClient.Builder> builderProvider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String provider;
    private final String credentials;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GigaChatDocumentClassifier(
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
    public Classification classify(ClassificationRequest request) {
        if (!enabled || !"gigachat".equalsIgnoreCase(provider)
                || credentials == null || credentials.isBlank()
                || request == null || request.maskedText() == null || request.maskedText().isBlank()
                || request.options() == null) {
            return unavailable();
        }
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        if (builder == null) {
            return unavailable();
        }
        Future<String> call = executor.submit(() -> builder.build().prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt(request))
                .call()
                .content());
        try {
            String response = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return parse(response, request.options().stream().map(TypeOption::code).collect(Collectors.toSet()));
        } catch (TimeoutException exception) {
            call.cancel(true);
            LOGGER.warn("Истёк тайм-аут определения вида документа GigaChat");
            return unavailable();
        } catch (InterruptedException exception) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (ExecutionException | RuntimeException exception) {
            LOGGER.warn("GigaChat недоступен при определении вида документа: {}", exception.getClass().getSimpleName());
            return unavailable();
        }
    }

    private String userPrompt(ClassificationRequest request) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("options", request.options());
        payload.put("text", request.maskedText());
        return objectMapper.writeValueAsString(payload);
    }

    /** Код вне списка вариантов не принимаем — это то же, что «не уверен». */
    Classification parse(String response, Set<String> allowedCodes) {
        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(stripMarkdownFence(response));
            if (root == null || !root.isObject()) {
                return unavailable();
            }
            String code = text(root.get("typeCode"));
            String title = text(root.get("title"));
            if (title != null) {
                title = title.strip().replaceAll("^[«\"]+|[»\"]+$", "").strip();
                if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
                    title = null;
                }
            }
            return new Classification(code != null && allowedCodes.contains(code) ? code : null, title, true);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            LOGGER.warn("GigaChat вернул некорректный JSON определения вида документа");
            return unavailable();
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.textValue();
        return value.isBlank() || "null".equalsIgnoreCase(value.strip()) ? null : value;
    }

    private static String stripMarkdownFence(String response) {
        if (response == null) {
            return null;
        }
        String stripped = response.strip();
        if (stripped.startsWith("```") && stripped.endsWith("```")) {
            int firstLineEnd = stripped.indexOf('\n');
            if (firstLineEnd > 0) {
                return stripped.substring(firstLineEnd + 1, stripped.length() - 3).strip();
            }
        }
        return stripped;
    }

    private static Classification unavailable() {
        return new Classification(null, null, false);
    }

    @PreDestroy
    void closeExecutor() {
        executor.shutdownNow();
    }
}
