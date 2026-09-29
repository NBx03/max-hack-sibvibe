package ru.sibvibe.approval.approval.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

/** Данные таблицы approval_step; ссылки на другие модули хранятся как ID. */
@Entity
@Table(name = "approval_step")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "approver_id", nullable = false)
    private Long approverId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "stage_order", nullable = false)
    private Integer stageOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 32)
    private Origin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 32)
    private Decision decision;

    /** APPROVAL — согласование на этапе, ENDORSEMENT — утверждение, последний шаг маршрута. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind = Kind.APPROVAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "auto_reason", length = 32)
    private AutoReason autoReason;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Когда согласующему в последний раз напоминали про этот шаг; {@code null} - ни разу. */
    @Column(name = "last_reminded_at")
    private Instant lastRemindedAt;

    public enum Origin { TEMPLATE, ADDED_BY_AUTHOR }

    /**
     * Документ утверждён, а не просто согласован: в версии есть одобренный шаг утверждения. То же правило, что у
     * показываемого статуса ENDORSED (DocumentReadRepository.DISPLAY_STATUS), — бот и приложение не расходятся, в том
     * числе когда утверждение перенесено с прошлой версии, а последним решал согласующий раннего этапа.
     */
    public static boolean endorsedIn(java.util.Collection<ApprovalStep> versionSteps) {
        return versionSteps.stream().anyMatch(step -> step.getKind() == Kind.ENDORSEMENT && step.getDecision() == Decision.APPROVED);
    }

    public enum Kind { APPROVAL, ENDORSEMENT }

    public enum Decision { PENDING, APPROVED, RETURNED, REJECTED, SKIPPED }

    /**
     * AUTHOR_HOLDS_ROLE — согласовано автоматически: обязательная роль есть только у автора. CARRIED_OVER —
     * одобрение прошлой версии перенесено: документ с тех пор не менялся. MEMBER_REMOVED — шаг закрыт
     * (SKIPPED), потому что согласующего исключили из компании или сняли с него роль; документ вернулся автору.
     */
    public enum AutoReason { AUTHOR_HOLDS_ROLE, CARRIED_OVER, MEMBER_REMOVED }
}
