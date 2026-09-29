package ru.sibvibe.approval.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sibvibe.approval.document.entity.DocumentTypeField;

import java.util.List;

public interface DocumentTypeFieldRepository extends JpaRepository<DocumentTypeField, Long> {
    List<DocumentTypeField> findByDocumentTypeIdOrderByPositionAscIdAsc(Long documentTypeId);

    List<DocumentTypeField> findAllByOrderByDocumentTypeIdAscPositionAscIdAsc();
}
