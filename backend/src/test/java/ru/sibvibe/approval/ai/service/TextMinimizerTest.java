package ru.sibvibe.approval.ai.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.ai.DocumentExtractor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextMinimizerTest {

    @Test
    void keepsOnlyBoundedFragmentNearSchemaHintForLongDocument() {
        String source = "x".repeat(35_000) + " дата начала отпуска 22.09.2026 " + "y".repeat(35_000);
        var schema = List.of(new DocumentExtractor.FieldSpec(
                "startDate", DocumentExtractor.FieldType.DATE, "Дата начала отпуска"));

        String minimized = new TextMinimizer().minimize(source, schema);

        assertThat(minimized).hasSizeLessThanOrEqualTo(TextMinimizer.MAX_MODEL_CHARS);
        assertThat(minimized).contains("дата начала отпуска 22.09.2026");
        assertThat(minimized.length()).isLessThan(source.length());
    }
}
