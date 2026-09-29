package ru.sibvibe.approval.ai;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Извлечение текста из файла - первый шаг конвейера проверки, до маскирования.
 *
 * Поддерживаются PDF (через PDFBox) и DOCX (без библиотек: zip-архив,
 * текст лежит в word/document.xml). Для остальных форматов возвращается пусто -
 * это не ошибка: документ всё равно хранится и идёт по маршруту как generic,
 * а поля автор может заполнить вручную.
 *
 * Постраничная разбивка нужна для навигации к замечанию. У PDF страницы есть,
 * у DOCX страниц как таковых нет - там возвращается одна "страница" с номером 1,
 * и навигация идёт по цитате, а не по номеру.
 */
public interface TextExtractor {

    Optional<ExtractedText> extract(InputStream content, String mimeType);

    /**
     * @param pages текст по страницам, в порядке следования; номер = индекс + 1
     * @param paged true, если номера страниц осмысленны (PDF), false для DOCX
     */
    record ExtractedText(List<String> pages, boolean paged) {

        public String fullText() {
            return String.join("\n", pages);
        }
    }
}
