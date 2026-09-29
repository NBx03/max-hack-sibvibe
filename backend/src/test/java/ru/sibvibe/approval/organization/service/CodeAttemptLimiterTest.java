package ru.sibvibe.approval.organization.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.common.api.DomainException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeAttemptLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-20T10:00:00Z"));
    private final CodeAttemptLimiter limiter = new CodeAttemptLimiter(3, clock);

    @Test
    void failedAttemptsBelowLimitDoNotBlock() {
        limiter.recordFailure(1);
        limiter.recordFailure(1);

        assertThatCode(() -> limiter.assertAllowed(1)).doesNotThrowAnyException();
    }

    @Test
    void exhaustedLimitBlocksWith429AndRetryAfter() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(1);
        }
        clock.advance(Duration.ofMinutes(10));

        assertThatThrownBy(() -> limiter.assertAllowed(1))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("TOO_MANY_ATTEMPTS");
                    assertThat(exception.httpStatus().value()).isEqualTo(429);
                    // первая неудача была 10 минут назад: окно в час откроется через ~50 минут
                    assertThat((Long) exception.details().get("retryAfterSeconds"))
                            .isBetween(2_990L, 3_010L);
                });
    }

    @Test
    void limitIsPerUser() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(1);
        }

        assertThatThrownBy(() -> limiter.assertAllowed(1)).isInstanceOf(DomainException.class);
        assertThatCode(() -> limiter.assertAllowed(2)).doesNotThrowAnyException();
    }

    @Test
    void attemptsExpireAfterAnHour() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(1);
        }
        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThatCode(() -> limiter.assertAllowed(1)).doesNotThrowAnyException();
    }

    /** Часы, которые можно переводить вперёд: настоящие часы в тесте времени не годятся. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
