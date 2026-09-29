package ru.sibvibe.approval.organization.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ru.sibvibe.approval.organization.entity.OrganizationMember;

import java.util.List;
import java.util.Optional;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, Long> {
    Optional<OrganizationMember> findFirstByUserIdAndStatusAndDemoFalse(
            Long userId, OrganizationMember.Status status);

    Optional<OrganizationMember> findByOrgIdAndUserIdAndStatusAndDemoTrue(
            Long orgId, Long userId, OrganizationMember.Status status);

    List<OrganizationMember> findByOrgIdAndStatusAndDemoTrueOrderById(
            Long orgId, OrganizationMember.Status status);

    /** Участник ищется только в компании администратора: чужой id даёт пустой результат. */
    Optional<OrganizationMember> findByIdAndOrgId(Long id, Long orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OrganizationMember m where m.id = :id and m.orgId = :orgId")
    Optional<OrganizationMember> findByIdAndOrgIdForUpdate(Long id, Long orgId);

    Optional<OrganizationMember> findByOrgIdAndUserId(Long orgId, Long userId);

    List<OrganizationMember> findByOrgIdOrderById(Long orgId);

    List<OrganizationMember> findByOrgIdAndStatusOrderById(Long orgId, OrganizationMember.Status status);

    long countByOrgIdAndStatusAndAdminTrue(Long orgId, OrganizationMember.Status status);

    /** Активные администраторы компании - получатели уведомлений о заявках (bot.NotificationDirectoryService). */
    List<OrganizationMember> findByOrgIdAndStatusAndAdminTrue(Long orgId, OrganizationMember.Status status);
}
