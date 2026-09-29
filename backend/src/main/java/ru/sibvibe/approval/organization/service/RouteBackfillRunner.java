package ru.sibvibe.approval.organization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * При старте добирает маршруты по умолчанию компаниям, созданным до появления типов документов.
 * Миграции уже выполнены к этому моменту, поэтому новые типы из справочника видны.
 *
 * Сбой здесь не должен мешать запуску: приложение и основной сценарий важнее добора маршрутов,
 * а повторный старт попробует снова.
 */
@Component
public class RouteBackfillRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteBackfillRunner.class);

    private final RouteTemplateService routeTemplateService;

    public RouteBackfillRunner(RouteTemplateService routeTemplateService) {
        this.routeTemplateService = routeTemplateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = routeTemplateService.backfillAllOrganizations();
            if (created > 0) {
                LOGGER.info("Добрано маршрутов согласования по умолчанию: {}", created);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Не удалось добрать маршруты по умолчанию; приложение продолжает работу", exception);
        }
    }
}
