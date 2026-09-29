package ru.sibvibe.approval.organization.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.organization.DocumentTypeDirectory;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.dto.RouteOverview;
import ru.sibvibe.approval.organization.dto.RouteTemplateRequest;
import ru.sibvibe.approval.organization.entity.ApprovalRoute;
import ru.sibvibe.approval.organization.entity.Organization;
import ru.sibvibe.approval.organization.entity.Role;
import ru.sibvibe.approval.organization.repository.ApprovalRouteRepository;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;
import ru.sibvibe.approval.organization.repository.RoleRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Шаблоны маршрутов компании: владеет ими организация, а не автор документа. */
@Service
public class RouteTemplateService {

    private final ApprovalRouteRepository routeRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final DocumentTypeDirectory typeDirectory;

    public RouteTemplateService(
            ApprovalRouteRepository routeRepository,
            RoleRepository roleRepository,
            OrganizationRepository organizationRepository,
            DocumentTypeDirectory typeDirectory
    ) {
        this.routeRepository = routeRepository;
        this.roleRepository = roleRepository;
        this.organizationRepository = organizationRepository;
        this.typeDirectory = typeDirectory;
    }

    /**
     * Шаблон маршрута компании для типа документа: строки по возрастанию этапа. Одинаковый {@code stageOrder}
     * — параллельные участники. Если у компании для типа ещё нет ни одной строки (типы появились после
     * компании, а добор при старте не успел), маршрут по умолчанию создаётся здесь же и читается заново.
     * Пустой результат значит, что для типа маршрута по умолчанию не существует.
     */
    @Transactional
    public List<TemplateRow> templateFor(long orgId, long documentTypeId) {
        List<TemplateRow> rows = readTemplate(orgId, documentTypeId);
        if (rows.isEmpty()) {
            ensureDefaultRoutes(orgId);
            rows = readTemplate(orgId, documentTypeId);
        }
        return rows;
    }

    private List<TemplateRow> readTemplate(long orgId, long documentTypeId) {
        return routeRepository.findByOrgIdAndDocumentTypeIdOrderByStageOrderAscIdAsc(orgId, documentTypeId).stream()
                .map(route -> new TemplateRow(route.getStageOrder(), route.getRoleId(), route.isMandatory()))
                .toList();
    }

    /**
     * Маршруты своей компании по всем типам — любому участнику, только чтение (раздел «Компания»). Это GET с
     * побочным эффектом, осознанно: как и предпросмотр маршрута, он идемпотентно добирает маршруты по умолчанию
     * ({@code ON CONFLICT DO NOTHING}), иначе список был бы пустым у типов, до которых ещё никто не дошёл. Недостающие
     * маршруты по умолчанию добираются здесь же, как и при отправке: список не должен быть пустым из-за того,
     * что до этого типа ещё никто не дошёл.
     */
    @Transactional
    public List<RouteOverview> overview(CurrentUser actor) {
        long orgId = OrganizationAccess.requireMemberOrg(actor);
        ensureDefaultRoutes(orgId);
        Map<Long, RoleRef> roles = new HashMap<>();
        for (Role role : roleRepository.findByOrgIdOrderById(orgId)) {
            roles.put(role.getId(), new RoleRef(role.getId(), role.getCode(), role.getName()));
        }
        Map<Long, TreeMap<Integer, List<RouteOverview.Participant>>> byType = new LinkedHashMap<>();
        for (ApprovalRoute route : routeRepository.findByOrgIdOrderByDocumentTypeIdAscStageOrderAscIdAsc(orgId)) {
            RoleRef role = roles.get(route.getRoleId());
            if (role == null) {
                continue;
            }
            byType.computeIfAbsent(route.getDocumentTypeId(), id -> new TreeMap<>())
                    .computeIfAbsent(route.getStageOrder(), order -> new ArrayList<>())
                    .add(new RouteOverview.Participant(role, route.isMandatory()));
        }
        List<RouteOverview> result = new ArrayList<>();
        byType.forEach((typeId, stages) -> result.add(new RouteOverview(typeId, stages.entrySet().stream()
                .map(stage -> new RouteOverview.Stage(stage.getKey(), List.copyOf(stage.getValue())))
                .toList())));
        return result;
    }

