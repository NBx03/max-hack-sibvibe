package ru.sibvibe.approval.organization.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.dto.JoinRequestResponse;
import ru.sibvibe.approval.organization.dto.JoinRequestView;
import ru.sibvibe.approval.organization.dto.MemberView;
import ru.sibvibe.approval.organization.entity.AppUser;
import ru.sibvibe.approval.organization.entity.JoinRequest;
import ru.sibvibe.approval.organization.entity.Organization;
import ru.sibvibe.approval.organization.entity.OrganizationMember;
import ru.sibvibe.approval.organization.event.JoinRequestDecidedEvent;
import ru.sibvibe.approval.organization.event.JoinRequestSubmittedEvent;
import ru.sibvibe.approval.organization.repository.AppUserRepository;
import ru.sibvibe.approval.organization.repository.JoinRequestRepository;
import ru.sibvibe.approval.organization.repository.OrganizationMemberRepository;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Заявки на вступление по общему коду: подать, отменить, принять с ролями, отклонить. */
@Service
public class JoinRequestService {

    private final JoinRequestRepository joinRequestRepository;
    private final AppUserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;
    private final MemberService memberService;
    private final InviteService inviteService;
    private final MemberViewAssembler assembler;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public JoinRequestService(
            JoinRequestRepository joinRequestRepository,
            AppUserRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMemberRepository memberRepository,
            MemberService memberService,
            InviteService inviteService,
            MemberViewAssembler assembler,
            ApplicationEventPublisher events,
            Clock clock
    ) {
        this.joinRequestRepository = joinRequestRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.inviteService = inviteService;
        this.assembler = assembler;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Подача заявки по коду. Строка пользователя блокируется, поэтому «одна ожидающая заявка»
     * держится не только индексом базы, но и понятной ошибкой вместо падения на ограничении.
     *
     * @param userId реальная личность, а не демо-участник
     */
    @Transactional
    public JoinRequestResponse submit(long userId, String code) {
        userRepository.findByIdForUpdate(userId).orElseThrow(OrganizationErrors::notFound);
        memberService.requireCanOnboard(userId);
        InviteService.ResolvedCode resolved = inviteService.resolveOrgCodeForJoin(userId, code);

        JoinRequest request = new JoinRequest();
        request.setOrgId(resolved.org().getId());
        request.setUserId(userId);
        request.setInviteId(resolved.invite().getId());
        request.setStatus(JoinRequest.Status.PENDING);
        request.setCreatedAt(clock.instant());
        request = joinRequestRepository.save(request);
        events.publishEvent(new JoinRequestSubmittedEvent(resolved.org().getId(), request.getId(), userId));
        return new JoinRequestResponse(request.getId(), resolved.org().getName(),
                request.getStatus().name(), request.getCreatedAt());
    }

    /** Отмена своей заявки. Чужая заявка выглядит как несуществующая: сам факт её наличия не раскрываем. */
    @Transactional
    public void cancel(long userId, long requestId) {
        JoinRequest request = joinRequestRepository.findByIdForUpdate(requestId)
                .filter(found -> found.getUserId() == userId)
                .orElseThrow(OrganizationErrors::notFound);
        requirePending(request);
        request.setStatus(JoinRequest.Status.CANCELLED);
    }

    @Transactional(readOnly = true)
    public List<JoinRequestView> list(CurrentUser actor, String rawStatus) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        JoinRequest.Status status = parseStatus(rawStatus);
        List<JoinRequest> requests = joinRequestRepository
                .findByOrgIdAndStatusOrderByCreatedAtAscIdAsc(orgId, status);
        Map<Long, AppUser> users = userRepository.findAllById(
                        requests.stream().map(JoinRequest::getUserId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        return requests.stream()
                .map(request -> new JoinRequestView(request.getId(),
                        new UserRef(request.getUserId(), users.get(request.getUserId()).getFullName()),
                        request.getStatus().name(), request.getCreatedAt(), previousRoles(orgId, request.getUserId())))
                .toList();
    }

    /** Прежние роли исключённого участника — при исключении они не удаляются. Новый человек — пусто. */
    private List<ru.sibvibe.approval.common.dto.RoleRef> previousRoles(long orgId, long userId) {
        return memberRepository.findByOrgIdAndUserId(orgId, userId)
                .filter(member -> member.getStatus() == ru.sibvibe.approval.organization.entity.OrganizationMember.Status.DISABLED)
                .map(member -> assembler.assemble(member).roles())
                .orElse(List.of());
    }

    /**
     * Принятие с назначением ролей в одном запросе. Заявка ищется только в компании администратора
     * (docs/DESIGN-DECISIONS.md, проверка №3): администратор другой компании получит 404.
     */
    @Transactional
    public MemberView approve(CurrentUser actor, long requestId, Collection<Long> roleIds, boolean admin) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        JoinRequest request = findInOrg(requestId, orgId);
        requirePending(request);
        Set<Long> roles = memberService.requireRolesInOrg(orgId, roleIds);
        if (roles.isEmpty() && !admin) {
            throw OrganizationErrors.validation("Назначьте хотя бы одну роль");
        }

        userRepository.findByIdForUpdate(request.getUserId()).orElseThrow(OrganizationErrors::notFound);
        if (memberRepository.findFirstByUserIdAndStatusAndDemoFalse(
                request.getUserId(), OrganizationMember.Status.ACTIVE).isPresent()) {
            throw OrganizationErrors.invalidState("Сотрудник уже состоит в компании");
        }
        Organization org = organizationRepository.findById(orgId).orElseThrow(OrganizationErrors::notFound);
        OrganizationMember member = memberService.addMember(org, request.getUserId(), roles, admin, actor.userId());
        decide(request, JoinRequest.Status.APPROVED, actor.userId());
        events.publishEvent(new JoinRequestDecidedEvent(orgId, requestId, request.getUserId(), true));
        return assembler.assemble(member);
    }

    @Transactional
    public void reject(CurrentUser actor, long requestId) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        JoinRequest request = findInOrg(requestId, orgId);
        requirePending(request);
        decide(request, JoinRequest.Status.REJECTED, actor.userId());
        events.publishEvent(new JoinRequestDecidedEvent(orgId, requestId, request.getUserId(), false));
    }

    private JoinRequest findInOrg(long requestId, long orgId) {
        return joinRequestRepository.findByIdForUpdate(requestId)
                .filter(found -> found.getOrgId() == orgId)
                .orElseThrow(OrganizationErrors::notFound);
    }

    private static void requirePending(JoinRequest request) {
        if (request.getStatus() != JoinRequest.Status.PENDING) {
            throw OrganizationErrors.invalidState("Заявка уже рассмотрена");
        }
    }

    private void decide(JoinRequest request, JoinRequest.Status status, long deciderUserId) {
        Instant now = clock.instant();
        request.setStatus(status);
        request.setDecidedBy(deciderUserId);
        request.setDecidedAt(now);
    }

    private static JoinRequest.Status parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return JoinRequest.Status.PENDING;
        }
        try {
            return JoinRequest.Status.valueOf(rawStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw OrganizationErrors.validation("Неизвестный статус заявки");
        }
    }
}
