package ru.sibvibe.approval.approval.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.event.StuckStepReminderEvent;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.document.service.DocumentStateService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Напоминание о застрявшем шаге: отбор и отметка «уже напомнили» уже сделаны атомарным
 * {@code UPDATE} в репозитории (не тестируется здесь без настоящей БД - см.
 * {@code backend/src/test/README.md}), проверяется только оркестрация вокруг него: пороги, переданные
 * в запрос, и то, что публикуется ровно по одному событию на каждый только что помеченный шаг.
 */
class StuckStepReminderJobTest {

    private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");

    private final ApprovalStepRepository steps = mock(ApprovalStepRepository.class);
    private final DocumentStateService documents = mock(DocumentStateService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final StuckStepReminderJob job =
            new StuckStepReminderJob(steps, documents, events, clock, 48, 24);

    @Test
    void passesConfiguredThresholdsToTheAtomicClaimWithAnHourOfDriftMargin() {
        when(steps.markStuckAsReminded(any(), any(), any(), any())).thenReturn(0);

        job.remindStuckSteps();

        // Запас в час: без него запуск, чуть сдвинутый во времени относительно
        // вчерашней отметки, примерно в половине случаев отсекал бы законное повторное напоминание.
        verify(steps).markStuckAsReminded(
                eq(NOW), eq(NOW.minusSeconds(48 * 3600)), eq(NOW.minusSeconds(23 * 3600)),
                eq(ApprovalStep.Decision.PENDING));
    }

    @Test
    void shortCooldownKeepsMostOfThePauseInsteadOfAFixedHour() {
        when(steps.markStuckAsReminded(any(), any(), any(), any())).thenReturn(0);

        // Пауза в час при частом cron: фиксированный запас в час обнулил бы её, и напоминание уходило бы
        // на каждом запуске. Запас - десятая часть паузы, то есть 6 минут.
        new StuckStepReminderJob(steps, documents, events, clock, 48, 1).remindStuckSteps();

        verify(steps).markStuckAsReminded(
                eq(NOW), eq(NOW.minusSeconds(48 * 3600)), eq(NOW.minusSeconds(54 * 60)),
                eq(ApprovalStep.Decision.PENDING));
    }

    @Test
    void nothingClaimedMeansNoLookupsAndNoEvents() {
        when(steps.markStuckAsReminded(any(), any(), any(), any())).thenReturn(0);

        job.remindStuckSteps();

        verify(steps, never()).findByLastRemindedAt(any());
        verifyNoInteractions(documents);
        verifyNoInteractions(events);
    }

    @Test
    void publishesOneEventPerClaimedStepWithTitleAndWaitingHours() {
        ApprovalStep first = step(1L, 10L, 2, 1, 100L, NOW.minusSeconds(60 * 3600));
        ApprovalStep second = step(2L, 11L, 3, 2, 101L, NOW.minusSeconds(50 * 3600));
        when(steps.markStuckAsReminded(any(), any(), any(), any())).thenReturn(2);
        when(steps.findByLastRemindedAt(NOW)).thenReturn(List.of(first, second));
        when(documents.titles(anySet())).thenReturn(Map.of(10L, "Заявление на отпуск", 11L, "Служебная записка"));

        job.remindStuckSteps();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(captor.capture());
        List<StuckStepReminderEvent> published = captor.getAllValues().stream()
                .map(StuckStepReminderEvent.class::cast).toList();

        assertThat(published).extracting(StuckStepReminderEvent::documentId).containsExactly(10L, 11L);
        assertThat(published).extracting(StuckStepReminderEvent::approverUserId).containsExactly(100L, 101L);
        assertThat(published).extracting(StuckStepReminderEvent::documentTitle)
                .containsExactly("Заявление на отпуск", "Служебная записка");
        assertThat(published).extracting(StuckStepReminderEvent::waitingHours).containsExactly(60L, 50L);
        assertThat(published).extracting(StuckStepReminderEvent::versionNo).containsExactly(2, 3);
        assertThat(published).extracting(StuckStepReminderEvent::stageOrder).containsExactly(1, 2);
    }

    @Test
    void skipsStepsWhoseDocumentTitleIsMissingButKeepsTheOthers() {
        ApprovalStep vanished = step(1L, 10L, 1, 1, 100L, NOW.minusSeconds(60 * 3600));
        ApprovalStep normal = step(2L, 11L, 1, 1, 101L, NOW.minusSeconds(60 * 3600));
        when(steps.markStuckAsReminded(any(), any(), any(), any())).thenReturn(2);
        when(steps.findByLastRemindedAt(NOW)).thenReturn(List.of(vanished, normal));
        // Документ 10 «исчез» - в MVP такого не бывает, но метод titles молча его не вернёт.
        when(documents.titles(anySet())).thenReturn(Map.of(11L, "Служебная записка"));

        job.remindStuckSteps();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertThat(((StuckStepReminderEvent) captor.getValue()).documentId()).isEqualTo(11L);
    }

    @Test
    void requestsTitlesOnlyForClaimedDocuments() {
        ApprovalStep step = step(1L, 10L, 1, 1, 100L, NOW.minusSeconds(60 * 3600));
        when(steps.markStuckAsReminded(any(), any(), any(), any())).thenReturn(1);
        when(steps.findByLastRemindedAt(NOW)).thenReturn(List.of(step));
        when(documents.titles(anySet())).thenReturn(Map.of(10L, "Заявка на меру поддержки"));

        job.remindStuckSteps();

        verify(documents).titles(Set.of(10L));
    }

    private static ApprovalStep step(
            long id, long documentId, int versionNo, int stageOrder, long approverId, Instant activatedAt
    ) {
        ApprovalStep step = new ApprovalStep();
        step.setId(id);
        step.setDocumentId(documentId);
        step.setVersionNo(versionNo);
        step.setStageOrder(stageOrder);
        step.setApproverId(approverId);
        step.setRoleId(1L);
        step.setOrigin(ApprovalStep.Origin.TEMPLATE);
        step.setDecision(ApprovalStep.Decision.PENDING);
        step.setCreatedAt(activatedAt);
        step.setActivatedAt(activatedAt);
        return step;
    }
}
