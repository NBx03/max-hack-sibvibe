package ru.sibvibe.approval.organization.entity;

import ru.sibvibe.approval.organization.service.CompanyTimeZones;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

/** Данные таблицы organization; ссылки на другие модули хранятся как ID. */
@Entity
@Table(name = "organization")
@Getter
@Setter
@NoArgsConstructor
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "inn")
    private String inn;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Column(name = "demo_owner_id")
    private Long demoOwnerId;

    /** Город компании — показывается в «Компании»; по нему выбран часовой пояс. */
    @Column(name = "city", nullable = false)
    private String city = CompanyTimeZones.DEFAULT_CITY;

    /** Часовой пояс компании (IANA, только пояса России): «сегодня» для правил, периодов и показателей. */
    @Column(name = "time_zone", nullable = false)
    private String timeZone = CompanyTimeZones.DEFAULT_ZONE;
}
