package ru.sibvibe.approval.document.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.document.DocumentLookup;
import ru.sibvibe.approval.document.repository.DocumentReadRepository;

import java.util.List;

/** Реализация {@link DocumentLookup} поверх уже существующей ACL-проекции {@link DocumentReadRepository}. */
@Service
public class DocumentLookupService implements DocumentLookup {

    private final DocumentReadRepository repository;

    public DocumentLookupService(DocumentReadRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Match> findByTitleFragment(long orgId, long userId, String fragment, int limit) {
        String trimmed = fragment == null ? "" : fragment.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return repository.findByTitleFragment(orgId, userId, trimmed, limit).stream()
                .map(row -> new Match(row.id(), row.title(), row.displayStatus().name(), row.currentStage()))
                .toList();
    }
}
