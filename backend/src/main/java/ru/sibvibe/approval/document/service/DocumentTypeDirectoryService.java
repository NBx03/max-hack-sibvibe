package ru.sibvibe.approval.document.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sibvibe.approval.document.entity.DocumentType;
import ru.sibvibe.approval.document.repository.DocumentTypeRepository;
import ru.sibvibe.approval.organization.DocumentTypeDirectory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Отдаёт organization id типов документов по кодам, не раскрывая сущности document. */
@Service
public class DocumentTypeDirectoryService implements DocumentTypeDirectory {

    private final DocumentTypeRepository typeRepository;

    public DocumentTypeDirectoryService(DocumentTypeRepository typeRepository) {
        this.typeRepository = typeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> idsByCode(Collection<String> codes) {
        Map<String, Long> result = new HashMap<>();
        for (DocumentType type : typeRepository.findByCodeIn(codes)) {
            result.put(type.getCode(), type.getId());
        }
        return Map.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TypeInfo> find(long id) {
        return typeRepository.findById(id).map(type -> new TypeInfo(type.getId(), type.getCode()));
    }
}
