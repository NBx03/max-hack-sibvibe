package ru.sibvibe.approval.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sibvibe.approval.document.entity.DocumentVersion;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {
    List<DocumentVersion> findByDocumentIdOrderByVersionNoAsc(Long documentId);

    Optional<DocumentVersion> findByDocumentIdAndVersionNo(Long documentId, Integer versionNo);
}
