package ru.sibvibe.approval.document.service;

import ru.sibvibe.approval.organization.service.CompanyZoneService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.dto.OrganizationMetricsResponse;
import ru.sibvibe.approval.document.repository.DocumentReadRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class DocumentMetricsService {


    private final DocumentReadRepository repository;
    private final CompanyZoneService companyZones;

    public DocumentMetricsService(DocumentReadRepository repository, CompanyZoneService companyZones) {
        this.repository = repository;
        this.companyZones = companyZones;
    }

    @Transactional(readOnly = true)
    public OrganizationMetricsResponse metrics(CurrentUser user, LocalDate from, LocalDate to) {
        if (user.orgId() == null || user.memberId() == null) {
            throw DocumentApiException.forbidden("Для просмотра показателей нужно состоять в компании");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw DocumentApiException.validation("Дата начала периода не может быть позже даты окончания");
        }

        // Границы периода — по часовому поясу компании: «день» у компании в Новосибирске начинается раньше.
        ZoneId zone = companyZones.zoneOf(user.orgId());
        Instant fromInstant = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        DocumentReadRepository.MetricsRow row = repository.metrics(user.orgId(), fromInstant, toExclusive);
        return new OrganizationMetricsResponse(
                row.medianApprovalHours(), row.returnRate(), row.documentsCount());
    }
}