    /**
     * Обязательные роли — единственная настройка шаблона администратором: обязательную роль автор не может
     * убрать из маршрута. Только администратор своей компании; действует на новые отправки, идущие согласования не
     * меняются (шаги уже созданы). Роль, которой нет в шаблоне вида, — «не найдено». Шаблон без обязательных допустим.
     */
    @Transactional
    public List<RouteOverview> setMandatory(CurrentUser actor, long documentTypeId, long roleId, boolean mandatory) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        // «Другой документ» — как любой вид: по умолчанию обязательных нет, но администратор может
        // отметить, например, Директора — тогда в обход обязательных не уйти ни выбором «Другого документа», ни сканом.
        ensureDefaultRoutes(orgId);
        List<ApprovalRoute> rows = routeRepository.findByOrgIdAndDocumentTypeIdOrderByStageOrderAscIdAsc(orgId, documentTypeId)
                .stream().filter(route -> route.getRoleId() == roleId).toList();
        if (rows.isEmpty()) {
            throw OrganizationErrors.notFound();
        }
        rows.forEach(route -> route.setMandatory(mandatory));
        routeRepository.saveAll(rows);
        return overview(actor);
    }

    /** Строка шаблона: роль на этапе; {@code mandatory = false} — пропускается, если носителей роли нет. */
    public record TemplateRow(int stageOrder, long roleId, boolean mandatory) {
    }

    /**
     * Состав шаблона: этапы, роли на этапах и их порядок. В отличие от конструктора маршрута документа,
     * администратор компании может убрать и переместить кого угодно, включая обязательных.
     * Замена полная: позиция этапа в списке становится {@code stage_order} (с 1 подряд), прежние строки шаблона
     * удаляются и создаются заново в одной транзакции.
     *
     * Шаблон должен содержать хотя бы одну роль: {@code overview}, {@code templateFor} и {@code RouteBackfillRunner}
     * восстанавливают маршрут по умолчанию, если строк для вида нет вовсе, и пустой шаблон был бы молча
     * перезаписан. Отдельный признак «шаблон задан компанией» потребовал бы миграции.
     */
    @Transactional
    public List<RouteOverview> replaceTemplate(CurrentUser actor, long documentTypeId, List<RouteTemplateRequest.StageInput> stages) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        // Блокировка строки компании — как CompanyService.updateLocation: без неё два одновременных сохранения не
        // видят строк друг друга (delete + saveAll без блокировки читает старый снимок) и роль может задвоиться
        // в разных этапах, либо вставка упадёт на uq_route_stage_role.
        organizationRepository.findByIdForUpdate(orgId).orElseThrow(OrganizationErrors::notFound);
        typeDirectory.find(documentTypeId).orElseThrow(OrganizationErrors::notFound);
        // «Другой документ» редактируется наравне с остальными видами: по умолчанию обязательных
        // нет — свобода автора, — но компания может их отметить.
        Map<Long, Role> orgRoles = new HashMap<>();
        for (Role role : roleRepository.findByOrgIdOrderById(orgId)) {
            orgRoles.put(role.getId(), role);
        }
        Set<Long> seenRoles = new HashSet<>();
        List<ApprovalRoute> rows = new ArrayList<>();
        int stageOrder = 0;
        for (RouteTemplateRequest.StageInput stage : stages) {
            stageOrder++;
            if (stage.participants().isEmpty()) {
                throw OrganizationErrors.validation("Этап " + stageOrder + " без ролей — убрать пустой этап целиком");
            }
            for (RouteTemplateRequest.RoleInput participant : stage.participants()) {
                Role role = orgRoles.get(participant.roleId());
                if (role == null) {
                    throw OrganizationErrors.notFound();
                }
                if (!seenRoles.add(participant.roleId())) {
                    throw OrganizationErrors.validation("Роль «" + role.getName() + "» в маршруте только один раз");
                }
                ApprovalRoute row = new ApprovalRoute();
                row.setOrgId(orgId);
                row.setDocumentTypeId(documentTypeId);
                row.setStageOrder(stageOrder);
                row.setRoleId(role.getId());
                row.setMandatory(participant.mandatory());
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            throw OrganizationErrors.validation("В шаблоне должна остаться хотя бы одна роль");
        }
        routeRepository.deleteByOrgIdAndDocumentTypeId(orgId, documentTypeId);
        routeRepository.saveAll(rows);
        return overview(actor);
    }

    /**
     * Добирает маршруты по умолчанию всем настоящим компаниям. Нужен, когда типы документов появляются
     * после компаний (миграция справочника): порядок слияния задач не должен оставлять компании без маршрутов.
     * Идемпотентен; вызывается при старте приложения ({@link RouteBackfillRunner}).
     *
     * @return сколько строк маршрутов создано
     */
    @Transactional
    public int backfillAllOrganizations() {
        int created = 0;
        for (Organization org : organizationRepository.findByDemoFalseOrderById()) {
            created += ensureDefaultRoutes(org.getId());
        }
        return created;
    }

    /**
     * Создаёт маршруты по умолчанию для типов, у которых их ещё нет. Повторный вызов ничего
     * не дублирует, поэтому им можно добрать маршруты после появления новых типов.
     *
     * @return сколько строк маршрутов создано
     */
    @Transactional
    public int ensureDefaultRoutes(long orgId) {
        Map<String, Long> roleIdsByCode = new HashMap<>();
        for (Role role : roleRepository.findByOrgIdOrderById(orgId)) {
            roleIdsByCode.put(role.getCode(), role.getId());
        }
        return createDefaultRoutes(orgId, roleIdsByCode);
    }

    /**
     * Маршруты создаются вставкой {@code ON CONFLICT DO NOTHING}, а не проверкой «есть ли уже»: два параллельных
     * первых обращения (предпросмотр или отправка двух документов одного типа) оба не увидят чужих ещё не
     * зафиксированных строк, и без атомарной вставки проигравший получил бы нарушение уникальности, то есть 500.
     * Типы и строки идут в одном и том же порядке во всех транзакциях — так они ждут друг друга, а не блокируются
     * взаимно.
     */
    int createDefaultRoutes(long orgId, Map<String, Long> roleIdsByCode) {
        Map<String, Long> typeIds = typeDirectory.idsByCode(DefaultRouteTemplates.typeCodes());
        int created = 0;
        for (Map.Entry<String, List<DefaultRouteTemplates.Row>> template
                : new TreeMap<>(DefaultRouteTemplates.TEMPLATES).entrySet()) {
            Long typeId = typeIds.get(template.getKey());
            if (typeId == null || routeRepository.existsByOrgIdAndDocumentTypeId(orgId, typeId)) {
                continue;
            }
            for (DefaultRouteTemplates.Row row : template.getValue()) {
                Long roleId = roleIdsByCode.get(row.roleCode());
                if (roleId == null) {
                    continue;
                }
                created += routeRepository.insertIfAbsent(orgId, typeId, row.stageOrder(), roleId, row.mandatory());
            }
        }
        return created;
    }

    /** Маршрут демо-записки из Issue: юрист и бухгалтер параллельно, затем директор. Обязателен только директор. */
    void createDemoMemoRoute(long orgId, Map<String, Long> roleIdsByCode) {
        Long typeId = typeDirectory.idsByCode(Set.of(DefaultRouteTemplates.OFFICIAL_MEMO))
                .get(DefaultRouteTemplates.OFFICIAL_MEMO);
        if (typeId == null) {
            throw new IllegalStateException("Не найден тип документа " + DefaultRouteTemplates.OFFICIAL_MEMO);
        }
        routeRepository.insertIfAbsent(orgId, typeId, 1, requiredRole(roleIdsByCode, DefaultRoles.LAWYER), false);
        routeRepository.insertIfAbsent(orgId, typeId, 1, requiredRole(roleIdsByCode, DefaultRoles.ACCOUNTANT), false);
        routeRepository.insertIfAbsent(orgId, typeId, 2, requiredRole(roleIdsByCode, DefaultRoles.DIRECTOR), true);
    }

    private long requiredRole(Map<String, Long> roleIdsByCode, String code) {
        Long roleId = roleIdsByCode.get(code);
        if (roleId == null) {
            throw new IllegalStateException("Не найдена демо-роль " + code);
        }
        return roleId;
    }
}
