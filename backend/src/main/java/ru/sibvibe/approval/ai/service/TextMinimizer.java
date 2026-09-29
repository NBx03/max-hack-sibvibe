package ru.sibvibe.approval.ai.service;

import org.springframework.stereotype.Component;
import ru.sibvibe.approval.ai.DocumentExtractor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class TextMinimizer {

    static final int MAX_MODEL_CHARS = 30_000;
    private static final int WINDOW_RADIUS = 1_500;

    public String minimize(String source, List<DocumentExtractor.FieldSpec> schema) {
        if (source == null || source.length() <= MAX_MODEL_CHARS) {
            return source == null ? "" : source;
        }
        String lower = source.toLowerCase(Locale.ROOT);
        Set<String> terms = searchTerms(schema);
        StringBuilder result = new StringBuilder(MAX_MODEL_CHARS);
        Set<Integer> starts = new LinkedHashSet<>();
        for (String term : terms) {
            int from = 0;
            int found;
            while ((found = lower.indexOf(term, from)) >= 0 && starts.size() < 20) {
                starts.add(Math.max(0, found - WINDOW_RADIUS));
                from = found + term.length();
            }
        }
        if (starts.isEmpty()) {
            return source.substring(0, MAX_MODEL_CHARS);
        }
        for (int start : starts.stream().sorted().toList()) {
            int end = Math.min(source.length(), start + WINDOW_RADIUS * 2);
            appendWindow(result, source.substring(start, end));
            if (result.length() >= MAX_MODEL_CHARS) {
                break;
            }
        }
        return result.substring(0, Math.min(result.length(), MAX_MODEL_CHARS));
    }

    private Set<String> searchTerms(List<DocumentExtractor.FieldSpec> schema) {
        Set<String> terms = new LinkedHashSet<>();
        for (DocumentExtractor.FieldSpec field : schema) {
            collect(terms, field.fieldName());
            collect(terms, field.hint());
        }
        return terms;
    }

    private void collect(Set<String> terms, String value) {
        if (value == null) {
            return;
        }
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 4) {
                terms.add(token);
            }
        }
    }

    private void appendWindow(StringBuilder target, String window) {
        if (!target.isEmpty()) {
            target.append("\n…\n");
        }
        int remaining = MAX_MODEL_CHARS - target.length();
        if (remaining > 0) {
            target.append(window, 0, Math.min(window.length(), remaining));
        }
    }
}
