package ru.sibvibe.approval.document;

import java.util.List;

/**
 * Единственный прямой вызов {@code bot → document} (ARCHITECTURE.md, раздел 2, таблица
 * «Может вызывать»): статус документа текстовым запросом в боте. Это не «обращённая зависимость» из
 * того же раздела (там интерфейс лежит у нуждающегося модуля, а реализует его тот, у кого есть данные,
 * потому что нуждающийся не может звать владельца напрямую) - здесь и определяет, и реализует порт сам
 * {@code document}, а {@code bot} просто становится его законным вызывающим. Интерфейс всё равно
 * выделен: тесты {@code bot} подменяют его без {@code DocumentReadRepository} и {@code JdbcClient}.
 */
public interface DocumentLookup {

    /**
     * @param orgId    компания запрашивающего (его реальное членство, не демо-песочница - у бота нет
     *                 понятия «действовать как», в отличие от {@code X-Demo-Act-As})
     * @param userId   запрашивающий - тот же список видимых документов, что и в мини-приложении
     * @param fragment часть названия, без учёта регистра
     * @param limit    не больше стольки совпадений
     * @return найденные документы, самые свежие первыми; пусто, если совпадений нет
     */
    List<Match> findByTitleFragment(long orgId, long userId, String fragment, int limit);

    /**
     * {@code status} - имя {@code Document.Status} строкой, не сама сущность: {@code bot} не имеет
     * права импортировать {@code document.entity} (ARCHITECTURE.md, проверяет {@code ModuleBoundariesTest}).
     */
    record Match(long id, String title, String status, Integer currentStage) {}
}
