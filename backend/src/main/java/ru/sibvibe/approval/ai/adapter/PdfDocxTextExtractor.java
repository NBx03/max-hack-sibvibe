package ru.sibvibe.approval.ai.adapter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.sibvibe.approval.ai.TextExtractor;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class PdfDocxTextExtractor implements TextExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfDocxTextExtractor.class);
    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    /** DOC: новые не принимаются; ветка — для черновиков, загруженных раньше: файл «без текста». */
    private static final String DOC = "application/msword";
    private static final String DOCUMENT_XML = "word/document.xml";
    private static final int MAX_INPUT_BYTES = 12 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 5_000_000;

    @Override
    public Optional<ExtractedText> extract(InputStream content, String mimeType) {
        if (content == null || mimeType == null) {
            return Optional.empty();
        }
        try {
            if (PDF.equals(mimeType)) {
                return Optional.of(extractPdf(readLimited(content)));
            }
            if (DOCX.equals(mimeType)) {
                return extractDocx(content);
            }
            if (DOC.equals(mimeType)) {
                return Optional.of(new ExtractedText(List.of(""), false));
            }
            return Optional.empty();
        } catch (IOException | RuntimeException | XMLStreamException exception) {
            LOGGER.warn("Не удалось извлечь текст документа формата {}", mimeType);
            return Optional.empty();
        }
    }

    private ExtractedText extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pages = new ArrayList<>(document.getNumberOfPages());
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(limitText(stripper.getText(document)));
            }
            return new ExtractedText(List.copyOf(pages), true);
        }
    }

    private Optional<ExtractedText> extractDocx(InputStream content)
            throws IOException, XMLStreamException {
        try (ZipInputStream zip = new ZipInputStream(content)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (DOCUMENT_XML.equals(entry.getName())) {
                    return Optional.of(new ExtractedText(List.of(readDocumentXml(zip)), false));
                }
            }
        }
        return Optional.empty();
    }

    private String readDocumentXml(InputStream content) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setIfSupported(factory, XMLInputFactory.SUPPORT_DTD, false);
        setIfSupported(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("Внешние сущности DOCX запрещены");
        });
        XMLStreamReader reader = factory.createXMLStreamReader(content);
        StringBuilder text = new StringBuilder();
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("t".equals(name)) {
                        appendLimited(text, reader.getElementText());
                    } else if ("tab".equals(name)) {
                        appendLimited(text, "\t");
                    } else if ("br".equals(name) || "cr".equals(name)) {
                        appendLimited(text, "\n");
                    }
                } else if (event == XMLStreamConstants.DTD) {
                    throw new XMLStreamException("DTD в DOCX запрещён");
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "p".equals(reader.getLocalName())) {
                    appendLimited(text, "\n");
                }
            }
        } finally {
            reader.close();
        }
        return text.toString().strip();
    }

    private static byte[] readLimited(InputStream content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = content.read(buffer)) != -1) {
            total += read;
            if (total > MAX_INPUT_BYTES) {
                throw new IOException("Файл превышает внутренний лимит извлечения");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String limitText(String value) throws IOException {
        if (value.length() > MAX_TEXT_CHARS) {
            throw new IOException("Извлечённый текст превышает внутренний лимит");
        }
        return value;
    }

    private static void appendLimited(StringBuilder target, String value) throws XMLStreamException {
        if (target.length() + value.length() > MAX_TEXT_CHARS) {
            throw new XMLStreamException("Извлечённый текст превышает внутренний лимит");
        }
        target.append(value);
    }

    private static void setIfSupported(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // XMLResolver и явный запрет DTD ниже остаются обязательной защитой.
        }
    }
}
