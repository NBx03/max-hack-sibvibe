package ru.sibvibe.approval.document.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

/** Данные таблицы document; ссылки на другие модули хранятся как ID. */
@Entity
@Table(name = "document")
@Getter
@Setter
@NoArgsConstructor
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_type_id", nullable = false)
    private Long documentTypeId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "current_stage")
    private Integer currentStage;

    @Column(name = "current_version_no", nullable = false)
    private Integer currentVersionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 32)
    private Visibility visibility;

    /** Тип выбрала модель по тексту документа, а не автор — показывается на экране проверки. */
    @Column(name = "type_auto_detected", nullable = false)
    private boolean typeAutoDetected;

    /** Вид, который определил ИИ, даже если автор выбрал вид сам; null — ИИ вид не определял. */
    @Column(name = "ai_type_id")
    private Long aiTypeId;

    /** Название не вводил автор — оно следует за заголовком документа (поле subject) после каждой проверки. */
    @Column(name = "title_auto", nullable = false)
    private boolean titleAuto;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum Status { DRAFT, IN_APPROVAL, APPROVED, RETURNED, REJECTED }

    public enum Visibility { PRIVATE, ORG }

    public boolean isTitleAuto() {
        return titleAuto;
    }

    public void setTitleAuto(boolean titleAuto) {
        this.titleAuto = titleAuto;
    }

    public Long getAiTypeId() {
        return aiTypeId;
    }

    public void setAiTypeId(Long aiTypeId) {
        this.aiTypeId = aiTypeId;
    }
}
