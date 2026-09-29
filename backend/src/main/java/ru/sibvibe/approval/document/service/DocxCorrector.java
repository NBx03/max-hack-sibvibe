package ru.sibvibe.approval.document.service;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Вписывает исправленные значения полей прямо в DOCX: раньше исправленное поле жило только
 * в карточке, а согласующий получал прежний файл. Работает средствами JDK — DOCX это zip с
 * {@code word/document.xml}; Apache POI не подключаем (лимит сборки, docs/DESIGN-DECISIONS.md).
 *
 * Старое значение ищется в тексте абзацев так, как его видит Word: текст абзаца — это подряд
 * идущие {@code w:t}, одно слово может быть разбито на несколько фрагментов с разным оформлением.
 * Замена кладётся в первый фрагмент совпадения (его оформление и остаётся), из остальных
 * фрагментов совпадение вырезается — разметка, стили, таблицы и всё прочее в файле не меняются.
 *
 * Не делается ничего сомнительного: значение, которого нет в тексте, которое встречается
 * несколько раз и неотличимо по цитате, или которое занимает несколько абзацев, не трогается —
 * в ответе причина, человек исправит это в самом документе.
 */
@Component
public class DocxCorrector {

    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String DOCUMENT_XML = "word/document.xml";
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int MAX_ENTRIES = 1_000;
    /** Тот же общий лимит распаковки, что при загрузке (DocumentFileValidator): защита от zip-бомбы. */
    private static final long MAX_UNCOMPRESSED_BYTES = DocumentFileValidator.MAX_DOCX_UNCOMPRESSED_SIZE;
    /** Короткие значения («119», «14») слишком легко совпадают с чужим текстом: только одно вхождение. */
    private static final int MIN_LENGTH_TO_REPLACE_ALL = 6;

    public Result apply(byte[] docx, List<Replacement> replacements) {
        Map<String, byte[]> entries = unzip(docx);
        byte[] documentXml = entries.get(DOCUMENT_XML);
        if (documentXml == null) {
            throw DocumentApiException.validation("Файл не похож на документ Word: нет word/document.xml");
        }
        Document xml = parse(documentXml);
        List<Paragraph> paragraphs = paragraphs(xml);
        List<String> applied = new ArrayList<>();
        Map<String, Failure> failed = new LinkedHashMap<>();
        for (Replacement replacement : replacements) {
            Failure failure = replace(paragraphs, replacement);
            if (failure == null) {
                applied.add(replacement.field());
            } else {
                failed.put(replacement.field(), failure);
            }
        }
        if (applied.isEmpty()) {
            return new Result(docx, applied, failed);
        }
        entries.put(DOCUMENT_XML, serialize(xml));
        return new Result(zip(docx, entries), applied, failed);
    }

    private Failure replace(List<Paragraph> paragraphs, Replacement replacement) {
        String oldValue = replacement.oldValue();
        if (oldValue == null || oldValue.isBlank()) {
            return Failure.NOT_IN_FILE;
        }
        // Пустое значение вырезало бы текст из документа, например ФИО из подписи.
        if (replacement.newValue() == null || replacement.newValue().isBlank()) {
            return Failure.EMPTY_VALUE;
        }
        if (oldValue.strip().contains("\n")) {
            return Failure.SPANS_PARAGRAPHS;
        }
        Pattern pattern = flexible(oldValue.strip());
        List<Match> matches = new ArrayList<>();
        for (Paragraph paragraph : paragraphs) {
            Matcher matcher = pattern.matcher(paragraph.text());
            while (matcher.find()) {
                matches.add(new Match(paragraph, matcher.start(), matcher.end()));
            }
        }
        if (matches.isEmpty()) {
            return Failure.NOT_FOUND;
        }
        List<Match> chosen = matches;
        if (matches.size() > 1) {
            chosen = narrowByQuote(matches, replacement.quote(), pattern);
            if (chosen.size() != 1 && oldValue.strip().length() < MIN_LENGTH_TO_REPLACE_ALL) {
                return Failure.AMBIGUOUS;
            }
            if (chosen.isEmpty()) {
                // Имя встречается несколько раз (подпись и «от кого») — исправляем везде, иначе документ
                // разошёлся бы сам с собой. Дату или номер — нет: та же дата в тексте («с 15.09.2026»)
                // означает другое, и замена молча поменяла бы смысл документа.
                if (oldValue.codePoints().noneMatch(Character::isLetter)) {
                    return Failure.AMBIGUOUS;
                }
                chosen = matches;
            }
        }
        // С конца абзаца к началу: замена не сдвигает позиции ещё не обработанных совпадений.
        for (int index = chosen.size() - 1; index >= 0; index--) {
            Match match = chosen.get(index);
            match.paragraph().replace(match.start(), match.end(), replacement.newValue());
        }
        return null;
    }

