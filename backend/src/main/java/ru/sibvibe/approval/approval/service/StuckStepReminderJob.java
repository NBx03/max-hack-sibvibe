package ru.sibvibe.approval.approval.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.event.StuckStepReminderEvent;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.document.service.DocumentStateService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Напоминание о шаге, который висит в {@code PENDING} дольше порога. Раз в сутки публикует по одному
 * {@link StuckStepReminderEvent} на такой шаг; {@code bot.NotificationListener} отправляет сообщение с кнопкой
 * открытия документа.
 *
 * Порог, период запуска и пауза между напоминаниями по одному шагу задаются настройками
 * {@code app.reminders.*} (application.yml).
 *
 * Отбор и отметка «напомнили» — один атомарный {@code UPDATE}
 * ({@link ApprovalStepRepository#markStuckAsReminded}): повторный запуск планировщика не присылает напоминание
 * дважды. Сбой отправки транзакцию не затрагивает: {@code Notifier} не пробрасывает исключения, слушатель
 * асинхронный.
 *
 * {@link #CLOCK_DRIFT_MARGIN}: время старта {@code @Scheduled} плавает, и условие
 * {@code lastRemindedAt <= now - cooldown} без запаса иногда отсекает законное напоминание — оно приходит
 * раз в двое суток. Запас не больше десятой части паузы.
 */
@Service
@ConditionalOnProperty(prefix = "app.reminders", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StuckStepReminderJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(StuckStepReminderJob.class);
    private static final Duration CLOCK_DRIFT_MARGIN = Duration.ofHours(1);
    private static final int MARGIN_SHARE_OF_COOLDOWN = 10;

    private final ApprovalStepRepository steps;
    private final DocumentStateService documents;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration stuckAfter;
    private final Duration cooldown;
    private final Duration margin;

    public StuckStepReminderJob(
            ApprovalStepRepository steps,
            DocumentStateService documents,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${app.reminders.stuck-after-hours:48}") long stuckAfterHours,
            @Value("${app.reminders.cooldown-hours:24}") long cooldownHours
    ) {
        this.steps = steps;
        this.documents = documents;
        this.events = events;
        this.clock = clock;
        this.stuckAfter = Duration.ofHours(stuckAfterHours);
        this.cooldown = Duration.ofHours(cooldownHours);
        Duration share = this.cooldown.dividedBy(MARGIN_SHARE_OF_COOLDOWN);
        this.margin = share.compareTo(CLOCK_DRIFT_MARGIN) < 0 ? share : CLOCK_DRIFT_MARGIN;
    }

    @Scheduled(cron = "${app.reminders.cron:0 0 8 * * *}", zone = "Europe/Moscow")
    @Transactional
    public void remindStuckSteps() {
        // Микросекунды - предел точности timestamptz в PostgreSQL; обрезаем явно, а не полагаемся
        // на то, что драйвер сделает это одинаково при записи в markStuckAsReminded и при последующем
        // чтении в findByLastRemindedAt.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant threshold = now.minus(stuckAfter);
        Instant cooldownBefore = now.minus(cooldown).plus(margin);

        int claimed = steps.markStuckAsReminded(now, threshold, cooldownBefore, ApprovalStep.Decision.PENDING);
        if (claimed == 0) {
            return;
        }

        List<ApprovalStep> reminded = steps.findByLastRemindedAt(now);
        Set<Long> documentIds = reminded.stream().map(ApprovalStep::getDocumentId).collect(Collectors.toSet());
        Map<Long, String> titles = documents.titles(documentIds);

        for (ApprovalStep step : reminded) {
            String title = titles.get(step.getDocumentId());
            if (title == null) {
                LOGGER.warn("Название документа {} не найдено для напоминания по шагу {}",
                        step.getDocumentId(), step.getId());
                continue;
            }
            long waitingHours = Duration.between(step.getActivatedAt(), now).toHours();
            events.publishEvent(new StuckStepReminderEvent(step.getDocumentId(), step.getVersionNo(),
                    step.getStageOrder(), step.getApproverId(), title, waitingHours,
                    step.getKind() == ApprovalStep.Kind.ENDORSEMENT));
        }
        LOGGER.info("Напоминания о застрявших шагах: {}", reminded.size());
    }
}
