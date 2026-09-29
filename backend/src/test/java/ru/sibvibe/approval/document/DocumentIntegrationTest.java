package ru.sibvibe.approval.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import ru.sibvibe.approval.storage.FileStorage;
import ru.sibvibe.approval.storage.StoredFileNotFoundException;
import ru.sibvibe.approval.document.service.DocxCorrector;
import ru.sibvibe.approval.document.service.DownloadTokenService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "SCHEMA_TEST_URL", matches = ".+")
class DocumentIntegrationTest {

    private static final String AUTHOR_AUTH = "Dev 920000001";
    private static final String FOREIGN_AUTH = "Dev 920000002";
    private static final String COLLEAGUE_AUTH = "Dev 920000003";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SCHEMA_TEST_URL"));
        registry.add("spring.datasource.username", () -> "schema_test");
        registry.add("spring.datasource.password", () -> System.getenv("SCHEMA_TEST_PASSWORD"));
        registry.add("spring.autoconfigure.exclude",
                () -> "chat.giga.springai.autoconfigure.GigaChatAutoConfiguration");
        registry.add("app.download.secret",
                () -> "document-integration-test-secret-32-bytes");
        registry.add("max.mini-app.url", () -> "");
        // Без токена бот и так деградирует до NoopNotifier, но флаг явный и не зависит от этого (BotConfiguration).
        registry.add("max.bot.enabled", () -> "false");
        registry.add("minio.endpoint", () -> "http://127.0.0.1:9000");
        registry.add("minio.access-key", () -> "test-access-key");
        registry.add("minio.secret-key", () -> "test-secret-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InMemoryFileStorage storage;

    private long authorId;
    private long foreignId;
    private long colleagueId;
    private long authorOrgId;
    private long foreignOrgId;
    private long typeId;

    @Autowired
    private DownloadTokenService tokenService;

    @Autowired
    private DocumentLookup documentLookup;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        storage.clear();
        authorId = user(920000001L, "Автор");
        foreignId = user(920000002L, "Чужой пользователь");
        colleagueId = user(920000003L, "Коллега");
        authorOrgId = organization("Компания автора", authorId);
        foreignOrgId = organization("Другая компания", foreignId);
        member(authorOrgId, authorId);
        member(authorOrgId, colleagueId);
        member(foreignOrgId, foreignId);
        typeId = jdbc.queryForObject("""
                insert into document_type(name, code, is_generic)
                values ('Приказ', 'ISSUE16_ORDER', false) returning id
                """, Long.class);
        jdbc.update("""
                insert into document_type_field(document_type_id, field_name, field_type, label)
                values (?, 'number', 'STRING', 'Номер')
                """, typeId);
        jdbc.update("""
                insert into requirement_rule(document_type_id, field_name, description, check_type, kind,
                    severity, source_title, source_checked_at)
                values (?, 'number', 'Укажите номер', 'REQUIRED', 'PRODUCT_RULE', 'BLOCKER',
                    'Правила продукта', current_timestamp)
                """, typeId);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
        storage.clear();
    }

    private void cleanDatabase() {
        String users = "select id from app_user where max_user_id in (920000001, 920000002, 920000003)";
        String organizations = "select id from organization where created_by in (" + users + ")";
        String documents = "select id from document where author_id in (" + users + ")";
        String versions = "select id from document_version where document_id in (" + documents + ")";
        jdbc.execute("delete from approval_step where document_id in (" + documents + ")");
        jdbc.execute("delete from approval_route where org_id in (" + organizations + ")");
        jdbc.execute("delete from document_file where version_id in (" + versions + ")");
        jdbc.execute("delete from document_version where document_id in (" + documents + ")");
        jdbc.execute("delete from document where id in (" + documents + ")");
        jdbc.execute("delete from requirement_rule where document_type_id in "
                + "(select id from document_type where code = 'ISSUE16_ORDER')");
        jdbc.execute("delete from document_type_field where document_type_id in "
                + "(select id from document_type where code = 'ISSUE16_ORDER')");
        jdbc.execute("delete from document_type where code = 'ISSUE16_ORDER'");
        jdbc.execute("delete from member_role where member_id in "
                + "(select id from organization_member where org_id in (" + organizations + "))");
        jdbc.execute("delete from join_request where org_id in (" + organizations + ") or user_id in (" + users + ")");
        jdbc.execute("delete from invite_role where invite_id in "
                + "(select id from invite where org_id in (" + organizations + "))");
        jdbc.execute("delete from invite where org_id in (" + organizations + ")");
        jdbc.execute("delete from organization_member where org_id in (" + organizations + ")");
        jdbc.execute("delete from role where org_id in (" + organizations + ")");
        jdbc.execute("delete from organization where id in (" + organizations + ")");
        jdbc.execute("delete from app_user where id in (" + users + ")");
    }

