package ru.sibvibe.approval.approval.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.sibvibe.approval.approval.entity.ApprovalStep;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, Long> {
    /**
     * Документы, где человек ещё не решил, — только номера: шаги читаются уже под блокировкой документа
     * (см. {@link #findDocumentIdOfStep}). {@code roleIds} {@code null} — в любой роли.
     */
    @Query("""
            select distinct s.documentId from ApprovalStep s
            where s.approverId = :approverId and s.decision = :decision
              and (:anyRole = true or s.roleId in :roleIds)
            order by s.documentId
            """)
    List<Long> findDocumentIdsWithDecision(
            Long approverId, ApprovalStep.Decision decision, boolean anyRole, Collection<Long> roleIds);

    /**
     * Документ шага, если шаг принадлежит этому пользователю. Возвращается число, а не сущность: до блокировки
     * документа строку шага читать нельзя, иначе кэш сессии сохранит устаревшее состояние, пока мы ждали очередь.
     */
    @Query("select s.documentId from ApprovalStep s where s.id = :stepId and s.approverId = :approverId")
    Optional<Long> findDocumentIdOfStep(Long stepId, Long approverId);

    List<ApprovalStep> findByDocumentIdAndVersionNoOrderByStageOrderAscIdAsc(Long documentId, Integer versionNo);

    /**
     * Атомарная отметка напоминания: один запрос находит застрявшие шаги активных этапов -
     * {@code PENDING}, активированные, старше {@code threshold} - и сразу помечает их напомненными, если
     * с прошлого напоминания прошло не меньше суток ({@code cooldownBefore}). Тот же приём, что и у
     * атомарного использования личной ссылки (docs/DESIGN-DECISIONS.md, «Обязательные проверки», п. 2а): повторный или
     * зависший предыдущий запуск планировщика не пришлёт напоминание дважды.
     *
     * @return число только что помеченных шагов
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ApprovalStep s set s.lastRemindedAt = :now
            where s.decision = :pending and s.activatedAt is not null and s.activatedAt <= :threshold
              and (s.lastRemindedAt is null or s.lastRemindedAt <= :cooldownBefore)
            """)
    int markStuckAsReminded(Instant now, Instant threshold, Instant cooldownBefore, ApprovalStep.Decision pending);

    /** Шаги, только что помеченные {@link #markStuckAsReminded} - по ним и рассылаются напоминания. */
    List<ApprovalStep> findByLastRemindedAt(Instant lastRemindedAt);
}
