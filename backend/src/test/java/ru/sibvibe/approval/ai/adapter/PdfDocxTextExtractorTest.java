package ru.sibvibe.approval.ai.adapter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDocxTextExtractorTest {

    private final PdfDocxTextExtractor extractor = new PdfDocxTextExtractor();

    @Test
    void extractsPdfPageByPage() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            addPage(document, "First page");
            addPage(document, "Second page");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            pdf = output.toByteArray();
        }

        var result = extractor.extract(new ByteArrayInputStream(pdf), "application/pdf");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().paged()).isTrue();
        assertThat(result.orElseThrow().pages()).hasSize(2);
        assertThat(result.orElseThrow().pages().get(0)).contains("First page");
        assertThat(result.orElseThrow().pages().get(1)).contains("Second page");
    }

    @Test
    void extractsDocxWithJdkZipAndKeepsParagraphs() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>Первая строка</w:t></w:r></w:p>
                  <w:p><w:r><w:t>Вторая строка</w:t></w:r></w:p></w:body>
                </w:document>
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        var result = extractor.extract(new ByteArrayInputStream(output.toByteArray()),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().paged()).isFalse();
        assertThat(result.orElseThrow().fullText()).contains("Первая строка", "Вторая строка");
    }

    @Test
    void unsupportedOrBrokenFileDoesNotEscapeAsException() {
        assertThat(extractor.extract(new ByteArrayInputStream(new byte[]{1, 2}), "image/png")).isEmpty();
        assertThat(extractor.extract(new ByteArrayInputStream(new byte[]{1, 2}), "application/pdf")).isEmpty();
    }

    private void addPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(50, 700);
            content.showText(text);
            content.endText();
        }
    }
}
