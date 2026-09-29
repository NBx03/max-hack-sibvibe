package ru.sibvibe.approval.document.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.sibvibe.approval.ai.DocumentExtractor;
import ru.sibvibe.approval.document.dto.DocumentCardResponse;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.entity.DocumentTypeField;
import ru.sibvibe.approval.document.repository.DocumentTypeFieldRepository;
import ru.sibvibe.approval.document.repository.DocumentTypeRepository;
import ru.sibvibe.approval.organization.service.CompanyZoneService;
import ru.sibvibe.approval.rules.RuleEngine;
import ru.sibvibe.approval.rules.service.RuleCatalogService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentRuleServiceTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Test
    void passesExplicitBusinessDateAndManualValuesToRuleEngine() {
        RuleEngine engine = mock(RuleEngine.class);
        var service = new DocumentRuleService(
                mock(DocumentTypeRepository.class),
                mock(DocumentTypeFieldRepository.class),
                mock(RuleCatalogService.class),
                engine, zones("Europe/Moscow"),
                Clock.fixed(Instant.parse("2026-09-20T21:30:00Z"), ZoneOffset.UTC));
        DocumentType type = new DocumentType();
        type.setId(1L);
        type.setCode("ORDER");
        DocumentTypeField field = new DocumentTypeField();
        field.setFieldName("date");
        var rule = new RuleEngine.RequirementRule(
                2L, "ORDER", "date", "Дата не из будущего",
                RuleEngine.CheckType.DATE_NOT_FUTURE, null,
                RuleEngine.RuleKind.PRODUCT_RULE, RuleEngine.Severity.BLOCKER,
                "Правила продукта", null, null, Instant.parse("2026-09-01T00:00:00Z"));
        var prepared = new DocumentRuleService.PreparedCheck(
                type, List.of(field), Map.of("date", "2026-09-21"), List.of(rule), Map.of(), MOSCOW);
        when(engine.validate(any(), any(), any())).thenReturn(List.of());

        DocumentRuleService.CheckData result = service.execute(prepared);

        ArgumentCaptor<RuleEngine.ValidationContext> context =
                ArgumentCaptor.forClass(RuleEngine.ValidationContext.class);
        verify(engine).validate(any(), any(), context.capture());
        assertThat(context.getValue().referenceDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(result.modelAvailable()).isFalse();
        assertThat(result.fields()).singleElement().satisfies(value -> {
            assertThat(value.name()).isEqualTo("date");
            assertThat(value.value()).isEqualTo("2026-09-21");
            assertThat(value.source()).isEqualTo("MANUAL");
        });
    }

    /**
     * «Сегодня» — по часовому поясу компании: в 18:40 UTC у новосибирской компании уже 27-е,
     * а у московской ещё 26-е.
     */
    @Test
    void todayIsTakenInTheCompanyTimeZone() {
        RuleEngine engine = mock(RuleEngine.class);
        var service = new DocumentRuleService(
                mock(DocumentTypeRepository.class),
                mock(DocumentTypeFieldRepository.class),
                mock(RuleCatalogService.class),
                engine, zones("Europe/Moscow"),
                Clock.fixed(Instant.parse("2026-09-26T18:40:00Z"), ZoneOffset.UTC));
        DocumentType type = new DocumentType();
        type.setCode("MEMO");
        when(engine.validate(any(), any(), any())).thenReturn(List.of());

        service.execute(new DocumentRuleService.PreparedCheck(
                type, List.of(), Map.of(), List.of(), Map.of(), ZoneId.of("Asia/Novosibirsk")));
        service.execute(new DocumentRuleService.PreparedCheck(
                type, List.of(), Map.of(), List.of(), Map.of(), ZoneId.of("Europe/Moscow")));

        ArgumentCaptor<RuleEngine.ValidationContext> context =
                ArgumentCaptor.forClass(RuleEngine.ValidationContext.class);
        verify(engine, times(2)).validate(any(), any(), context.capture());
        assertThat(context.getAllValues()).extracting(RuleEngine.ValidationContext::referenceDate)
                .containsExactly(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 26));
    }

    /** Пояс проверке даёт компания документа: prepare берёт его по id компании. */
    @Test
    void prepareCarriesTheCompanyTimeZone() {
        DocumentTypeRepository types = mock(DocumentTypeRepository.class);
        DocumentTypeFieldRepository fields = mock(DocumentTypeFieldRepository.class);
        RuleCatalogService catalog = mock(RuleCatalogService.class);
        DocumentType type = new DocumentType();
        type.setId(3L);
        type.setCode("MEMO");
        when(types.findById(3L)).thenReturn(java.util.Optional.of(type));
        when(fields.findByDocumentTypeIdOrderByPositionAscIdAsc(3L)).thenReturn(List.of());
        when(catalog.forType(5L, 3L)).thenReturn(List.of());
        CompanyZoneService companyZones = mock(CompanyZoneService.class);
        when(companyZones.zoneOf(5L)).thenReturn(ZoneId.of("Asia/Novosibirsk"));
        var service = new DocumentRuleService(types, fields, catalog, mock(RuleEngine.class), companyZones,
                Clock.fixed(Instant.parse("2026-09-26T18:40:00Z"), ZoneOffset.UTC));

        assertThat(service.prepare(5L, 3L, Map.of()).zone()).isEqualTo(ZoneId.of("Asia/Novosibirsk"));
    }

    /** Правка «без изменения файла», перенесённая в новую версию, сохраняет значение из файла. */
    @Test
    void carriedManualValueKeepsWhatIsWrittenInTheFile() {
        RuleEngine engine = mock(RuleEngine.class);
        var service = new DocumentRuleService(
                mock(DocumentTypeRepository.class),
                mock(DocumentTypeFieldRepository.class),
                mock(RuleCatalogService.class),
                engine, zones("Europe/Moscow"),
                Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC));
        DocumentType type = new DocumentType();
        type.setCode("MEMO");
        DocumentTypeField number = new DocumentTypeField();
        number.setFieldName("number");
        var prepared = new DocumentRuleService.PreparedCheck(type, List.of(number), Map.of("number", "СЗ-7"), List.of(), Map.of(), MOSCOW)
                .withFileValues(Map.of("number", "СЗ 7"));
        when(engine.validate(any(), any(), any())).thenReturn(List.of());

        DocumentRuleService.CheckData result = service.execute(prepared,
                new DocumentExtractor.ExtractionResult(Map.of(), true, "test"));

        assertThat(result.fields()).singleElement().satisfies(value -> {
            assertThat(value.value()).isEqualTo("СЗ-7");
            assertThat(value.source()).isEqualTo("MANUAL");
            assertThat(value.fileValue()).isEqualTo("СЗ 7");
        });
    }

    @Test
    void mergesModelFieldsWithManualOverridesAndPassesVerifiedLocations() {
        RuleEngine engine = mock(RuleEngine.class);
        var service = new DocumentRuleService(
                mock(DocumentTypeRepository.class),
                mock(DocumentTypeFieldRepository.class),
                mock(RuleCatalogService.class),
                engine, zones("Europe/Moscow"),
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC));
        DocumentType type = new DocumentType();
        type.setCode("MEMO");
        DocumentTypeField date = new DocumentTypeField();
        date.setFieldName("date");
        DocumentTypeField author = new DocumentTypeField();
        author.setFieldName("author");
        var prepared = new DocumentRuleService.PreparedCheck(
                type, List.of(date, author), Map.of("author", "Введено вручную"), List.of(), Map.of(), MOSCOW);
        var extraction = new DocumentExtractor.ExtractionResult(Map.of(
                "date", new DocumentExtractor.ExtractedField("2026-09-22", "Дата: 22.09.2026", 2),
                "author", new DocumentExtractor.ExtractedField("Из модели", "Автор", 1)),
                true, "gigachat");
        when(engine.validate(any(), any(), any())).thenReturn(List.of());

        var result = service.execute(prepared, extraction);

        ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<RuleEngine.ValidationContext> context =
                ArgumentCaptor.forClass(RuleEngine.ValidationContext.class);
        verify(engine).validate(values.capture(), any(), context.capture());
        assertThat(values.getValue()).containsEntry("date", "2026-09-22")
                .containsEntry("author", "Введено вручную");
        assertThat(context.getValue().locations()).containsEntry(
                "date", new RuleEngine.FieldLocation("Дата: 22.09.2026", 2));
        assertThat(context.getValue().locations()).doesNotContainKey("author");
        assertThat(result.modelAvailable()).isTrue();
        assertThat(result.fields()).extracting(DocumentCardResponse.FieldValue::source)
                .containsExactly("MODEL", "MANUAL");
    }

    @Test
    void manualUpdatePreservesUnchangedModelFieldAndMarksChangedFieldManual() {
        RuleEngine engine = mock(RuleEngine.class);
        var service = new DocumentRuleService(
                mock(DocumentTypeRepository.class), mock(DocumentTypeFieldRepository.class),
                mock(RuleCatalogService.class), engine, zones("Europe/Moscow"),
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC));
        DocumentType type = new DocumentType();
        type.setCode("MEMO");
        DocumentTypeField date = new DocumentTypeField();
        date.setFieldName("date");
        DocumentTypeField author = new DocumentTypeField();
        author.setFieldName("author");
        var prepared = new DocumentRuleService.PreparedCheck(
                type, List.of(date, author), Map.of("date", "2026-09-22", "author", "Исправлено"), List.of(),
                Map.of(), MOSCOW);
        var previous = new DocumentRuleService.CheckData(true, List.of(
                new DocumentCardResponse.FieldValue("date", "2026-09-22", "MODEL", "22.09.2026", 2),
                new DocumentCardResponse.FieldValue("author", "Из модели", "MODEL", "Автор", 1)), List.of());
        when(engine.validate(any(), any(), any())).thenReturn(List.of());

        var result = service.executeManualUpdate(prepared, previous);

        assertThat(result.modelAvailable()).isTrue();
        // Изменённое поле помнит, что в файле: согласующий видит ручную правку.
        assertThat(result.fields()).containsExactly(
                new DocumentCardResponse.FieldValue("date", "2026-09-22", "MODEL", "22.09.2026", 2),
                new DocumentCardResponse.FieldValue("author", "Исправлено", "MANUAL", null, null, "Из модели"));
    }

    /**
     * «Сохранить и проверить» и «Проверить заново» идут через executeManualUpdate — «сегодня» и там по поясу компании
     *: в 18:40 UTC 26.09 в Новосибирске уже 27.09, в Москве ещё 26.09.
     */
    @Test
    void manualUpdateTakesTodayInTheCompanyTimeZone() {
        RuleEngine engine = mock(RuleEngine.class);
        when(engine.validate(any(), any(), any())).thenReturn(List.of());
        var service = new DocumentRuleService(
                mock(DocumentTypeRepository.class), mock(DocumentTypeFieldRepository.class),
                mock(RuleCatalogService.class), engine, zones("Europe/Moscow"),
                Clock.fixed(Instant.parse("2026-09-26T18:40:00Z"), ZoneOffset.UTC));
        DocumentType type = new DocumentType();
        type.setCode("MEMO");
        DocumentTypeField date = new DocumentTypeField();
        date.setFieldName("date");
        var previous = new DocumentRuleService.CheckData(true, List.of(
                new DocumentCardResponse.FieldValue("date", "27.09.2026", "MODEL", "27.09.2026", 1)), List.of());

        service.executeManualUpdate(new DocumentRuleService.PreparedCheck(
                type, List.of(date), Map.of("date", "27.09.2026"), List.of(), Map.of(),
                ZoneId.of("Asia/Novosibirsk")), previous);

        ArgumentCaptor<RuleEngine.ValidationContext> context =
                ArgumentCaptor.forClass(RuleEngine.ValidationContext.class);
        verify(engine).validate(any(), any(), context.capture());
        assertThat(context.getValue().referenceDate()).isEqualTo(LocalDate.of(2026, 9, 27));
    }

    private static CompanyZoneService zones(String zone) {
        CompanyZoneService companyZones = mock(CompanyZoneService.class);
        when(companyZones.zoneOf(org.mockito.ArgumentMatchers.anyLong())).thenReturn(ZoneId.of(zone));
        return companyZones;
    }

    /** Сервис с двумя полями схемы — «адресат» и «дата» — и правилами, которые ничего не находят. */
    private static DocumentRuleService manualUpdateService() {
        RuleEngine engine = mock(RuleEngine.class);
        when(engine.validate(any(), any(), any())).thenReturn(List.of());
        return new DocumentRuleService(
                mock(DocumentTypeRepository.class), mock(DocumentTypeFieldRepository.class),
                mock(RuleCatalogService.class), engine, zones("Europe/Moscow"),
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC));
    }

    private static DocumentRuleService.PreparedCheck supplied(Map<String, String> values) {
        DocumentType type = new DocumentType();
        type.setCode("MEMO");
        DocumentTypeField addressee = new DocumentTypeField();
        addressee.setFieldName("addressee");
        DocumentTypeField date = new DocumentTypeField();
        date.setFieldName("date");
        return new DocumentRuleService.PreparedCheck(type, List.of(addressee, date), values, List.of(), Map.of(), MOSCOW);
    }

    @Test
    void fieldTheModelDidNotFindStaysModelWhenSentBackEmpty() {
        var previous = new DocumentRuleService.CheckData(true, List.of(
                new DocumentCardResponse.FieldValue("addressee", null, "MODEL", null, null),
                new DocumentCardResponse.FieldValue("date", "2026-09-22", "MODEL", "22.09.2026", 2)), List.of());

        var result = manualUpdateService().executeManualUpdate(
                supplied(Map.of("addressee", "", "date", "2026-09-22")), previous);

        // Поле открыли и не тронули — оно не становится «указано вручную».
        assertThat(result.fields()).extracting(DocumentCardResponse.FieldValue::name, DocumentCardResponse.FieldValue::source)
                .containsExactly(tuple("addressee", "MODEL"), tuple("date", "MODEL"));
    }

    @Test
    void fieldMissingInTheFileFilledByTheAuthorIsManualWithEmptyFileValue() {
        var previous = new DocumentRuleService.CheckData(true, List.of(
                new DocumentCardResponse.FieldValue("addressee", "  ", "MODEL", null, null),
                new DocumentCardResponse.FieldValue("date", "2026-09-22", "MODEL", "22.09.2026", 2)), List.of());

        var result = manualUpdateService().executeManualUpdate(
                supplied(Map.of("addressee", "Генеральному директору")), previous);

        // fileValue = "" — модель прочитала файл, и адресата в нём нет: «в файле этого нет».
        assertThat(result.fields()).contains(new DocumentCardResponse.FieldValue(
                "addressee", "Генеральному директору", "MANUAL", null, null, ""));
    }

    @Test
    void repeatedManualEditKeepsTheKnowledgeThatTheFileHasNoValue() {
        var previous = new DocumentRuleService.CheckData(true, List.of(
                new DocumentCardResponse.FieldValue("addressee", "Директору", "MANUAL", null, null, ""),
                new DocumentCardResponse.FieldValue("date", "2026-09-22", "MODEL", "22.09.2026", 2)), List.of());

        var result = manualUpdateService().executeManualUpdate(
                supplied(Map.of("addressee", "Генеральному директору")), previous);

        assertThat(result.fields()).contains(new DocumentCardResponse.FieldValue(
                "addressee", "Генеральному директору", "MANUAL", null, null, ""));
    }
}
