package ru.sibvibe.approval.organization.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Данные таблицы approval_route; ссылки на другие модули хранятся как ID. */
@Entity
@Table(name = "approval_route")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_type_id", nullable = false)
    private Long documentTypeId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "stage_order", nullable = false)
    private Integer stageOrder;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "is_mandatory", nullable = false)
    private boolean mandatory;
}
