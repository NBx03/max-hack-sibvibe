package ru.sibvibe.approval.document.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFileValidatorTest {

    private final DocumentFileValidator validator = new DocumentFileValidator();

    @Test
    void detectsValidPdfByContent() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }

        var result = validator.validateMain(file("renamed.bin", "text/plain", pdf));

        assertThat(result.mimeType()).isEqualTo("application/pdf");
        assertThat(result.fileName()).isEqualTo("renamed.bin");
    }

    @Test
    void detectsDocxByRequiredZipEntries() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<document/>".getBytes());
            zip.closeEntry();
        }

        var result = validator.validateMain(file("document.any", "application/octet-stream", output.toByteArray()));

        assertThat(result.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void legacyWordDocumentIsRejectedWithAdviceToSaveAsDocx() {
        byte[] ole2 = new byte[512];
        byte[] header = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(header, 0, ole2, 0, header.length);

        // DOC не принимаем, но говорим, как пересохранить
        assertThatThrownBy(() -> validator.validateMain(file("Приказ.DOC", "application/octet-stream", ole2)))
                .isInstanceOfSatisfying(DocumentApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
                    assertThat(exception.getMessage()).contains("сохраните как DOCX");
                });
        // тот же контейнер у старых XLS и PPT — по расширению это не документ
        assertThatThrownBy(() -> validator.validateMain(file("table.xls", "application/vnd.ms-excel", ole2)))
                .isInstanceOfSatisfying(DocumentApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_TYPE_NOT_ALLOWED"));
    }

    @Test
    void rejectsSpoofedCorruptEmptyAndOversizedFiles() {
        // Имя обещает PDF, внутри не он — говорим именно это, с советом
        assertThatThrownBy(() -> validator.validateMain(file("fake.pdf", "application/pdf", "not pdf".getBytes())))
                .isInstanceOfSatisfying(DocumentApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
                    assertThat(exception.getMessage()).startsWith("Файл повреждён или это не PDF");
                });
        assertThat(DocumentFileValidator.mismatchMessage("отчёт.DOCX")).startsWith("Файл повреждён или это не DOCX");
        assertThat(DocumentFileValidator.mismatchMessage("таблица.xlsx")).isEqualTo("Основной файл должен быть PDF или DOCX");
        // Сигнатура PDF на месте, но файл битый — тот же «файловый» код, что и у подмены: ошибка у зоны файла
        assertThatThrownBy(() -> validator.validateMain(file("broken.pdf", "application/pdf", "%PDF-broken".getBytes())))
                .isInstanceOfSatisfying(DocumentApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_INVALID");
                    assertThat(exception.getMessage()).startsWith("PDF повреждён и не открывается");
                });
        assertThatThrownBy(() -> validator.validateMain(file("empty.pdf", "application/pdf", new byte[0])))
                .isInstanceOfSatisfying(DocumentApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_INVALID"));
        assertThatThrownBy(() -> validator.validateMain(file(
                "large.pdf", "application/pdf", new byte[(int) DocumentFileValidator.MAX_FILE_SIZE + 1])))
                .isInstanceOfSatisfying(DocumentApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_TOO_LARGE"));
    }

    @Test
    void removesClientPathFromFileName() throws Exception {
        byte[] docx;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.closeEntry();
        }
        docx = output.toByteArray();

        assertThat(validator.validateMain(file("C:\\fakepath\\document.docx", "ignored", docx)).fileName())
                .isEqualTo("document.docx");
    }

    @Test
    void rejectsDocxWithTooManyEntries() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.closeEntry();
            for (int index = 0; index < DocumentFileValidator.MAX_DOCX_ENTRIES; index++) {
                zip.putNextEntry(new ZipEntry("extra/" + index));
                zip.closeEntry();
            }
        }

        assertThatThrownBy(() -> validator.validateMain(file(
                "bomb.docx", "application/octet-stream", output.toByteArray())))
                .isInstanceOfSatisfying(DocumentApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_INVALID"))
                .hasMessageContaining("не может быть проверен");
    }

    @Test
    void rejectsDocxWithExcessiveUncompressedSize() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] megabyte = new byte[1024 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            for (int index = 0; index <= DocumentFileValidator.MAX_DOCX_UNCOMPRESSED_SIZE / megabyte.length;
                    index++) {
                zip.write(megabyte);
            }
            zip.closeEntry();
        }

        assertThatThrownBy(() -> validator.validateMain(file(
                "bomb.docx", "application/octet-stream", output.toByteArray())))
                .isInstanceOfSatisfying(DocumentApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_INVALID"))
                .hasMessageContaining("слишком большой");
    }

    /**: приложения — PDF, DOCX, XLSX, PNG, JPG, тип по содержимому, а не по расширению. */
    @Test
    void attachmentTypeIsDetectedByContent() throws Exception {
        assertThat(validator.validateAttachment(file("Смета.bin", "", ooxml("xl/workbook.xml"))).mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(validator.validateAttachment(file("Письмо", "", ooxml("word/document.xml"))).mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(validator.validateAttachment(file("scan.pdf", "", "%PDF-1.7".getBytes())).mimeType())
                .isEqualTo("application/pdf");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0};
        assertThat(validator.validateAttachment(file("photo.jpg", "image/jpeg", png)).mimeType()).isEqualTo("image/png");
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0};
        assertThat(validator.validateAttachment(file("photo.jpg", "", jpeg)).mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void attachmentErrorsNameTheFile() {
        assertThatThrownBy(() -> validator.validateAttachment(file("Смета.xlsx", "", "not xlsx".getBytes())))
                .isInstanceOfSatisfying(DocumentApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
                    assertThat(exception.getMessage()).contains("«Смета.xlsx»").contains("XLSX");
                    // экран отличает ошибку приложения от ошибки основного файла только по details
                    assertThat(exception.details()).containsEntry("file", "ATTACHMENT").containsEntry("fileName", "Смета.xlsx");
                });
        assertThatThrownBy(() -> validator.validateAttachment(file(
                "big.png", "", new byte[(int) DocumentFileValidator.MAX_FILE_SIZE + 1])))
                .isInstanceOfSatisfying(DocumentApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_TOO_LARGE");
                    assertThat(exception.getMessage()).contains("«big.png»");
                    assertThat(exception.details()).containsEntry("file", "ATTACHMENT").containsEntry("fileName", "big.png");
                });
        assertThatThrownBy(() -> validator.validateAttachment(file("empty.pdf", "", new byte[0])))
                .isInstanceOfSatisfying(DocumentApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_INVALID");
                    assertThat(exception.getMessage()).contains("пустое");
                    assertThat(exception.details()).containsEntry("file", "ATTACHMENT");
                });
    }

    /**
     * Ревью: приложение Office распознаётся по оглавлению архива, без распаковки — таблица, которая распаковывается
     * больше лимита для основного DOCX, остаётся таблицей, а не ошибкой «слишком большой».
     */
    @Test
    void largeSpreadsheetAttachmentIsNotUnpacked() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] megabyte = new byte[1024 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setComment("комментарий архива");
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            for (int index = 0; index <= DocumentFileValidator.MAX_DOCX_UNCOMPRESSED_SIZE / megabyte.length; index++) {
                zip.write(megabyte);
            }
            zip.closeEntry();
        }

        assertThat(validator.validateAttachment(file("Большая смета.xlsx", "", output.toByteArray())).mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void zipDirectoryReaderRejectsWhatIsNotAnArchive() throws Exception {
        assertThat(DocumentFileValidator.zipEntryNames(new byte[0])).isEmpty();
        assertThat(DocumentFileValidator.zipEntryNames("PK not a zip at all".getBytes())).isEmpty();
        byte[] archive = ooxml("xl/workbook.xml");
        assertThat(DocumentFileValidator.zipEntryNames(archive)).containsExactlyInAnyOrder("[Content_Types].xml", "xl/workbook.xml");
        // обрезанный архив: каталога в конце нет
        assertThat(DocumentFileValidator.zipEntryNames(java.util.Arrays.copyOf(archive, archive.length - 10))).isEmpty();
    }

    @Test
    void spreadsheetIsNotAMainFile() throws Exception {
        assertThatThrownBy(() -> validator.validateMain(file("Смета.xlsx", "", ooxml("xl/workbook.xml"))))
                .isInstanceOfSatisfying(DocumentApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_TYPE_NOT_ALLOWED"));
    }

    private byte[] ooxml(String mainPart) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(mainPart));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private MockMultipartFile file(String name, String type, byte[] content) {
        return new MockMultipartFile("main", name, type, content);
    }
}
