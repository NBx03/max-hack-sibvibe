package ru.sibvibe.approval.document.service;

import org.springframework.stereotype.Service;
import ru.sibvibe.approval.common.api.NotFoundException;
import ru.sibvibe.approval.document.repository.DocumentReadRepository;
import ru.sibvibe.approval.storage.FileStorage;
import ru.sibvibe.approval.storage.StoredFileNotFoundException;

@Service
public class FileDownloadService {

    private final DownloadTokenService tokenService;
    private final DocumentReadRepository readRepository;
    private final FileStorage fileStorage;

    public FileDownloadService(
            DownloadTokenService tokenService,
            DocumentReadRepository readRepository,
            FileStorage fileStorage
    ) {
        this.tokenService = tokenService;
        this.readRepository = readRepository;
        this.fileStorage = fileStorage;
    }

    public DownloadedFile download(long fileId, String token) {
        DownloadTokenService.VerifiedToken verified = tokenService.verify(fileId, token);
        if (!readRepository.canUserReadFile(fileId, verified.userId())) {
            throw new NotFoundException();
        }
        DocumentReadRepository.FileAccess file = readRepository.findFile(fileId)
                .orElseThrow(NotFoundException::new);
        try {
            FileStorage.StoredFile stored = fileStorage.get(file.storageKey());
            return new DownloadedFile(
                    stored.content(),
                    file.fileName(),
                    file.mimeType(),
                    file.size());
        } catch (StoredFileNotFoundException exception) {
            throw new NotFoundException();
        }
    }

    public record DownloadedFile(
            java.io.InputStream content,
            String fileName,
            String mimeType,
            long size
    ) {}
}
