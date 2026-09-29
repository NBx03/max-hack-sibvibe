package ru.sibvibe.approval.organization.service;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Часовые пояса компании: «сегодня» для правил, периодов и показателей считается по поясу компании,
 * а не по Москве. Допустимы только пояса России — тот же список, что в ограничении БД (017-organization-time-zone.sql)
 * и в выборе города на экране. Для каждого пояса — город по умолчанию: его получает демо-компания, созданная
 * из этого пояса.
 */
public final class CompanyTimeZones {

    public static final String DEFAULT_ZONE = "Europe/Moscow";
    public static final String DEFAULT_CITY = "Москва";

    private static final Map<String, String> CITY_BY_ZONE = new LinkedHashMap<>();
    /**
     * Города, из которых выбирают на экране, и их пояса — тот же список, что COMPANY_CITIES во
     * frontend/src/app/companyTime.ts (их совпадение проверяет CompanyTimeZonesTest). Сервер сверяет пару
     * «город — пояс»: иначе запросом в обход экрана можно записать «Омск · МСК».
     */
    private static final Map<String, String> ZONE_BY_CITY = new LinkedHashMap<>();

    static {
        CITY_BY_ZONE.put("Europe/Kaliningrad", "Калининград");
        CITY_BY_ZONE.put("Europe/Moscow", "Москва");
        CITY_BY_ZONE.put("Europe/Samara", "Самара");
        CITY_BY_ZONE.put("Asia/Yekaterinburg", "Екатеринбург");
        CITY_BY_ZONE.put("Asia/Omsk", "Омск");
        CITY_BY_ZONE.put("Asia/Novosibirsk", "Новосибирск");
        CITY_BY_ZONE.put("Asia/Krasnoyarsk", "Красноярск");
        CITY_BY_ZONE.put("Asia/Irkutsk", "Иркутск");
        CITY_BY_ZONE.put("Asia/Yakutsk", "Якутск");
        CITY_BY_ZONE.put("Asia/Vladivostok", "Владивосток");
        CITY_BY_ZONE.put("Asia/Magadan", "Магадан");
        CITY_BY_ZONE.put("Asia/Kamchatka", "Петропавловск-Камчатский");

        ZONE_BY_CITY.put("Калининград", "Europe/Kaliningrad");
        ZONE_BY_CITY.put("Москва", "Europe/Moscow");
        ZONE_BY_CITY.put("Санкт-Петербург", "Europe/Moscow");
        ZONE_BY_CITY.put("Казань", "Europe/Moscow");
        ZONE_BY_CITY.put("Нижний Новгород", "Europe/Moscow");
        ZONE_BY_CITY.put("Ростов-на-Дону", "Europe/Moscow");
        ZONE_BY_CITY.put("Краснодар", "Europe/Moscow");
        ZONE_BY_CITY.put("Воронеж", "Europe/Moscow");
        ZONE_BY_CITY.put("Волгоград", "Europe/Moscow");
        ZONE_BY_CITY.put("Ярославль", "Europe/Moscow");
        ZONE_BY_CITY.put("Мурманск", "Europe/Moscow");
        ZONE_BY_CITY.put("Архангельск", "Europe/Moscow");
        ZONE_BY_CITY.put("Сочи", "Europe/Moscow");
        ZONE_BY_CITY.put("Самара", "Europe/Samara");
        ZONE_BY_CITY.put("Саратов", "Europe/Samara");
        ZONE_BY_CITY.put("Ижевск", "Europe/Samara");
        ZONE_BY_CITY.put("Ульяновск", "Europe/Samara");
        ZONE_BY_CITY.put("Екатеринбург", "Asia/Yekaterinburg");
        ZONE_BY_CITY.put("Челябинск", "Asia/Yekaterinburg");
        ZONE_BY_CITY.put("Пермь", "Asia/Yekaterinburg");
        ZONE_BY_CITY.put("Уфа", "Asia/Yekaterinburg");
        ZONE_BY_CITY.put("Тюмень", "Asia/Yekaterinburg");
        ZONE_BY_CITY.put("Омск", "Asia/Omsk");
        ZONE_BY_CITY.put("Новосибирск", "Asia/Novosibirsk");
        ZONE_BY_CITY.put("Томск", "Asia/Novosibirsk");
        ZONE_BY_CITY.put("Барнаул", "Asia/Novosibirsk");
        ZONE_BY_CITY.put("Кемерово", "Asia/Novosibirsk");
        ZONE_BY_CITY.put("Новокузнецк", "Asia/Novosibirsk");
        ZONE_BY_CITY.put("Красноярск", "Asia/Krasnoyarsk");
        ZONE_BY_CITY.put("Иркутск", "Asia/Irkutsk");
        ZONE_BY_CITY.put("Улан-Удэ", "Asia/Irkutsk");
        ZONE_BY_CITY.put("Якутск", "Asia/Yakutsk");
        ZONE_BY_CITY.put("Чита", "Asia/Yakutsk");
        ZONE_BY_CITY.put("Владивосток", "Asia/Vladivostok");
        ZONE_BY_CITY.put("Хабаровск", "Asia/Vladivostok");
        ZONE_BY_CITY.put("Магадан", "Asia/Magadan");
        ZONE_BY_CITY.put("Южно-Сахалинск", "Asia/Magadan");
        ZONE_BY_CITY.put("Петропавловск-Камчатский", "Asia/Kamchatka");
        ZONE_BY_CITY.put("Анадырь", "Asia/Kamchatka");
    }

    private CompanyTimeZones() {
    }

    public static boolean isSupported(String zone) {
        return zone != null && CITY_BY_ZONE.containsKey(zone);
    }

    /** Город из списка и именно его пояс. */
    public static boolean isKnownPlace(String city, String zone) {
        return city != null && zone != null && zone.equals(ZONE_BY_CITY.get(city));
    }

    /** Список городов с поясами — для сверки с экраном в тестах. */
    public static Map<String, String> cities() {
        return java.util.Collections.unmodifiableMap(ZONE_BY_CITY);
    }

    /** Пояс компании для расчётов; незнакомое значение (его не пропустит и БД) — Москва, а не ошибка посреди проверки. */
    public static ZoneId zoneOf(String zone) {
        return ZoneId.of(isSupported(zone) ? zone : DEFAULT_ZONE);
    }

    /** Город по умолчанию для пояса; пояс не из списка — Москва. */
    public static String defaultCity(String zone) {
        return CITY_BY_ZONE.getOrDefault(isSupported(zone) ? zone : DEFAULT_ZONE, DEFAULT_CITY);
    }
}
