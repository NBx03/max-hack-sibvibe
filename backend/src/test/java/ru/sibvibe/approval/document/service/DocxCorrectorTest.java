package ru.sibvibe.approval.document.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.ai.TextExtractor;
import ru.sibvibe.approval.ai.adapter.PdfDocxTextExtractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocxCorrectorTest {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final DocxCorrector corrector = new DocxCorrector();

    /** Word разбивает дату на фрагменты с разным оформлением — замена всё равно находит её целиком. */
    @Test
    void replacesValueSplitAcrossRunsAndKeepsFormattingOfFirstRun() throws IOException {
        byte[] docx = docx("""
                <w:p><w:r><w:t xml:space="preserve">Дата: «23» </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>сентября</w:t></w:r><w:r><w:t xml:space="preserve"> 2026г.</w:t></w:r></w:p>
                <w:p><w:r><w:t>Прошу согласовать.</w:t></w:r></w:p>
                """);

        DocxCorrector.Result result = corrector.apply(docx, List.of(
                new DocxCorrector.Replacement("doc_date", "«23» сентября 2026г.", "23.09.2026", "Дата: «23» сентября 2026г.")));

        assertThat(result.applied()).containsExactly("doc_date");
        assertThat(result.failed()).isEmpty();
        assertThat(text(result.content())).isEqualTo("Дата: 23.09.2026\nПрошу согласовать.");
        String xml = documentXml(result.content());
        assertThat(xml).contains("<w:b/>").contains("Дата: 23.09.2026").doesNotContain("сентября");
    }

    @Test
    void reportsWhatCouldNotBeWrittenInsteadOfGuessing() throws IOException {
        byte[] docx = docx("""
                <w:p><w:r><w:t>№ 119 от 15.09.2026, пункт 119</w:t></w:r></w:p>
                """);

        DocxCorrector.Result result = corrector.apply(docx, List.of(
                new DocxCorrector.Replacement("reg_number", "119", "СЗ-119", null),
                new DocxCorrector.Replacement("addressee", null, "Директору", null),
                new DocxCorrector.Replacement("subject", "О закупке", "О покупке", null)));

        assertThat(result.applied()).isEmpty();
        assertThat(result.failed()).containsEntry("reg_number", DocxCorrector.Failure.AMBIGUOUS)
                .containsEntry("addressee", DocxCorrector.Failure.NOT_IN_FILE)
                .containsEntry("subject", DocxCorrector.Failure.NOT_FOUND);
        assertThat(result.content()).isSameAs(docx);
    }

    /** Дата документа совпала с датой в тексте: менять обе — значит молча поменять смысл документа. */
    @Test
    void repeatedDateIsNotReplacedEverywhereButNameIs() throws IOException {
        byte[] docx = docx("""
                <w:p><w:r><w:t>Дата: 15.09.2026. От кого: Анна Петрова</w:t></w:r></w:p>
                <w:p><w:r><w:t>Прошу согласовать с 15.09.2026. Подпись: Анна Петрова</w:t></w:r></w:p>
                """);

        DocxCorrector.Result result = corrector.apply(docx, List.of(
                new DocxCorrector.Replacement("doc_date", "15.09.2026", "16.09.2026", null),
                new DocxCorrector.Replacement("signer_name", "Анна Петрова", "А.С. Петрова", null)));

        assertThat(result.failed()).containsEntry("doc_date", DocxCorrector.Failure.AMBIGUOUS);
        assertThat(result.applied()).containsExactly("signer_name");
        String text = new ru.sibvibe.approval.ai.adapter.PdfDocxTextExtractor()
                .extract(new java.io.ByteArrayInputStream(result.content()), DocxCorrector.DOCX).orElseThrow().fullText();
        assertThat(text).contains("15.09.2026").doesNotContain("Анна Петрова").contains("А.С. Петрова");
    }

    @Test
    void emptyValueNeverCutsTextOutOfTheFile() throws IOException {
        byte[] docx = docx("""
                <w:p><w:r><w:t>Руководитель отдела А.С. Петрова</w:t></w:r></w:p>
                """);

        DocxCorrector.Result result = corrector.apply(docx, List.of(
                new DocxCorrector.Replacement("signer_name", "А.С. Петрова", "  ", null)));

        assertThat(result.applied()).isEmpty();
        assertThat(result.failed()).containsEntry("signer_name", DocxCorrector.Failure.EMPTY_VALUE);
        assertThat(result.content()).isSameAs(docx);
    }

    @Test
    void quoteChoosesTheRightOccurrenceOfShortValue() throws IOException {
        byte[] docx = docx("""
                <w:p><w:r><w:t>№ 119 от 15.09.2026</w:t></w:r></w:p>
                <w:p><w:r><w:t>Сумма 119 рублей</w:t></w:r></w:p>
                """);

        DocxCorrector.Result result = corrector.apply(docx, List.of(
                new DocxCorrector.Replacement("reg_number", "119", "СЗ-119", "№ 119")));

        assertThat(result.applied()).containsExactly("reg_number");
        assertThat(text(result.content())).isEqualTo("№ СЗ-119 от 15.09.2026\nСумма 119 рублей");
    }

    /** Настоящий тестовый документ: подпись встречается дважды («от кого» и расшифровка) — правятся оба места. */
    @Test
    void correctsRealTestDocumentAndItStaysReadable() throws IOException {
        byte[] docx = Files.readAllBytes(Path.of("..", "testdata", "documents", "01_memo_ok.docx"));

        DocxCorrector.Result result = corrector.apply(docx, List.of(
                new DocxCorrector.Replacement("doc_date", "15.09.2026", "16.09.2026", null),
                new DocxCorrector.Replacement("signer_name", "А.С. Петрова", "Петрова А.С.", null)));

        assertThat(result.applied()).containsExactly("doc_date", "signer_name");
        String text = text(result.content());
        assertThat(text).contains("16.09.2026").doesNotContain("15.09.2026").contains("Петрова А.С.")
                .doesNotContain("А.С. Петрова");
    }

    private static String text(byte[] docx) {
        return new PdfDocxTextExtractor()
                .extract(new ByteArrayInputStream(docx), DocxCorrector.DOCX)
                .map(TextExtractor.ExtractedText::fullText)
                .orElseThrow();
    }

    private static String documentXml(byte[] docx) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("нет word/document.xml");
    }

    private static byte[] docx(String body) throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"" + W + "\"><w:body>" + body.strip() + "</w:body></w:document>";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
