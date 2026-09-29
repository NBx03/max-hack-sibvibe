package ru.sibvibe.approval.organization;

import java.time.Instant;

/**
 * Наполняет личную демо-песочницу данными модулей document и approval.
 * Интерфейс принадлежит organization, чтобы модуль не зависел от внутренних классов этих модулей.
 */
public interface DemoSandboxContentSeeder {

    void seed(Seed seed);

    record Seed(
            long orgId,
            long authorId,
            long lawyerId,
            long accountantId,
            long directorId,
            long lawyerRoleId,
            long accountantRoleId,
            long directorRoleId,
            Instant createdAt
    ) {
    }
}
