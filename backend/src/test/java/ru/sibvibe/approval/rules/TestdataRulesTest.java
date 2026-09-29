package ru.sibvibe.approval.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import ru.sibvibe.approval.rules.RuleEngine.CheckType;
import ru.sibvibe.approval.rules.RuleEngine.RequirementRule;
import ru.sibvibe.approval.rules.RuleEngine.RuleKind;
import ru.sibvibe.approval.rules.RuleEngine.Severity;
import ru.sibvibe.approval.rules.RuleEngine.ValidationContext;
import ru.sibvibe.approval.rules.RuleEngine.ValidationIssue;
import ru.sibvibe.approval.rules.service.DefaultRuleEngine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Данные из testdata/ против кода: правила из rules.json гоняются настоящим DefaultRuleEngine
 * по полям из expected.json, а поля сверяются с реальным текстом DOCX и PDF.
 * Актуальность самих файлов относительно генератора проверяет generate.py --check в CI.
 */
class TestdataRulesTest {

    private static final Path TESTDATA = Path.of("..", "testdata");
    // GENERIC — «Другой документ»: общая проверка реквизитов любого документа.
    private static final Set<String> TYPE_CODES =
            Set.of("OFFICIAL_MEMO", "VACATION_REQUEST", "SUPPORT_MEASURE_REQUEST", "BUSINESS_TRIP_REQUEST", "GENERIC");
    private static final Set<String> FIELD_TYPES = Set.of("STRING", "DATE", "NUMBER", "PERSON", "ORGANIZATION");
    private static final Pattern DOCX_TEXT = Pattern.compile("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>");

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleEngine engine = new DefaultRuleEngine(Clock.system(ZoneOffset.UTC), mapper);

