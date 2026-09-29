package ru.sibvibe.approval.document.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.sibvibe.approval.document.service.FileDownloadService;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileDownloadService downloadService;

    public FileController(FileDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable long fileId,
            @RequestParam String token
    ) {
        FileDownloadService.DownloadedFile file = downloadService.download(fileId, token);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(file.content()));
    }
}
