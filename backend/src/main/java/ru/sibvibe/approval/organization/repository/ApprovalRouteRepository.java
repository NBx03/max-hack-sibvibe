package ru.sibvibe.approval.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.sibvibe.approval.organization.entity.ApprovalRoute;

import java.util.List;

public interface ApprovalRouteRepository extends JpaRepository<ApprovalRoute, Long> {
    boolean existsByOrgIdAndDocumentTypeId(Long orgId, Long documentTypeId);

    List<ApprovalRoute> findByOrgIdAndDocumentTypeIdOrderByStageOrderAscIdAsc(Long orgId, Long documentTypeId);

    List<ApprovalRoute> findByOrgIdOrderByDocumentTypeIdAscStageOrderAscIdAsc(Long orgId);

    /**
     * Строка шаблона по умолчанию, вставляемая атомарно: если её параллельно создала другая транзакция, вставка
     * дожидается её и ничего не делает, а не падает на {@code uq_route_stage_role}.
     *
     * @return 1, если строка создана, 0 — если такая уже есть
     */
    @Modifying
    @Query(value = """
            insert into approval_route (org_id, document_type_id, stage_order, role_id, is_mandatory)
            values (:orgId, :documentTypeId, :stageOrder, :roleId, :mandatory)
            on conflict (org_id, document_type_id, stage_order, role_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("orgId") long orgId,
            @Param("documentTypeId") long documentTypeId,
            @Param("stageOrder") int stageOrder,
            @Param("roleId") long roleId,
            @Param("mandatory") boolean mandatory
    );

    /**
     * Удаляет весь шаблон вида документа перед пересозданием ({@code replaceTemplate}): проще и надёжнее
     * пересчитать состав целиком, чем сравнивать старые и новые строки построчно.
     */
    @Modifying
    @Query("delete from ApprovalRoute route where route.orgId = :orgId and route.documentTypeId = :documentTypeId")
    void deleteByOrgIdAndDocumentTypeId(@Param("orgId") long orgId, @Param("documentTypeId") long documentTypeId);
}
