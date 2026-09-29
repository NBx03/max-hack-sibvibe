package ru.sibvibe.approval.rules.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RussianDatesTest {

    private static final LocalDate SEPTEMBER_23 = LocalDate.of(2026, 9, 23);

    /** Все виды, в которых эту дату пишут в документах («"23" сентября 2026г.»). */
    @ParameterizedTest
    @ValueSource(strings = {
            "23.09.2026",
            "23.09.2026 г.",
            "23.09.2026г",
            "23/09/2026",
            "«23» сентября 2026 г.",
            "\"23\" сентября 2026г.",
            "“23” сентября 2026 года",
            "23 сентября 2026",
            "23  Сентября  2026 год",
            "2026-09-23",
    })
    void parsesAcceptedForms(String value) {
        assertThat(RussianDates.parse(value)).contains(SEPTEMBER_23);
    }

    /** Без года, с годом из двух цифр, несуществующая дата или не дата — не разбирается. */
    @ParameterizedTest
    @ValueSource(strings = {"1 октября", "23.09.26", "31.02.2026", "23 сентябрь 2026", "сентябрь", "", "  "})
    void rejectsIncompleteOrInvalid(String value) {
        assertThat(RussianDates.parse(value)).isEmpty();
    }
}
