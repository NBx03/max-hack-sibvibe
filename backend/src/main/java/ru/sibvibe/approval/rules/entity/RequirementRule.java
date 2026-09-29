package ru.sibvibe.approval.rules.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import ru.sibvibe.approval.rules.RuleEngine.CheckType;
import ru.sibvibe.approval.rules.RuleEngine.RuleKind;
import ru.sibvibe.approval.rules.RuleEngine.Severity;

/** Данные таблицы requirement_rule; ссылки на другие модули хранятся как ID. */
@Entity
@Table(name = "requirement_rule")
@Getter
@Setter
@NoArgsConstructor
public class RequirementRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_type_id", nullable = false)
    private Long documentTypeId;

    @Column(name = "field_name", nullable = false)
    private String fieldName;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 32)
    private CheckType checkType;

    @Column(name = "expected", columnDefinition = "text")
    private String expected;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private RuleKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 32)
    private Severity severity;

    @Column(name = "source_title", nullable = false, columnDefinition = "text")
    private String sourceTitle;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "source_ref", columnDefinition = "text")
    private String sourceRef;

    @Column(name = "source_checked_at", nullable = false)
    private Instant sourceCheckedAt;
}
