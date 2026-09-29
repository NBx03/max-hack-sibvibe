package ru.sibvibe.approval.document.service;

import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class DocumentFileValidator {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final long MAX_VERSION_SIZE = 30L * 1024 * 1024;
    public static final int MAX_ATTACHMENTS = 10;
    static final int MAX_DOCX_ENTRIES = 1_000;
    static final long MAX_DOCX_UNCOMPRESSED_SIZE = 50L * 1024 * 1024;

    private static final String PDF = "application/pdf";
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PNG = "image/png";
    private static final String JPEG = "image/jpeg";
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    static final String ATTACHMENT_TYPES = "PDF, DOCX, XLSX, PNG или JPG";
    private static final String CONTENT_TYPES = "[Content_Types].xml";
    private static final String DOCX_MAIN_PART = "word/document.xml";
    private static final String XLSX_MAIN_PART = "xl/workbook.xml";
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    public ValidatedFile validateMain(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw DocumentApiException.fileInvalid("Файл пустой. Выберите файл документа ещё раз");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw DocumentApiException.fileTooLarge("Размер файла превышает 10 МБ");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw DocumentApiException.fileInvalid("Не удалось прочитать файл. Выберите его ещё раз");
        }
        if (content.length == 0) {
            throw DocumentApiException.fileInvalid("Файл пустой. Выберите файл документа ещё раз");
        }
        String mimeType = detectMainType(content, fileName);
        return new ValidatedFile(content, fileName, mimeType);
    }

    /**
     * Приложение: хранится и скачивается, но не анализируется — поэтому тип проверяется только по содержимому,
     * без разбора PDF. В ошибке — имя файла: приложений до десяти, человек должен понять, какое не подошло.
     */
    public ValidatedFile validateAttachment(MultipartFile file) {
        String fileName = safeFileName(file.getOriginalFilename());
        if (file.isEmpty()) {
            throw DocumentApiException.fileInvalid("Приложение «" + fileName + "» пустое").aboutAttachment(fileName);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw DocumentApiException.fileTooLarge("Приложение «" + fileName + "» больше 10 МБ").aboutAttachment(fileName);
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw DocumentApiException.fileInvalid("Не удалось прочитать приложение «" + fileName + "»")
                    .aboutAttachment(fileName);
        }
        if (content.length == 0) {
            throw DocumentApiException.fileInvalid("Приложение «" + fileName + "» пустое").aboutAttachment(fileName);
        }
        return new ValidatedFile(content, fileName, detectAttachmentType(content, fileName));
    }

    private String detectAttachmentType(byte[] content, String fileName) {
        if (isPdf(content)) {
            return PDF;
        }
        if (startsWith(content, PNG_SIGNATURE)) {
            return PNG;
        }
        if (content.length >= 3 && content[0] == (byte) 0xFF && content[1] == (byte) 0xD8 && content[2] == (byte) 0xFF) {
            return JPEG;
        }
        // Office — по оглавлению архива, без распаковки: приложение не читается, а таблица сжимается сильнее
        // документа, и честный XLSX на несколько мегабайт упёрся бы в лимит распаковки DOCX.
        Set<String> entries = zipEntryNames(content);
        if (entries.contains(CONTENT_TYPES) && entries.contains(DOCX_MAIN_PART)) {
            return DOCX;
        }
        if (entries.contains(CONTENT_TYPES) && entries.contains(XLSX_MAIN_PART)) {
            return XLSX;
        }
        throw DocumentApiException.fileTypeNotAllowed(
                "Приложение «" + fileName + "» не подходит: нужен " + ATTACHMENT_TYPES).aboutAttachment(fileName);
    }

    private String detectMainType(byte[] content, String fileName) {
        if (isPdf(content)) {
            validatePdf(content);
            return PDF;
        }
        if (isDocx(content)) {
            return DOCX;
        }
        if (isDoc(content, fileName)) {
            // DOC не принимаем — ИИ его не читает, а полуработающий формат выглядит недоделкой.
            // Говорим, как пересохранить: это десять секунд в Word.
            throw DocumentApiException.fileTypeNotAllowed(DOC_NOT_SUPPORTED);
        }
        throw DocumentApiException.fileTypeNotAllowed(mismatchMessage(fileName));
    }

    /**
     * Имя обещает PDF или DOCX, а внутри не он — файл повреждён или переименован: так и говорим, с советом, а не общее
     * «должен быть PDF или DOCX» при файле .pdf.
     */
    static String mismatchMessage(String fileName) {
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return "Файл повреждён или это не PDF. Откройте его и сохраните заново как PDF";
        }
        if (name.endsWith(".docx")) {
            return "Файл повреждён или это не DOCX. Откройте его в Word и сохраните заново";
        }
        return "Основной файл должен быть PDF или DOCX";
    }

    static final String DOC_NOT_SUPPORTED = "DOC — устаревший формат Word. Откройте файл в Word и сохраните как DOCX: "
            + "«Файл → Сохранить как → Документ Word (.docx)»";

    /** DOC — контейнер OLE2 с расширением .doc: распознаём только ради понятной ошибки с советом. */
    private boolean isDoc(byte[] content, String fileName) {
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".doc") && startsWith(content, OLE2);
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean isPdf(byte[] content) {
        return content.length >= 5
                && content[0] == '%'
                && content[1] == 'P'
                && content[2] == 'D'
                && content[3] == 'F'
                && content[4] == '-';
    }

    private void validatePdf(byte[] content) {
        try (var document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() < 1) {
                throw DocumentApiException.fileInvalid("В PDF нет ни одной страницы. Сохраните документ в PDF заново");
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof DocumentApiException apiException) {
                throw apiException;
            }
            throw DocumentApiException.fileInvalid("PDF повреждён и не открывается. Откройте исходный документ и сохраните его в PDF заново");
        }
    }

    /**
     * Основной DOCX: его текст потом читается, поэтому архив распаковывается целиком — с ограничением числа записей
     * и распакованного размера (защита от zip-бомбы).
     */
    private boolean isDocx(byte[] content) {
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
            return false;
        }
        Set<String> required = new HashSet<>(Set.of(CONTENT_TYPES, DOCX_MAIN_PART));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int entries = 0;
            long uncompressedSize = 0;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_DOCX_ENTRIES) {
                    throw DocumentApiException.fileInvalid("DOCX устроен необычно и не может быть проверен. Сохраните его в Word заново");
                }
                required.remove(entry.getName());
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    uncompressedSize += read;
                    if (uncompressedSize > MAX_DOCX_UNCOMPRESSED_SIZE) {
                        throw DocumentApiException.fileInvalid("DOCX слишком большой после распаковки. Сохраните его в Word заново без лишних вложений");
                    }
                }
            }
            return required.isEmpty();
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Имена записей zip-архива из центрального каталога — без распаковки содержимого. Не zip или каталог не сходится
     * с размером файла — пустое множество. ZIP64 не разбираем: у файла до 10 МБ его не бывает.
     */
    static Set<String> zipEntryNames(byte[] content) {
        int end = findEndOfCentralDirectory(content);
        if (end < 0) {
            return Set.of();
        }
        int count = readShort(content, end + 10);
        long directoryOffset = readInt(content, end + 16);
        Set<String> names = new HashSet<>();
        long position = directoryOffset;
        for (int index = 0; index < count; index++) {
            if (position < 0 || position + 46 > end || readInt(content, (int) position) != 0x02014b50L) {
                return Set.of();
            }
            int at = (int) position;
            int nameLength = readShort(content, at + 28);
            int extraLength = readShort(content, at + 30);
            int commentLength = readShort(content, at + 32);
            if (at + 46L + nameLength > end) {
                return Set.of();
            }
            // Нужные нам имена — латиница: ISO-8859-1 читает их одинаково при любой кодировке имён архива.
            names.add(new String(content, at + 46, nameLength, java.nio.charset.StandardCharsets.ISO_8859_1));
            position = at + 46L + nameLength + extraLength + commentLength;
        }
        return names;
    }

    /** Конец центрального каталога: 22 байта в конце архива плюс комментарий до 64 КБ. */
    private static int findEndOfCentralDirectory(byte[] content) {
        int lowest = Math.max(0, content.length - 22 - 0xFFFF);
        for (int at = content.length - 22; at >= lowest; at--) {
            if (readInt(content, at) == 0x06054b50L && at + 22 + readShort(content, at + 20) == content.length) {
                return at;
            }
        }
        return -1;
    }

    private static int readShort(byte[] content, int at) {
        return (content[at] & 0xFF) | (content[at + 1] & 0xFF) << 8;
    }

    private static long readInt(byte[] content, int at) {
        return readShort(content, at) | (long) readShort(content, at + 2) << 16;
    }

    private String safeFileName(String original) {
        if (original == null || original.isBlank()) {
            throw DocumentApiException.validation("У файла отсутствует имя");
        }
        String normalized = original.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (fileName.isBlank() || fileName.length() > 255
                || fileName.chars().anyMatch(character -> character < 32)) {
            throw DocumentApiException.validation("Некорректное имя файла");
        }
        return fileName;
    }

    public record ValidatedFile(byte[] content, String fileName, String mimeType) {
        public long size() {
            return content.length;
        }
    }
}