    @TestFactory
    Stream<DynamicTest> documentsProduceExpectedIssues() throws IOException {
        JsonNode expected = read("expected.json");
        LocalDate referenceDate = LocalDate.parse(expected.get("referenceDate").asText());
        Map<String, TypeRules> byType = typeRules(read("rules.json"));

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode document : expected.get("documents")) {
            String file = document.get("file").asText();
            tests.add(DynamicTest.dynamicTest(file, () -> {
                TypeRules type = byType.get(document.get("type").asText());
                assertThat(type).as("тип документа %s описан в rules.json", file).isNotNull();

                Map<String, String> fields = new HashMap<>();
                document.get("fields").properties().forEach(e -> fields.put(e.getKey(), e.getValue().asText()));
                Set<String> normalized = new HashSet<>();
                document.get("normalized").forEach(name -> normalized.add(name.asText()));
                assertThat(type.fieldNames()).as("поля %s описаны в rules.json", file)
                        .containsAll(fields.keySet()).containsAll(normalized);

                // Поля должны реально быть в файле, иначе файл можно подменить любым другим.
                String text = collapse(extractText(TESTDATA.resolve(file)));
                fields.forEach((name, value) -> {
                    if (!normalized.contains(name)) {
                        assertThat(text).as("значение поля %s есть в тексте %s", name, file).contains(collapse(value));
                    }
                });

                List<ValidationIssue> issues = engine.validate(fields, type.rules(),
                        new ValidationContext(referenceDate, Map.of()));
                List<String> codes = issues.stream().map(issue -> type.codeById().get(issue.ruleId())).toList();
                List<String> want = new ArrayList<>();
                document.get("issues").forEach(code -> want.add(code.asText()));
                assertThat(codes).as("замечания для %s", file).isEqualTo(want);
            }));
        }
        return tests.stream();
    }

    @Test
    void rulesFitDatabaseConstraintsAndProductScope() throws IOException {
        JsonNode root = read("rules.json");
        Set<String> typeCodes = new HashSet<>();
        Set<String> ruleCodes = new HashSet<>();
        for (JsonNode type : root.get("types")) {
            String code = type.get("code").asText();
            assertThat(typeCodes.add(code)).as("код типа %s уникален", code).isTrue();

            Set<String> names = new HashSet<>();
            for (JsonNode field : type.get("fields")) {
                assertThat(FIELD_TYPES).contains(field.get("type").asText());
                assertThat(names.add(field.get("name").asText())).as("имя поля уникально в %s", code).isTrue();
                assertThat(field.get("label").asText()).isNotBlank();
                assertThat(field.get("hint").asText()).isNotBlank();
            }

            JsonNode rules = type.get("rules");
            // Объём из docs/DESIGN-DECISIONS.md, решение B, и: записка — 8–10 правил, заявление на отпуск — 4–5,
            // заявка на меру поддержки, заявка на командировку и «Другой документ» — 5–6.
            switch (code) {
                case "OFFICIAL_MEMO" -> assertThat(rules.size()).isBetween(8, 10);
                case "VACATION_REQUEST" -> assertThat(rules.size()).isBetween(4, 5);
                default -> assertThat(rules.size()).isBetween(5, 6);
            }
            long legal = 0;
            for (JsonNode rule : rules) {
                assertThat(ruleCodes.add(rule.get("code").asText())).as("код правила уникален").isTrue();
                // requirement_rule ссылается на document_type_field по (тип, поле).
                assertThat(names).contains(rule.get("field").asText());
                assertThat(rule.get("description").asText()).isNotBlank();
                assertThat(root.get("sources").has(rule.get("source").asText())).isTrue();
                String kind = rule.get("kind").asText();
                if (kind.equals("LEGAL")) {
                    legal++;
                    // У закона всегда точное место и ссылка.
                    assertThat(rule.get("sourceRef").asText("")).isNotBlank();
                    assertThat(root.get("sources").get(rule.get("source").asText()).get("url").asText("")).isNotBlank();
                }
            }
            // Вид «Закон» — только у документа, который уходит наружу (docs/DESIGN-DECISIONS.md, решение B).
            if (code.equals("SUPPORT_MEASURE_REQUEST")) {
                assertThat(legal).as("у заявки на меру поддержки есть правило вида «Закон»").isPositive();
            } else {
                assertThat(legal).as("у внутреннего документа %s нет правил вида «Закон»", code).isZero();
            }
        }
        assertThat(typeCodes).isEqualTo(TYPE_CODES);
    }

    @Test
    void everyTypeHasCleanAndFaultyDocument() throws IOException {
        Map<String, List<Boolean>> cleanByType = new HashMap<>();
        for (JsonNode document : read("expected.json").get("documents")) {
            cleanByType.computeIfAbsent(document.get("type").asText(), key -> new ArrayList<>())
                    .add(document.get("issues").isEmpty());
        }
        assertThat(cleanByType.keySet()).isEqualTo(TYPE_CODES);
        cleanByType.forEach((type, clean) -> {
            assertThat(clean).as("у %s есть чистый документ", type).contains(true);
            assertThat(clean).as("у %s есть документ с ошибками", type).contains(false);
        });
    }

    @Test
    void everyRuleFiresInAtLeastOneDocument() throws IOException {
        Set<String> fired = new HashSet<>();
        for (JsonNode document : read("expected.json").get("documents")) {
            document.get("issues").forEach(code -> fired.add(code.asText()));
        }
        Set<String> all = new HashSet<>();
        for (JsonNode type : read("rules.json").get("types")) {
            type.get("rules").forEach(rule -> all.add(rule.get("code").asText()));
        }
        // Правило, которое ни разу не срабатывает, можно сломать или удалить незаметно.
        assertThat(fired).as("каждое правило срабатывает хотя бы в одном документе").containsAll(all);
    }

    private static String extractText(Path path) throws IOException {
        String name = path.getFileName().toString();
        if (name.endsWith(".pdf")) {
            try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
                return new PDFTextStripper().getText(pdf);
            }
        }
        // DOCX — это zip с word/document.xml; так же его будет читать TextExtractor, без Apache POI.
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            assertThat(entry).as("%s — настоящий DOCX", name).isNotNull();
            try (InputStream in = zip.getInputStream(entry)) {
                String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                StringBuilder text = new StringBuilder();
                Matcher matcher = DOCX_TEXT.matcher(xml);
                while (matcher.find()) {
                    text.append(unescapeXml(matcher.group(1))).append(' ');
                }
                return text.toString();
            }
        }
    }

    private static String unescapeXml(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private Map<String, TypeRules> typeRules(JsonNode root) {
        JsonNode sources = root.get("sources");
        Instant checkedAt = LocalDate.parse(root.get("sourceCheckedAt").asText()).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, TypeRules> result = new HashMap<>();
        long id = 1;
        for (JsonNode type : root.get("types")) {
            String typeCode = type.get("code").asText();
            List<RequirementRule> list = new ArrayList<>();
            Map<Long, String> codeById = new LinkedHashMap<>();
            for (JsonNode rule : type.get("rules")) {
                JsonNode source = sources.get(rule.get("source").asText());
                list.add(new RequirementRule(id, typeCode, rule.get("field").asText(),
                        rule.get("description").asText(), CheckType.valueOf(rule.get("check").asText()),
                        text(rule, "expected"), RuleKind.valueOf(rule.get("kind").asText()),
                        Severity.valueOf(rule.get("severity").asText()), source.get("title").asText(),
                        text(source, "url"), text(rule, "sourceRef"), checkedAt));
                codeById.put(id++, rule.get("code").asText());
            }
            Set<String> fieldNames = new HashSet<>();
            type.get("fields").forEach(field -> fieldNames.add(field.get("name").asText()));
            result.put(typeCode, new TypeRules(List.copyOf(list), codeById, fieldNames));
        }
        return result;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private JsonNode read(String name) throws IOException {
        return mapper.readTree(Files.readString(TESTDATA.resolve(name)));
    }

    private record TypeRules(List<RequirementRule> rules, Map<Long, String> codeById, Set<String> fieldNames) {}
}
