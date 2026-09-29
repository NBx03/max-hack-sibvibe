package ru.sibvibe.approval.ai.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.ai.adapter.PdfDocxTextExtractor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void masksNamedValuesAndRestoresOriginalText() {
        String source = "Иванов И.И., ИНН 1234567890, СНИЛС 123-456-789 01, "
                + "паспорт 1234 567890, телефон +7 (913) 123-45-67";

        SensitiveDataMasker.MaskedText masked = masker.mask(source);

        assertThat(masked.text())
                .contains("<PERSON_1>", "<INN_1>", "<SNILS_1>", "<PASSPORT_1>", "<PHONE_1>")
                .doesNotContain("1234567890", "123-456-789 01", "1234 567890", "+7 (913) 123-45-67");
        assertThat(masked.restore(masked.text())).isEqualTo(source);
    }

    @Test
    void usesStableIndexesForRepeatedValues() {
        SensitiveDataMasker.MaskedText masked = masker.mask("ИНН 1234567890, ИНН 0987654321");

        assertThat(masked.text()).contains("<INN_1>", "<INN_2>");
        assertThat(masked.restore(masked.text())).isEqualTo("ИНН 1234567890, ИНН 0987654321");
    }

    @Test
    void masksPeopleFromEverySyntheticDocument() throws Exception {
        Path documents = Path.of("..", "testdata", "documents");
        var extractor = new PdfDocxTextExtractor();
        List<String> people = List.of(
                "А.С. Петрова", "Анна Петрова", "Д.В. Смирнов",
                "Кузнецов Игорь Олегович", "Соколовой Е.Н.", "Орлову В.П.");

        try (var files = Files.list(documents)) {
            // «~$…» — служебный файл Word рядом с открытым документом, не тестовый документ.
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("~$")).toList()) {
                String mime = file.toString().endsWith(".pdf")
                        ? "application/pdf"
                        : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                try (InputStream content = Files.newInputStream(file)) {
                    String masked = masker.mask(extractor.extract(content, mime).orElseThrow().fullText()).text();
                    assertThat(masked).as("персональные данные в %s", file.getFileName())
                            .doesNotContain(people.toArray(String[]::new));
                }
            }
        }
    }
}
