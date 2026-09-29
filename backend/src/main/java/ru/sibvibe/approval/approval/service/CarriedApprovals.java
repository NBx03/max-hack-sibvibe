package ru.sibvibe.approval.approval.service;

import org.springframework.stereotype.Component;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.approval.repository.ApprovalStepRepository;
import ru.sibvibe.approval.document.service.VersionChangesService;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Одобрения прошлой версии, которые остаются в силе.
 *
 * Повторная отправка по-прежнему начинается с первого этапа: одобрение относится к содержимому, и если
 * документ изменился, прежнее «согласовано» к новому тексту не относится. Но если новая версия <b>ничем не
 * отличается</b> от прошлой — те же файлы, те же значения полей, — повторно спрашивать тех, кто её уже
 * одобрил, незачем: это те же байты. Так бывает, когда автор отозвал документ, чтобы добавить согласующего,
 * или документ вернули из-за маршрута, а не из-за текста, или согласующего исключили из компании.
 * Вернувший документ решает заново: его шаг в прошлой версии — не одобрение. Переносится только решение того же
 * вида: «согласовал» не превращается в «утвердил» — гриф «УТВЕРЖДАЮ» ставит только нажавший «Утвердить».
 *
 * Сравнивается только с непосредственно предыдущей версией; цепочка переносов работает сама: перенесённое
 * одобрение — тоже одобрение.
 */
@Component
public class CarriedApprovals {

    private final VersionChangesService changes;
    private final ApprovalStepRepository steps;

    public CarriedApprovals(VersionChangesService changes, ApprovalStepRepository steps) {
        this.changes = changes;
        this.steps = steps;
    }

    /** Кто одобрил предыдущую версию и в какой роли; пусто — версия первая, изменилась или одобрений не было. */
    public Optional<Carried> of(long documentId, int versionNo) {
        if (versionNo <= 1 || !changes.sameAsPrevious(documentId, versionNo)) {
            return Optional.empty();
        }
        Set<Key> approved = steps.findByDocumentIdAndVersionNoOrderByStageOrderAscIdAsc(documentId, versionNo - 1)
                .stream()
                // Автосогласование автором не переносим: при новой отправке оно и так получится само.
                .filter(step -> step.getDecision() == ApprovalStep.Decision.APPROVED
                        && step.getAutoReason() != ApprovalStep.AutoReason.AUTHOR_HOLDS_ROLE)
                .map(step -> new Key(step.getRoleId(), step.getApproverId(), step.getKind()))
                .collect(Collectors.toUnmodifiableSet());
        return approved.isEmpty() ? Optional.empty() : Optional.of(new Carried(versionNo - 1, approved));
    }

    public record Key(long roleId, long userId, ApprovalStep.Kind kind) {
    }

    public record Carried(int fromVersionNo, Set<Key> approvals) {

        public boolean covers(long roleId, long userId, ApprovalStep.Kind kind) {
            return approvals.contains(new Key(roleId, userId, kind));
        }
    }
}
