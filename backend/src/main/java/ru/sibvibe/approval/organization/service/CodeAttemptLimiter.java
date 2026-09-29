package ru.sibvibe.approval.organization.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Ограничение неудачных попыток ввода кода компании на пользователя (docs/DESIGN-DECISIONS.md, проверка №2).
 *
 * Считаются только неверные коды: верный код не тратит попытки. Исчерпав лимит, человек не может
 * проверять и верные коды до конца окна, иначе перебор продолжился бы. Хранится в памяти процесса:
 * рассчитано на одну реплику бэкенда (так запускает compose.yaml), при перезапуске окно начинается заново,
 * а при нескольких репликах каждая считала бы попытки отдельно — тогда счётчик нужно переносить в базу.
 */
@Component
public class CodeAttemptLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<Long, Deque<Instant>> failures = new HashMap<>();
    private final int limit;
    private final Clock clock;

    public CodeAttemptLimiter(
            @Value("${app.onboarding.join-attempts-per-hour:10}") int limit,
            Clock clock
    ) {
        this.limit = limit;
        this.clock = clock;
    }

    /** @throws ru.sibvibe.approval.common.api.DomainException 429, если лимит исчерпан */
    public synchronized void assertAllowed(long userId) {
        Instant now = clock.instant();
        Deque<Instant> attempts = pruned(userId, now);
        if (attempts != null && attempts.size() >= limit) {
            Instant reopensAt = attempts.peekFirst().plus(WINDOW);
            long seconds = Math.max(1, Duration.between(now, reopensAt).toSeconds() + 1);
            throw OrganizationErrors.tooManyAttempts(seconds);
        }
    }

    public synchronized void recordFailure(long userId) {
        Instant now = clock.instant();
        if (failures.size() > CLEANUP_THRESHOLD) {
            failures.keySet().removeIf(id -> pruned(id, now) == null);
        }
        failures.computeIfAbsent(userId, id -> new ArrayDeque<>()).addLast(now);
    }

    private Deque<Instant> pruned(long userId, Instant now) {
        Deque<Instant> attempts = failures.get(userId);
        if (attempts == null) {
            return null;
        }
        Instant threshold = now.minus(WINDOW);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(threshold)) {
            attempts.removeFirst();
        }
        if (attempts.isEmpty()) {
            failures.remove(userId);
            return null;
        }
        return attempts;
    }
}
