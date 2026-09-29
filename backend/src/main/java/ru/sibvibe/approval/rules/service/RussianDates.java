package ru.sibvibe.approval.rules.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор полной даты в тех видах, в каких её пишут в российских документах: цифровом
 * («23.09.2026», «23.09.2026 г.») и словесно-цифровом («"23" сентября 2026 г.», «23 сентября
 * 2026 года») — оба способа допускает ГОСТ Р 7.0.97-2016, п. 5.10. Плюс ISO «2026-09-23».
 *
 * Дата без года («1 октября») или с годом из двух цифр («23.09.26») не разбирается: по ней
 * нельзя понять, о каком годе речь, — это и есть замечание правила о формате даты.
 *
 * Тот же алгоритм повторён в testdata/generate.py (parse_ru_date): эталонные замечания
 * тестовых документов считаются им, и они обязаны совпадать с движком.
 */
public final class RussianDates {

    /** Значение {@code expected} правила даты: «любая полная дата в принятом виде». */
    public static final String RU_DATE = "RU_DATE";

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("января", 1), Map.entry("февраля", 2), Map.entry("марта", 3),
            Map.entry("апреля", 4), Map.entry("мая", 5), Map.entry("июня", 6),
            Map.entry("июля", 7), Map.entry("августа", 8), Map.entry("сентября", 9),
            Map.entry("октября", 10), Map.entry("ноября", 11), Map.entry("декабря", 12));

    // Кавычки вокруг числа: «23», "23", “23”, ‘23’ и их смесь — как ставит Word и как пишут от руки.
    private static final Pattern QUOTES = Pattern.compile("[«»\"“”„‘’']");
    // Хвост «г.», «г», «год», «года» — с пробелом или без («2026г.»).
    private static final Pattern YEAR_SUFFIX = Pattern.compile("\\s*(г\\.?|год|года)\\s*$");
    private static final Pattern DIGITAL = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})");
    private static final Pattern VERBAL = Pattern.compile("(\\d{1,2})\\s+([а-яё]+)\\s+(\\d{4})");
    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    private RussianDates() {
    }

    public static Optional<LocalDate> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String text = QUOTES.matcher(value).replaceAll("")
                .replace(' ', ' ')
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
        text = YEAR_SUFFIX.matcher(text).replaceFirst("").strip();

        Matcher digital = DIGITAL.matcher(text);
        if (digital.matches()) {
            return date(digital.group(3), digital.group(2), digital.group(1));
        }
        Matcher verbal = VERBAL.matcher(text);
        if (verbal.matches()) {
            Integer month = MONTHS.get(verbal.group(2));
            return month == null ? Optional.empty() : date(verbal.group(3), month.toString(), verbal.group(1));
        }
        Matcher iso = ISO.matcher(text);
        if (iso.matches()) {
            return date(iso.group(1), iso.group(2), iso.group(3));
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> date(String year, String month, String day) {
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day)));
        } catch (DateTimeException | NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
