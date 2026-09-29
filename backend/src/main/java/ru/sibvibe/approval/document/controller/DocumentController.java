package ru.sibvibe.approval.document.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.document.service.DisplayStatus;
import ru.sibvibe.approval.document.dto.ChangeTypeRequest;
import ru.sibvibe.approval.document.dto.CreateDocumentRequest;
import ru.sibvibe.approval.document.dto.CreateFormDocumentRequest;
import ru.sibvibe.approval.document.dto.CreateFormVersionRequest;
import ru.sibvibe.approval.document.dto.CreateVersionRequest;
import ru.sibvibe.approval.document.dto.DocumentCardResponse;
import ru.sibvibe.approval.document.dto.DocumentListResponse;
import ru.sibvibe.approval.document.dto.DocumentTypeResponse;
import ru.sibvibe.approval.document.dto.FileCorrectionResponse;
import ru.sibvibe.approval.document.dto.UpdateFieldsRequest;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.service.DocumentListTab;
import ru.sibvibe.approval.document.service.DocumentApiException;
import ru.sibvibe.approval.document.service.DocumentCommandService;
import ru.sibvibe.approval.document.service.DocumentViewService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentCommandService commandService;
    private final DocumentViewService viewService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public DocumentController(
            DocumentCommandService commandService,
            DocumentViewService viewService,
            ObjectMapper objectMapper,
            Validator validator
    ) {
        this.commandService = commandService;
        this.viewService = viewService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @GetMapping("/document-types")
    public List<DocumentTypeResponse> documentTypes() {
        return viewService.documentTypes();
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentCardResponse create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestPart("meta") String metaJson,
            @RequestPart("main") MultipartFile main,
            @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments
    ) {
        CreateDocumentRequest meta = parseMeta(metaJson, CreateDocumentRequest.class);
        return commandService.create(currentUser, meta, main, attachments);
    }

    /** Документ-форма: тот же адрес JSON-телом с content вместо файлов. */
    @PostMapping(value = "/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentCardResponse createForm(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @org.springframework.web.bind.annotation.RequestBody CreateFormDocumentRequest request
    ) {
        return commandService.createForm(currentUser, request);
    }

    @GetMapping("/documents")
    public DocumentListResponse documents(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam DocumentListTab tab,
            @RequestParam(required = false) DisplayStatus status,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0 || size < 1 || size > 100 || from != null && to != null && from.isAfter(to)) {
            throw DocumentApiException.validation("Некорректные параметры списка");
        }
        if (q != null && q.length() > 100) {
            throw DocumentApiException.validation("Слишком длинный запрос поиска");
        }
        return viewService.list(currentUser, tab, status, typeId, authorId, from, to, q, page, size);
    }

    /** Удалить черновик, который ещё не отправлялся. Только автор; иначе 404 или 409. */
    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable long id) {
        commandService.deleteDraft(id, currentUser);
    }

    @GetMapping("/documents/{id}")
    public DocumentCardResponse card(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long id
    ) {
        return viewService.card(id, currentUser);
    }

    @PostMapping(value = "/documents/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentCardResponse createVersion(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long id,
            @RequestPart("meta") String metaJson,
            @RequestPart(value = "main", required = false) MultipartFile main,
            @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments
    ) {
        CreateVersionRequest meta = parseMeta(metaJson, CreateVersionRequest.class);
        return commandService.createVersion(id, currentUser, meta, main, attachments);
    }

    /** Новая версия документа-формы: правка формы, файлов нет. */
    @PostMapping(value = "/documents/{id}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DocumentCardResponse createFormVersion(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long id,
            @Valid @org.springframework.web.bind.annotation.RequestBody CreateFormVersionRequest request
    ) {
        return commandService.createFormVersion(id, currentUser, request);
    }

    @PutMapping("/documents/{id}/versions/{versionNo}/fields")
    public DocumentCardResponse.CheckResult updateFields(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long id,
            @PathVariable int versionNo,
            @Valid @org.springframework.web.bind.annotation.RequestBody UpdateFieldsRequest request
    ) {
        return commandService.updateFields(id, versionNo, currentUser, request);
    }

    /**
     * «Проверить заново»: черновик или возвращённый документ перепроверяется по текущим правилам компании
     * по сохранённым полям, без обращения к модели. Сами версии не пересчитываются — это явное действие автора.
     */
    @PostMapping("/documents/{id}/versions/{versionNo}/recheck")
    public DocumentCardResponse recheck(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long id,
            @PathVariable int versionNo
    ) {
        return commandService.recheck(id, versionNo, currentUser);
    }

    /** Сменить тип черновика и перепроверить текущую версию по его правилам. */
    @PutMapping("/documents/{id}/versions/{versionNo}/type")
    public DocumentCardResponse changeType(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long id,
            @PathVariable int versionNo,
            @Valid @org.springframework.web.bind.annotation.RequestBody ChangeTypeRequest request
    ) {
        return commandService.changeType(id, versionNo, currentUser, request.documentTypeId());
    }

    /** «Исправить в файле»: вписать исправленные значения полей в DOCX новой версией. */
    @PostMapping("/documents/{id}/versions/{versionNo}/corrections")
    public FileCorrectionResponse correctFile(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long id,
            @PathVariable int versionNo,
            @Valid @org.springframework.web.bind.annotation.RequestBody UpdateFieldsRequest request
    ) {
        return commandService.correctFile(id, versionNo, currentUser, request);
    }

    private <T> T parseMeta(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw DocumentApiException.validation("Часть meta не должна быть пустой");
        }
        try {
            T value = objectMapper.readValue(json, type);
            if (!validator.validate(value).isEmpty()) {
                throw DocumentApiException.validation("Некорректные метаданные документа");
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw DocumentApiException.validation("Часть meta содержит некорректный JSON");
        }
    }
}