    @Test
    void rejectsOversizedMetadataAndAcceptsMetaWithoutContentType() throws Exception {
        byte[] pdf = pdf();
        mvc.perform(multipart("/api/v1/documents")
                        .file(textJsonPart("meta", Map.of(
                                "documentTypeId", typeId,
                                "title", "x".repeat(256),
                                "visibility", "PRIVATE",
                                "containsSensitive", false,
                                "fields", Map.of("number", "42"))))
                        .file(new MockMultipartFile("main", "memo.pdf", "application/pdf", pdf))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(multipart("/api/v1/documents")
                        .file(textJsonPart("meta", Map.of(
                                "documentTypeId", typeId,
                                "title", "Приказ",
                                "visibility", "PRIVATE",
                                "containsSensitive", false,
                                "fields", Map.of("number", "x".repeat(10_001)))))
                        .file(new MockMultipartFile("main", "memo.pdf", "application/pdf", pdf))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        createPrivateDocument(pdf);
    }

    /**: исправленное значение попадает в сам DOCX новой версией, а не только в карточку. */
    @Test
    void correctionIsWrittenIntoDocxAsNewVersion() throws Exception {
        JsonNode created = json(mvc.perform(multipart("/api/v1/documents")
                        .file(textJsonPart("meta", Map.of(
                                "documentTypeId", typeId,
                                "title", "Приказ",
                                "visibility", "PRIVATE",
                                "containsSensitive", true,
                                "fields", Map.of("number", "42"))))
                        .file(new MockMultipartFile("main", "order.docx", DocxCorrector.DOCX, docx("Номер: 42")))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permissions.canCorrectFile").value(true))
                .andReturn().getResponse().getContentAsString());
        long documentId = created.path("id").asLong();

        JsonNode corrected = json(mvc.perform(post("/api/v1/documents/{id}/versions/1/corrections", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("fields", Map.of("number", "43"))))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied[0]").value("number"))
                .andExpect(jsonPath("$.card.currentVersionNo").value(2))
                .andExpect(jsonPath("$.card.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString());
        long fileId = corrected.path("card").path("versions").get(1).path("files").get(0).path("id").asLong();
        byte[] file = mvc.perform(get(tokenService.issue(fileId, authorId).url()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String text = new ru.sibvibe.approval.ai.adapter.PdfDocxTextExtractor()
                .extract(new java.io.ByteArrayInputStream(file), DocxCorrector.DOCX).orElseThrow().fullText();
        assertThat(text).isEqualTo("Номер: 43");
        assertThat(corrected.path("card").path("check").path("fields").get(0).path("value").asText()).isEqualTo("43");
    }

    /**
     * Ревью: новая версия с тем же основным файлом (убрали приложение, возвращённый документ без правки
     * файла) переносит поля прежней версии, а не перечитывает файл и не стирает правки автора.
     */
    @Test
    void newVersionWithSameMainFileKeepsFieldsOfPreviousVersion() throws Exception {
        JsonNode created = createPrivateDocument(pdf());
        long documentId = created.path("id").asLong();
        long fileId = created.path("versions").get(0).path("files").get(0).path("id").asLong();

        mvc.perform(multipart("/api/v1/documents/{id}/versions", documentId)
                        .file(textJsonPart("meta", Map.of(
                                "containsSensitive", false,
                                "keepFileIds", new long[]{fileId})))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(2))
                .andExpect(jsonPath("$.check.status").value("CHECKED"))
                .andExpect(jsonPath("$.check.fields[0].value").value("42"))
                .andExpect(jsonPath("$.check.issues").isEmpty());
    }

    /**: без выбранного типа — «определить автоматически»; без модели это «Другой документ». */
    @Test
    void autoTypeWithoutModelFallsBackToOtherDocumentAndTypeCanBeChanged() throws Exception {
        JsonNode created = json(mvc.perform(multipart("/api/v1/documents")
                        .file(textJsonPart("meta", Map.of("visibility", "PRIVATE", "containsSensitive", false)))
                        .file(new MockMultipartFile("main", "Приказ_о_премии.pdf", "application/pdf", pdf()))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Приказ о премии"))
                .andExpect(jsonPath("$.type.name").value("Другой документ"))
                .andExpect(jsonPath("$.type.autoDetected").value(true))
                // Проверка общих реквизитов — только предупреждения: отправлять можно.
                .andExpect(jsonPath("$.permissions.canSubmit").value(true))
                .andExpect(jsonPath("$.permissions.canCorrectFile").value(false))
                .andReturn().getResponse().getContentAsString());
        long documentId = created.path("id").asLong();
        assertThat(created.path("type").path("code").asText()).isEqualTo("GENERIC");

        // Вид, который назвал сам документ, — в списке рядом с названием; признак — код GENERIC, а не is_generic.
        jdbc.update("""
                update document_version set extracted_fields = '{"fields": [{"name": "doc_kind", "value": "Приказ", "source": "MODEL"}]}'::jsonb
                where document_id = ?
                """, documentId);
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recognizedKind.value").value("Приказ"))
                .andExpect(jsonPath("$.items[0].recognizedKind.fromAi").value(true));
        // fields — JSON null: список и поиск не падают
        jdbc.update("update document_version set extracted_fields = '{\"fields\": null}'::jsonb where document_id = ?", documentId);
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("q", "ничего").header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recognizedKind").isEmpty());

        mvc.perform(put("/api/v1/documents/{id}/versions/1/type", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("documentTypeId", typeId)))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type.id").value(typeId))
                .andExpect(jsonPath("$.type.autoDetected").value(false))
                .andExpect(jsonPath("$.check.issues[0].message").value("Укажите номер"));

        // PDF автоматически не исправить — внятная ошибка, а не молчание.
        mvc.perform(post("/api/v1/documents/{id}/versions/1/corrections", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("fields", Map.of("number", "43"))))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     *: документ-форма без единого файла — создать, получить замечание, исправить в форме,
     * после возврата исправить новой версией формы. Модель не участвует.
     */
    @Test
    void formDocumentIsCheckedFixedAndRevisedWithoutFiles() throws Exception {
        JsonNode created = json(mvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "documentTypeId", typeId,
                                "visibility", "PRIVATE",
                                "containsSensitive", false,
                                "content", Map.of("number", ""))))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Приказ"))
                .andExpect(jsonPath("$.versions[0].files").isEmpty())
                .andExpect(jsonPath("$.versions[0].content.number").value(""))
                .andExpect(jsonPath("$.check.status").value("CHECKED"))
                .andExpect(jsonPath("$.check.issues[0].message").value("Укажите номер"))
                .andExpect(jsonPath("$.permissions.canSubmit").value(false))
                .andExpect(jsonPath("$.permissions.canCorrectFile").value(false))
                .andReturn().getResponse().getContentAsString());
        long documentId = created.path("id").asLong();

        mvc.perform(put("/api/v1/documents/{id}/versions/1/fields", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("fields", Map.of("number", "42"))))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues").isEmpty());
        mvc.perform(get("/api/v1/documents/{id}", documentId).header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].content.number").value("42"))
                .andExpect(jsonPath("$.permissions.canSubmit").value(true));

        // Тип формы выбирал автор: сменить его нельзя, у другого типа другая схема полей.
        mvc.perform(put("/api/v1/documents/{id}/versions/1/type", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("documentTypeId", typeId)))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isBadRequest());

        jdbc.update("update document set status = 'RETURNED' where id = ?", documentId);
        mvc.perform(post("/api/v1/documents/{id}/versions", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "containsSensitive", false,
                                "content", Map.of("number", "43"))))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currentVersionNo").value(2))
                .andExpect(jsonPath("$.versions[0].content.number").value("42"))
                .andExpect(jsonPath("$.versions[1].content.number").value("43"))
                .andExpect(jsonPath("$.versions[1].files").isEmpty())
                .andExpect(jsonPath("$.check.issues").isEmpty())
                .andExpect(jsonPath("$.check.fields[0].value").value("43"))
                .andExpect(jsonPath("$.changes.fields[0].before").value("42"))
                .andExpect(jsonPath("$.changes.fields[0].after").value("43"))
                .andExpect(jsonPath("$.changes.contentChanged").value(true));
        assertThat(storage.putCount()).isZero();

        // Документ с файлом формой не заменяется.
        long withFile = createPrivateDocument(pdf()).path("id").asLong();
        mvc.perform(post("/api/v1/documents/{id}/versions", withFile)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "containsSensitive", false,
                                "content", Map.of("number", "43"))))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void foreignCompanyCannotDownloadWithOtherwiseValidToken() throws Exception {
        JsonNode created = createPrivateDocument(pdf());
        long fileId = created.path("versions").get(0).path("files").get(0).path("id").asLong();

        mvc.perform(get(tokenService.issue(fileId, foreignId).url()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void onlyTheAuthorDeletesADraftAndItIsGoneEverywhere() throws Exception {
        long documentId = createPrivateDocument(pdf()).path("id").asLong();

        // Чужой — «не найдено», коллега без прав — тоже не автор
        mvc.perform(delete("/api/v1/documents/{id}", documentId).header("Authorization", FOREIGN_AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/{id}", documentId).header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/documents/{id}", documentId).header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/documents/{id}", documentId).header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("q", Long.toString(documentId))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void sameCompanyReaderCanViewButCannotModifyDocument() throws Exception {
        JsonNode created = createPrivateDocument(pdf());
        long documentId = created.path("id").asLong();
        long fileId = created.path("versions").get(0).path("files").get(0).path("id").asLong();

        mvc.perform(multipart("/api/v1/documents/{id}/versions", documentId)
                        .file(textJsonPart("meta", Map.of(
                                "containsSensitive", false,
                                "fields", Map.of("number", "43"),
                                "keepFileIds", new long[]{fileId})))
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(put("/api/v1/documents/{id}/versions/1/fields", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("fields", Map.of("number", "43"))))
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        jdbc.update("update document set visibility = 'ORG' where id = ?", documentId);

        // «видно всей компании» не открывает черновик — ни карточку, ни файл, ни строку в списке.
        mvc.perform(get("/api/v1/documents/{id}", documentId)
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents").param("tab", "AVAILABLE")
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(tokenService.issue(fileId, colleagueId).url()))
                .andExpect(status().isNotFound());

        // Решение №9: согласующий прошлой версии черновик видит — и карточку, и файл.
        long reviewerRole = jdbc.queryForObject("""
                insert into role(org_id, code, name) values (?, 'PAST_REVIEWER', 'Прошлый согласующий') returning id
                """, Long.class, authorOrgId);
        jdbc.update("""
                insert into approval_step(document_id, version_no, approver_id, role_id, stage_order,
                    origin, decision, created_at, decided_at)
                values (?, 1, ?, ?, 1, 'TEMPLATE', 'APPROVED', current_timestamp, current_timestamp)
                """, documentId, colleagueId, reviewerRole);
        mvc.perform(get("/api/v1/documents/{id}", documentId)
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isOk());
        mvc.perform(get(tokenService.issue(fileId, colleagueId).url()))
                .andExpect(status().isOk());
        jdbc.update("delete from approval_step where document_id = ?", documentId);

        // Отправленный документ компания уже видит.
        jdbc.update("update document set status = 'IN_APPROVAL' where id = ?", documentId);

        mvc.perform(get("/api/v1/documents/{id}", documentId)
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents").param("tab", "AVAILABLE")
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(multipart("/api/v1/documents/{id}/versions", documentId)
                        .file(textJsonPart("meta", Map.of(
                                "containsSensitive", false,
                                "fields", Map.of("number", "43"),
                                "keepFileIds", new long[]{fileId})))
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put("/api/v1/documents/{id}/versions/1/fields", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("fields", Map.of("number", "43"))))
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void documentLifecycleKeepsAclRulesAndSignedDownloadConsistent() throws Exception {
        byte[] pdf = pdf();
        JsonNode created = createPrivateDocument(pdf);

        long documentId = created.path("id").asLong();
        JsonNode file = created.path("versions").get(0).path("files").get(0);
        long fileId = file.path("id").asLong();
        String downloadUrl = file.path("downloadUrl").asText();
        assertThat(file.path("fileName").asText()).isEqualTo("secret.pdf");
        assertThat(file.path("mimeType").asText()).isEqualTo("application/pdf");
        assertThat(created.toString()).doesNotContain("storageKey").doesNotContain("storage_key");

        mvc.perform(get(downloadUrl))
                .andExpect(status().isOk())
                .andExpect(content().bytes(pdf))
                .andExpect(header().string("Content-Type", "application/pdf"));
        mvc.perform(get(downloadUrl + "x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/documents/{id}", documentId)
                        .header("Authorization", FOREIGN_AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/{id}", documentId)
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents")
                        .param("tab", "MINE")
                        .header("Authorization", FOREIGN_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/documents")
                        .param("tab", "MINE")
                        .param("status", "DRAFT")
                        .param("typeId", Long.toString(typeId))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(documentId));
        // Период создания: раньше даты уходили в драйвер как Instant и запрос падал с 500 (ни разу не проверялся).
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"));
        mvc.perform(get("/api/v1/documents").param("tab", "MINE")
                        .param("from", today.minusDays(6).toString()).param("to", today.plusDays(1).toString())
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("from", today.plusDays(2).toString())
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        // Поиск по части названия и по номеру документа; ACL — тот же, что у списка
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("q", "прика")
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("q", Long.toString(documentId))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(documentId));
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("q", "100%_\\")
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        // по автору и по регистрационному номеру из полей
        String authorName = jdbc.queryForObject("select full_name from app_user where id = ?", String.class, authorId);
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("q", authorName.toLowerCase())
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(documentId));
        jdbc.update("""
                update document_version set extracted_fields = '{"fields": [{"name": "reg_number", "value": "СЗ-117", "source": "MODEL"}]}'::jsonb
                where document_id = ?
                """, documentId);
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("q", "сз-117")
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        // По виду документа, в других формах слова и по заголовку из текста: название
        // вида в названии документа не встречается — «приказы» находят его по виду «Приказ», «закупку» — по
        // названию «Закупка…», «инвентаризац» — по заголовку из полей.
        jdbc.update("update document set title = 'Закупка ноутбуков' where id = ?", documentId);
        jdbc.update("""
                update document_version set extracted_fields = '{"fields": [{"name": "subject", "value": "О проведении инвентаризации", "source": "MODEL"}]}'::jsonb
                where document_id = ?
                """, documentId);
        for (String query : new String[]{"приказы", "закупку", "инвентаризац"}) {
            mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("q", query)
                            .header("Authorization", AUTHOR_AUTH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }
        mvc.perform(get("/api/v1/documents").param("tab", "MINE").param("q", "договор")
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        jdbc.update("update document set title = 'Приказ № 42' where id = ?", documentId);

        JsonNode versioned = json(mvc.perform(multipart("/api/v1/documents/{id}/versions", documentId)
                        .file(jsonPart("meta", Map.of(
                                "containsSensitive", true,
                                "fields", Map.of("number", "43"),
                                "keepFileIds", new long[]{fileId})))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andReturn().getResponse().getContentAsString());
        assertThat(versioned.path("versions").get(1).path("files").get(0).path("id").asLong())
                .isNotEqualTo(fileId);
        assertThat(storage.putCount()).isEqualTo(1);

        mvc.perform(put("/api/v1/documents/{id}/versions/2/fields", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("fields", Map.of("number", ""))))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED"))
                .andExpect(jsonPath("$.issues[0].ruleId").isNumber())
                .andExpect(jsonPath("$.issues[0].severity").value("BLOCKER"));
        // Замечания — у каждой версии своим снимком: у прошлой их видно и после новой (история версий).
        JsonNode withIssues = json(mvc.perform(get("/api/v1/documents/{id}", documentId).header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[1].issues[0].severity").value("BLOCKER"))
                .andReturn().getResponse().getContentAsString());
        assertThat(withIssues.path("versions").get(1).path("issues")).isEqualTo(withIssues.path("check").path("issues"));

        long roleId = jdbc.queryForObject("""
                insert into role(org_id, code, name) values (?, 'REVIEWER', 'Проверяющий') returning id
                """, Long.class, authorOrgId);
        jdbc.update("""
                insert into approval_step(document_id, version_no, approver_id, role_id, stage_order,
                    origin, decision, created_at, decided_at)
                values (?, 1, ?, ?, 1, 'TEMPLATE', 'APPROVED', current_timestamp, current_timestamp)
                """, documentId, colleagueId, roleId);
        jdbc.update("""
                insert into approval_step(document_id, version_no, approver_id, role_id, stage_order,
                    origin, decision, created_at, activated_at)
                values (?, 2, ?, ?, 1, 'TEMPLATE', 'PENDING', current_timestamp, current_timestamp)
                """, documentId, colleagueId, roleId);
        jdbc.update("update document set status = 'IN_APPROVAL', current_stage = 1 where id = ?", documentId);

        JsonNode colleagueCard = json(mvc.perform(get("/api/v1/documents/{id}", documentId)
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.history[0].versionNo").value(1))
                .andExpect(jsonPath("$.myActiveStepIds.length()").value(1))
                .andReturn().getResponse().getContentAsString());
        String colleagueDownload = colleagueCard.path("versions").get(1).path("files").get(0)
                .path("downloadUrl").asText();
        mvc.perform(get(colleagueDownload))
                .andExpect(status().isOk())
                .andExpect(content().bytes(pdf));
        mvc.perform(get("/api/v1/documents")
                        .param("tab", "WAITING_ME")
                        .header("Authorization", COLLEAGUE_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].waitingSince").isNotEmpty());
        jdbc.update("update organization_member set status = 'DISABLED' where user_id = ?", colleagueId);
        mvc.perform(get(colleagueDownload))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
                "select count(*) from document_version where document_id = ?", Integer.class, documentId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(distinct storage_key) from document_file", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from document_version where check_status = 'CHECKED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void documentLookupFindsOnlyVisibleDocumentsOfTheCallersOwnCompany() {
        long visible = insertTitledDocument(authorOrgId, authorId, "Закупка ноутбуков для отдела", "ORG");
        insertTitledDocument(authorOrgId, colleagueId, "Закупка мониторов", "PRIVATE");
        long otherCompany = organization("Ещё одна компания", foreignId);
        insertTitledDocument(otherCompany, foreignId, "Закупка ноутбуков в другой компании", "ORG");

        List<DocumentLookup.Match> matches = documentLookup.findByTitleFragment(authorOrgId, authorId, "закупка", 5);

        assertThat(matches).extracting(DocumentLookup.Match::id).containsExactly(visible);
    }

    @Test
    void documentLookupIsCaseInsensitiveAndTreatsPercentAndUnderscoreLiterally() {
        long exact = insertTitledDocument(authorOrgId, authorId, "Скидка 20%_особая", "ORG");
        // Не проэкранированные "%" и "_" в LIKE значили бы "20, что угодно, один любой символ, особая" -
        // и это тоже совпало бы, хотя по смыслу название совсем другое.
        insertTitledDocument(authorOrgId, authorId, "Скидка 20XYособая", "ORG");

        List<DocumentLookup.Match> matches = documentLookup.findByTitleFragment(
                authorOrgId, authorId, "СКИДКА 20%_особая", 5);

        assertThat(matches).extracting(DocumentLookup.Match::id).containsExactly(exact);
    }

    private long insertTitledDocument(long orgId, long author, String title, String visibility) {
        return jdbc.queryForObject("""
                insert into document(document_type_id, author_id, org_id, title, status,
                    current_version_no, visibility, created_at, updated_at)
                values (?, ?, ?, ?, 'DRAFT', 1, ?, ?, ?) returning id
                """, Long.class, typeId, author, orgId, title, visibility,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    @Test
    void metricsUseFirstSubmissionHistoryDateRangeAndCurrentOrganization() throws Exception {
        long ownRole = role(authorOrgId, "METRICS_REVIEWER");
        long foreignRole = role(foreignOrgId, "FOREIGN_METRICS_REVIEWER");

        metricDocument(authorOrgId, authorId, ownRole, "APPROVED",
                "2026-09-01T08:00:00Z", "2026-09-01T10:00:00Z", false);
        metricDocument(authorOrgId, authorId, ownRole, "APPROVED",
                "2026-09-02T08:00:00Z", "2026-09-02T14:00:00Z", true);
        metricDocument(authorOrgId, authorId, ownRole, "REJECTED",
                "2026-09-03T08:00:00Z", "2026-09-03T09:00:00Z", false);
        metricDocument(authorOrgId, authorId, ownRole, "RETURNED",
                "2026-09-04T08:00:00Z", "2026-09-04T09:00:00Z", true);

        // Не должны влиять: черновик своей компании, документ другой компании и
        // отправленный вне выбранного периода документ.
        insertDocument(authorOrgId, authorId, "DRAFT", Instant.parse("2026-09-05T08:00:00Z"),
                Instant.parse("2026-09-05T08:00:00Z"));
        metricDocument(foreignOrgId, foreignId, foreignRole, "RETURNED",
                "2026-09-03T08:00:00Z", "2026-09-03T09:00:00Z", true);
        metricDocument(authorOrgId, authorId, ownRole, "APPROVED",
                "2026-08-31T08:00:00Z", "2026-08-31T18:00:00Z", false);

        JsonNode allSeptember = json(mvc.perform(get("/api/v1/orgs/current/metrics")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-30")
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(allSeptember.path("documentsCount").asLong()).isEqualTo(4);
        assertThat(allSeptember.path("medianApprovalHours").asDouble()).isEqualTo(4.0);
        assertThat(allSeptember.path("returnRate").asDouble()).isEqualTo(0.5);

        JsonNode oneDay = json(mvc.perform(get("/api/v1/orgs/current/metrics")
                        .param("from", "2026-09-02")
                        .param("to", "2026-09-02")
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(oneDay.path("documentsCount").asLong()).isEqualTo(1);
        assertThat(oneDay.path("medianApprovalHours").asDouble()).isEqualTo(6.0);
        assertThat(oneDay.path("returnRate").asDouble()).isEqualTo(1.0);

        mvc.perform(get("/api/v1/orgs/current/metrics")
                        .param("from", "2026-10-01")
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsCount").value(0))
                .andExpect(jsonPath("$.medianApprovalHours").isEmpty())
                .andExpect(jsonPath("$.returnRate").isEmpty());
        mvc.perform(get("/api/v1/orgs/current/metrics")
                        .param("from", "2026-09-02")
                        .param("to", "2026-09-01")
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private JsonNode createPrivateDocument(byte[] pdf) throws Exception {
        return json(mvc.perform(multipart("/api/v1/documents")
                        .file(textJsonPart("meta", Map.of(
                                "documentTypeId", typeId,
                                "title", "Приказ № 42",
                                "visibility", "PRIVATE",
                                "containsSensitive", false,
                                "fields", Map.of("number", "42"))))
                        .file(new MockMultipartFile("main", "..\\secret.pdf", "text/plain", pdf))
                        .header("Authorization", AUTHOR_AUTH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.check.status").value("CHECKED"))
                .andExpect(jsonPath("$.check.modelAvailable").value(false))
                .andExpect(jsonPath("$.check.issues").isEmpty())
                .andExpect(jsonPath("$.permissions.canSubmit").value(true))
                .andReturn().getResponse().getContentAsString());
    }

    private MockMultipartFile jsonPart(String name, Object value) throws Exception {
        return new MockMultipartFile(
                name, "", MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(value));
    }

    private MockMultipartFile textJsonPart(String name, Object value) throws Exception {
        return new MockMultipartFile(name, "", null, objectMapper.writeValueAsBytes(value));
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private long user(long maxUserId, String name) {
        return jdbc.queryForObject("""
                insert into app_user(max_user_id, full_name, created_at)
                values (?, ?, current_timestamp) returning id
                """, Long.class, maxUserId, name);
    }

    private long organization(String name, long creatorId) {
        return jdbc.queryForObject("""
                insert into organization(name, created_by, created_at, is_demo)
                values (?, ?, current_timestamp, false) returning id
                """, Long.class, name, creatorId);
    }

    private long role(long orgId, String code) {
        return jdbc.queryForObject("""
                insert into role(org_id, code, name) values (?, ?, ?) returning id
                """, Long.class, orgId, code, code);
    }

    private long insertDocument(long orgId, long author, String status, Instant createdAt, Instant updatedAt) {
        return jdbc.queryForObject("""
                insert into document(document_type_id, author_id, org_id, title, status,
                    current_version_no, visibility, created_at, updated_at)
                values (?, ?, ?, 'Метрика', ?, 1, 'ORG', ?, ?) returning id
                """, Long.class, typeId, author, orgId, status,
                Timestamp.from(createdAt), Timestamp.from(updatedAt));
    }

    private void metricDocument(
            long orgId,
            long author,
            long roleId,
            String status,
            String submittedAt,
            String updatedAt,
            boolean returned
    ) {
        Instant submitted = Instant.parse(submittedAt);
        Instant updated = Instant.parse(updatedAt);
        long documentId = insertDocument(orgId, author, status, submitted.minusSeconds(3600), updated);
        jdbc.update("""
                insert into document_version(document_id, version_no, contains_sensitive, created_by, created_at)
                values (?, 1, false, ?, ?)
                """, documentId, author, Timestamp.from(submitted.minusSeconds(3600)));
        if (returned) {
            jdbc.update("""
                    insert into approval_step(document_id, version_no, approver_id, role_id, stage_order,
                        origin, decision, created_at, activated_at, decided_at)
                    values (?, 1, ?, ?, 1, 'TEMPLATE', 'RETURNED', ?, ?, ?)
                    """, documentId, author, roleId, Timestamp.from(submitted), Timestamp.from(submitted),
                    Timestamp.from(submitted.plusSeconds(900)));
        }
        if ("APPROVED".equals(status)) {
            Instant approvedSubmission = returned ? submitted.plusSeconds(3600) : submitted;
            if (returned) {
                jdbc.update("""
                        insert into document_version(document_id, version_no, contains_sensitive, created_by, created_at)
                        values (?, 2, false, ?, ?)
                        """, documentId, author, Timestamp.from(approvedSubmission.minusSeconds(60)));
                jdbc.update("update document set current_version_no = 2 where id = ?", documentId);
            }
            jdbc.update("""
                    insert into approval_step(document_id, version_no, approver_id, role_id, stage_order,
                        origin, decision, created_at, activated_at, decided_at)
                    values (?, ?, ?, ?, 1, 'TEMPLATE', 'APPROVED', ?, ?, ?)
                    """, documentId, returned ? 2 : 1, author, roleId,
                    Timestamp.from(approvedSubmission), Timestamp.from(approvedSubmission), Timestamp.from(updated));
        } else if (!returned) {
            jdbc.update("""
                    insert into approval_step(document_id, version_no, approver_id, role_id, stage_order,
                        origin, decision, created_at, activated_at, decided_at)
                    values (?, 1, ?, ?, 1, 'TEMPLATE', 'REJECTED', ?, ?, ?)
                    """, documentId, author, roleId,
                    Timestamp.from(submitted), Timestamp.from(submitted), Timestamp.from(updated));
        }
    }

    private void member(long orgId, long userId) {
        jdbc.update("""
                insert into organization_member(org_id, user_id, status, is_admin, joined_at, is_demo)
                values (?, ?, 'ACTIVE', true, current_timestamp, false)
                """, orgId, userId);
    }

    /** Минимальный DOCX: один абзац текста — валидатор проверяет, что это zip с word/document.xml. */
    private byte[] docx(String paragraph) throws IOException {
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                + ".wordprocessingml.document.main+xml\"/></Types>";
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + "<w:p><w:r><w:t>" + paragraph + "</w:t></w:r></w:p></w:body></w:document>";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            zip.write(contentTypes.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write(document.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private byte[] pdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            byte[] value = output.toByteArray();
            try (PDDocument ignored = Loader.loadPDF(value)) {
                return value;
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {

        @Bean
        @Primary
        InMemoryFileStorage inMemoryFileStorage() {
            return new InMemoryFileStorage();
        }
    }

    static final class InMemoryFileStorage implements FileStorage {

        private final Map<String, Stored> files = new ConcurrentHashMap<>();
        private int putCount;

        @Override
        public String put(InputStream content, String fileName, String contentType, long size) {
            try {
                byte[] bytes = content.readAllBytes();
                String key = UUID.randomUUID().toString();
                files.put(key, new Stored(bytes, fileName, contentType));
                putCount++;
                return key;
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public StoredFile get(String storageKey) {
            Stored stored = files.get(storageKey);
            if (stored == null) {
                throw new StoredFileNotFoundException();
            }
            return new StoredFile(
                    new ByteArrayInputStream(stored.content()),
                    stored.fileName(), stored.contentType(), stored.content().length);
        }

        @Override
        public void delete(String storageKey) {
            files.remove(storageKey);
        }

        int putCount() {
            return putCount;
        }

        void clear() {
            files.clear();
            putCount = 0;
        }

        private record Stored(byte[] content, String fileName, String contentType) {}
    }
}
