package ru.sibvibe.approval.rules.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.sibvibe.approval.rules.RuleEngine.Severity;

import java.io.Serializable;
import java.time.Instant;

/**
 * Отличие правила компании от шаблона сервиса. {@code null} в колонке — как в шаблоне; строки нет — правило
 * целиком как в шаблоне. Если задан {@code sourceTitle}, пункт — только {@code sourceRef}, ссылка шаблона не действует.
 */
@Entity
@Table(name = "organization_rule_setting")
@IdClass(OrganizationRuleSetting.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class OrganizationRuleSetting {

    @Id
    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Id
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 32)
    private Severity severity;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "source_title", columnDefinition = "text")
    private String sourceTitle;

    @Column(name = "source_ref", columnDefinition = "text")
    private String sourceRef;

    @Column(name = "expected", columnDefinition = "text")
    private String expected;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private Long orgId;
        private Long ruleId;

        public Key(Long orgId, Long ruleId) {
            this.orgId = orgId;
            this.ruleId = ruleId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && java.util.Objects.equals(orgId, key.orgId)
                    && java.util.Objects.equals(ruleId, key.ruleId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(orgId, ruleId);
        }
    }
}
