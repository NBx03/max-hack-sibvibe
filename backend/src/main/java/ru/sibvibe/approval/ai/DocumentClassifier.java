package ru.sibvibe.approval.ai;

import java.util.List;

/**
 * Порт определения вида документа по его тексту — второе и последнее место, где работает модель,
 * рядом с {@link DocumentExtractor}. Она не оценивает документ: только выбирает, по какой схеме
 * полей его читать, и предлагает короткое название для списка. Проверку по-прежнему делают
 * правила ({@code RuleEngine}), решение — люди.
 *
 * Зачем: человек, загрузивший свой файл, не обязан знать наши типы.
 */
public interface DocumentClassifier {

    /**
     * Никогда не выбрасывает исключение: при недоступной модели — {@code available = false},
     * и вызывающий берёт тип «Другой документ» и название из имени файла.
     */
    Classification classify(ClassificationRequest request);

    /** Вариант выбора: код типа, его название и чем он отличается — подсказка модели. */
    record TypeOption(String code, String name, String description) {}

    /**
     * @param maskedText начало текста документа, уже маскированное: сырой текст в порт не передаётся
     */
    record ClassificationRequest(String maskedText, List<TypeOption> options) {}

    /**
     * @param typeCode код из {@code options} или null — ни один вариант не подходит уверенно
     * @param title    короткое название по содержанию документа, ещё маскированное; null — не нашлось
     */
    record Classification(String typeCode, String title, boolean available) {}
}
