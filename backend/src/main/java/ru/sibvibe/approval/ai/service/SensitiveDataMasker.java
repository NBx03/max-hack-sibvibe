package ru.sibvibe.approval.ai.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SensitiveDataMasker {

    private static final List<MaskPattern> PATTERNS = List.of(
            new MaskPattern("SNILS", Pattern.compile("(?<!\\d)\\d{3}[- ]?\\d{3}[- ]?\\d{3}[ -]?\\d{2}(?!\\d)")),
            new MaskPattern("PASSPORT", Pattern.compile("(?<!\\d)\\d{4}[ -]\\d{6}(?!\\d)")),
            new MaskPattern("PHONE", Pattern.compile("(?<!\\d)(?:\\+7|8)[ ()-]*\\d{3}[ ()-]*\\d{3}[- ]*\\d{2}[- ]*\\d{2}(?!\\d)")),
            new MaskPattern("INN", Pattern.compile("(?<!\\d)(?:\\d{12}|\\d{10})(?!\\d)")),
            new MaskPattern("PERSON", Pattern.compile(
                    "(?U)(?<![\\p{L}-])(?:"
                            + "[А-ЯЁ]\\.\\s?[А-ЯЁ]\\.\\s?[А-ЯЁ][а-яё-]+"
                            + "|[А-ЯЁ][а-яё-]+\\s+[А-ЯЁ][а-яё-]*(?:ов|ова|ев|ева|ин|ина|ский|ская|цкий|цкая)"
                            + "|[А-ЯЁ][а-яё-]+\\s+[А-ЯЁ]\\.[А-ЯЁ]\\."
                            + "|[А-ЯЁ][а-яё-]+\\s+[А-ЯЁ][а-яё-]+\\s+[А-ЯЁ][а-яё-]+"
                            + ")(?![\\p{L}-])"))
    );

    public MaskedText mask(String source) {
        String masked = source == null ? "" : source;
        Map<String, String> values = new LinkedHashMap<>();
        for (MaskPattern pattern : PATTERNS) {
            masked = replace(masked, pattern.kind(), pattern.pattern(), values);
        }
        return new MaskedText(masked, Map.copyOf(values));
    }

    private String replace(String source, String kind, Pattern pattern, Map<String, String> values) {
        Matcher matcher = pattern.matcher(source);
        StringBuffer result = new StringBuffer();
        int index = 1;
        while (matcher.find()) {
            String placeholder = "<" + kind + "_" + index++ + ">";
            values.put(placeholder, matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public record MaskedText(String text, Map<String, String> values) {
        public String restore(String value) {
            if (value == null) {
                return null;
            }
            String restored = value;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                restored = restored.replace(entry.getKey(), entry.getValue());
            }
            return restored;
        }
    }

    private record MaskPattern(String kind, Pattern pattern) {}
}
