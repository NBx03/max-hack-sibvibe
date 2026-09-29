package ru.sibvibe.approval.organization.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/** Данные таблицы invite_role; ссылки на другие модули хранятся как ID. */
@Entity
@Table(name = "invite_role")
@Getter
@Setter
@NoArgsConstructor
@IdClass(InviteRole.Key.class)
public class InviteRole {

    @Id
    @Column(name = "invite_id", nullable = false)
    private Long inviteId;

    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long inviteId;
        private Long roleId;
    }
}
