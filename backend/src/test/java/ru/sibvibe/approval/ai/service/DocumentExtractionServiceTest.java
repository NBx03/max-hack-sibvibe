package ru.sibvibe.approval.ai.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.ai.DocumentExtractor;
import ru.sibvibe.approval.ai.TextExtractor;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentExtractionServiceTest {

    private static final DocumentExtractor.FieldSpec FIELD = new DocumentExtractor.FieldSpec(
            "inn", DocumentExtractor.FieldType.STRING, "ИНН заявителя");

    @Test
    void restoresMaskedValueAndAcceptsOnlyExactQuote() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(
                List.of("ИНН заявителя 1234567890"), true));
        DocumentExtractor model = extractor(
                request -> {
                    assertThat(request.maskedText()).contains("<INN_1>").doesNotContain("1234567890");
                    return new DocumentExtractor.ExtractionResult(Map.of(
                            "inn", new DocumentExtractor.ExtractedField("<INN_1>", "ИНН заявителя <INN_1>", 99)),
                            true, "test");
                },
                request -> new DocumentExtractor.SummaryResult("Документ касается <INN_1> и подан 15.09.2026.", true));
        var service = service(text, model);

        var result = service.extract(new ByteArrayInputStream(new byte[0]), "application/pdf", "TYPE", List.of(FIELD));

        assertThat(result.fields().available()).isTrue();
        assertThat(result.fields().fields().get("inn"))
                .isEqualTo(new DocumentExtractor.ExtractedField("1234567890", "ИНН заявителя 1234567890", 1));
        // Даты в сводке тоже обязаны быть в исходном тексте, поэтому вся сводка отклонена.
        assertThat(result.summary()).isNull();
    }

    @Test
    void restoresMaskedSummaryWhenEveryNumberOccursVerbatim() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(
                List.of("ИНН заявителя 1234567890. Срок исполнения 15.09.2026."), true));
        DocumentExtractor model = extractor(
                request -> new DocumentExtractor.ExtractionResult(Map.of(
                        "inn", new DocumentExtractor.ExtractedField("<INN_1>", "ИНН заявителя <INN_1>", 1)),
                        true, "test"),
                request -> new DocumentExtractor.SummaryResult(
                        "Документ касается <INN_1>. Срок исполнения — 15.09.2026.", true));

        var result = service(text, model).extract(
                new ByteArrayInputStream(new byte[0]), "application/pdf", "TYPE", List.of(FIELD));

        assertThat(result.summary())
                .isEqualTo("Документ касается 1234567890. Срок исполнения — 15.09.2026.");
    }

    @Test
    void discardsEntireSummaryWhenModelInventsNumber() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(
                List.of("Срок исполнения 15.09.2026, сумма 1000 рублей."), true));
        DocumentExtractor model = extractor(
                request -> new DocumentExtractor.ExtractionResult(Map.of(), true, "test"),
                request -> new DocumentExtractor.SummaryResult(
                        "Исполнить до 15.09.2026. Сумма составляет 2000 рублей.", true));

        var result = service(text, model).extract(
                new ByteArrayInputStream(new byte[0]), "application/pdf", "TYPE", List.of(FIELD));

        assertThat(result.summary()).isNull();
    }

    /**
     * Оба вызова идут параллельно: к моменту, когда поля не извлеклись, сводка могла
     * уже прийти — но раз поля недоступны, результат сводки всё равно отбрасывается.
     */
    @Test
    void ignoresSummaryOutcomeWhenFieldsExtractionFailed() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(
                List.of("ИНН заявителя 1234567890"), true));
        DocumentExtractor model = extractor(
                request -> new DocumentExtractor.ExtractionResult(Map.of(), false, "gigachat"),
                request -> new DocumentExtractor.SummaryResult("будет проигнорирована", true));

        var result = service(text, model).extract(
                new ByteArrayInputStream(new byte[0]), "application/pdf", "TYPE", List.of(FIELD));

        assertThat(result.fields().available()).isFalse();
        assertThat(result.summary()).isNull();
    }

    /**
     * сводка не должна задерживать или ломать поля. Здесь summarize
     * «висит», а затем падает — поля при этом приходят вовремя, в пределах короткого тайм-аута теста. Вызов
     * идёт после полей (не параллельно), но всё равно ограничен бюджетом.
     */
    @Test
    void hangingOrFailingSummaryDoesNotDelayOrBreakFields() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(
                List.of("ИНН заявителя 1234567890"), true));
        DocumentExtractor model = extractor(
                request -> new DocumentExtractor.ExtractionResult(Map.of(
                        "inn", new DocumentExtractor.ExtractedField("<INN_1>", "ИНН заявителя <INN_1>", 1)),
                        true, "test"),
                request -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    throw new RuntimeException("сводка недоступна");
                });

        long start = System.nanoTime();
        var result = service(text, model, 1).extract(
                new ByteArrayInputStream(new byte[0]), "application/pdf", "TYPE", List.of(FIELD));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.fields().available()).isTrue();
        assertThat(result.fields().fields().get("inn").value()).isEqualTo("1234567890");
        assertThat(result.summary()).isNull();
        assertThat(elapsedMs).as("сводка не должна была задержать общий результат").isLessThan(4_000);
    }

    /**
     * поля отвечают почти весь бюджет — сводку либо совсем не
     * вызывают (бюджета не осталось), либо обрывают в пределах общего дедлайна, но не тянут вызов ко
     * второму полному тайм-ауту поверх первого.
     */
    @Test
    void whenFieldsConsumeMostOfBudgetSummaryDoesNotDoubleTheWait() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(
                List.of("ИНН заявителя 1234567890"), true));
        DocumentExtractor model = extractor(
                request -> {
                    sleep(900);
                    return new DocumentExtractor.ExtractionResult(Map.of(
                            "inn", new DocumentExtractor.ExtractedField("<INN_1>", "ИНН заявителя <INN_1>", 1)),
                            true, "test");
                },
                request -> {
                    // Если вызвали (бюджета едва хватило) — обязана оборваться немедленно, не за свои полные
                    // 5 секунд: elapsedMs ниже проверяет именно это, независимо от того, вызвали её или нет.
                    sleep(5_000);
                    return new DocumentExtractor.SummaryResult("не должно быть использовано", true);
                });

        long start = System.nanoTime();
        var result = service(text, model, 1).extract(
                new ByteArrayInputStream(new byte[0]), "application/pdf", "TYPE", List.of(FIELD));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.fields().available()).isTrue();
        assertThat(result.summary()).isNull();
        assertThat(elapsedMs).as("общее время — не сумма двух тайм-аутов, а один бюджет").isLessThan(2_000);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void requiresWholeNumericTokensWordMonthAndResolvedPlaceholders() {
        String source = "Срок 15.09.2026, сумма 1000 рублей. Начало с 1 ноября 2026 года. Лимит 1 000.";

        assertThat(DocumentExtractionService.summarySupportedBy("Сумма 100 рублей.", source)).isFalse();
        assertThat(DocumentExtractionService.summarySupportedBy("Касается 5 сотрудников.", source)).isFalse();
        assertThat(DocumentExtractionService.summarySupportedBy("Начало 1 октября 2026 года.", source)).isFalse();
        assertThat(DocumentExtractionService.summarySupportedBy("Документ подписал <PERSON_9>.", source)).isFalse();
        assertThat(DocumentExtractionService.summarySupportedBy(
                "Начало 1 ноября 2026 года. Лимит 1 000.", source)).isTrue();
    }

    /**
     * дата без года в сводке — не то же самое, что дата с неверным годом. День и месяц
     * совпадают с текстом, год сводка просто не указала — отбрасывать её незачем.
     */
    @Test
    void dateWithoutYearMatchesSourceDateRegardlessOfYear() {
        String source = "Начало с 1 ноября 2026 года на 14 дней.";

        assertThat(DocumentExtractionService.summarySupportedBy("Начало с 1 ноября на 14 дней.", source)).isTrue();
    }

    /**
     * Канцелярская дата с числом в кавычках — «18» сентября 2026 г. — та же дата, что «18 сентября 2026» в сводке
     * (из-за неё верная сводка отбрасывалась, и «Кратко от ИИ» то было, то нет).
     * Неверный день по-прежнему отбрасывает сводку.
     */
    @Test
    void quotedDayInOfficialDateMatchesPlainDateInSummary() {
        String source = "ПРИКАЗ от «18» сентября 2026 г. № 42 О проведении инвентаризации";

        assertThat(DocumentExtractionService.summarySupportedBy("Приказ от 18 сентября 2026 года.", source)).isTrue();
        assertThat(DocumentExtractionService.unsupportedFact("Приказ от 19 сентября 2026 года.", source))
                .contains("дата «19 сентября 2026»");
    }

    /** месяц, которого нет в тексте, отбрасывает сводку, даже без числа дня рядом. */
    @Test
    void monthWordAbsentFromSourceIsRejectedEvenWithoutLeadingDay() {
        String source = "Начало с 1 ноября 2026 года на 14 дней.";

        assertThat(DocumentExtractionService.summarySupportedBy("Согласование в декабре 2026 года.", source)).isFalse();
    }

    /**
     * документ пишет дату цифрами («01.10.2026») — модель просят
     * оставлять её как есть, а не переводить в «1 октября 2026 года». Числовая дата целиком ловится обычной
     * проверкой чисел, отдельная логика для дат со словом-месяцем тут не нужна.
     */
    @Test
    void digitDateMatchesVerbatimWithoutWordDateLogic() {
        String source = "Отпуск с 01.10.2026 на 14 дней.";

        assertThat(DocumentExtractionService.summarySupportedBy("Отпуск с 01.10.2026 на 14 дней.", source)).isTrue();
    }

    /**
     * «01» и «1» — один день, сравнение не должно быть строковым.
     */
    @Test
    void dayWithLeadingZeroMatchesSameDayWithoutIt() {
        String source = "Отпуск с 01 октября 2026 года.";

        assertThat(DocumentExtractionService.summarySupportedBy("Отпуск с 1 октября 2026 года.", source)).isTrue();
    }

    /** «мае» (предложный падеж) — тоже «май», без числа дня рядом. */
    @Test
    void monthWordInPrepositionalCaseIsRecognized() {
        String source = "Отпуск в мае 2026 года.";

        assertThat(DocumentExtractionService.summarySupportedBy("Отпуск запланирован в мае.", source)).isTrue();
    }

    /**
     * месяц с заглавной буквы (начало предложения,
     * например) — та же основа, регистр значения не имеет.
     */
    @Test
    void monthWordWithCapitalLetterIsRecognizedRegardlessOfCase() {
        String source = "Май 2026 года — срок поставки.";

        assertThat(DocumentExtractionService.summarySupportedBy("Поставка ожидается в мае 2026 года.", source))
                .isTrue();
        // прежняя вторая проверка («Декабрь» при
        // «мая») проходила бы и без флага (?iu) — месяц там в любом случае чужой. Обратный случай — тот же
        // месяц, но с заглавной буквы в сводке — проходит, только если регистр кириллицы действительно не
        // играет роли.
        assertThat(DocumentExtractionService.summarySupportedBy(
                "Поставка — Май 2026 года.", "5 мая 2026 года, поставка."))
                .isTrue();
    }

    /**
     * «Мартынов» и «Августова» — фамилии, не месяцы; закрытый список
     * падежных окончаний не должен принимать их за «март»/«август».
     */
    @Test
    void surnamesResemblingMonthStemsAreNotTreatedAsMonths() {
        String source = "Заявку подписал Мартынов И.И., согласовала Августова Е.Н. Сроков в тексте нет.";

        assertThat(DocumentExtractionService.summarySupportedBy(
                "Заявку подписал Мартынов, согласовала Августова.", source)).isTrue();
    }

    @Test
    void discardsFabricatedQuoteButKeepsExtractedValue() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(
                List.of("ИНН заявителя 1234567890"), true));
        DocumentExtractor model = fieldsOnly(request -> new DocumentExtractor.ExtractionResult(Map.of(
                "inn", new DocumentExtractor.ExtractedField("<INN_1>", "выдуманная цитата", 1)),
                true, "test"));

        var result = service(text, model).extract(
                new ByteArrayInputStream(new byte[0]), "application/pdf", "TYPE", List.of(FIELD));

        assertThat(result.fields().fields().get("inn"))
                .isEqualTo(new DocumentExtractor.ExtractedField("1234567890", null, null));
    }

    @Test
    void keepsVerifiedDocxQuoteWithoutInventingPageNumber() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(
                List.of("ИНН заявителя 1234567890"), false));
        DocumentExtractor model = fieldsOnly(request -> new DocumentExtractor.ExtractionResult(Map.of(
                "inn", new DocumentExtractor.ExtractedField("<INN_1>", "ИНН заявителя <INN_1>", 1)),
                true, "test"));

        var result = service(text, model).extract(
                new ByteArrayInputStream(new byte[0]), "docx", "TYPE", List.of(FIELD));

        assertThat(result.fields().fields().get("inn").quote()).isEqualTo("ИНН заявителя 1234567890");
        assertThat(result.fields().fields().get("inn").page()).isNull();
    }

    /** Скан без текстового слоя: в модель ничего не уходит, иначе «не найдено» по каждому полю. */
    @Test
    void scannedPdfWithoutTextIsNotSentToModelAndIsMarkedAsTextMissing() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(List.of(" ", "1", "2 "), true));
        DocumentExtractor model = extractor(
                request -> {
                    throw new AssertionError("модель не должна вызываться для файла без текста");
                },
                request -> {
                    throw new AssertionError("модель не должна вызываться для файла без текста");
                });

        var result = service(text, model).extract(
                new ByteArrayInputStream(new byte[0]), "application/pdf", "TYPE", List.of(FIELD));

        assertThat(result.fields().available()).isFalse();
        assertThat(result.fields().textMissing()).isTrue();
        assertThat(result.fields().fields()).isEmpty();
        assertThat(result.summary()).isNull();
    }

    @Test
    void acceptsQuoteWhoseLineBreakModelReturnedAsSpaceAndKeepsDocumentText() {
        TextExtractor text = (content, mime) -> Optional.of(new TextExtractor.ExtractedText(
                List.of("Шапка", "ИНН\u00A0заявителя\n  1234567890"), true));
        DocumentExtractor model = fieldsOnly(request -> new DocumentExtractor.ExtractionResult(Map.of(
                "inn", new DocumentExtractor.ExtractedField("<INN_1>", "ИНН заявителя <INN_1>", 1)),
                true, "test"));

        var result = service(text, model).extract(
                new ByteArrayInputStream(new byte[0]), "application/pdf", "TYPE", List.of(FIELD));

        assertThat(result.fields().fields().get("inn"))
                .isEqualTo(new DocumentExtractor.ExtractedField("1234567890", "ИНН\u00A0заявителя\n  1234567890", 2));
    }

    private DocumentExtractionService service(TextExtractor text, DocumentExtractor model) {
        return service(text, model, 3);
    }

    private DocumentExtractionService service(TextExtractor text, DocumentExtractor model, long timeoutSeconds) {
        return new DocumentExtractionService(text, model,
                request -> new ru.sibvibe.approval.ai.DocumentClassifier.Classification(null, null, false),
                new TextMinimizer(), new SensitiveDataMasker(), timeoutSeconds);
    }

    /** Сводка — отдельный вызов; когда тест её не проверяет, она просто «недоступна». */
    private static DocumentExtractor fieldsOnly(
            Function<DocumentExtractor.ExtractionRequest, DocumentExtractor.ExtractionResult> extract
    ) {
        return extractor(extract, request -> new DocumentExtractor.SummaryResult(null, false));
    }

    private static DocumentExtractor extractor(
            Function<DocumentExtractor.ExtractionRequest, DocumentExtractor.ExtractionResult> extract,
            Function<DocumentExtractor.SummaryRequest, DocumentExtractor.SummaryResult> summarize
    ) {
        return new DocumentExtractor() {
            @Override
            public ExtractionResult extract(ExtractionRequest request) {
                return extract.apply(request);
            }

            @Override
            public SummaryResult summarize(SummaryRequest request) {
                return summarize.apply(request);
            }
        };
    }
}
