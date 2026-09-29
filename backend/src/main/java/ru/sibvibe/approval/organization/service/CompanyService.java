package ru.sibvibe.approval.organization.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.dto.CompanyLocationRequest;
import ru.sibvibe.approval.organization.dto.CompanyNameRequest;
import ru.sibvibe.approval.organization.dto.CreateOrganizationRequest;
import ru.sibvibe.approval.organization.dto.OrganizationCreatedResponse;
import ru.sibvibe.approval.organization.entity.Organization;
import ru.sibvibe.approval.organization.entity.Role;
import ru.sibvibe.approval.organization.repository.AppUserRepository;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;
import ru.sibvibe.approval.organization.repository.RoleRepository;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Создание компании: создатель — администратор с ролью «Директор», роли и маршруты — по умолчанию. */
@Service
public class CompanyService {

    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RoleRepository roleRepository;
    private final MemberService memberService;
    private final InviteService inviteService;
    private final RouteTemplateService routeTemplateService;
    private final Clock clock;

    public CompanyService(
            AppUserRepository userRepository,
            OrganizationRepository organizationRepository,
            RoleRepository roleRepository,
            MemberService memberService,
            InviteService inviteService,
            RouteTemplateService routeTemplateService,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.roleRepository = roleRepository;
        this.memberService = memberService;
        this.inviteService = inviteService;
        this.routeTemplateService = routeTemplateService;
        this.clock = clock;
    }

    /**
     * Всё в одной транзакции: компания либо создаётся целиком, либо не создаётся вовсе.
     * Строка пользователя блокируется, чтобы две одновременные попытки не создали две компании.
     *
     * @param userId реальная личность, а не демо-участник: демо-персонаж компанию не создаёт
     */
    @Transactional
    public OrganizationCreatedResponse create(long userId, CreateOrganizationRequest request) {
        userRepository.findByIdForUpdate(userId).orElseThrow(OrganizationErrors::notFound);
        memberService.requireCanOnboard(userId);

        Organization org = new Organization();
        org.setName(request.name().trim());
        org.setInn(blankToNull(request.inn()));
        // Город и пояс не переданы (старый клиент) — Москва, как было до.
        if (request.timeZone() != null || request.city() != null) {
            applyLocation(org, request.city(), request.timeZone());
        }
        org.setCreatedBy(userId);
        org.setCreatedAt(clock.instant());
        org.setDemo(false);
        org = organizationRepository.save(org);

        Map<String, Long> roleIdsByCode = new HashMap<>();
        for (DefaultRoles.Seed seed : DefaultRoles.ALL) {
            Role role = new Role();
            role.setOrgId(org.getId());
            role.setCode(seed.code());
            role.setName(seed.name());
            roleIdsByCode.put(seed.code(), roleRepository.save(role).getId());
        }

        memberService.addMember(org, userId, List.of(roleIdsByCode.get(DefaultRoles.DIRECTOR)), true, userId);
        inviteService.issueOrgCode(org.getId(), userId);
        routeTemplateService.createDefaultRoutes(org.getId(), roleIdsByCode);
        return new OrganizationCreatedResponse(org.getId(), org.getName());
    }

    /** Город и часовой пояс компании меняет администратор: действует на новые проверки, периоды и показатели. */
    @Transactional
    public CompanyLocationView updateLocation(CurrentUser actor, CompanyLocationRequest request) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        Organization org = organizationRepository.findByIdForUpdate(orgId).orElseThrow(OrganizationErrors::notFound);
        if (org.isDemo()) {
            throw OrganizationErrors.forbidden("Город демо-компании не меняется");
        }
        applyLocation(org, request.city(), request.timeZone());
        return new CompanyLocationView(org.getCity(), org.getTimeZone());
    }

    /**
     * Название компании меняет администратор: ошиблись при создании или компания переименовалась.
     * Бот и экраны читают название из базы, поэтому новое видно сразу везде. У демо-песочницы — нет: она общая заготовка.
     */
    @Transactional
    public CompanyNameView rename(CurrentUser actor, CompanyNameRequest request) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        Organization org = organizationRepository.findByIdForUpdate(orgId).orElseThrow(OrganizationErrors::notFound);
        if (org.isDemo()) {
            throw OrganizationErrors.forbidden("Название демо-компании не меняется");
        }
        org.setName(request.name().trim());
        return new CompanyNameView(org.getName());
    }

    public record CompanyNameView(String name) {
    }

    private static void applyLocation(Organization org, String city, String timeZone) {
        if (!CompanyTimeZones.isSupported(timeZone)) {
            throw OrganizationErrors.validation("Выберите город из списка");
        }
        if (city == null || city.isBlank()) {
            throw OrganizationErrors.validation("Укажите город компании");
        }
        // Только город из списка и его пояс: по поясу считается «сегодня», а город видят сотрудники.
        if (!CompanyTimeZones.isKnownPlace(city.trim(), timeZone)) {
            throw OrganizationErrors.validation("Выберите город из списка");
        }
        org.setCity(city.trim());
        org.setTimeZone(timeZone);
    }

    public record CompanyLocationView(String city, String timeZone) {
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
