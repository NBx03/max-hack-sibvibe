package ru.sibvibe.approval.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sibvibe.approval.document.entity.DocumentFile;

import java.util.Collection;
import java.util.List;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, Long> {
    List<DocumentFile> findByVersionIdOrderByPosition(Long versionId);

    List<DocumentFile> findByVersionIdInOrderByVersionIdAscPositionAsc(Collection<Long> versionIds);
}
