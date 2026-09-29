package ru.sibvibe.approval.ai.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.sibvibe.approval.ai.DocumentClassifier;
import ru.sibvibe.approval.ai.DocumentExtractor;
import ru.sibvibe.approval.ai.TextExtractor;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DocumentExtractionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentExtractionService.class);

    /** Вид документа понятен по началу: заголовок, адресат, первые абзацы. Минимизация — и здесь. */
    private static final int CLASSIFICATION_TEXT_LIMIT = 4_000;
    /**
     * Меньше букв — считаем, что текста нет: у скана PDF текстового слоя нет вовсе или в нём только
     * номера страниц и мусор распознавания.
     */
    private static final int MIN_TEXT_LETTERS = 10;
    /** Любые пробелы, включая неразрывный и перевод строки. */
    private static final String WHITESPACE = "[\\s\\u00A0]+";
    /** Целое число, дата, сумма или другой числовой фрагмент — проверяется целиком и дословно. */
    private static final Pattern SUMMARY_NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])\\d+(?:(?:[ \\u00A0\\u202F]+|[.,:/-])\\d+)*(?![\\p{L}\\p{N}_])");
    /** Дата после числа дня по-русски всегда родительный падеж («15 января»), поэтому здесь — только он. */
    private static final String MONTH_NAMES =
            "января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря";
    /**
     * Число дня может стоять в кавычках — канцелярская запись «18» сентября 2026 г. (без этого
     * такая дата в документе не узнавалась, и верная сводка отбрасывалась).
     */
    private static final Pattern SUMMARY_WORD_DATE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(\\d{1,2})[»\"“]?[\\s\\u00A0\\u202F]+(" + MONTH_NAMES + ")"
                    + "(?:[\\s\\u00A0\\u202F]+(\\d{4}))?(?![\\p{L}\\p{N}_])");
    /**
     * Название месяца отдельно от даты целиком: неверный месяц отбрасывает сводку, даже когда числа дня рядом нет
     * («в декабре»). Без числа дня месяц чаще стоит в предложном падеже («в декабре», не «в декабря»), поэтому
     * для каждого месяца задан закрытый список падежных окончаний. Основа с произвольным хвостом не находила «мае»
     * и принимала за месяц фамилии «Мартынов», «Августова».
     *
     * Флаги {@code (?iu)}, а не только {@code (?i)}: без {@code UNICODE_CASE} регистронезависимость в Java
     * действует только для ASCII, и «Май» с заглавной буквы не совпал бы ни с одной формой.
     */
    private static final Pattern[] MONTH_STEM_PATTERNS = {
            Pattern.compile("(?iu)январ(?:ь|я|е|ю|ём)"),
            Pattern.compile("(?iu)феврал(?:ь|я|е|ю|ём)"),
            Pattern.compile("(?iu)март(?:а|у|е|ом)?"),
            Pattern.compile("(?iu)апрел(?:ь|я|е|ю|ём)"),
            Pattern.compile("(?iu)ма(?:й|я|ю|е|ем)"),
            Pattern.compile("(?iu)июн(?:ь|я|е|ю|ём)"),
            Pattern.compile("(?iu)июл(?:ь|я|е|ю|ём)"),
            Pattern.compile("(?iu)август(?:а|у|е|ом)?"),
            Pattern.compile("(?iu)сентябр(?:ь|я|е|ю|ём)"),
            Pattern.compile("(?iu)октябр(?:ь|я|е|ю|ём)"),
            Pattern.compile("(?iu)ноябр(?:ь|я|е|ю|ём)"),
            Pattern.compile("(?iu)декабр(?:ь|я|е|ю|ём)"),
    };
    private static final String[] MONTH_STEM_NAMES = {
            "январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр",
    };
    private static final Pattern SUMMARY_MONTH_WORD = Pattern.compile("(?u)(?<![\\p{L}])("
            + Arrays.stream(MONTH_STEM_PATTERNS).map(Pattern::pattern).collect(Collectors.joining("|"))
            + ")(?![\\p{L}])");
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("<[A-Z]+_\\d+>");
    private static final Pattern NUMBER_SPACES = Pattern.compile("[ \\u00A0\\u202F]+");

    /**
     * Сводка не ждётся дольше этого потолка сверх бюджета полей:
     * иначе редкий медленный ответ растягивал бы загрузку почти до второго полного тайм-аута.
     */
    private static final Duration SUMMARY_MAX_WAIT = Duration.ofSeconds(15);

    private final TextExtractor textExtractor;
    private final DocumentExtractor documentExtractor;
    private final DocumentClassifier classifier;
    private final TextMinimizer minimizer;
    private final SensitiveDataMasker masker;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DocumentExtractionService(
            TextExtractor textExtractor,
            DocumentExtractor documentExtractor,
            DocumentClassifier classifier,
            TextMinimizer minimizer,
            SensitiveDataMasker masker,
            @Value("${app.ai.timeout-seconds:30}") long timeoutSeconds
    ) {
        this.textExtractor = textExtractor;
        this.documentExtractor = documentExtractor;
        this.classifier = classifier;
        this.minimizer = minimizer;
        this.masker = masker;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    /**
     * @param fields        поля и статус модели — как раньше
     * @param summary       проверенная краткая сводка; {@code null}, если {@code summaryStatus} не
     *                      {@link SummaryStatus#PRESENT}
     * @param summaryStatus почему сводки нет, если её нет:
     *                      документ, для которого поля недоступны, всегда получает {@code NOT_ATTEMPTED}
     */
    public record ExtractionOutcome(
            DocumentExtractor.ExtractionResult fields, String summary, SummaryStatus summaryStatus
    ) {}

    public enum SummaryStatus {
        /** Сводка есть и прошла проверку фактов. */
        PRESENT,
        /** Не вызывали вовсе: поля недоступны, или к их получению бюджет времени уже кончился. */
        NOT_ATTEMPTED,
        /** Модель была недоступна для сводки — тайм-аут, ошибка вызова или формат не восстановился за повторы. */
        MODEL_UNAVAILABLE,
        /** Модель сама ответила, что сводки нет («НЕТ» или пустой ответ). */
        NO_SUMMARY_FROM_MODEL,
        /** Проверка фактов отбросила ответ целиком: число или дата не нашлись в тексте документа. */
        REJECTED_BY_FACT_CHECK,
    }

    public ExtractionOutcome extract(
            InputStream content,
            String mimeType,
            String documentTypeCode,
            List<DocumentExtractor.FieldSpec> schema
    ) {
        Optional<TextExtractor.ExtractedText> extracted = textExtractor.extract(content, mimeType);
        if (extracted.isEmpty()) {
            return new ExtractionOutcome(unavailable(), null, SummaryStatus.NOT_ATTEMPTED);
        }
        if (!hasText(extracted.get())) {
            return new ExtractionOutcome(
                    new DocumentExtractor.ExtractionResult(Map.of(), false, DocumentExtractor.NO_TEXT),
                    null, SummaryStatus.NOT_ATTEMPTED);
        }
        TextExtractor.ExtractedText original = extracted.get();
        String minimized = minimizer.minimize(original.fullText(), schema);
        SensitiveDataMasker.MaskedText masked = masker.mask(minimized);

        // Последовательно, не параллельно: личный аккаунт GigaChat
        // (scope GIGACHAT_API_PERS) допускает только один поток вызовов одновременно — два параллельных запроса
        // на документ гарантированно роняют один из них 429 «Too Many Requests», и это било по полям, не только
        // по сводке. Сначала поля с полным бюджетом; сводка — только в оставшееся время,
        // дополнительно ограниченное отдельным потолком, чтобы редкий медленный ответ не растягивал загрузку
        // почти до второго полного тайм-аута.
        long fieldsDeadline = System.nanoTime() + timeout.toNanos();
        DocumentExtractor.ExtractionResult model = documentExtractor.extract(
                new DocumentExtractor.ExtractionRequest(masked.text(), documentTypeCode, schema));
        if (!model.available()) {
            return new ExtractionOutcome(model, null, SummaryStatus.NOT_ATTEMPTED);
        }

        Map<String, DocumentExtractor.ExtractedField> restored = new LinkedHashMap<>();
        for (DocumentExtractor.FieldSpec spec : schema) {
            DocumentExtractor.ExtractedField field = model.fields().get(spec.fieldName());
            if (field == null) {
                continue;
            }
            String value = masked.restore(field.value());
            String quote = masked.restore(field.quote());
            VerifiedQuote verified = verifyQuote(original, quote);
            restored.put(spec.fieldName(), new DocumentExtractor.ExtractedField(
                    value, verified.valid() ? verified.quote() : null, verified.valid() ? verified.page() : null));
        }
        DocumentExtractor.ExtractionResult extractionResult =
                new DocumentExtractor.ExtractionResult(Map.copyOf(restored), true, model.providerId());

        long summaryBudget = Math.min(fieldsDeadline - System.nanoTime(), SUMMARY_MAX_WAIT.toNanos());
        if (summaryBudget <= 0) {
            return summaryOutcome(extractionResult, null, SummaryStatus.NOT_ATTEMPTED, summaryBudget, 0);
        }
        long summaryStarted = System.nanoTime();
        DocumentExtractor.SummaryResult summaryResult = summarizeWithinBudget(masked.text(), summaryBudget);
        long summaryNanos = System.nanoTime() - summaryStarted;
        if (!summaryResult.available()) {
            return summaryOutcome(extractionResult, null, SummaryStatus.MODEL_UNAVAILABLE, summaryBudget, summaryNanos);
        }
        if (summaryResult.summary() == null) {
            return summaryOutcome(extractionResult, null, SummaryStatus.NO_SUMMARY_FROM_MODEL, summaryBudget, summaryNanos);
        }
        String summary = masked.restore(summaryResult.summary());
        Optional<String> unsupported = unsupportedFact(summary, original.fullText());
        if (unsupported.isPresent()) {
            LOGGER.info("Сводка ИИ отброшена: в документе не нашлось — {}", unsupported.get());
            return summaryOutcome(extractionResult, null, SummaryStatus.REJECTED_BY_FACT_CHECK, summaryBudget, summaryNanos);
        }
        return summaryOutcome(extractionResult, summary, SummaryStatus.PRESENT, summaryBudget, summaryNanos);
    }

    /**
     * Почему у документа нет «Кратко от ИИ» — в лог («иногда есть, иногда нет», а причина
     * нигде не видна). Текст сводки в лог не пишется — только исход и время.
     */
    private static ExtractionOutcome summaryOutcome(
            DocumentExtractor.ExtractionResult fields, String summary, SummaryStatus status, long budgetNanos, long tookNanos
    ) {
        LOGGER.info("Сводка ИИ: {} (бюджет {} мс, заняло {} мс)",
                status, Math.max(0, budgetNanos / 1_000_000), tookNanos / 1_000_000);
        return new ExtractionOutcome(fields, summary, status);
    }

    /**
     * Сводка вызывается только после того, как поля уже получены (не параллельно с ними) — не длиннее
     * {@code budgetNanos}. «Висит» или падает — задание отменяется, сводки просто нет, поля это уже не трогает.
     */
    private DocumentExtractor.SummaryResult summarizeWithinBudget(String maskedText, long budgetNanos) {
        Future<DocumentExtractor.SummaryResult> future = executor.submit(
                () -> documentExtractor.summarize(new DocumentExtractor.SummaryRequest(maskedText)));
        try {
            return future.get(budgetNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return new DocumentExtractor.SummaryResult(null, false);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new DocumentExtractor.SummaryResult(null, false);
        } catch (ExecutionException exception) {
            return new DocumentExtractor.SummaryResult(null, false);
        }
    }

    /**
     * Цитата принимается, только если она есть в тексте документа. Пробелы сравниваются без учёта
     * их вида и числа: модель возвращает перенос строки одним пробелом, и точное сравнение отбрасывало
     * верную цитату. Возвращается фрагмент самого документа, а не пересказ модели.
     */
    private VerifiedQuote verifyQuote(TextExtractor.ExtractedText text, String quote) {
        Optional<Pattern> pattern = quotePattern(quote);
        if (pattern.isEmpty()) {
            return new VerifiedQuote(false, null, null);
        }
        Matcher inFullText = pattern.get().matcher(text.fullText());
        if (!inFullText.find()) {
            return new VerifiedQuote(false, null, null);
        }
        if (!text.paged()) {
            return new VerifiedQuote(true, inFullText.group(), null);
        }
        for (int index = 0; index < text.pages().size(); index++) {
            Matcher onPage = pattern.get().matcher(text.pages().get(index));
            if (onPage.find()) {
                return new VerifiedQuote(true, onPage.group(), index + 1);
            }
        }
        return new VerifiedQuote(false, null, null);
    }

    private static Optional<Pattern> quotePattern(String quote) {
        if (quote == null || quote.isBlank()) {
            return Optional.empty();
        }
        String regex = Pattern.compile(WHITESPACE).splitAsStream(quote.strip())
                .map(Pattern::quote)
                .collect(Collectors.joining(WHITESPACE));
        return Optional.of(Pattern.compile(regex));
    }

    /**
     * Сводка принимается, только если каждое число, записанное цифрами, и каждая дата с месяцем словом в ней
     * дословно (для чисел) или по дню и месяцу (для дат) находятся в исходном тексте. Год у даты сверяется,
     * только если сводка его указала: дата без года — не то же самое, что дата
     * с неверным годом, и не должна отбрасываться зря. Слова-числа («пятьсот тысяч») эта проверка не ловит —
     * такого от неё не требуется (см. README §12, API_CONTRACTS.md): модель просят писать числа цифрами,
     * а не отгадывать их прописью в коде.
     */
    static boolean summarySupportedBy(String summary, String source) {
        return unsupportedFact(summary, source).isEmpty();
    }

    /**
     * Что из сводки не нашлось в документе — число, месяц или дата; пусто — сводка подтверждена. Нужно логу: без этого
     * было не понять, почему у документа нет «Кратко от ИИ».
     */
    static Optional<String> unsupportedFact(String summary, String source) {
        if (summary == null || summary.isBlank()) {
            return Optional.empty();
        }
        if (source == null) {
            return Optional.of("нет текста документа");
        }
        Matcher placeholder = UNRESOLVED_PLACEHOLDER.matcher(summary);
        if (placeholder.find()) {
            return Optional.of("метка " + placeholder.group());
        }
        Set<String> sourceNumbers = numericTokens(source);
        for (String number : numericTokens(summary)) {
            if (!sourceNumbers.contains(number)) {
                return Optional.of("число «" + number + "»");
            }
        }
        Set<String> sourceMonthWords = monthWords(source);
        for (String month : monthWords(summary)) {
            if (!sourceMonthWords.contains(month)) {
                return Optional.of("месяц «" + month + "…»");
            }
        }
        List<DateToken> sourceDates = dateTokens(source);
        for (DateToken date : dateTokens(summary)) {
            if (sourceDates.stream().noneMatch(date::matchesIgnoringAbsentYear)) {
                return Optional.of("дата «" + date.day() + " " + date.month() + (date.year() == null ? "" : " " + date.year()) + "»");
            }
        }
        return Optional.empty();
    }

    /**
     * Число дня внутри словесной даты («1» в «1 октября») сюда не попадает — его сравнивает {@link DateToken}
     * как целое число, а не как строку: иначе «01» из документа
     * и «1» из сводки — один и тот же день — здесь же ловились как разные строки и отбрасывали верную сводку
     * ещё до date-проверки.
     */
    private static Set<String> numericTokens(String text) {
        Set<Integer> dayDigitStarts = dayDigitStarts(text);
        Set<String> result = new HashSet<>();
        Matcher matcher = SUMMARY_NUMBER.matcher(text);
        while (matcher.find()) {
            if (dayDigitStarts.contains(matcher.start())) {
                continue;
            }
            result.add(NUMBER_SPACES.matcher(matcher.group()).replaceAll(" "));
        }
        return result;
    }

    private static Set<Integer> dayDigitStarts(String text) {
        Set<Integer> starts = new HashSet<>();
        Matcher matcher = SUMMARY_WORD_DATE.matcher(text);
        while (matcher.find()) {
            starts.add(matcher.start(1));
        }
        return starts;
    }

    private static Set<String> monthWords(String text) {
        Set<String> result = new HashSet<>();
        Matcher matcher = SUMMARY_MONTH_WORD.matcher(text);
        while (matcher.find()) {
            result.add(monthStemOf(matcher.group(1)));
        }
        return result;
    }

    /** «мае», «мая», «маю» и т. п. — один и тот же токен «ма», по какой бы форме падежа он ни встретился. */
    private static String monthStemOf(String word) {
        for (int index = 0; index < MONTH_STEM_PATTERNS.length; index++) {
            if (MONTH_STEM_PATTERNS[index].matcher(word).matches()) {
                return MONTH_STEM_NAMES[index];
            }
        }
        return word.toLowerCase(Locale.ROOT);
    }

    private static List<DateToken> dateTokens(String text) {
        List<DateToken> result = new ArrayList<>();
        Matcher matcher = SUMMARY_WORD_DATE.matcher(text);
        while (matcher.find()) {
            result.add(new DateToken(
                    Integer.parseInt(matcher.group(1)), matcher.group(2).toLowerCase(Locale.ROOT), matcher.group(3)));
        }
        return result;
    }

    /**
     * @param day  число дня как число, не строка: «01» и «1» —
     *             один и тот же день, а сравнение строк их различало
     * @param year {@code null}, если дата в тексте без года
     */
    private record DateToken(int day, String month, String year) {
        /** Год сверяется, только если он указан в этом токене — иначе дата без года отбрасывалась бы зря. */
        boolean matchesIgnoringAbsentYear(DateToken sourceToken) {
            return day == sourceToken.day && month.equals(sourceToken.month)
                    && (year == null || year.equals(sourceToken.year));
        }
    }

    /**
     * Вид документа и короткое название по началу текста (порт {@link DocumentClassifier}).
     * В модель уходит только начало документа и только маскированным — как и при извлечении полей.
     */
    public DocumentClassifier.Classification classify(
            InputStream content,
            String mimeType,
            List<DocumentClassifier.TypeOption> options
    ) {
        Optional<TextExtractor.ExtractedText> extracted = textExtractor.extract(content, mimeType);
        if (extracted.isEmpty() || !hasText(extracted.get())) {
            return new DocumentClassifier.Classification(null, null, false);
        }
        String text = extracted.get().fullText();
        String head = text.length() > CLASSIFICATION_TEXT_LIMIT ? text.substring(0, CLASSIFICATION_TEXT_LIMIT) : text;
        SensitiveDataMasker.MaskedText masked = masker.mask(head);
        DocumentClassifier.Classification result = classifier.classify(
                new DocumentClassifier.ClassificationRequest(masked.text(), options));
        return new DocumentClassifier.Classification(result.typeCode(), masked.restore(result.title()), result.available());
    }

    private static boolean hasText(TextExtractor.ExtractedText text) {
        return text.fullText().codePoints().filter(Character::isLetter).limit(MIN_TEXT_LETTERS).count()
                >= MIN_TEXT_LETTERS;
    }

    private DocumentExtractor.ExtractionResult unavailable() {
        return new DocumentExtractor.ExtractionResult(Map.of(), false, "none");
    }

    @PreDestroy
    void closeExecutor() {
        executor.shutdownNow();
    }

    private record VerifiedQuote(boolean valid, String quote, Integer page) {}
}
