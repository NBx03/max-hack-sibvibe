package ru.sibvibe.approval.organization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CompanyTimeZonesTest {

    /** Список городов экрана — в companyTime.ts; сервер держит свою копию для проверки пары «город — пояс». */
    private static final Path SCREEN_CITIES = Path.of("..", "frontend", "src", "app", "companyTime.ts");

    @Test
    void citiesMatchTheListOnTheScreen() throws IOException {
        String source = Files.readString(SCREEN_CITIES, StandardCharsets.UTF_8);
        int start = source.indexOf("COMPANY_CITIES");
        String block = source.substring(start, source.indexOf("].map(", start));
        Matcher pair = Pattern.compile("\\['([^']+)', '([^']+)'\\]").matcher(block);
        Map<String, String> onScreen = new LinkedHashMap<>();
        while (pair.find()) {
            onScreen.put(pair.group(1), pair.group(2));
        }

        assertThat(onScreen).hasSizeGreaterThan(30);
        assertThat(CompanyTimeZones.cities()).containsExactlyEntriesOf(onScreen);
    }

    @Test
    void cityMustComeWithItsOwnZone() {
        assertThat(CompanyTimeZones.isKnownPlace("Омск", "Asia/Omsk")).isTrue();
        assertThat(CompanyTimeZones.isKnownPlace("Казань", "Europe/Moscow")).isTrue();
        assertThat(CompanyTimeZones.isKnownPlace("Омск", "Europe/Moscow")).isFalse();
        assertThat(CompanyTimeZones.isKnownPlace("Атлантида", "Europe/Moscow")).isFalse();
        assertThat(CompanyTimeZones.isKnownPlace(null, "Europe/Moscow")).isFalse();
    }

    /** Город песочницы по умолчанию тоже из списка — иначе её город не прошёл бы ту же проверку. */
    @Test
    void defaultCitiesAreKnownPlaces() {
        for (String zone : CompanyTimeZones.cities().values()) {
            assertThat(CompanyTimeZones.isKnownPlace(CompanyTimeZones.defaultCity(zone), zone)).as(zone).isTrue();
        }
    }
}
