package ru.sibvibe.approval.organization.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.organization.entity.AppUser;
import ru.sibvibe.approval.organization.entity.JoinRequest;
import ru.sibvibe.approval.organization.entity.OrganizationMember;
import ru.sibvibe.approval.organization.repository.AppUserRepository;
import ru.sibvibe.approval.organization.repository.JoinRequestRepository;
import ru.sibvibe.approval.organization.repository.OrganizationMemberRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Единственная точка входа модуля {@code bot} в {@code organization} (ARCHITECTURE.md, раздел 2):
 * получатели уведомлений - {@code max_user_id} и отображаемое имя по внутреннему {@code userId}.
 * Модуль {@code bot} не читает {@code organization.entity} и {@code organization.repository} напрямую.
 */
@Service
public class NotificationDirectoryService {

    private final AppUserRepository users;
    private final OrganizationMemberRepository members;
    private final JoinRequestRepository joinRequests;

    public NotificationDirectoryService(AppUserRepository users, OrganizationMemberRepository members,
                                        JoinRequestRepository joinRequests) {
        this.users = users;
        this.members = members;
        this.joinRequests = joinRequests;
    }

    /**
     * Есть ли у человека заявка на рассмотрении: тогда ссылка-приглашение в мини-приложении не сработает (оно покажет
     * прежнюю заявку), и бот не должен обещать новую.
     */
    @Transactional(readOnly = true)
    public boolean hasPendingJoinRequest(long maxUserId) {
        return users.findByMaxUserId(maxUserId)
                .map(user -> joinRequests.existsByUserIdAndStatus(user.getId(), JoinRequest.Status.PENDING))
                .orElse(false);
    }

    /** @return получатель по внутреннему id пользователя; пусто, если пользователь не найден */
    @Transactional(readOnly = true)
    public Optional<Recipient> find(long userId) {
        return users.findById(userId).map(NotificationDirectoryService::recipient);
    }

    /** @return найденные получатели по набору внутренних id; отсутствующие в базе пропускаются, а не падают */
    @Transactional(readOnly = true)
    public Map<Long, Recipient> find(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(userIds).stream()
                .collect(Collectors.toUnmodifiableMap(AppUser::getId, NotificationDirectoryService::recipient));
    }

    /** @return активные администраторы компании - получатели уведомлений о заявках на вступление */
    @Transactional(readOnly = true)
    public List<Recipient> activeAdmins(long orgId) {
        Set<Long> adminUserIds = members
                .findByOrgIdAndStatusAndAdminTrue(orgId, OrganizationMember.Status.ACTIVE).stream()
                .map(OrganizationMember::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return List.copyOf(find(adminUserIds).values());
    }

    /**
     * Реальная компания отправителя входящего сообщения боту, по {@code max_user_id} (статус
     * документа текстовым запросом). Только настоящая компания: у бота нет заголовка {@code X-Demo-Act-As}
     * и понятия «действовать как», поэтому личная песочница здесь принципиально не рассматривается.
     * Пусто - человек не писал в мини-приложение ни разу ({@code AppUser} не создан) или не состоит
     * в компании; тогда бот отвечает обычным приветствием, а не пытается искать документы.
     */
    @Transactional(readOnly = true)
    public Optional<Membership> findRealMembershipByMaxUserId(long maxUserId) {
        return users.findByMaxUserId(maxUserId).flatMap(user ->
                members.findFirstByUserIdAndStatusAndDemoFalse(user.getId(), OrganizationMember.Status.ACTIVE)
                        .map(member -> new Membership(user.getId(), member.getOrgId())));
    }

    private static Recipient recipient(AppUser user) {
        return new Recipient(user.getMaxUserId(), user.getFullName());
    }

    public record Recipient(long maxUserId, String displayName) {
    }

    public record Membership(long userId, long orgId) {
    }
}
