package ru.sibvibe.approval.organization.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.dto.InviteCodeResponse;
import ru.sibvibe.approval.organization.dto.InvitePreviewResponse;
import ru.sibvibe.approval.organization.dto.PersonalInvitePreview;
import ru.sibvibe.approval.organization.dto.PersonalInviteView;
import ru.sibvibe.approval.organization.entity.Invite;
import ru.sibvibe.approval.organization.entity.InviteRole;
import ru.sibvibe.approval.organization.entity.Organization;
import ru.sibvibe.approval.organization.entity.OrganizationMember;
import ru.sibvibe.approval.organization.entity.Role;
import ru.sibvibe.approval.organization.event.MemberJoinedByLinkEvent;
import ru.sibvibe.approval.organization.repository.AppUserRepository;
import ru.sibvibe.approval.organization.repository.InviteRepository;
import ru.sibvibe.approval.organization.repository.InviteRoleRepository;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;
import ru.sibvibe.approval.organization.repository.RoleRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Приглашения: общий код компании (даёт право подать заявку) и личные одноразовые ссылки
 * (дают вступление без заявки). Подтверждение зависит от типа приглашения, а не от того, код это
 * или ссылка (docs/DESIGN-DECISIONS.md, «Подключение компании»).
 */
@Service
public class InviteService {

    private static final int CODE_ATTEMPTS = 10;

    private final InviteRepository inviteRepository;
    private final InviteRoleRepository inviteRoleRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final AppUserRepository userRepository;
    private final MemberService memberService;
    private final InviteSecrets secrets;
    private final InviteLinks links;
    private final CodeAttemptLimiter limiter;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration personalLinkTtl;

    public InviteService(
            InviteRepository inviteRepository,
            InviteRoleRepository inviteRoleRepository,
            RoleRepository roleRepository,
            OrganizationRepository organizationRepository,
            AppUserRepository userRepository,
            MemberService memberService,
            InviteSecrets secrets,
            InviteLinks links,
            CodeAttemptLimiter limiter,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${app.onboarding.personal-link-ttl-hours:72}") long personalLinkTtlHours
    ) {
        this.inviteRepository = inviteRepository;
        this.inviteRoleRepository = inviteRoleRepository;
        this.roleRepository = roleRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.memberService = memberService;
        this.secrets = secrets;
        this.links = links;
        this.limiter = limiter;
        this.events = events;
        this.clock = clock;
        this.personalLinkTtl = Duration.ofHours(personalLinkTtlHours);
    }

    // ---------- Код компании ----------

