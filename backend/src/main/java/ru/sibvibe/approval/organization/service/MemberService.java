package ru.sibvibe.approval.organization.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.PendingStepsGuard;
import ru.sibvibe.approval.organization.dto.MemberChangeView;
import ru.sibvibe.approval.organization.dto.MemberView;
import ru.sibvibe.approval.organization.entity.JoinRequest;
import ru.sibvibe.approval.organization.entity.MemberRole;
import ru.sibvibe.approval.organization.entity.Organization;
import ru.sibvibe.approval.organization.entity.OrganizationMember;
import ru.sibvibe.approval.organization.event.AdminChangedEvent;
import ru.sibvibe.approval.organization.repository.JoinRequestRepository;
import ru.sibvibe.approval.organization.repository.MemberRoleRepository;
import ru.sibvibe.approval.organization.repository.OrganizationMemberRepository;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;
import ru.sibvibe.approval.organization.repository.RoleRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Участники компании: список, роли, права администратора, отключение. Все действия — только ADMIN. */
@Service
public class MemberService {

    private final OrganizationMemberRepository memberRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final RoleRepository roleRepository;
    private final OrganizationRepository organizationRepository;
    private final JoinRequestRepository joinRequestRepository;
    private final PendingStepsGuard pendingStepsGuard;
    private final MemberViewAssembler assembler;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public MemberService(
            OrganizationMemberRepository memberRepository,
            MemberRoleRepository memberRoleRepository,
            RoleRepository roleRepository,
            OrganizationRepository organizationRepository,
            JoinRequestRepository joinRequestRepository,
            PendingStepsGuard pendingStepsGuard,
            MemberViewAssembler assembler,
            Clock clock,
            ApplicationEventPublisher events
    ) {
        this.memberRepository = memberRepository;
        this.memberRoleRepository = memberRoleRepository;
        this.roleRepository = roleRepository;
        this.organizationRepository = organizationRepository;
        this.joinRequestRepository = joinRequestRepository;
        this.pendingStepsGuard = pendingStepsGuard;
        this.assembler = assembler;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<MemberView> list(CurrentUser actor) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        return assembler.assemble(memberRepository.findByOrgIdOrderById(orgId));
    }

    /**
     * Сотрудники своей компании для любого участника (раздел «Компания»): кто есть
     * и с какими ролями — видно всем, как в любом рабочем чате. Только активные: отключённых видит
     * администратор в {@link #list}. Менять по-прежнему может только администратор.
     */
    @Transactional(readOnly = true)
    public List<MemberView> colleagues(CurrentUser actor) {
        long orgId = OrganizationAccess.requireMemberOrg(actor);
        return assembler.assemble(memberRepository.findByOrgIdOrderById(orgId).stream()
                .filter(member -> member.getStatus() == OrganizationMember.Status.ACTIVE)
                .toList());
    }

    /**
     * Заменяет набор ролей участника. Документы, где он ещё не решил в снятой роли, возвращаются авторам
     * (docs/DESIGN-DECISIONS.md, проверка №7): раньше роль с незавершёнными шагами снять было нельзя, и администратор
     * ждал, пока человек решит.
     */
    @Transactional
    public MemberChangeView setRoles(CurrentUser actor, long memberId, Collection<Long> roleIds) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        OrganizationMember member = memberRepository.findByIdAndOrgIdForUpdate(memberId, orgId)
                .orElseThrow(OrganizationErrors::notFound);
        // Свои роли администратор тоже меняет: роли — не привилегии (права администратора — отдельный
        // флаг), и назначить любую роль любому он может и так. Раньше единственный администратор не мог взять себе
        // даже вторую роль. Сотрудник без прав себе ничего не назначит — менять роли может только администратор.
        if (member.getStatus() != OrganizationMember.Status.ACTIVE) {
            throw OrganizationErrors.invalidState("Участник отключён");
        }
        Set<Long> target = requireRolesInOrg(orgId, roleIds);
        Set<Long> current = memberRoleRepository.findByMemberId(memberId).stream()
                .map(MemberRole::getRoleId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> removed = new LinkedHashSet<>(current);
        removed.removeAll(target);
        Set<Long> added = new LinkedHashSet<>(target);
        added.removeAll(current);

        int returned = 0;
        if (!removed.isEmpty()) {
            memberRoleRepository.deleteByMemberIdAndRoleIdIn(memberId, removed);
            returned = pendingStepsGuard.releaseSteps(orgId, member.getUserId(), removed);
        }
        grantRoles(memberId, added, actor.userId(), clock.instant());
        return new MemberChangeView(assembler.assemble(member), returned);
    }

