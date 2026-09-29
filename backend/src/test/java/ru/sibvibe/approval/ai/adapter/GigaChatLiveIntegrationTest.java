package ru.sibvibe.approval.ai.adapter;

import chat.giga.springai.autoconfigure.GigaChatAutoConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.sibvibe.approval.ai.DocumentClassifier;
import ru.sibvibe.approval.ai.DocumentExtractor;
import ru.sibvibe.approval.ai.service.DocumentExtractionService;
import ru.sibvibe.approval.ai.service.SensitiveDataMasker;
import ru.sibvibe.approval.ai.service.TextMinimizer;
import ru.sibvibe.approval.rules.RuleEngine;
import ru.sibvibe.approval.rules.service.DefaultRuleEngine;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GigaChatLiveIntegrationTest {

    private static final Path TESTDATA = Path.of("..", "testdata");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @EnabledIfEnvironmentVariable(named = "GIGACHAT_CREDENTIALS", matches = ".+")
    void extractsExpectedFieldsAndSummariesFromSyntheticDocumentsWithLiveModel() throws Exception {
        String credentials = System.getenv("GIGACHAT_CREDENTIALS");
        String clientId = System.getenv().getOrDefault("GIGACHAT_CLIENT_ID", "");
        String scope = System.getenv().getOrDefault("GIGACHAT_SCOPE", "GIGACHAT_API_PERS");
        String modelName = System.getenv().getOrDefault("GIGACHAT_MODEL", "GigaChat-2-Max");
        var context = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        GigaChatAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withPropertyValues(
                        "spring.ai.model.chat=gigachat",
                        "spring.ai.gigachat.auth.bearer.api-key=" + credentials,
                        "spring.ai.gigachat.auth.bearer.client-id=" + clientId,
                        "spring.ai.gigachat.auth.scope=" + scope,
                        "spring.ai.gigachat.chat.options.model=" + modelName,
                        "spring.ai.gigachat.chat.options.temperature=0.0");

        context.run(application -> {
            assertThat(application).hasNotFailed();
            GigaChatDocumentExtractor model = new GigaChatDocumentExtractor(
                    application.getBeanProvider(ChatClient.Builder.class), mapper,
                    true, "gigachat", credentials, 120);
            GigaChatDocumentClassifier classifier = new GigaChatDocumentClassifier(
                    application.getBeanProvider(ChatClient.Builder.class), mapper,
                    true, "gigachat", credentials, 120);
            try {
                DocumentExtractionService service = new DocumentExtractionService(
                        new PdfDocxTextExtractor(), model, classifier, new TextMinimizer(), new SensitiveDataMasker(), 120);
                verifyDocuments(service);
            } finally {
                model.closeExecutor();
                classifier.closeExecutor();
            }
        });
    }

    private void verifyDocuments(DocumentExtractionService service) {
        JsonNode rules;
        JsonNode expected;
        try {
            rules = mapper.readTree(TESTDATA.resolve("rules.json").toFile());
            expected = mapper.readTree(TESTDATA.resolve("expected.json").toFile());
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать testdata", exception);
        }
        List<DocumentReport> reports = new ArrayList<>();
        boolean issueMismatch = false;
        int summariesCount = 0;
        assertThat(expected.path("documents").isArray()).isTrue();
        assertThat(expected.path("documents").size()).isPositive();
        for (JsonNode document : expected.path("documents")) {
            String typeCode = document.path("type").asText();
            TypeData type = typeData(rules, typeCode);
            List<DocumentExtractor.FieldSpec> schema = type.schema();
            Path file = TESTDATA.resolve(document.path("file").asText());
            String mime = file.toString().endsWith(".pdf")
                    ? "application/pdf"
                    : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            DocumentExtractionService.ExtractionOutcome outcome;
            DocumentClassifier.Classification classification;
            try (InputStream content = Files.newInputStream(file);
                 InputStream again = Files.newInputStream(file)) {
                outcome = service.extract(content, mime, typeCode, schema);
                classification = service.classify(again, mime, typeOptions(rules));
            } catch (IOException exception) {
                throw new IllegalStateException("Не удалось прочитать " + file, exception);
            }
            DocumentExtractor.ExtractionResult result = outcome.fields();
            if (!result.available()) {
                reports.add(new DocumentReport(file.getFileName().toString(), "недоступен", "—", "—", "—", "—"));
                issueMismatch = true;
                continue;
            }
            // Автоопределение вида: «Другой документ» — это «ни один вид не подходит», null.
            String detected = classification.typeCode() == null ? "GENERIC" : classification.typeCode();
            boolean typeMatches = detected.equals(typeCode);
            issueMismatch |= !typeMatches;
            String typeReport = typeMatches ? "вид верный" : "вид: " + typeCode + " → " + detected;
            Set<String> normalized = new HashSet<>();
            document.path("normalized").forEach(value -> normalized.add(value.asText()));
            Map<String, String> values = new LinkedHashMap<>();
            List<String> fieldDifferences = new ArrayList<>();
            for (DocumentExtractor.FieldSpec field : schema) {
                String value = result.fields().get(field.fieldName()).value();
                values.put(field.fieldName(), value);
                JsonNode expectedValue = document.path("fields").get(field.fieldName());
                String wanted = expectedValue == null ? null : expectedValue.asText();
                if (!java.util.Objects.equals(value, wanted)) {
                    String suffix = normalized.contains(field.fieldName()) ? " (нормализация допустима)" : "";
                    fieldDifferences.add(field.fieldName() + ": " + display(wanted) + " → " + display(value) + suffix);
                }
            }
            RuleEngine engine = new DefaultRuleEngine(Clock.systemUTC(), mapper);
            List<String> actualIssues = engine.validate(values, type.rules(),
                            new RuleEngine.ValidationContext(
                                    LocalDate.parse(expected.path("referenceDate").asText()), Map.of()))
                    .stream().map(issue -> type.codeById().get(issue.ruleId())).toList();
            List<String> expectedIssues = new ArrayList<>();
            document.path("issues").forEach(issue -> expectedIssues.add(issue.asText()));
            boolean matches = actualIssues.equals(expectedIssues);
            issueMismatch |= !matches;
            // Сводка — не обязательный результат: проверка фактов намеренно отбрасывает её целиком, если
            // модель что-то досочинила, и это не регрессия. В отчёте видна, но не проваливает извлечение полей.
            // Причина отсутствия — отдельной колонкой: по одному
            // «СВОДКИ НЕТ» не понять, отброшена ли она проверкой фактов, сказала ли модель «НЕТ» сама, или вызов
            // просто не удался.
            boolean hasSummary = outcome.summary() != null && !outcome.summary().isBlank();
            if (hasSummary) {
                summariesCount++;
            }
            reports.add(new DocumentReport(
                    file.getFileName().toString(),
                    String.join(" ", expectedIssues) + " → " + String.join(" ", actualIssues),
                    (matches ? "совпали" : "расхождение") + "; " + typeReport
                            + "; название: " + display(classification.title()),
                    fieldDifferences.isEmpty() ? "—" : String.join("; ", fieldDifferences),
                    hasSummary ? outcome.summary() : "СВОДКИ НЕТ",
                    summaryStatusLabel(outcome.summaryStatus())));
        }
        // Готово, когда: живой прогон комментарием, со сводкой по числу документов, у которых есть сводка —
        // её отсутствие у части документов законно (проверка фактов отбросила или модель ничего не дала), поэтому
        // это не входит в issueMismatch и не проваливает тест.
        String report = report(reports) + "\nСводок: " + summariesCount + " из " + reports.size() + ".\n";
        if (issueMismatch) {
            throw new AssertionError(report);
        }
        System.out.println(report);
    }

    private String report(List<DocumentReport> reports) {
        StringBuilder result = new StringBuilder("Отчёт GigaChat по testdata/expected.json:\n\n")
                .append("| Документ | Замечания: ожидалось → получили | Результат | Отличия полей | Кратко от ИИ | Если сводки нет — почему |\n")
                .append("| --- | --- | --- | --- | --- | --- |\n");
        reports.forEach(row -> result.append("| ").append(table(row.file()))
                .append(" | ").append(table(row.issues()))
                .append(" | ").append(table(row.status()))
                .append(" | ").append(table(row.fields()))
                .append(" | ").append(table(row.summary()))
                .append(" | ").append(table(row.summaryStatusLabel())).append(" |\n"));
        return result.toString();
    }

    private String summaryStatusLabel(DocumentExtractionService.SummaryStatus status) {
        return switch (status) {
            case PRESENT -> "—";
            case NOT_ATTEMPTED -> "не вызывалась (поля недоступны или бюджет времени кончился)";
            case MODEL_UNAVAILABLE -> "модель недоступна (тайм-аут или ошибка вызова)";
            case NO_SUMMARY_FROM_MODEL -> "модель ответила «НЕТ»";
            case REJECTED_BY_FACT_CHECK -> "отброшена проверкой фактов";
        };
    }

    private String display(String value) {
        return value == null ? "null" : "«" + value + "»";
    }

    private String table(String value) {
        return value.replace("|", "\\|").replaceAll("\\s+", " ").trim();
    }

    private TypeData typeData(JsonNode root, String typeCode) {
        for (JsonNode type : root.path("types")) {
            if (!typeCode.equals(type.path("code").asText())) {
                continue;
            }
            List<DocumentExtractor.FieldSpec> schema = new ArrayList<>();
            for (JsonNode field : type.path("fields")) {
                schema.add(new DocumentExtractor.FieldSpec(
                        field.path("name").asText(),
                        DocumentExtractor.FieldType.valueOf(field.path("type").asText()),
                        field.path("hint").asText()));
            }
            List<RuleEngine.RequirementRule> rules = new ArrayList<>();
            Map<Long, String> codeById = new LinkedHashMap<>();
            Instant checkedAt = LocalDate.parse(root.path("sourceCheckedAt").asText())
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            long id = 1;
            for (JsonNode rule : type.path("rules")) {
                JsonNode source = root.path("sources").path(rule.path("source").asText());
                rules.add(new RuleEngine.RequirementRule(
                        id, typeCode, rule.path("field").asText(), rule.path("description").asText(),
                        RuleEngine.CheckType.valueOf(rule.path("check").asText()), text(rule, "expected"),
                        RuleEngine.RuleKind.valueOf(rule.path("kind").asText()),
                        RuleEngine.Severity.valueOf(rule.path("severity").asText()),
                        source.path("title").asText(), text(source, "url"), text(rule, "sourceRef"), checkedAt));
                codeById.put(id++, rule.path("code").asText());
            }
            return new TypeData(List.copyOf(schema), List.copyOf(rules), Map.copyOf(codeById));
        }
        throw new IllegalArgumentException("Неизвестный тип " + typeCode);
    }

    /** Те же варианты, что предлагает загрузка (DocumentTypeGuessService): все типы, кроме GENERIC. */
    private List<DocumentClassifier.TypeOption> typeOptions(JsonNode root) {
        List<DocumentClassifier.TypeOption> options = new ArrayList<>();
        for (JsonNode type : root.path("types")) {
            String code = type.path("code").asText();
            if (!"GENERIC".equals(code)) {
                options.add(new DocumentClassifier.TypeOption(code, type.path("name").asText(),
                        ru.sibvibe.approval.document.service.DocumentTypeGuessService.describe(code)));
            }
        }
        return options;
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private record TypeData(
            List<DocumentExtractor.FieldSpec> schema,
            List<RuleEngine.RequirementRule> rules,
            Map<Long, String> codeById
    ) {}

    private record DocumentReport(
            String file, String issues, String status, String fields, String summary, String summaryStatusLabel
    ) {}
}
