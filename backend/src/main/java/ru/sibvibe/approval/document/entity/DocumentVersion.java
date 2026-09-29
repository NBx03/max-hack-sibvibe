package ru.sibvibe.approval.document.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Данные таблицы document_version; ссылки на другие модули хранятся как ID. */
@Entity
@Table(name = "document_version")
@Getter
@Setter
@NoArgsConstructor
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "contains_sensitive", nullable = false)
    private boolean containsSensitive;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb")
    private JsonNode content;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_fields", columnDefinition = "jsonb")
    private JsonNode extractedFields;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_issues", columnDefinition = "jsonb")
    private JsonNode validationIssues;

    @Column(name = "check_status")
    private String checkStatus;

    /** Проверенная краткая сводка модели; {@code null}, если модель недоступна или документ чувствительный. */
    @Column(name = "ai_summary")
    private String aiSummary;

    /** Автор отозвал эту версию с согласования; {@code null} — не отзывалась. */
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;
}
