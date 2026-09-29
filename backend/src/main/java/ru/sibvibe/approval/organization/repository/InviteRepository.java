package ru.sibvibe.approval.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.sibvibe.approval.organization.entity.Invite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InviteRepository extends JpaRepository<Invite, Long> {

    /** Действующий код: отозванный старый код не находится. */
    Optional<Invite> findByCodeAndKindAndRevokedAtIsNull(String code, Invite.Kind kind);

    boolean existsByCode(String code);

    Optional<Invite> findFirstByOrgIdAndKindAndRevokedAtIsNull(Long orgId, Invite.Kind kind);

    Optional<Invite> findByToken(String token);

    Optional<Invite> findByIdAndOrgIdAndKind(Long id, Long orgId, Invite.Kind kind);

    List<Invite> findByOrgIdAndKindAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtAfterOrderByIdDesc(
            Long orgId, Invite.Kind kind, Instant now);

    /**
     * Атомарное использование личной ссылки: одна операция проверяет и помечает её.
     * Если две попытки пришли одновременно, вторая получит 0 строк и не вступит.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Invite i set i.usedAt = :now, i.usedBy = :userId
            where i.id = :id and i.kind = :kind
              and i.usedAt is null and i.revokedAt is null and i.expiresAt > :now
            """)
    int markPersonalUsed(Long id, Long userId, Instant now, Invite.Kind kind);

    /**
     * Атомарный отзыв личной ссылки: только неиспользованной и неотозванной, только своей компании.
     * Отзыв и вступление по ссылке идут одним условным UPDATE по одной строке, поэтому побеждает
     * ровно одна операция и вторая не может затереть результат первой.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Invite i set i.revokedAt = :now
            where i.id = :id and i.orgId = :orgId and i.kind = :kind
              and i.usedAt is null and i.revokedAt is null
            """)
    int revokePersonal(Long id, Long orgId, Instant now, Invite.Kind kind);
}
