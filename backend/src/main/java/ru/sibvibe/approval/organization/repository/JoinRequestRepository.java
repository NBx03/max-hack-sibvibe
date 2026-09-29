package ru.sibvibe.approval.organization.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ru.sibvibe.approval.organization.entity.JoinRequest;

import java.util.List;
import java.util.Optional;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, Long> {
    Optional<JoinRequest> findFirstByUserIdAndStatusOrderByCreatedAtDesc(
            Long userId, JoinRequest.Status status);

    boolean existsByUserIdAndStatus(Long userId, JoinRequest.Status status);

    /** Блокировка строки заявки: два администратора не решат одну заявку одновременно. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from JoinRequest r where r.id = :id")
    Optional<JoinRequest> findByIdForUpdate(Long id);

    List<JoinRequest> findByOrgIdAndStatusOrderByCreatedAtAscIdAsc(Long orgId, JoinRequest.Status status);
}
