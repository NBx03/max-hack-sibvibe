package ru.sibvibe.approval.rules.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import ru.sibvibe.approval.rules.RuleEngine;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@RequiredArgsConstructor
public class DefaultRuleEngine implements RuleEngine {

    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Override
    public List<ValidationIssue> validate(Map<String, String> fields, List<RequirementRule> rules) {
        return validate(fields, rules, new ValidationContext(LocalDate.now(clock), Map.of()));
    }

    @Override
    public List<ValidationIssue> validate(Map<String, String> fields, List<RequirementRule> rules,
                                          ValidationContext context) {
        if (fields == null || rules == null || context == null
                || context.referenceDate() == null || context.locations() == null) {
            throw new IllegalArgumentException("Не заданы данные или контекст проверки");
        }
        List<ValidationIssue> issues = new ArrayList<>();
        for (RequirementRule rule : rules) {
            validateRule(rule);
            // Конфигурацию проверяем до пропуска пустого поля, чтобы не скрыть сломанное правило.
            Predicate<String> check = compile(rule, context.referenceDate());
            String value = fields.get(rule.fieldName());
            boolean missing = value == null || value.isBlank();
            boolean valid = rule.check() == CheckType.REQUIRED ? !missing : missing || check.test(value);
            if (!valid) {
                FieldLocation location = missing ? null : context.locations().get(rule.fieldName());
                issues.add(new ValidationIssue(rule.id(), rule.fieldName(), rule.description(),
                        rule.severity(), rule.kind(), rule.sourceTitle(), rule.sourceUrl(), rule.sourceRef(),
                        location == null ? null : location.quote(), location == null ? null : location.page()));
            }
        }
        return List.copyOf(issues);
    }

    private static void validateRule(RequirementRule rule) {
        if (rule == null || rule.id() == null || blank(rule.fieldName()) || blank(rule.description())
                || rule.check() == null || rule.kind() == null || rule.severity() == null
                || blank(rule.sourceTitle())) {
            throw invalidRule(rule);
        }
    }

    private Predicate<String> compile(RequirementRule rule, LocalDate referenceDate) {
        // Исчерпывающий switch: новый CheckType требует явной реализации при компиляции.
        return switch (rule.check()) {
            case REQUIRED -> value -> !blank(value);
            case DATE_FORMAT -> {
                Function<String, Optional<LocalDate>> parser = dateParser(rule);
                yield value -> parser.apply(value).isPresent();
            }
            case DATE_NOT_FUTURE -> {
                // Неразборчивую дату это правило не судит: о формате сообщает DATE_FORMAT, а второе
                // замечание на ту же дату («не в будущем») только путало (обсуждение).
                Function<String, Optional<LocalDate>> parser = dateParser(rule);
                yield value -> parser.apply(value).map(date -> !date.isAfter(referenceDate)).orElse(true);
            }
            case MATCHES_PATTERN -> {
                if (blank(rule.expected())) {
                    throw invalidRule(rule);
                }
                try {
                    Pattern pattern = Pattern.compile(rule.expected());
                    yield value -> pattern.matcher(value).matches();
                } catch (PatternSyntaxException exception) {
                    throw invalidRule(rule);
                }
            }
            case MIN_LENGTH -> {
                int minimum;
                try {
                    minimum = Integer.parseInt(rule.expected());
                    if (minimum < 0) {
                        throw invalidRule(rule);
                    }
                } catch (NumberFormatException exception) {
                    throw invalidRule(rule);
                }
                yield value -> value.codePointCount(0, value.length()) >= minimum;
            }
            case ONE_OF -> {
                Set<String> allowed = allowedValues(rule);
                yield allowed::contains;
            }
        };
    }

    /**
     * RU_DATE — полная дата в любом принятом виде (RussianDates); иначе — строгий шаблон
     * DateTimeFormatter, как раньше.
     */
    private static Function<String, Optional<LocalDate>> dateParser(RequirementRule rule) {
        if (RussianDates.RU_DATE.equals(rule.expected())) {
            return RussianDates::parse;
        }
        DateTimeFormatter formatter = dateFormatter(rule);
        return value -> {
            try {
                return Optional.of(LocalDate.parse(value, formatter));
            } catch (DateTimeException exception) {
                return Optional.empty();
            }
        };
    }

    private static DateTimeFormatter dateFormatter(RequirementRule rule) {
        if (blank(rule.expected())) {
            return DateTimeFormatter.ISO_LOCAL_DATE;
        }
        try {
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .appendPattern(rule.expected())
                    // yyyy — год эры; при строгом разборе явно задаём нашу эру.
                    .parseDefaulting(ChronoField.ERA, 1)
                    .toFormatter(Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
            LocalDate sample = LocalDate.of(2000, 2, 29);
            if (!LocalDate.parse(formatter.format(sample), formatter).equals(sample)) {
                throw invalidRule(rule);
            }
            return formatter;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidRule(rule);
        }
    }

    private Set<String> allowedValues(RequirementRule rule) {
        if (blank(rule.expected())) {
            throw invalidRule(rule);
        }
        try {
            JsonNode values = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(rule.expected());
            if (!values.isArray() || values.isEmpty()) {
                throw invalidRule(rule);
            }
            Set<String> allowed = new HashSet<>();
            for (JsonNode value : values) {
                if (!value.isTextual() || value.textValue().isBlank()) {
                    throw invalidRule(rule);
                }
                allowed.add(value.textValue());
            }
            return allowed;
        } catch (JsonProcessingException exception) {
            throw invalidRule(rule);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalidRule(RequirementRule rule) {
        // Не включаем значения полей, regex и сообщения парсера в исключение или логи.
        return new IllegalArgumentException("Некорректная конфигурация правила"
                + (rule == null || rule.id() == null ? "" : " #" + rule.id()));
    }
}
