package ru.sibvibe.approval.document.service;

/**
 * Статус документа, каким его видит человек. В базе у документа прежние пять
 * статусов; «На утверждении» и «Утверждён» вычисляются по шагам маршрута одним SQL-выражением
 * ({@code DocumentReadRepository.DISPLAY_STATUS}) — поэтому карточка, списки, фильтр и бот показывают одно и то же.
 */
public enum DisplayStatus {
    DRAFT,
    IN_APPROVAL,
    /** {@code IN_APPROVAL}, и текущий этап — утверждение. */
    IN_ENDORSEMENT,
    /** {@code APPROVED} без этапа утверждения. */
    APPROVED,
    /** {@code APPROVED}, и утверждающий утвердил. */
    ENDORSED,
    RETURNED,
    REJECTED
}