    /** Текущий код компании; если его ещё нет (например, у демо-компании), выпускается первый. */
    @Transactional
    public InviteCodeResponse currentCode(CurrentUser actor) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        Optional<Invite> active = inviteRepository.findFirstByOrgIdAndKindAndRevokedAtIsNull(
                orgId, Invite.Kind.ORG_CODE);
        if (active.isEmpty()) {
            organizationRepository.findByIdForUpdate(orgId).orElseThrow(OrganizationErrors::notFound);
            active = inviteRepository.findFirstByOrgIdAndKindAndRevokedAtIsNull(orgId, Invite.Kind.ORG_CODE);
        }
        Invite invite = active.orElseGet(() -> issueOrgCode(orgId, actor.userId()));
        return codeResponse(invite);
    }

    /**
     * Перевыпуск отзывает старый код (docs/DESIGN-DECISIONS.md, проверка №9). Уже поданные по нему заявки остаются
     * на рассмотрении: решение по ним всё равно за администратором.
     */
    @Transactional
    public InviteCodeResponse regenerate(CurrentUser actor) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        organizationRepository.findByIdForUpdate(orgId).orElseThrow(OrganizationErrors::notFound);
        inviteRepository.findFirstByOrgIdAndKindAndRevokedAtIsNull(orgId, Invite.Kind.ORG_CODE)
                .ifPresent(old -> {
                    old.setRevokedAt(clock.instant());
                    // Частичный уникальный индекс допускает один действующий код: отзыв должен дойти
                    // до базы раньше вставки нового, а Hibernate вставляет раньше, чем обновляет.
                    inviteRepository.saveAndFlush(old);
                });
        return codeResponse(issueOrgCode(orgId, actor.userId()));
    }

    /** Выпускает первый или новый код компании. Для действующего кода вызывающий берёт блокировку компании. */
    Invite issueOrgCode(long orgId, long createdBy) {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String code = secrets.newCode();
            if (inviteRepository.existsByCode(code)) {
                continue;
            }
            Invite invite = new Invite();
            invite.setOrgId(orgId);
            invite.setKind(Invite.Kind.ORG_CODE);
            invite.setCode(code);
            invite.setCreatedBy(createdBy);
            invite.setCreatedAt(clock.instant());
            return inviteRepository.saveAndFlush(invite);
        }
        throw new IllegalStateException("Не удалось подобрать свободный код компании");
    }

    /** Человек ввёл код и видит только название компании — доступа к данным это не даёт. */
    @Transactional
    public InvitePreviewResponse preview(long userId, String rawCode) {
        return new InvitePreviewResponse(resolveOrgCode(userId, rawCode).org().getName());
    }

    /**
     * Находит компанию по коду. Неверные коды считаются на пользователя: исчерпав лимит, он не может
     * проверять и верные, иначе перебор продолжился бы. Код демо-компании не принимается никогда.
     */
    ResolvedCode resolveOrgCode(long userId, String rawCode) {
        limiter.assertAllowed(userId);
        String code = secrets.normalizeCode(rawCode);
        Invite invite = code == null ? null
                : inviteRepository.findByCodeAndKindAndRevokedAtIsNull(code, Invite.Kind.ORG_CODE).orElse(null);
        Organization org = invite == null ? null
                : organizationRepository.findById(invite.getOrgId()).filter(found -> !found.isDemo()).orElse(null);
        if (org == null) {
            limiter.recordFailure(userId);
            throw OrganizationErrors.inviteNotFound();
        }
        return new ResolvedCode(invite, org);
    }

    /**
     * Код для подачи заявки. Отличается от {@link #resolveOrgCode} тем, что берёт общую блокировку компании
     * и проверяет код ещё раз уже под ней. Перевыпуск кода берёт ту же строку под FOR UPDATE, поэтому
     * подача заявки и перевыпуск идут по очереди: заявка не сохранится со старым кодом после того, как
     * перевыпуск его отозвал (docs/DESIGN-DECISIONS.md, проверка №9). Заявка, сохранённая раньше перевыпуска, остаётся.
     */
    ResolvedCode resolveOrgCodeForJoin(long userId, String rawCode) {
        ResolvedCode resolved = resolveOrgCode(userId, rawCode);
        organizationRepository.findByIdForShare(resolved.org().getId())
                .orElseThrow(OrganizationErrors::inviteNotFound);
        boolean stillActive = inviteRepository.findByCodeAndKindAndRevokedAtIsNull(
                resolved.invite().getCode(), Invite.Kind.ORG_CODE).isPresent();
        if (!stillActive) {
            // Код был верным, пока мы ждали блокировку, — это не попытка перебора, штрафовать её не за что.
            throw OrganizationErrors.inviteNotFound();
        }
        return resolved;
    }

    // ---------- Личные ссылки ----------

    /**
     * Личная ссылка — предъявительский токен: вступит тот, кто откроет первым. Риск ограничен сроком,
     * одноразовостью и тем, что права администратора через ссылку не выдаются никогда: роли выбираются
     * здесь, а флаг администратора у вступившего всегда снят.
     */
    @Transactional
    public PersonalInviteView createPersonal(CurrentUser actor, Collection<Long> roleIds) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        Set<Long> ids = new LinkedHashSet<>(roleIds);
        if (ids.isEmpty()) {
            throw OrganizationErrors.validation("Выберите хотя бы одну роль");
        }
        if (ids.stream().anyMatch(id -> id == null)) {
            throw OrganizationErrors.validation("Некорректный список ролей");
        }
        List<Role> roles = roleRepository.findByOrgIdAndIdIn(orgId, ids);
        if (roles.size() != ids.size()) {
            throw OrganizationErrors.notFound();
        }
        Instant now = clock.instant();
        Invite invite = new Invite();
        invite.setOrgId(orgId);
        invite.setKind(Invite.Kind.PERSONAL_LINK);
        invite.setToken(secrets.newToken());
        invite.setCreatedBy(actor.userId());
        invite.setCreatedAt(now);
        invite.setExpiresAt(now.plus(personalLinkTtl));
        invite = inviteRepository.save(invite);
        for (Role role : roles) {
            InviteRole link = new InviteRole();
            link.setInviteId(invite.getId());
            link.setRoleId(role.getId());
            inviteRoleRepository.save(link);
        }
        return personalView(invite, roles.stream().map(InviteService::ref).toList());
    }

    /** Неиспользованные, неотозванные и ещё не истёкшие ссылки — их видят все администраторы компании. */
    @Transactional(readOnly = true)
    public List<PersonalInviteView> listPersonal(CurrentUser actor) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        List<Invite> invites = inviteRepository
                .findByOrgIdAndKindAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtAfterOrderByIdDesc(
                        orgId, Invite.Kind.PERSONAL_LINK, clock.instant());
        Map<Long, List<RoleRef>> roles = rolesByInvite(invites);
        return invites.stream()
                .map(invite -> personalView(invite, roles.getOrDefault(invite.getId(), List.of())))
                .toList();
    }

    /**
     * Отзыв неиспользованной ссылки; повторный отзыв ничего не меняет, использованную отозвать нельзя.
     *
     * Отзыв — условный UPDATE, а не чтение с последующей записью: иначе при одновременном вступлении
     * по ссылке Hibernate записал бы устаревшее состояние поверх {@code used_at/used_by}, и ссылка
     * выглядела бы отозванной, хотя человек уже вступил.
     */
    @Transactional
    public void revokePersonal(CurrentUser actor, long inviteId) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        // Ссылка ищется только в компании администратора: чужая выглядит несуществующей.
        inviteRepository.findByIdAndOrgIdAndKind(inviteId, orgId, Invite.Kind.PERSONAL_LINK)
                .orElseThrow(OrganizationErrors::notFound);
        int revoked = inviteRepository.revokePersonal(inviteId, orgId, clock.instant(), Invite.Kind.PERSONAL_LINK);
        if (revoked == 1) {
            return;
        }
        Invite fresh = inviteRepository.findById(inviteId).orElseThrow(OrganizationErrors::notFound);
        if (fresh.getUsedAt() != null) {
            throw OrganizationErrors.inviteUsed();
        }
        // Иначе ссылка уже отозвана: повторный отзыв ничего не меняет.
    }

    /** Что увидит человек, открывший ссылку: компания и роли. Ничего при этом не происходит. */
    @Transactional(readOnly = true)
    public PersonalInvitePreview previewPersonal(String token) {
        Invite invite = findPersonal(token);
        Organization org = joinableOrg(invite);
        requireUsable(invite, clock.instant());
        List<RoleRef> roles = rolesByInvite(List.of(invite)).getOrDefault(invite.getId(), List.of());
        return new PersonalInvitePreview(org.getName(), roles, invite.getExpiresAt());
    }

    /**
     * Вступление по личной ссылке. Использование атомарно: один запрос проверяет и помечает ссылку
     * (docs/DESIGN-DECISIONS.md, проверка №2а), поэтому из двух одновременных попыток вступает одна. Если вступление
     * не удалось, транзакция откатывается и ссылка остаётся неиспользованной.
     */
    @Transactional
    public void acceptPersonal(long userId, String token) {
        userRepository.findByIdForUpdate(userId).orElseThrow(OrganizationErrors::notFound);
        memberService.requireCanOnboard(userId);
        Invite invite = findPersonal(token);
        Organization org = joinableOrg(invite);

        Instant now = clock.instant();
        int marked = inviteRepository.markPersonalUsed(invite.getId(), userId, now, Invite.Kind.PERSONAL_LINK);
        if (marked == 0) {
            Invite fresh = inviteRepository.findById(invite.getId()).orElseThrow(OrganizationErrors::inviteNotFound);
            requireUsable(fresh, now);
            throw OrganizationErrors.inviteUsed();
        }
        List<Long> roleIds = inviteRoleRepository.findByInviteId(invite.getId()).stream()
                .map(InviteRole::getRoleId).toList();
        OrganizationMember member = memberService.addMember(org, userId, roleIds, false, invite.getCreatedBy());
        events.publishEvent(new MemberJoinedByLinkEvent(org.getId(), member.getId(), userId, invite.getCreatedBy()));
    }

    // ---------- Внутреннее ----------

    private Invite findPersonal(String token) {
        return inviteRepository.findByToken(token == null ? "" : token)
                .filter(invite -> invite.getKind() == Invite.Kind.PERSONAL_LINK)
                .orElseThrow(OrganizationErrors::inviteNotFound);
    }

    /** Компания ссылки; демо-компания недоступна для вступления по ссылке. */
    private Organization joinableOrg(Invite invite) {
        return organizationRepository.findById(invite.getOrgId())
                .filter(org -> !org.isDemo())
                .orElseThrow(OrganizationErrors::inviteNotFound);
    }

    private static void requireUsable(Invite invite, Instant now) {
        if (invite.getUsedAt() != null) {
            throw OrganizationErrors.inviteUsed();
        }
        if (invite.getRevokedAt() != null) {
            throw OrganizationErrors.inviteRevoked();
        }
        if (invite.getExpiresAt() != null && !invite.getExpiresAt().isAfter(now)) {
            throw OrganizationErrors.inviteExpired();
        }
    }

    private Map<Long, List<RoleRef>> rolesByInvite(Collection<Invite> invites) {
        if (invites.isEmpty()) {
            return Map.of();
        }
        List<InviteRole> links = inviteRoleRepository.findByInviteIdIn(
                invites.stream().map(Invite::getId).toList());
        Map<Long, Role> roles = roleRepository.findAllById(
                        links.stream().map(InviteRole::getRoleId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
        Map<Long, List<RoleRef>> result = new HashMap<>();
        for (InviteRole link : links) {
            result.computeIfAbsent(link.getInviteId(), id -> new java.util.ArrayList<>())
                    .add(ref(roles.get(link.getRoleId())));
        }
        result.values().forEach(list -> list.sort(Comparator.comparingLong(RoleRef::id)));
        return result;
    }

    private InviteCodeResponse codeResponse(Invite invite) {
        return new InviteCodeResponse(invite.getCode(), links.orgCodeLink(invite.getCode()), invite.getCreatedAt());
    }

    private PersonalInviteView personalView(Invite invite, List<RoleRef> roles) {
        return new PersonalInviteView(invite.getId(), links.personalLink(invite.getToken()),
                invite.getExpiresAt(), roles);
    }

    private static RoleRef ref(Role role) {
        return new RoleRef(role.getId(), role.getCode(), role.getName());
    }

    record ResolvedCode(Invite invite, Organization org) {
    }
}
