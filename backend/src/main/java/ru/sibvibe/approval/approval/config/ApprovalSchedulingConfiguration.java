package ru.sibvibe.approval.approval.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает {@code @Scheduled} для модуля {@code approval} - сейчас единственный потребитель:
 * {@link ru.sibvibe.approval.approval.service.StuckStepReminderJob}. Модульно-scoped
 * бин конфигурации, как {@code bot.config.BotConfiguration} с {@code @EnableAsync}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ApprovalSchedulingConfiguration {
}