    private static List<Match> narrowByQuote(List<Match> matches, String quote, Pattern value) {
        if (quote == null || quote.isBlank()) {
            return List.of();
        }
        Pattern quotePattern = flexible(quote.strip());
        List<Match> result = new ArrayList<>();
        for (Match match : matches) {
            Matcher matcher = quotePattern.matcher(match.paragraph().text());
            while (matcher.find()) {
                if (matcher.start() <= match.start() && match.end() <= matcher.end()) {
                    result.add(match);
                    break;
                }
            }
        }
        return result;
    }

    /** Пробелы в значении — любые пробельные символы в файле: модель схлопывает двойные пробелы и табуляции. */
    private static Pattern flexible(String value) {
        String[] words = value.split("\\s+");
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < words.length; index++) {
            if (index > 0) {
                regex.append("\\s+");
            }
            regex.append(Pattern.quote(words[index]));
        }
        return Pattern.compile(regex.toString());
    }

    private static List<Paragraph> paragraphs(Document xml) {
        NodeList nodes = xml.getElementsByTagNameNS(W, "p");
        List<Paragraph> result = new ArrayList<>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            Element paragraph = (Element) nodes.item(index);
            List<Element> texts = new ArrayList<>();
            collectTexts(paragraph, paragraph, texts);
            result.add(new Paragraph(texts));
        }
        return result;
    }

    /** w:t этого абзаца — без вложенных абзацев (надписи внутри абзаца — свои w:p). */
    private static void collectTexts(Element root, Node node, List<Element> texts) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            if (W.equals(element.getNamespaceURI()) && "p".equals(element.getLocalName()) && element != root) {
                continue;
            }
            if (W.equals(element.getNamespaceURI()) && "t".equals(element.getLocalName())) {
                texts.add(element);
            } else {
                collectTexts(root, element, texts);
            }
        }
    }

    private static Document parse(byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(content));
        } catch (Exception exception) {
            throw DocumentApiException.validation("Не удалось прочитать текст документа Word");
        }
    }

    private static byte[] serialize(Document xml) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            xml.setXmlStandalone(true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(xml), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось сохранить исправленный документ", exception);
        }
    }

    private static Map<String, byte[]> unzip(byte[] docx) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entries.size() >= MAX_ENTRIES) {
                    throw DocumentApiException.validation("DOCX содержит слишком много записей");
                }
                byte[] content = readLimited(zip, MAX_UNCOMPRESSED_BYTES - total);
                total += content.length;
                entries.put(entry.getName(), content);
            }
        } catch (IOException exception) {
            throw DocumentApiException.validation("Не удалось открыть документ Word");
        }
        return entries;
    }

    private static byte[] readLimited(InputStream input, long remaining) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > remaining) {
                throw DocumentApiException.validation("Распакованный DOCX слишком большой");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /** Порядок записей — как в исходном файле: Word чувствителен к первой записи [Content_Types].xml. */
    private static byte[] zip(byte[] original, Map<String, byte[]> entries) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(original.length + 1024);
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось собрать исправленный документ", exception);
        }
        return output.toByteArray();
    }

    /**
     * @param oldValue значение, как оно написано в файле
     * @param quote    цитата из файла, в которой модель нашла значение: уточняет, какое из вхождений
     */
    public record Replacement(String field, String oldValue, String newValue, String quote) {}

    public record Result(byte[] content, List<String> applied, Map<String, Failure> failed) {}

    public enum Failure {
        NOT_IN_FILE("этого поля нет в файле — допишите его в документ и загрузите исправленный файл"),
        NOT_FOUND("не нашли это значение в тексте файла — исправьте его в самом документе"),
        AMBIGUOUS("значение встречается в файле несколько раз — исправьте его в самом документе"),
        SPANS_PARAGRAPHS("значение занимает несколько абзацев — исправьте его в самом документе"),
        EMPTY_VALUE("пустое значение в файл не вписываем — удалить текст можно только в самом документе");

        private final String message;

        Failure(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private record Match(Paragraph paragraph, int start, int end) {}

    /** Текст абзаца как склейка его w:t и замена диапазона этого текста с сохранением фрагментов. */
    private static final class Paragraph {

        private final List<Element> texts;

        Paragraph(List<Element> texts) {
            this.texts = texts;
        }

        String text() {
            StringBuilder builder = new StringBuilder();
            texts.forEach(element -> builder.append(element.getTextContent()));
            return builder.toString();
        }

        void replace(int start, int end, String replacement) {
            int offset = 0;
            boolean inserted = false;
            for (Element element : texts) {
                String value = element.getTextContent();
                int from = offset;
                int to = offset + value.length();
                offset = to;
                if (to <= start || from >= end) {
                    continue;
                }
                int cutFrom = Math.max(start, from) - from;
                int cutTo = Math.min(end, to) - from;
                String updated = value.substring(0, cutFrom) + (inserted ? "" : replacement) + value.substring(cutTo);
                inserted = true;
                element.setTextContent(updated);
                // Пробелы на краях фрагмента Word иначе отбросит.
                element.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
            }
        }
    }
}