    /**
     * Права администратора — флаг участника. Двух администраторов, снимающих права друг с друга
     * одновременно, сериализует блокировка строки компании: последний администратор остаётся.
     */
    @Transactional
    public MemberView setAdmin(CurrentUser actor, long memberId, boolean admin) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        Organization org = organizationRepository.findByIdForUpdate(orgId).orElseThrow(OrganizationErrors::notFound);
        OrganizationMember member = memberRepository.findByIdAndOrgId(memberId, orgId)
                .orElseThrow(OrganizationErrors::notFound);
        if (member.getStatus() != OrganizationMember.Status.ACTIVE) {
            throw OrganizationErrors.invalidState("Участник отключён");
        }
        if (member.isAdmin() == admin) {
            return assembler.assemble(member);
        }
        if (!admin && activeAdmins(orgId) <= 1) {
            throw OrganizationErrors.lastAdmin(
                    "Нельзя снять права администратора с последнего администратора");
        }
        member.setAdmin(admin);
        publishAdminChange(org, actor, member.getUserId(),
                admin ? AdminChangedEvent.Kind.GRANTED : AdminChangedEvent.Kind.REVOKED);
        return assembler.assemble(member);
    }

    /**
     * Исключает участника из компании сразу; повторный вызов ничего не меняет. Документы, где он ещё не
     * решил, возвращаются авторам. Запись участника остаётся: на неё ссылается история
     * решений, а вернувшийся по заявке или ссылке человек включается в той же записи.
     */
    @Transactional
    public MemberChangeView disable(CurrentUser actor, long memberId) {
        long orgId = OrganizationAccess.requireAdminOrg(actor);
        Organization org = organizationRepository.findByIdForUpdate(orgId).orElseThrow(OrganizationErrors::notFound);
        OrganizationMember member = memberRepository.findByIdAndOrgIdForUpdate(memberId, orgId)
                .orElseThrow(OrganizationErrors::notFound);
        if (member.getStatus() == OrganizationMember.Status.DISABLED) {
            return new MemberChangeView(assembler.assemble(member), 0);
        }
        if (member.isAdmin() && activeAdmins(orgId) <= 1) {
            throw OrganizationErrors.lastAdmin("Нельзя отключить последнего администратора");
        }
        member.setStatus(OrganizationMember.Status.DISABLED);
        if (member.isAdmin()) {
            publishAdminChange(org, actor, member.getUserId(), AdminChangedEvent.Kind.EXCLUDED);
        }
        int returned = pendingStepsGuard.releaseSteps(orgId, member.getUserId(), null);
        return new MemberChangeView(assembler.assemble(member), returned);
    }

    /**
     * Человек, который подаёт заявку, создаёт компанию или вступает по ссылке, не должен уже
     * состоять в настоящей компании и не должен иметь заявку на рассмотрении. Вызывать под
     * блокировкой строки пользователя, иначе две одновременные попытки проскочат обе.
     */
    void requireCanOnboard(long userId) {
        if (memberRepository.findFirstByUserIdAndStatusAndDemoFalse(
                userId, OrganizationMember.Status.ACTIVE).isPresent()) {
            throw OrganizationErrors.alreadyMember();
        }
        if (joinRequestRepository.existsByUserIdAndStatus(userId, JoinRequest.Status.PENDING)) {
            throw OrganizationErrors.joinRequestExists();
        }
    }

    /**
     * Делает человека участником компании с ролями. Если он уже когда-то состоял и был отключён,
     * участник включается заново: пара (компания, пользователь) уникальна.
     */
    OrganizationMember addMember(
            Organization org, long userId, Collection<Long> roleIds, boolean admin, long grantedBy
    ) {
        Instant now = clock.instant();
        OrganizationMember member = memberRepository.findByOrgIdAndUserId(org.getId(), userId).orElse(null);
        if (member == null) {
            member = new OrganizationMember();
            member.setOrgId(org.getId());
            member.setUserId(userId);
            member.setDemo(org.isDemo());
        } else {
            if (member.getStatus() == OrganizationMember.Status.ACTIVE) {
                throw OrganizationErrors.alreadyMember();
            }
            memberRoleRepository.deleteByMemberId(member.getId());
            // Hibernate выполняет вставки раньше удалений: без сброса старые пары ролей помешали бы новым.
            memberRoleRepository.flush();
        }
        member.setStatus(OrganizationMember.Status.ACTIVE);
        member.setAdmin(admin);
        member.setJoinedAt(now);
        member = memberRepository.save(member);
        grantRoles(member.getId(), new LinkedHashSet<>(roleIds), grantedBy, now);
        return member;
    }

    /** Роли ищутся только среди ролей компании; любой чужой id даёт 404 (контракт, «Идентификаторы от клиента»). */
    Set<Long> requireRolesInOrg(long orgId, Collection<Long> roleIds) {
        Set<Long> distinct = new LinkedHashSet<>(roleIds);
        if (distinct.stream().anyMatch(id -> id == null)) {
            throw OrganizationErrors.validation("Некорректный список ролей");
        }
        if (!distinct.isEmpty() && roleRepository.findByOrgIdAndIdIn(orgId, distinct).size() != distinct.size()) {
            throw OrganizationErrors.notFound();
        }
        return distinct;
    }

    private void grantRoles(long memberId, Set<Long> roleIds, long grantedBy, Instant now) {
        for (Long roleId : roleIds) {
            MemberRole link = new MemberRole();
            link.setMemberId(memberId);
            link.setRoleId(roleId);
            link.setGrantedBy(grantedBy);
            link.setGrantedAt(now);
            memberRoleRepository.save(link);
        }
    }

    private long activeAdmins(long orgId) {
        return memberRepository.countByOrgIdAndStatusAndAdminTrue(orgId, OrganizationMember.Status.ACTIVE);
    }

    /** Уведомление об изменении прав администратора — только у настоящей компании: в демо уведомлять некого. */
    private void publishAdminChange(Organization org, CurrentUser actor, long targetUserId, AdminChangedEvent.Kind kind) {
        if (!org.isDemo()) {
            events.publishEvent(new AdminChangedEvent(org.getId(), actor.userId(), targetUserId, kind));
        }
    }
}
