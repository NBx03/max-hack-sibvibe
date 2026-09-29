package ru.sibvibe.approval.organization.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;

import java.time.ZoneId;

/**
 * Часовой пояс компании для модуля документов: «сегодня» в правилах, периоды списков и показатели. Отдельно от
 * OrganizationSecurityService: тот через наполнение песочницы вызывает проверку документов, и зависимость проверки
 * от него замкнула бы круг.
 */
@Service
public class CompanyZoneService {

    private final OrganizationRepository organizationRepository;

    public CompanyZoneService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    /** Компания не найдена — Москва: так считалось до, и проверка не падает на пустом месте. */
    @Transactional(readOnly = true)
    public ZoneId zoneOf(long orgId) {
        return organizationRepository.findById(orgId)
                .map(org -> CompanyTimeZones.zoneOf(org.getTimeZone()))
                .orElseGet(() -> CompanyTimeZones.zoneOf(null));
    }
}
