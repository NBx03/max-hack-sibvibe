package ru.sibvibe.approval.organization.service;

import java.util.List;

/**
 * Стандартные предметные роли новой компании (docs/DESIGN-DECISIONS.md, «Подключение компании»).
 * Права администратора — флаг участника, а не роль, поэтому здесь их нет.
 */
final class DefaultRoles {

    static final String DIRECTOR = "DIRECTOR";
    static final String DEPARTMENT_HEAD = "DEPARTMENT_HEAD";
    static final String LAWYER = "LAWYER";
    static final String ACCOUNTANT = "ACCOUNTANT";
    static final String HR = "HR";

    static final List<Seed> ALL = List.of(
            new Seed(DIRECTOR, "Директор"),
            new Seed(DEPARTMENT_HEAD, "Руководитель отдела"),
            new Seed(LAWYER, "Юрист"),
            new Seed(ACCOUNTANT, "Бухгалтер"),
            new Seed(HR, "Кадровик")
    );

    private DefaultRoles() {
    }

    record Seed(String code, String name) {
    }
}
