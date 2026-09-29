package ru.sibvibe.approval.organization.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Маршруты новой компании по умолчанию — таблица в ARCHITECTURE.md, раздел 5.
 *
 * Маршруты консервативные: необязательный этап пропускается, если в компании нет такой роли,
 * поэтому компания из двух человек работает сразу. Одинаковый {@code stageOrder} — параллельно.
 * Типы задаются кодами: справочник типов наполняет миграция, а не эта задача.
 */
final class DefaultRouteTemplates {

    static final String OFFICIAL_MEMO = "OFFICIAL_MEMO";
    static final String VACATION_REQUEST = "VACATION_REQUEST";
    static final String SUPPORT_MEASURE_REQUEST = "SUPPORT_MEASURE_REQUEST";
    static final String BUSINESS_TRIP_REQUEST = "BUSINESS_TRIP_REQUEST";
    static final String GENERIC = "GENERIC";

    static final Map<String, List<Row>> TEMPLATES = Map.of(
            // Четвёртый тип: три этапа подряд — единственный маршрут с последовательными необязательными.
            BUSINESS_TRIP_REQUEST, List.of(
                    new Row(1, DefaultRoles.DEPARTMENT_HEAD, false),
                    new Row(2, DefaultRoles.ACCOUNTANT, false),
                    new Row(3, DefaultRoles.DIRECTOR, true)),
            OFFICIAL_MEMO, List.of(
                    new Row(1, DefaultRoles.DEPARTMENT_HEAD, false),
                    new Row(2, DefaultRoles.DIRECTOR, true)),
            VACATION_REQUEST, List.of(
                    new Row(1, DefaultRoles.HR, false),
                    new Row(2, DefaultRoles.DIRECTOR, true)),
            SUPPORT_MEASURE_REQUEST, List.of(
                    new Row(1, DefaultRoles.LAWYER, false),
                    new Row(1, DefaultRoles.ACCOUNTANT, false),
                    new Row(2, DefaultRoles.DIRECTOR, true)),
            // «Другой документ» — без обязательных: Директор лишь подставляется в маршрут.
            GENERIC, List.of(
                    new Row(1, DefaultRoles.DIRECTOR, false))
    );

    private DefaultRouteTemplates() {
    }

    static Set<String> typeCodes() {
        return TEMPLATES.keySet();
    }

    record Row(int stageOrder, String roleCode, boolean mandatory) {
    }
}
