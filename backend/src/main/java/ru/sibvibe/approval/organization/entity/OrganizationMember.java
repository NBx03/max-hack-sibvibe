package ru.sibvibe.approval.organization.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

/** Данные таблицы organization_member; ссылки на другие модули хранятся как ID. */
@Entity
@Table(name = "organization_member")
@Getter
@Setter
@NoArgsConstructor
public class OrganizationMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "is_admin", nullable = false)
    private boolean admin;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    // Составной FK в БД гарантирует совпадение с organization.is_demo.
    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    public enum Status { ACTIVE, DISABLED }
}
