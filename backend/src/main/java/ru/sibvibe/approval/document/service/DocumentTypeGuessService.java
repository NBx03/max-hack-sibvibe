package ru.sibvibe.approval.document.service;

import org.springframework.stereotype.Service;
import ru.sibvibe.approval.ai.DocumentClassifier;
import ru.sibvibe.approval.ai.service.DocumentExtractionService;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.repository.DocumentTypeRepository;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * «Определить автоматически» при загрузке: модель по тексту выбирает один из типов с подробной
 * проверкой; не уверена или недоступна — «Другой документ» (GENERIC) с общей проверкой реквизитов.
 * Так ИИ работает с любым загруженным файлом, а не только с тем, для которого человек угадал тип.
 */
@Service
public class DocumentTypeGuessService {

    static final String OTHER_DOCUMENT = "GENERIC";

    // Чем виды отличаются — подсказка модели. Описания держим рядом с кодами типов, а не в базе:
    // это часть того, как модель выбирает схему, а не справочник компании.
    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "OFFICIAL_MEMO", "Служебная записка: внутренний документ сотрудника руководителю или коллеге внутри "
                    + "одной организации — адресат, слова «служебная записка», заголовок «О …», просьба или сообщение, "
                    + "подпись с должностью. Письмо в другую организацию (исходящий номер «Исх. №», обращение "
                    + "«Уважаемый…», адресат из другой компании), приказ, акт, договор — не служебная записка.",
            "VACATION_REQUEST", "Заявление сотрудника на отпуск: «прошу предоставить … отпуск с … на … календарных дней».",
            "SUPPORT_MEASURE_REQUEST", "Заявка организации или индивидуального предпринимателя на меру государственной "
                    + "поддержки: заявитель, ИНН, категория субъекта МСП, вид поддержки.",
            "BUSINESS_TRIP_REQUEST", "Заявка сотрудника на служебную командировку: «прошу направить в командировку», "
                    + "место командировки, цель, дата начала, срок в календарных днях.");

    private final DocumentTypeRepository typeRepository;
    private final DocumentExtractionService extractionService;

    public DocumentTypeGuessService(DocumentTypeRepository typeRepository, DocumentExtractionService extractionService) {
        this.typeRepository = typeRepository;
        this.extractionService = extractionService;
    }

    /** Описание вида для модели; пусто — для типов, которые модели не предлагаются. */
    public static String describe(String code) {
        return DESCRIPTIONS.getOrDefault(code, "");
    }

    public Guess guess(byte[] content, String mimeType) {
        List<DocumentType> types = typeRepository.findAllByOrderById();
        List<DocumentClassifier.TypeOption> options = types.stream()
                .filter(type -> !type.isGeneric() && DESCRIPTIONS.containsKey(type.getCode()))
                .map(type -> new DocumentClassifier.TypeOption(type.getCode(), type.getName(), describe(type.getCode())))
                .toList();
        DocumentClassifier.Classification classification =
                extractionService.classify(new ByteArrayInputStream(content), mimeType, options);
        String code = classification.typeCode() == null ? OTHER_DOCUMENT : classification.typeCode();
        DocumentType type = typeRepository.findByCodeIn(Set.of(code)).stream().findFirst()
                .or(() -> typeRepository.findByCodeIn(Set.of(OTHER_DOCUMENT)).stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("Не найден тип документа " + OTHER_DOCUMENT));
        return new Guess(type, classification.title());
    }

    /** @param title название по содержанию или null — тогда вызывающий берёт имя файла */
    public record Guess(DocumentType type, String title) {}
}
