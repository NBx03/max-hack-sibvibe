package ru.sibvibe.approval.rules.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import ru.sibvibe.approval.rules.RuleEngine;
import ru.sibvibe.approval.rules.RuleEngine.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class DefaultRuleEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 18);
    private final RuleEngine engine = new DefaultRuleEngine(
            Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC), new ObjectMapper());

    @ParameterizedTest
    @MethodSource("cases")
    void checksEachType(CheckType type, String expected, String value, boolean valid) {
        List<ValidationIssue> issues = engine.validate(Map.of("field", value), List.of(rule(type, expected)));
        assertThat(issues).hasSize(valid ? 0 : 1);
        if (!valid) {
            assertThat(issues.getFirst().ruleId()).isEqualTo(15L);
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(CheckType.REQUIRED, null, "значение", true),
                Arguments.of(CheckType.REQUIRED, null, "", false),
                Arguments.of(CheckType.REQUIRED, null, " \t\n", false),
                Arguments.of(CheckType.REQUIRED, null, "\u2003", false),
                Arguments.of(CheckType.DATE_FORMAT, null, "2024-02-29", true),
                Arguments.of(CheckType.DATE_FORMAT, null, "2023-02-29", false),
                Arguments.of(CheckType.DATE_FORMAT, null, "2026-04-31", false),
                Arguments.of(CheckType.DATE_FORMAT, null, "2026-13-01", false),
                Arguments.of(CheckType.DATE_FORMAT, null, "18.09.2026", false),
                Arguments.of(CheckType.DATE_FORMAT, null, "2026-09-18extra", false),
                Arguments.of(CheckType.DATE_FORMAT, null, " 2026-09-18", false),
                Arguments.of(CheckType.DATE_FORMAT, "dd.MM.yyyy", "29.02.2024", true),
                Arguments.of(CheckType.DATE_FORMAT, "dd.MM.uuuu", "29.02.2023", false),
                Arguments.of(CheckType.DATE_NOT_FUTURE, null, "2026-09-17", true),
                Arguments.of(CheckType.DATE_NOT_FUTURE, null, "2026-09-18", true),
                Arguments.of(CheckType.DATE_NOT_FUTURE, null, "2026-09-19", false),
                // Неразборчивую дату DATE_NOT_FUTURE не судит: о формате сообщает DATE_FORMAT.
                Arguments.of(CheckType.DATE_NOT_FUTURE, null, "invalid", true),
                Arguments.of(CheckType.DATE_NOT_FUTURE, null, "2026-02-30", true),
                Arguments.of(CheckType.DATE_FORMAT, "RU_DATE", "«23» сентября 2026 г.", true),
                Arguments.of(CheckType.DATE_FORMAT, "RU_DATE", "23.09.2026 г.", true),
                Arguments.of(CheckType.DATE_FORMAT, "RU_DATE", "1 октября", false),
                Arguments.of(CheckType.DATE_NOT_FUTURE, "RU_DATE", "\"18\" сентября 2026г.", true),
                Arguments.of(CheckType.DATE_NOT_FUTURE, "RU_DATE", "19 сентября 2026 года", false),
                Arguments.of(CheckType.DATE_NOT_FUTURE, "RU_DATE", "сентябрь", true),
                Arguments.of(CheckType.DATE_NOT_FUTURE, "dd.MM.yyyy", "18.09.2026", true),
                Arguments.of(CheckType.DATE_NOT_FUTURE, "dd.MM.yyyy", "19.09.2026", false),
                Arguments.of(CheckType.MATCHES_PATTERN, "[А-Я]{2}-[0-9]{3}", "АБ-123", true),
                Arguments.of(CheckType.MATCHES_PATTERN, "[А-Я]{2}-[0-9]{3}", "prefixАБ-123", false),
                Arguments.of(CheckType.MATCHES_PATTERN, "[А-Я]{2}-[0-9]{3}", "аб-123", false),
                Arguments.of(CheckType.MIN_LENGTH, "3", "абв", true),
                Arguments.of(CheckType.MIN_LENGTH, "3", "абвг", true),
                Arguments.of(CheckType.MIN_LENGTH, "3", "аб", false),
                Arguments.of(CheckType.MIN_LENGTH, "2", "😀", false),
                Arguments.of(CheckType.MIN_LENGTH, "2", "😀а", true),
                Arguments.of(CheckType.MIN_LENGTH, "0", "а", true),
                Arguments.of(CheckType.ONE_OF, "[\"А\",\"Б\"]", "А", true),
                Arguments.of(CheckType.ONE_OF, "[\"А\",\"Б\"]", "В", false),
                Arguments.of(CheckType.ONE_OF, "[\"А\",\"Б\"]", "а", false),
                Arguments.of(CheckType.ONE_OF, "[\"А\",\"Б\"]", " А ", false),
                Arguments.of(CheckType.ONE_OF, "[\"А,Б\",\"А|Б\",\"А,Б\"]", "А,Б", true));
    }

    @ParameterizedTest
    @EnumSource(CheckType.class)
    void missingValuesAreOnlyRequiredViolations(CheckType type) {
        RequirementRule rule = rule(type, expected(type));
        assertThat(engine.validate(Map.of(), List.of(rule))).hasSize(type == CheckType.REQUIRED ? 1 : 0);
        for (String value : Arrays.asList(null, "", " \t\n", "\u2003")) {
            Map<String, String> fields = new HashMap<>();
            fields.put("field", value);
            assertThat(engine.validate(fields, List.of(rule))).hasSize(type == CheckType.REQUIRED ? 1 : 0);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurations")
    void badConfigurationIsNotSilentlySkipped(CheckType type, String expected) {
        for (Map<String, String> fields : List.of(Map.<String, String>of(), Map.of("field", "value"))) {
            assertThatThrownBy(() -> engine.validate(fields, List.of(rule(type, expected))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Некорректная конфигурация правила #15").hasNoCause();
        }
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                Arguments.of(CheckType.MATCHES_PATTERN, "["),
                Arguments.of(CheckType.MATCHES_PATTERN, null),
                Arguments.of(CheckType.MATCHES_PATTERN, ""),
                Arguments.of(CheckType.MIN_LENGTH, null),
                Arguments.of(CheckType.MIN_LENGTH, "-1"),
                Arguments.of(CheckType.MIN_LENGTH, "1.5"),
                Arguments.of(CheckType.MIN_LENGTH, "2147483648"),
                Arguments.of(CheckType.ONE_OF, null),
                Arguments.of(CheckType.ONE_OF, "A,B"),
                Arguments.of(CheckType.ONE_OF, "[]"),
                Arguments.of(CheckType.ONE_OF, "{}"),
                Arguments.of(CheckType.ONE_OF, "null"),
                Arguments.of(CheckType.ONE_OF, "[null]"),
                Arguments.of(CheckType.ONE_OF, "[1]"),
                Arguments.of(CheckType.ONE_OF, "[\" \"]"),
                Arguments.of(CheckType.ONE_OF, "[\"A\"] []"),
                Arguments.of(CheckType.DATE_FORMAT, "invalid pattern"),
                Arguments.of(CheckType.DATE_FORMAT, "HH:mm"),
                Arguments.of(CheckType.DATE_NOT_FUTURE, "MM-dd"));
    }

    @Test
    void preservesEverySeverityKindAndSourceWithoutInventingLocation() {
        for (Severity severity : Severity.values()) {
            for (RuleKind kind : RuleKind.values()) {
                RequirementRule rule = new RequirementRule(42L, "TEST", "field", "Заполните поле",
                        CheckType.REQUIRED, null, kind, severity, "Тестовый регламент",
                        "https://example.invalid/rules", "п. 2", Instant.EPOCH);
                assertThat(engine.validate(Map.of(), List.of(rule))).containsExactly(new ValidationIssue(
                        42L, "field", "Заполните поле", severity, kind, "Тестовый регламент",
                        "https://example.invalid/rules", "п. 2", null, null));
            }
        }
    }

    @Test
    void transfersLocationOnlyForPresentValue() {
        ValidationContext context = new ValidationContext(TODAY, Map.of("field", new FieldLocation("тест", 2)));
        ValidationIssue issue = engine.validate(Map.of("field", "тест"),
                List.of(rule(CheckType.MIN_LENGTH, "10")), context).getFirst();
        assertThat(issue.quote()).isEqualTo("тест");
        assertThat(issue.page()).isEqualTo(2);
        ValidationIssue missing = engine.validate(Map.of(), List.of(rule(CheckType.REQUIRED, null)), context).getFirst();
        assertThat(missing.quote()).isNull();
        assertThat(missing.page()).isNull();
    }

    @Test
    void preservesQuoteWhenPageIsUnknownAndDoesNotUseOtherFieldsLocation() {
        List<RequirementRule> rules = List.of(rule(CheckType.MIN_LENGTH, "10"));
        Map<String, String> fields = Map.of("field", "тест");
        ValidationIssue issue = engine.validate(fields, rules,
                new ValidationContext(TODAY, Map.of("field", new FieldLocation("тест", null)))).getFirst();
        assertThat(issue.quote()).isEqualTo("тест");
        assertThat(issue.page()).isNull();
        ValidationIssue unrelated = engine.validate(fields, rules,
                new ValidationContext(TODAY, Map.of("other", new FieldLocation("другое", 3)))).getFirst();
        assertThat(unrelated.quote()).isNull();
        assertThat(unrelated.page()).isNull();
    }

    @Test
    void explicitContextIsIndependentOfClockAndInputsAreNotModified() {
        RuleEngine laterEngine = new DefaultRuleEngine(
                Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC), new ObjectMapper());
        Map<String, String> fields = Map.of("field", "2026-09-19");
        List<RequirementRule> rules = List.of(rule(CheckType.DATE_NOT_FUTURE, null));
        ValidationContext context = new ValidationContext(TODAY, Map.of());
        List<ValidationIssue> first = engine.validate(fields, rules, context);
        assertThat(first).hasSize(1).isEqualTo(engine.validate(fields, rules, context))
                .isEqualTo(laterEngine.validate(fields, rules, context));
        assertThat(laterEngine.validate(fields, rules)).isEmpty();
        assertThat(fields).containsExactlyEntriesOf(Map.of("field", "2026-09-19"));
        assertThatThrownBy(() -> first.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesRuleOrderAndReturnsAllViolations() {
        RequirementRule first = rule(CheckType.MIN_LENGTH, "10");
        RequirementRule second = new RequirementRule(2L, "TEST", "other", "Заполните другое поле",
                CheckType.REQUIRED, null, RuleKind.PRODUCT_RULE, Severity.INFO, "Тест", null, null, null);
        assertThat(engine.validate(Map.of("field", "x"), List.of(first, second)))
                .extracting(ValidationIssue::ruleId).containsExactly(15L, 2L);
        assertThat(engine.validate(Map.of(), List.of())).isEmpty();
    }

    @Test
    void localTodayNearMidnightIsNotFutureWhenBusinessDateIsProvided() {
        Instant instant = Instant.parse("2026-09-18T18:30:00Z");
        Clock localClock = Clock.fixed(instant, ZoneId.of("Asia/Novosibirsk"));
        RuleEngine localEngine = new DefaultRuleEngine(localClock, new ObjectMapper());
        RuleEngine utcEngine = new DefaultRuleEngine(Clock.fixed(instant, ZoneOffset.UTC), new ObjectMapper());
        List<RequirementRule> rules = List.of(rule(CheckType.DATE_NOT_FUTURE, null));
        Map<String, String> fields = Map.of("field", "2026-09-19");

        assertThat(localEngine.validate(fields, rules)).isEmpty();
        assertThat(utcEngine.validate(fields, rules)).hasSize(1);
        ValidationContext context = new ValidationContext(LocalDate.now(localClock), Map.of());
        assertThat(utcEngine.validate(fields, rules, context)).isEmpty();
        assertThat(localEngine.validate(fields, rules, context)).isEmpty();
        assertThat(utcEngine.validate(Map.of("field", "2026-09-20"), rules, context)).hasSize(1);
    }

    @Test
    void checksOneDocumentAgainstSeveralRulesAndRechecksCorrections() {
        List<RequirementRule> rules = List.of(
                documentRule(1L, "title", CheckType.REQUIRED, null, Severity.BLOCKER),
                documentRule(2L, "title", CheckType.MIN_LENGTH, "5", Severity.WARNING),
                documentRule(3L, "date", CheckType.DATE_FORMAT, null, Severity.BLOCKER),
                documentRule(4L, "date", CheckType.DATE_NOT_FUTURE, null, Severity.BLOCKER),
                documentRule(5L, "code", CheckType.MATCHES_PATTERN, "[A-Z]{2}-[0-9]{3}", Severity.INFO),
                documentRule(6L, "category", CheckType.ONE_OF, "[\"INTERNAL\",\"EXTERNAL\"]", Severity.WARNING));
        ValidationContext context = new ValidationContext(TODAY,
                Map.of("date", new FieldLocation("2026-09-19", 1)));
        List<ValidationIssue> issues = engine.validate(Map.of(
                "title", "", "date", "2026-09-19", "code", "AB-123", "category", "OTHER"), rules, context);

        assertThat(issues).extracting(ValidationIssue::ruleId).containsExactly(1L, 4L, 6L);
        assertThat(issues).extracting(ValidationIssue::severity)
                .containsExactly(Severity.BLOCKER, Severity.BLOCKER, Severity.WARNING);
        assertThat(issues.getFirst().quote()).isNull();
        assertThat(issues.get(1).quote()).isEqualTo("2026-09-19");
        assertThat(issues.get(1).page()).isEqualTo(1);
        assertThat(engine.validate(Map.of(
                "title", "Тестовый документ", "date", "2026-09-18", "code", "AB-123", "category", "INTERNAL"),
                rules, new ValidationContext(TODAY, Map.of()))).isEmpty();
    }

    private static RequirementRule documentRule(long id, String field, CheckType check,
                                                 String expected, Severity severity) {
        return new RequirementRule(id, "TEST", field, "Поле не соответствует правилу",
                check, expected, RuleKind.INTERNAL_POLICY, severity,
                "Тестовый регламент", null, "п. " + id, Instant.EPOCH);
    }

    @Test
    void rejectsNullInputsAndUnknownCheck() {
        assertThatThrownBy(() -> engine.validate(null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.validate(Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.validate(Map.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.validate(Map.of(), List.of(), new ValidationContext(null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.validate(Map.of(), List.of(), new ValidationContext(TODAY, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.validate(Map.of(), Arrays.asList((RequirementRule) null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.validate(Map.of(), List.of(rule(null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CheckType.valueOf("UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String expected(CheckType type) {
        return switch (type) {
            case REQUIRED, DATE_FORMAT, DATE_NOT_FUTURE -> null;
            case MATCHES_PATTERN -> "[a-z]+";
            case MIN_LENGTH -> "3";
            case ONE_OF -> "[\"a\",\"b\"]";
        };
    }

    private static RequirementRule rule(CheckType check, String expected) {
        return new RequirementRule(15L, "TEST", "field", "Поле не соответствует правилу",
                check, expected, RuleKind.INTERNAL_POLICY, Severity.BLOCKER,
                "Тестовый регламент", null, "п. 1", Instant.EPOCH);
    }
}
