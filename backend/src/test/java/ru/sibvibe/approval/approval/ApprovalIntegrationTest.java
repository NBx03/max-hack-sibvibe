package ru.sibvibe.approval.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.sibvibe.approval.approval.service.StuckStepReminderJob;
import ru.sibvibe.approval.storage.FileStorage;
import ru.sibvibe.approval.storage.StoredFileNotFoundException;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Движок маршрутов и состояния документа на настоящем PostgreSQL. Тест идёт по реальным API от
 * создания документа до статуса «Согласован», в собственной схеме {@value #SCHEMA} со свежими миграциями:
 * типы документов и маршруты по умолчанию здесь такие, какими их создают миграция и создание компании.
 *
 * <p>Гонки проверяются детерминированно: отдельная транзакция удерживает блокировку строки документа, обе
 * операции встают в очередь, затем блокировка снимается. Запускается только при заданных
 * {@code SCHEMA_TEST_URL} и {@code SCHEMA_TEST_PASSWORD} (backend/src/test/README.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "SCHEMA_TEST_URL", matches = ".+")
class ApprovalIntegrationTest {

    private static final String SCHEMA = "approval_it";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong MAX_IDS = new AtomicLong(940_000_000L);

    static {
        String url = System.getenv("SCHEMA_TEST_URL");
        if (url != null && !url.isBlank()) {
            try (Connection connection = DriverManager.getConnection(
                    url, "schema_test", System.getenv("SCHEMA_TEST_PASSWORD"));
                 Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + SCHEMA + " cascade");
                statement.execute("create schema " + SCHEMA);
            } catch (SQLException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String url = System.getenv("SCHEMA_TEST_URL");
            return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        });
        registry.add("spring.datasource.username", () -> "schema_test");
        registry.add("spring.datasource.password", () -> System.getenv("SCHEMA_TEST_PASSWORD"));
        registry.add("app.demo-mode", () -> "true");
        registry.add("app.download.secret", () -> "approval-integration-test-secret-32-bytes");
        registry.add("max.bot.token", () -> "approval-test-token");
        // Тестовый токен нужен только для проверки initData - без этого флага бэкенд слал бы
        // настоящие HTTP-запросы на platform-api2.max.ru при каждом уведомлении (BotConfiguration).
        registry.add("max.bot.enabled", () -> "false");
        registry.add("minio.endpoint", () -> "http://127.0.0.1:9000");
        registry.add("minio.access-key", () -> "test-access-key");
        registry.add("minio.secret-key", () -> "test-secret-key");
        registry.add("spring.autoconfigure.exclude",
                () -> "chat.giga.springai.autoconfigure.GigaChatAutoConfiguration");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private InMemoryFileStorage storage;

    @Autowired
    private StuckStepReminderJob reminderJob;

    private Company company;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();
        company = newCompany("Ромашка");
    }

    @AfterEach
    void cleanDatabase() {
        for (String table : List.of("approval_step", "document_file", "document_version", "document",
                "approval_route", "invite_role", "join_request", "invite", "member_role", "organization_member",
                "role", "organization", "app_user")) {
            jdbc.update("delete from " + table);
        }
        storage.clear();
    }

    // ============ предпросмотр ============

    @Test
    void previewShowsHowEveryParticipantIsResolved() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        JsonNode memoPreview = ok(call(company.frank, "GET", "/api/v1/documents/" + memo + "/route-preview", null));
        assertThat(memoPreview.path("problems")).isEmpty();
        JsonNode head = memoPreview.path("stages").get(0).path("participants").get(0);
        assertThat(head.path("role").path("code").asText()).isEqualTo("DEPARTMENT_HEAD");
        assertThat(head.path("resolution").asText()).isEqualTo("AUTO");
        assertThat(head.path("selectedUserId").asLong()).isEqualTo(company.bob.dbId);
        assertThat(head.path("mandatory").asBoolean()).isFalse();
        JsonNode director = memoPreview.path("stages").get(1).path("participants").get(0);
        assertThat(director.path("resolution").asText()).isEqualTo("AUTO");
        assertThat(director.path("selectedUserId").asLong()).isEqualTo(company.alice.dbId);

        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        JsonNode supportPreview = ok(call(company.frank, "GET", "/api/v1/documents/" + support + "/route-preview", null));
        JsonNode parallel = supportPreview.path("stages").get(0).path("participants");
        assertThat(parallel).hasSize(2);
        assertThat(parallel.get(0).path("role").path("code").asText()).isEqualTo("LAWYER");
        assertThat(parallel.get(0).path("resolution").asText()).isEqualTo("SELECT");
        assertThat(parallel.get(0).path("candidates")).hasSize(2);
        assertThat(parallel.get(0).path("selectedUserId").isNull()).isTrue();
        assertThat(parallel.get(1).path("resolution").asText()).isEqualTo("AUTO");

        // автор — единственный директор: обязательный шаг согласован им автоматически
        long vacation = createDocument(company.alice, "VACATION_REQUEST");
        JsonNode selfPreview = ok(call(company.alice, "GET", "/api/v1/documents/" + vacation + "/route-preview", null));
        assertThat(selfPreview.path("stages").get(1).path("participants").get(0).path("resolution").asText())
                .isEqualTo("AUTHOR_HOLDS_ROLE");
        assertThat(selfPreview.path("problems")).isEmpty();

        // автор — единственный руководитель отдела: необязательный участник пропускается
        long bobMemo = createDocument(company.bob, "OFFICIAL_MEMO");
        JsonNode skipped = ok(call(company.bob, "GET", "/api/v1/documents/" + bobMemo + "/route-preview", null));
        assertThat(skipped.path("stages").get(0).path("participants").get(0).path("resolution").asText())
                .isEqualTo("SKIPPED");
    }

    @Test
    void mandatoryRoleWithNoHolderIsReportedByPreviewAndBlocksSubmission() throws Exception {
        jdbc.update("delete from member_role where role_id = (select id from role where code = 'DIRECTOR')");
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");

        JsonNode preview = ok(call(company.frank, "GET", "/api/v1/documents/" + memo + "/route-preview", null));
        assertThat(preview.path("problems")).hasSize(1);
        assertThat(preview.path("problems").get(0).path("code").asText()).isEqualTo("ROUTE_ROLE_EMPTY");
        assertThat(preview.path("problems").get(0).path("message").asText())
                .startsWith("В компании нет сотрудника с ролью «Директор» — попросите администратора");

        Resp submit = call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null);
        assertThat(submit.status()).isEqualTo(409);
        assertThat(submit.code()).isEqualTo("ROUTE_ROLE_EMPTY");
        assertThat(submit.json().path("details").path("role").asText()).isEqualTo("Директор");
        assertThat(status(memo)).isEqualTo("DRAFT");
        assertThat(count("approval_step")).isZero();
    }

    @Test
    void blockingIssuesAndFailedChecksStopSubmissionAndAreShownInPreview() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        jdbc.update("update document_version set check_status = 'CHECKED', validation_issues = "
                + "'[{\"severity\":\"BLOCKER\",\"ruleId\":1}]'::jsonb where document_id = ?", memo);

        JsonNode preview = ok(call(company.frank, "GET", "/api/v1/documents/" + memo + "/route-preview", null));
        assertThat(preview.path("problems")).extracting(node -> node.path("code").asText())
                .containsExactly("BLOCKING_ISSUES");
        assertThat(card(company.frank, memo).path("permissions").path("canSubmit").asBoolean()).isFalse();
        Resp blocked = call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("BLOCKING_ISSUES");

        jdbc.update("update document_version set check_status = 'FAILED', validation_issues = '[]'::jsonb"
                + " where document_id = ?", memo);
        assertThat(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null).code())
                .isEqualTo("BLOCKING_ISSUES");

        jdbc.update("update document_version set check_status = 'CHECKED' where document_id = ?", memo);
        assertThat(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null).status()).isEqualTo(200);
    }

    // ============ последовательные этапы ============

    /** Автор отзывает документ с согласования — шаги пропущены, документ возвращён к нему. */
    @Test
    void authorWithdrawsDocumentFromApproval() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null));
        long bobStep = stepOf(submitted, company.bob);
        assertThat(submitted.path("permissions").path("canWithdraw").asBoolean()).isTrue();

        // отозвать может только автор
        assertThat(call(company.bob, "POST", "/api/v1/documents/" + memo + "/withdraw", null).status()).isEqualTo(403);

        JsonNode withdrawn = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/withdraw", null));
        assertThat(withdrawn.path("status").asText()).isEqualTo("RETURNED");
        assertThat(withdrawn.path("permissions").path("canWithdraw").asBoolean()).isFalse();
        assertThat(withdrawn.path("permissions").path("canUploadVersion").asBoolean()).isTrue();
        assertThat(withdrawn.path("versions").get(0).path("withdrawnAt").isNull()).isFalse();
        assertThat(jdbc.queryForList("select decision from approval_step where document_id = ?", String.class, memo))
                .containsOnly("SKIPPED");

        // решение по отозванному документу уже не принять, повторно не отозвать
        Resp late = decide(company.bob, bobStep, "APPROVE", null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(call(company.frank, "POST", "/api/v1/documents/" + memo + "/withdraw", null).status()).isEqualTo(409);
        assertThat(waitingMe(company.bob)).isZero();
    }

    @Test
    void sequentialStagesActivateOneByOneAndTheDocumentEndsApproved() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");

        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null));
        assertThat(submitted.path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(stageStates(submitted)).containsExactly("ACTIVE", "WAITING");
        long bobStep = stepOf(submitted, company.bob);
        long aliceStep = stepOf(submitted, company.alice);
        assertThat(myActive(card(company.bob, memo))).containsExactly(bobStep);
        assertThat(myActive(card(company.alice, memo))).isEmpty();
        assertThat(waitingMe(company.bob)).isEqualTo(1);
        assertThat(waitingMe(company.alice)).isZero();

        // директор не может решить за руководителя: его этап ещё не начался
        Resp early = decide(company.alice, aliceStep, "APPROVE", null);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.code()).isEqualTo("STEP_NOT_ACTIVE");

        JsonNode afterBob = ok(decide(company.bob, bobStep, "APPROVE", null));
        assertThat(stageStates(afterBob)).containsExactly("DONE", "ACTIVE");
        assertThat(afterBob.path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(myActive(card(company.alice, memo))).containsExactly(aliceStep);
        assertThat(waitingMe(company.alice)).isEqualTo(1);
        assertThat(waitingMe(company.bob)).isZero();
        // двойной клик безопасен: повтор того же решения — 200 без изменений
        String decidedAt = jdbc.queryForObject("select decided_at::text from approval_step where id = ?", String.class, bobStep);
        assertThat(ok(decide(company.bob, bobStep, "APPROVE", null)).path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(jdbc.queryForObject("select decided_at::text from approval_step where id = ?", String.class, bobStep))
                .isEqualTo(decidedAt);

        JsonNode approved = ok(decide(company.alice, aliceStep, "APPROVE", "Согласовано"));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(stageStates(approved)).containsExactly("DONE", "DONE");
        assertThat(jdbc.queryForObject("select current_stage from document where id = ?", Integer.class, memo)).isNull();
        // согласованный документ неизменяем: решение по закрытому шагу другим решением — конфликт
        assertThat(decide(company.alice, aliceStep, "RETURN", "поздно").code()).isEqualTo("STEP_NOT_ACTIVE");
        assertThat(card(company.frank, memo).path("permissions").path("canUploadVersion").asBoolean()).isFalse();
    }

    // ============ четвёртый тип документа ============

    /**
     * Доказательство расширяемости: заявка на командировку добавлена одной миграцией справочника и строкой
     * маршрута по умолчанию — и проходит тот же путь, что и первые три типа, без единого нового класса:
     * тип виден в списке, маршрут из трёх последовательных этапов, решение на каждом.
     */
    @Test
    void businessTripRequestAddedAsDataGoesThroughItsThreeStageRoute() throws Exception {
        assertThat(ok(call(company.frank, "GET", "/api/v1/document-types", null)))
                .extracting(type -> type.path("code").asText()).contains("BUSINESS_TRIP_REQUEST");
        long trip = createDocument(company.frank, "BUSINESS_TRIP_REQUEST");

        JsonNode preview = ok(call(company.frank, "GET", "/api/v1/documents/" + trip + "/route-preview", null));
        assertThat(preview.path("problems")).isEmpty();
        assertThat(preview.path("stages")).extracting(stage -> stage.path("participants").get(0).path("role").path("code").asText())
                .containsExactly("DEPARTMENT_HEAD", "ACCOUNTANT", "DIRECTOR");

        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + trip + "/submit", null));
        assertThat(stageStates(submitted)).containsExactly("ACTIVE", "WAITING", "WAITING");
        long bobStep = stepOf(submitted, company.bob);
        long erinStep = stepOf(submitted, company.erin);
        long aliceStep = stepOf(submitted, company.alice);

        assertThat(stageStates(ok(decide(company.bob, bobStep, "APPROVE", null)))).containsExactly("DONE", "ACTIVE", "WAITING");
        assertThat(stageStates(ok(decide(company.erin, erinStep, "APPROVE", null)))).containsExactly("DONE", "DONE", "ACTIVE");
        JsonNode approved = ok(decide(company.alice, aliceStep, "APPROVE", "Согласовано"));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
    }

    // ============ параллельный этап, выбор, добавленные согласующие ============

    @Test
    void parallelStageNeedsEveryParticipantAndSelectionIsValidated() throws Exception {
        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        String submit = "/api/v1/documents/" + support + "/submit";
        long lawyerRole = company.roles.get("LAWYER");

        Resp none = call(company.frank, "POST", submit, null);
        assertThat(none.status()).isEqualTo(409);
        assertThat(none.code()).isEqualTo("SELECTION_REQUIRED");
        assertThat(call(company.frank, "POST", submit, Map.of("selections",
                List.of(choice(1, lawyerRole, company.bob.dbId)))).status()).as("не кандидат").isEqualTo(404);
        assertThat(call(company.frank, "POST", submit, Map.of("selections",
                List.of(choice(1, lawyerRole, company.erin.dbId)))).status()).as("не тот носитель роли").isEqualTo(404);
        assertThat(count("approval_step")).isZero();

        JsonNode submitted = ok(call(company.frank, "POST", submit,
                Map.of("selections", List.of(choice(1, lawyerRole, company.carol.dbId)))));
        assertThat(stageStates(submitted)).containsExactly("ACTIVE", "WAITING");
        long carolStep = stepOf(submitted, company.carol);
        long erinStep = stepOf(submitted, company.erin);
        long aliceStep = stepOf(submitted, company.alice);
        assertThat(call(company.dave, "GET", "/api/v1/documents/" + support, null).status())
                .as("Дэйв не выбран, шага у него нет, а документ приватный: он его не видит").isEqualTo(404);

        JsonNode afterCarol = ok(decide(company.carol, carolStep, "APPROVE", null));
        assertThat(stageStates(afterCarol)).as("этап закрыт только когда одобрили все").containsExactly("ACTIVE", "WAITING");
        assertThat(myActive(card(company.alice, support))).isEmpty();
        JsonNode afterErin = ok(decide(company.erin, erinStep, "APPROVE", null));
        assertThat(stageStates(afterErin)).containsExactly("DONE", "ACTIVE");
        assertThat(myActive(card(company.alice, support))).containsExactly(aliceStep);
        assertThat(ok(decide(company.alice, aliceStep, "APPROVE", null)).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void addedApproverJoinsTheirRoleAndTheRouteOnlyGrows() throws Exception {
        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        long lawyerRole = company.roles.get("LAWYER");

        // автор не может быть добавлен, а не носитель роли выглядит как «не найден»
        assertThat(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit", Map.of(
                "selections", List.of(choice(1, lawyerRole, company.carol.dbId)),
                "extraApprovers", List.of(choice(1, company.roles.get("ACCOUNTANT"), company.frank.dbId)))).status())
                .isEqualTo(400);
        assertThat(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit", Map.of(
                "selections", List.of(choice(1, lawyerRole, company.carol.dbId)),
                "extraApprovers", List.of(choice(1, lawyerRole, company.erin.dbId)))).status())
                .as("человек без этой роли — «обновите экран», а не «не найдено»").isEqualTo(409);
        assertThat(card(company.frank, support).path("permissions").path("canAddApprover").asBoolean()).isTrue();

        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit", Map.of(
                "selections", List.of(choice(1, lawyerRole, company.carol.dbId)),
                "extraApprovers", List.of(choice(1, lawyerRole, company.dave.dbId)))));

        JsonNode firstStage = submitted.path("route").path("stages").get(0).path("steps");
        assertThat(firstStage).hasSize(3);
        JsonNode extra = null;
        for (JsonNode step : firstStage) {
            if ("ADDED_BY_AUTHOR".equals(step.path("origin").asText())) {
                extra = step;
            }
        }
        assertThat(extra).isNotNull();
        assertThat(extra.path("approver").path("id").asLong()).isEqualTo(company.dave.dbId);
        assertThat(extra.path("role").path("code").asText()).isEqualTo("LAWYER");
        // добавленный видит документ и решает наравне с шаблонными
        assertThat(myActive(card(company.dave, support))).hasSize(1);
    }

    // ============ возврат, повторная отправка, отклонение ============

    @Test
    void returnSkipsTheRestAndTheDocumentIsResubmittedFromTheFirstStageAsANewVersion() throws Exception {
        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        long lawyerRole = company.roles.get("LAWYER");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit",
                Map.of("selections", List.of(choice(1, lawyerRole, company.carol.dbId)))));
        long carolStep = stepOf(submitted, company.carol);
        long erinStep = stepOf(submitted, company.erin);

        Resp noComment = decide(company.carol, carolStep, "RETURN", "   ");
        assertThat(noComment.status()).isEqualTo(400);
        assertThat(noComment.code()).isEqualTo("COMMENT_REQUIRED");

        JsonNode returned = ok(decide(company.carol, carolStep, "RETURN", "Уточните сумму"));
        assertThat(returned.path("status").asText()).isEqualTo("RETURNED");
        assertThat(decisions(returned)).containsExactly("RETURNED", "SKIPPED", "SKIPPED");
        assertThat(jdbc.queryForObject("select current_stage from document where id = ?", Integer.class, support)).isNull();
        Resp late = decide(company.erin, erinStep, "APPROVE", null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.code()).isEqualTo("STEP_NOT_ACTIVE");
        assertThat(late.json().path("message").asText()).isEqualTo("Документ уже возвращён");
        assertThat(card(company.frank, support).path("permissions").path("canUploadVersion").asBoolean()).isTrue();

        // исправление — новая версия: документ снова черновик, старые шаги остаются историей
        long fileId = returned.path("versions").get(0).path("files").get(0).path("id").asLong();
        JsonNode draft = ok(newVersion(company.frank, support, fileId, "SUPPORT_MEASURE_REQUEST"));
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("currentVersionNo").asInt()).isEqualTo(2);
        assertThat(draft.path("route").path("history")).hasSize(3);

        JsonNode resubmitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit",
                Map.of("selections", List.of(choice(1, lawyerRole, company.dave.dbId)))));
        assertThat(resubmitted.path("route").path("versionNo").asInt()).isEqualTo(2);
        assertThat(stageStates(resubmitted)).as("новая версия начинается с первого этапа").containsExactly("ACTIVE", "WAITING");
        assertThat(resubmitted.path("route").path("history")).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from approval_step where version_no = 1 and decision = 'PENDING'",
                Integer.class)).as("прежние ожидающие шаги остались SKIPPED, а не ожили").isZero();

        long daveStep = stepOf(resubmitted, company.dave);
        long erinStep2 = stepOf(resubmitted, company.erin);
        long aliceStep2 = stepOf(resubmitted, company.alice);
        ok(decide(company.dave, daveStep, "APPROVE", null));
        ok(decide(company.erin, erinStep2, "APPROVE", null));
        assertThat(ok(decide(company.alice, aliceStep2, "APPROVE", null)).path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void authorBuildsTheRouteWithAnEndorserAndEveryScreenShowsTheSameStatus() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        long head = company.roles.get("DEPARTMENT_HEAD");
        long lawyer = company.roles.get("LAWYER");
        long director = company.roles.get("DIRECTOR");
        String submit = "/api/v1/documents/" + memo + "/submit";

        // Обязательного директора убрать нельзя, человека дважды поставить нельзя
        Resp withoutDirector = call(company.frank, "POST", submit,
                Map.of("stages", List.of(stage(person(company.bob, head)))));
        assertThat(withoutDirector.status()).isEqualTo(400);
        assertThat(withoutDirector.code()).isEqualTo("MANDATORY_ROLE_MISSING");
        Resp twice = call(company.frank, "POST", submit, Map.of(
                "stages", List.of(stage(person(company.carol, lawyer))),
                "endorser", person(company.carol, lawyer)));
        assertThat(twice.status()).isEqualTo(400);

        // юрист первым, руководитель вторым, директор утверждает — порядок шаблона автор поменял
        JsonNode submitted = ok(call(company.frank, "POST", submit, Map.of(
                "stages", List.of(stage(person(company.carol, lawyer)), stage(person(company.bob, head))),
                "endorser", person(company.alice, director))));
        assertThat(submitted.path("displayStatus").asText()).isEqualTo("IN_APPROVAL");
        assertThat(stageStates(submitted)).containsExactly("ACTIVE", "WAITING", "WAITING");

        ok(decide(company.carol, stepOf(submitted, company.carol), "APPROVE", null));
        JsonNode atEndorsement = ok(decide(company.bob, stepOf(submitted, company.bob), "APPROVE", null));
        assertThat(atEndorsement.path("status").asText()).isEqualTo("IN_APPROVAL");
        assertThat(atEndorsement.path("displayStatus").asText()).isEqualTo("IN_ENDORSEMENT");
        JsonNode endorseStep = atEndorsement.path("route").path("stages").get(2).path("steps").get(0);
        assertThat(endorseStep.path("kind").asText()).isEqualTo("ENDORSEMENT");
        // список и фильтр видят тот же статус, что карточка
        JsonNode list = ok(call(company.frank, "GET", "/api/v1/documents?tab=MINE&status=IN_ENDORSEMENT", null));
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("displayStatus").asText()).isEqualTo("IN_ENDORSEMENT");

        JsonNode endorsed = ok(decide(company.alice, endorseStep.path("id").asLong(), "APPROVE", null));
        assertThat(endorsed.path("status").asText()).isEqualTo("APPROVED");
        assertThat(endorsed.path("displayStatus").asText()).isEqualTo("ENDORSED");
    }

    @Test
    void afterAReturnThePreviewOffersThePreviousRoute() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        long lawyer = company.roles.get("LAWYER");
        long director = company.roles.get("DIRECTOR");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", Map.of(
                "stages", List.of(stage(person(company.carol, lawyer))),
                "endorser", person(company.alice, director))));

        JsonNode returned = ok(decide(company.carol, stepOf(submitted, company.carol), "RETURN", "Уточните сумму"));
        long fileId = returned.path("versions").get(0).path("files").get(0).path("id").asLong();
        ok(newVersion(company.frank, memo, fileId, "OFFICIAL_MEMO"));

        // Снова черновик, но уже отправлялся — у него история, удалить нельзя
        assertThat(call(company.frank, "DELETE", "/api/v1/documents/" + memo, null).status()).isEqualTo(409);

        // Конструктор новой версии заполняется маршрутом прошлой, а не шаблоном
        JsonNode previous = ok(call(company.frank, "GET", "/api/v1/documents/" + memo + "/route-preview", null)).path("previous");
        assertThat(previous.path("versionNo").asInt()).isEqualTo(1);
        assertThat(previous.path("stages")).hasSize(1);
        assertThat(previous.path("stages").get(0).path("participants").get(0).path("userId").asLong()).isEqualTo(company.carol.dbId);
        assertThat(previous.path("endorser").path("userId").asLong()).isEqualTo(company.alice.dbId);
    }

    @Test
    void adminDecidesWhichRolesAreMandatoryAndTheAuthorCannotDropThem() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        long memoType = jdbc.queryForObject("select document_type_id from document where id = ?", Long.class, memo);
        long head = company.roles.get("DEPARTMENT_HEAD");
        long director = company.roles.get("DIRECTOR");
        String toggle = "/api/v1/orgs/current/routes/" + memoType + "/roles/" + head;

        // Только администратор
        assertThat(call(company.frank, "PATCH", toggle, Map.of("mandatory", true)).status()).isEqualTo(403);
        JsonNode routes = ok(call(company.alice, "PATCH", toggle, Map.of("mandatory", true)));
        // именно у руководителя отдела: у директора mandatory=true было и раньше
        boolean headMandatory = false;
        for (JsonNode route : routes) {
            if (route.path("documentTypeId").asLong() != memoType) continue;
            for (JsonNode stage : route.path("stages")) {
                for (JsonNode participant : stage.path("participants")) {
                    if (participant.path("role").path("id").asLong() == head) {
                        headMandatory = participant.path("mandatory").asBoolean();
                    }
                }
            }
        }
        assertThat(headMandatory).isTrue();
        assertThat(call(company.alice, "PATCH", "/api/v1/orgs/current/routes/" + memoType + "/roles/" + company.roles.get("LAWYER"),
                Map.of("mandatory", true)).status()).as("роли нет в шаблоне вида").isEqualTo(404);
        long genericType = jdbc.queryForObject("select id from document_type where code = 'GENERIC'", Long.class);
        // «Другой документ» — как любой вид: по умолчанию обязательных нет, но компания может отметить
        assertThat(call(company.alice, "PATCH", "/api/v1/orgs/current/routes/" + genericType + "/roles/" + director,
                Map.of("mandatory", true)).status()).isEqualTo(200);
        assertThat(call(company.alice, "PATCH", "/api/v1/orgs/current/routes/" + genericType + "/roles/" + director,
                Map.of("mandatory", false)).status()).isEqualTo(200);

        // руководитель стал обязательным — без него отправить нельзя; директор больше не обязателен — без него можно
        Resp withoutHead = call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit",
                Map.of("stages", List.of(stage(person(company.alice, director)))));
        assertThat(withoutHead.code()).isEqualTo("MANDATORY_ROLE_MISSING");
        ok(call(company.alice, "PATCH", "/api/v1/orgs/current/routes/" + memoType + "/roles/" + director, Map.of("mandatory", false)));
        ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit",
                Map.of("stages", List.of(stage(person(company.bob, head))))));
    }

    @Test
    void adminEditsTheCompositionOfTheRouteTemplate() throws Exception {
        long memoType = jdbc.queryForObject("select id from document_type where code = 'OFFICIAL_MEMO'", Long.class);
        long lawyer = company.roles.get("LAWYER");
        long accountant = company.roles.get("ACCOUNTANT");
        long director = company.roles.get("DIRECTOR");
        long head = company.roles.get("DEPARTMENT_HEAD");
        String url = "/api/v1/orgs/current/routes/" + memoType;

        // Только администратор
        assertThat(call(company.frank, "PUT", url, Map.of("stages", List.of())).status()).isEqualTo(403);

        // «Другой документ» редактируется наравне с остальными
        long genericType = jdbc.queryForObject("select id from document_type where code = 'GENERIC'", Long.class);
        assertThat(call(company.alice, "PUT", "/api/v1/orgs/current/routes/" + genericType, Map.of("stages", List.of(
                Map.of("participants", List.of(Map.of("roleId", director, "mandatory", false)))))).status()).isEqualTo(200);

        // пустой этап отвергается
        assertThat(call(company.alice, "PUT", url,
                Map.of("stages", List.of(Map.of("participants", List.of())))).status()).isEqualTo(400);

        // одна и та же роль дважды отвергается
        assertThat(call(company.alice, "PUT", url, Map.of("stages", List.of(
                Map.of("participants", List.of(Map.of("roleId", director, "mandatory", true))),
                Map.of("participants", List.of(Map.of("roleId", director, "mandatory", false))))))
                .status()).isEqualTo(400);

        // роль другой компании — 404
        long foreignRoleId = newCompany("Вторая").roles.get("DIRECTOR");
        assertThat(call(company.alice, "PUT", url, Map.of("stages", List.of(
                Map.of("participants", List.of(Map.of("roleId", foreignRoleId, "mandatory", false))))))
                .status()).isEqualTo(404);

        // администратор меняет состав: раньше «Руководитель отдела (необязательно) → Директор (обязательно)»,
        // теперь «Бухгалтер (обязательно) → Юрист и Директор параллельно (оба не обязательны)» — руководитель
        // убран целиком, чего автор в своём конструкторе сделать не может (отличается от  именно этим)
        JsonNode routes = ok(call(company.alice, "PUT", url, Map.of("stages", List.of(
                Map.of("participants", List.of(Map.of("roleId", accountant, "mandatory", true))),
                Map.of("participants", List.of(
                        Map.of("roleId", lawyer, "mandatory", false),
                        Map.of("roleId", director, "mandatory", false)))))));
        JsonNode memoRoute = null;
        for (JsonNode route : routes) {
            if (route.path("documentTypeId").asLong() == memoType) memoRoute = route;
        }
        assertThat(memoRoute).isNotNull();
        assertThat(memoRoute.path("stages")).hasSize(2);
        assertThat(memoRoute.path("stages").get(0).path("participants").get(0).path("role").path("id").asLong())
                .isEqualTo(accountant);
        assertThat(memoRoute.path("stages").get(0).path("participants").get(0).path("mandatory").asBoolean()).isTrue();
        assertThat(memoRoute.path("stages").get(1).path("participants")).hasSize(2);

        // новый документ этого вида предзаполняется уже изменённым шаблоном, руководителя отдела в нём больше нет
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        JsonNode preview = ok(call(company.frank, "GET", "/api/v1/documents/" + memo + "/route-preview", null));
        assertThat(preview.path("stages")).hasSize(2);
        for (JsonNode stage : preview.path("stages")) {
            for (JsonNode participant : stage.path("participants")) {
                assertThat(participant.path("role").path("id").asLong()).isNotEqualTo(head);
            }
        }

        // пустой шаблон отвергается: без строк он неотличим от «ещё не создан» и был бы молча
        // восстановлен по умолчанию на следующем же обращении — «Сохранить» выглядело бы так, будто ничего не изменилось
        assertThat(call(company.alice, "PUT", url, Map.of("stages", List.of())).status()).isEqualTo(400);
        JsonNode stillThere = ok(call(company.alice, "GET", "/api/v1/orgs/current/routes", null));
        for (JsonNode route : stillThere) {
            if (route.path("documentTypeId").asLong() == memoType) {
                assertThat(route.path("stages")).hasSize(2);
            }
        }
    }

    @Test
    void returningMemberComesBackWithTheirPreviousRolesPreselected() throws Exception {
        String code = ok(call(company.alice, "GET", "/api/v1/orgs/current/invite-code", null)).path("code").asText();
        ok(call(company.alice, "POST", "/api/v1/orgs/current/members/" + memberId(company.erin) + "/disable", null));

        ok(call(company.erin, "POST", "/api/v1/join-requests", Map.of("code", code)), 201);

        // Заявка бывшего участника — с его прежними ролями, администратору остаётся «Принять»
        JsonNode requests = ok(call(company.alice, "GET", "/api/v1/orgs/current/join-requests?status=PENDING", null));
        JsonNode erinRequest = null;
        for (JsonNode request : requests) {
            if (request.path("user").path("id").asLong() == company.erin.dbId) {
                erinRequest = request;
            }
        }
        assertThat(erinRequest).isNotNull();
        assertThat(erinRequest.path("previousRoles").get(0).path("code").asText()).isEqualTo("ACCOUNTANT");
    }

    @Test
    void rejectionIsFinal() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null));
        long bobStep = stepOf(submitted, company.bob);
        long aliceStep = stepOf(submitted, company.alice);

        JsonNode rejected = ok(decide(company.bob, bobStep, "REJECT", "Не по регламенту"));

        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(decisions(rejected)).containsExactly("REJECTED", "SKIPPED");
        assertThat(decide(company.alice, aliceStep, "APPROVE", null).code()).isEqualTo("STEP_NOT_ACTIVE");
        assertThat(card(company.frank, memo).path("permissions").path("canUploadVersion").asBoolean()).isFalse();
        assertThat(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null).code()).isEqualTo("INVALID_STATE");
    }

    // ============ маленькая компания ============

    @Test
    void documentOfAOnePersonCompanyIsApprovedAtOnceWithAVisibleAutoApproval() throws Exception {
        Person solo = newUser();
        ok(call(solo, "POST", "/api/v1/orgs", Map.of("name", "Одиночка")), 201);
        long memo = createDocument(solo, "OFFICIAL_MEMO");

        JsonNode approved = ok(call(solo, "POST", "/api/v1/documents/" + memo + "/submit", null));

        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        JsonNode step = approved.path("route").path("stages").get(0).path("steps").get(0);
        assertThat(step.path("autoReason").asText()).isEqualTo("AUTHOR_HOLDS_ROLE");
        assertThat(step.path("decision").asText()).isEqualTo("APPROVED");
        assertThat(step.path("approver").path("id").asLong()).isEqualTo(solo.dbId);
        assertThat(step.path("decidedAt").isNull()).isFalse();
    }

    // ============ права и идентификаторы ============

    @Test
    void submissionAndDecisionsAreScopedToTheirOwners() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        String submit = "/api/v1/documents/" + memo + "/submit";
        String preview = "/api/v1/documents/" + memo + "/route-preview";

        // до отправки чужой документ не виден: 404, а не 403
        assertThat(call(company.bob, "POST", submit, null).status()).isEqualTo(404);
        assertThat(call(company.bob, "GET", preview, null).status()).isEqualTo(404);
        assertThat(call(company.carol, "POST", "/api/v1/documents/999999/submit", null).status()).isEqualTo(404);

        JsonNode submitted = ok(call(company.frank, "POST", submit, null));
        long bobStep = stepOf(submitted, company.bob);
        // после отправки Боб видит документ, но не автор: 403
        assertThat(call(company.bob, "POST", submit, null).status()).isEqualTo(403);
        assertThat(call(company.bob, "GET", preview, null).status()).isEqualTo(403);
        // повторная отправка автором: документ уже не черновик
        assertThat(call(company.frank, "POST", submit, null).status()).isEqualTo(409);

        // чужой шаг выглядит несуществующим; человеку без компании отвечают как везде в документах: нужна компания
        assertThat(decide(company.erin, bobStep, "APPROVE", null).status()).isEqualTo(404);
        assertThat(decide(company.frank, bobStep, "APPROVE", null).status()).isEqualTo(404);
        Person outsider = newUser();
        assertThat(decide(outsider, bobStep, "APPROVE", null).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select decision from approval_step where id = ?", String.class, bobStep))
                .isEqualTo("PENDING");
    }

    @Test
    void removingAnAwaitedApproverReturnsTheDocumentAndAnUnchangedResendKeepsEarlierApprovals() throws Exception {
        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        long lawyerRole = company.roles.get("LAWYER");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit",
                Map.of("selections", List.of(choice(1, lawyerRole, company.carol.dbId)))));
        // бухгалтер Эрин одобрил, юрист Карол ещё не решила
        ok(decide(company.erin, stepOf(submitted, company.erin), "APPROVE", null));

        Resp removal = call(company.alice, "PUT", "/api/v1/orgs/current/members/" + memberId(company.carol) + "/roles",
                Map.of("roleIds", List.of()));
        assertThat(removal.status()).as(removal.body()).isEqualTo(200);

        JsonNode returned = card(company.frank, support);
        assertThat(returned.path("status").asText()).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject("select auto_reason from approval_step where document_id = ? and approver_id = ?",
                String.class, support, company.carol.dbId)).isEqualTo("MEMBER_REMOVED");

        // автор отправляет заново, ничего не меняя: одобрение Эрин переносится, юриста подбирает маршрут
        long fileId = returned.path("versions").get(0).path("files").get(0).path("id").asLong();
        JsonNode draft = ok(newVersion(company.frank, support, fileId, "SUPPORT_MEASURE_REQUEST"));
        assertThat(draft.path("changes").path("comparedToVersionNo").asInt()).isEqualTo(1);
        assertThat(draft.path("changes").path("files")).isEmpty();
        assertThat(draft.path("changes").path("fields")).isEmpty();
        JsonNode preview = ok(call(company.frank, "GET", "/api/v1/documents/" + support + "/route-preview", null));
        assertThat(preview.path("carryOver").path("fromVersionNo").asInt()).isEqualTo(1);
        assertThat(preview.path("carryOver").path("approvals")).hasSize(1);

        JsonNode resubmitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit", null));
        assertThat(jdbc.queryForObject("select decision || '/' || auto_reason from approval_step"
                + " where document_id = ? and version_no = 2 and approver_id = ?",
                String.class, support, company.erin.dbId)).isEqualTo("APPROVED/CARRIED_OVER");
        assertThat(stageStates(resubmitted)).containsExactly("ACTIVE", "WAITING");
        assertThat(myActive(card(company.dave, support))).as("юрист теперь Дейв — решает он").hasSize(1);
        assertThat(myActive(card(company.erin, support))).as("Эрин повторно не спрашивают").isEmpty();
    }

    @Test
    void changedVersionIsApprovedByEveryoneAgainAndTheCardShowsWhatChanged() throws Exception {
        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        long lawyerRole = company.roles.get("LAWYER");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit",
                Map.of("selections", List.of(choice(1, lawyerRole, company.carol.dbId)))));
        ok(decide(company.erin, stepOf(submitted, company.erin), "APPROVE", null));
        JsonNode returned = ok(decide(company.carol, stepOf(submitted, company.carol), "RETURN", "Уточните ИНН"));

        long fileId = returned.path("versions").get(0).path("files").get(0).path("id").asLong();
        ok(newVersion(company.frank, support, fileId, "SUPPORT_MEASURE_REQUEST"));
        ok(call(company.frank, "PUT", "/api/v1/documents/" + support + "/versions/2/fields",
                Map.of("fields", Map.of("applicant_inn", "7707083893"))));
        JsonNode draft = card(company.frank, support);
        JsonNode changes = draft.path("changes");
        assertThat(changes.path("fields")).hasSize(1);
        assertThat(changes.path("fields").get(0).path("name").asText()).isEqualTo("applicant_inn");
        assertThat(changes.path("fields").get(0).path("before").asText()).isEqualTo("1234567890");
        assertThat(changes.path("fields").get(0).path("after").asText()).isEqualTo("7707083893");
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");

        JsonNode resubmitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit",
                Map.of("selections", List.of(choice(1, lawyerRole, company.carol.dbId)))));
        assertThat(myActive(card(company.erin, resubmitted.path("id").asLong())))
                .as("документ изменился — Эрин согласует заново").hasSize(1);
    }

    // ============ правила проверки компании ============

    @Test
    void administratorAdaptsARuleAndTheAuthorsDraftUsesItAfterRecheck() throws Exception {
        long signerPosition = ruleId("OFFICIAL_MEMO", "signer_position", "REQUIRED");
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        assertThat(issue(card(company.frank, memo), "signer_position").path("severity").asText()).isEqualTo("WARNING");

        // смотреть может любой участник, менять — только администратор
        JsonNode rules = ok(call(company.frank, "GET", "/api/v1/orgs/current/rules", null));
        assertThat(rules.path("canEdit").asBoolean()).isFalse();
        assertThat(rules.path("types").get(0).path("code").asText()).isEqualTo("OFFICIAL_MEMO");
        assertThat(rule(rules, signerPosition).path("sourceTitle").asText()).startsWith("Типовые правила оформления");
        Map<String, Object> setting = new HashMap<>();
        setting.put("enabled", true);
        setting.put("severity", "BLOCKER");
        setting.put("description", "Укажите должность — так требует наша инструкция.");
        setting.put("sourceTitle", "Инструкция по делопроизводству ООО «Ромашка»");
        setting.put("sourceRef", "п. 4.2");
        Resp notAdmin = call(company.frank, "PUT", "/api/v1/orgs/current/rules/" + signerPosition, setting);
        assertThat(notAdmin.status()).isEqualTo(403);
        assertThat(notAdmin.code()).isEqualTo("NOT_ADMIN");

        JsonNode changed = rule(ok(call(company.alice, "PUT", "/api/v1/orgs/current/rules/" + signerPosition, setting)),
                signerPosition);
        assertThat(changed.path("customized").asBoolean()).isTrue();
        assertThat(changed.path("sourceUrl").isNull()).isTrue();

        // уже выданные замечания — снимок: без «Проверить заново» черновик не меняется
        assertThat(issue(card(company.frank, memo), "signer_position").path("severity").asText()).isEqualTo("WARNING");
        JsonNode rechecked = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/versions/1/recheck", null));
        JsonNode adapted = issue(rechecked, "signer_position");
        assertThat(adapted.path("severity").asText()).isEqualTo("BLOCKER");
        assertThat(adapted.path("message").asText()).isEqualTo("Укажите должность — так требует наша инструкция.");
        assertThat(adapted.path("sourceTitle").asText()).isEqualTo("Инструкция по делопроизводству ООО «Ромашка»");
        assertThat(adapted.path("sourceRef").asText()).isEqualTo("п. 4.2");
        assertThat(rechecked.path("permissions").path("canSubmit").asBoolean()).isFalse();

        // выключенное правило не проверяется
        setting.put("enabled", false);
        ok(call(company.alice, "PUT", "/api/v1/orgs/current/rules/" + signerPosition, setting));
        JsonNode withoutRule = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/versions/1/recheck", null));
        assertThat(issue(withoutRule, "signer_position").isMissingNode()).isTrue();
        assertThat(withoutRule.path("permissions").path("canSubmit").asBoolean()).isTrue();

        // у другой компании всё по-прежнему
        Company other = newCompany("Лютик");
        assertThat(rule(ok(call(other.frank, "GET", "/api/v1/orgs/current/rules", null)), signerPosition)
                .path("customized").asBoolean()).isFalse();

        // «Сбросить к типовому»
        JsonNode reset = rule(ok(call(company.alice, "DELETE", "/api/v1/orgs/current/rules/" + signerPosition, null)),
                signerPosition);
        assertThat(reset.path("customized").asBoolean()).isFalse();
        assertThat(reset.path("enabled").asBoolean()).isTrue();
        assertThat(reset.path("severity").asText()).isEqualTo("WARNING");
    }

    /** Ревью  и 5: RETURNED — новой версией, IN_APPROVAL — нельзя, чужой компании документ не виден. */
    @Test
    void recheckWorksForDraftAndReturnedOnlyAndOnlyForTheAuthor() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        String recheck = "/api/v1/documents/" + memo + "/versions/1/recheck";
        String addressee = issueFreeValue(card(company.frank, memo), "addressee");

        Company other = newCompany("Одуванчик");
        assertThat(call(other.alice, "POST", recheck, null).status()).as("администратор чужой компании").isEqualTo(404);
        assertThat(call(company.alice, "POST", recheck, null).status()).as("не автор, документ не виден").isEqualTo(404);

        jdbc.update("update document set status = 'IN_APPROVAL' where id = ?", memo);
        Resp inApproval = call(company.frank, "POST", recheck, null);
        assertThat(inApproval.status()).isEqualTo(409);
        assertThat(inApproval.code()).isEqualTo("INVALID_STATE");

        jdbc.update("update document set status = 'RETURNED' where id = ?", memo);
        JsonNode returned = ok(call(company.frank, "POST", recheck, null));
        assertThat(returned.path("status").asText()).isEqualTo("DRAFT");
        assertThat(returned.path("currentVersionNo").asInt()).isEqualTo(2);
        assertThat(returned.path("versions")).hasSize(2);
        assertThat(returned.path("versions").get(1).path("files").get(0).path("fileName").asText())
                .isEqualTo(returned.path("versions").get(0).path("files").get(0).path("fileName").asText());
        assertThat(issueFreeValue(returned, "addressee")).as("поля не потерялись").isEqualTo(addressee);

        // повторная перепроверка черновика тоже ничего не теряет
        JsonNode again = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/versions/2/recheck", null));
        assertThat(again.path("currentVersionNo").asInt()).isEqualTo(2);
        assertThat(issueFreeValue(again, "addressee")).isEqualTo(addressee);
    }

    private static String issueFreeValue(JsonNode card, String fieldName) {
        for (JsonNode field : card.path("check").path("fields")) {
            if (fieldName.equals(field.path("name").asText())) {
                return field.path("value").asText();
            }
        }
        throw new AssertionError("нет поля " + fieldName);
    }

    @Test
    void lawCannotBeChangedAndOnlySimpleExpectedValuesAreEditable() throws Exception {
        long smbCategory = ruleId("SUPPORT_MEASURE_REQUEST", "smb_category", "ONE_OF");
        JsonNode law = rule(ok(call(company.alice, "GET", "/api/v1/orgs/current/rules", null)), smbCategory);
        assertThat(law.path("locked").asBoolean()).isTrue();
        Map<String, Object> weaken = Map.of("enabled", false, "severity", "INFO",
                "description", law.path("description").asText());
        Resp refused = call(company.alice, "PUT", "/api/v1/orgs/current/rules/" + smbCategory, weaken);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");

        long vacationType = ruleId("VACATION_REQUEST", "vacation_type", "ONE_OF");
        JsonNode vacation = rule(ok(call(company.alice, "GET", "/api/v1/orgs/current/rules", null)), vacationType);
        Map<String, Object> wider = new HashMap<>();
        wider.put("enabled", true);
        wider.put("severity", vacation.path("severity").asText());
        wider.put("description", vacation.path("description").asText());
        wider.put("allowedValues", List.of("ежегодный оплачиваемый", "без сохранения заработной платы", "учебный"));
        JsonNode saved = rule(ok(call(company.alice, "PUT", "/api/v1/orgs/current/rules/" + vacationType, wider)),
                vacationType);
        assertThat(saved.path("allowedValues")).hasSize(3);
        assertThat(saved.path("customized").asBoolean()).isTrue();

        // у шаблона формата ожидаемое значение не настраивается: regex через интерфейс не открываем
        long regNumber = ruleId("OFFICIAL_MEMO", "reg_number", "MATCHES_PATTERN");
        JsonNode pattern = rule(ok(call(company.alice, "GET", "/api/v1/orgs/current/rules", null)), regNumber);
        Map<String, Object> withLength = new HashMap<>();
        withLength.put("enabled", true);
        withLength.put("severity", pattern.path("severity").asText());
        withLength.put("description", pattern.path("description").asText());
        withLength.put("minLength", 5);
        assertThat(call(company.alice, "PUT", "/api/v1/orgs/current/rules/" + regNumber, withLength).status())
                .isEqualTo(400);
    }

    @Test
    void demoCompanyShowsItsOwnLocalActsWhileTheTemplateStaysNeutral() throws Exception {
        Person reviewer = newUser();
        JsonNode sandbox = ok(call(reviewer, "POST", "/api/v1/demo/sandbox", null));
        long demoAuthor = demoUser(sandbox, "DEPARTMENT_HEAD");
        long regNumber = ruleId("OFFICIAL_MEMO", "reg_number", "MATCHES_PATTERN");

        JsonNode demo = rule(ok(callAs(reviewer, demoAuthor, "GET", "/api/v1/orgs/current/rules", null)), regNumber);
        assertThat(demo.path("customized").asBoolean()).isTrue();
        assertThat(demo.path("sourceTitle").asText()).startsWith("Инструкция по делопроизводству Демо-компании");
        assertThat(demo.path("sourceRef").asText()).isEqualTo("п. 2.5");
        assertThat(rule(ok(call(company.alice, "GET", "/api/v1/orgs/current/rules", null)), regNumber)
                .path("sourceTitle").asText()).startsWith("Типовые правила оформления");

        // четвёртый тип — тем же способом: своё положение о командировках у демо-компании
        long tripDestination = ruleId("BUSINESS_TRIP_REQUEST", "destination", "REQUIRED");
        JsonNode trip = rule(ok(callAs(reviewer, demoAuthor, "GET", "/api/v1/orgs/current/rules", null)), tripDestination);
        assertThat(trip.path("sourceTitle").asText()).startsWith("Положение о служебных командировках Демо-компании");
        assertThat(trip.path("sourceRef").asText()).isEqualTo("п. 2.2");
    }

    private long ruleId(String typeCode, String field, String check) {
        return jdbc.queryForObject("select rule.id from requirement_rule rule"
                + " join document_type type on type.id = rule.document_type_id"
                + " where type.code = ? and rule.field_name = ? and rule.check_type = ?",
                Long.class, typeCode, field, check);
    }

    private static JsonNode rule(JsonNode rules, long ruleId) {
        for (JsonNode type : rules.path("types")) {
            for (JsonNode rule : type.path("rules")) {
                if (rule.path("id").asLong() == ruleId) {
                    return rule;
                }
            }
        }
        throw new AssertionError("Нет правила " + ruleId);
    }

    private static JsonNode issue(JsonNode card, String field) {
        for (JsonNode issue : card.path("check").path("issues")) {
            if (field.equals(issue.path("fieldName").asText())) {
                return issue;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    // ============ демо-режим: проверяющий один проходит всю цепочку ============

    @Test
    void returnedDemoDocumentWithoutFilesCanBeResentUnchanged() throws Exception {
        Person reviewer = newUser();
        JsonNode sandbox = ok(call(reviewer, "POST", "/api/v1/demo/sandbox", null));
        long demoAuthor = demoUser(sandbox, "DEPARTMENT_HEAD");
        long returned = jdbc.queryForObject(
                "select id from document where title = 'Служебная записка на доработке' and author_id = ?",
                Long.class, demoAuthor);

        // у демо-документов файлов нет: новая версия без файлов, поля прежние — иначе возвращённый демо-документ
        // был тупиком
        Map<String, Object> meta = Map.of("containsSensitive", false, "keepFileIds", List.of());
        MvcResult result = mvc.perform(multipart("/api/v1/documents/" + returned + "/versions")
                .file(new MockMultipartFile("meta", "", "application/json", MAPPER.writeValueAsBytes(meta)))
                .header("Authorization", reviewer.auth())
                .header("X-Demo-Act-As", demoAuthor)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        JsonNode draft = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("permissions").path("canSubmit").asBoolean()).isTrue();
        assertThat(draft.path("changes").path("fields")).isEmpty();
        assertThat(ok(callAs(reviewer, demoAuthor, "POST", "/api/v1/documents/" + returned + "/submit", null))
                .path("status").asText()).isEqualTo("IN_APPROVAL");
    }

    @Test
    void reviewerActingAsDemoParticipantsPassesTheWholeChainAlone() throws Exception {
        Person reviewer = newUser();
        JsonNode sandbox = ok(call(reviewer, "POST", "/api/v1/demo/sandbox", null));
        long demoAuthor = demoUser(sandbox, "DEPARTMENT_HEAD");
        long demoLawyer = demoUser(sandbox, "LAWYER");
        long demoAccountant = demoUser(sandbox, "ACCOUNTANT");
        long demoDirector = demoUser(sandbox, "DIRECTOR");

        long memo = createDocumentAs(reviewer, demoAuthor, "OFFICIAL_MEMO");
        JsonNode submitted = ok(callAs(reviewer, demoAuthor, "POST", "/api/v1/documents/" + memo + "/submit", null));
        assertThat(submitted.path("status").asText()).isEqualTo("IN_APPROVAL");

        JsonNode lawyerCard = ok(callAs(reviewer, demoLawyer, "GET", "/api/v1/documents/" + memo, null));
        JsonNode accountantCard = ok(callAs(reviewer, demoAccountant, "GET", "/api/v1/documents/" + memo, null));
        assertThat(myActive(lawyerCard)).hasSize(1);
        assertThat(myActive(accountantCard)).hasSize(1);
        ok(callAs(reviewer, demoLawyer, "POST",
                "/api/v1/approval-steps/" + myActive(lawyerCard).get(0) + "/decision",
                Map.of("decision", "APPROVE")));
        ok(callAs(reviewer, demoAccountant, "POST",
                "/api/v1/approval-steps/" + myActive(accountantCard).get(0) + "/decision",
                Map.of("decision", "APPROVE")));

        JsonNode directorCard = ok(callAs(reviewer, demoDirector, "GET", "/api/v1/documents/" + memo, null));
        assertThat(myActive(directorCard)).hasSize(1);
        JsonNode approved = ok(callAs(reviewer, demoDirector, "POST",
                "/api/v1/approval-steps/" + myActive(directorCard).get(0) + "/decision",
                Map.of("decision", "APPROVE")));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
    }

    // ============ гонки: очередь на блокировку строки документа ============

    @Test
    void approvalThatWinsTheRaceDoesNotStopALaterReturnOnAParallelStage() throws Exception {
        Race race = raceOnParallelStage(true);

        assertThat(race.carol().status()).as(race.carol().body()).isEqualTo(200);
        assertThat(race.erin().status()).as(race.erin().body()).isEqualTo(200);
        assertThat(status(race.documentId())).isEqualTo("RETURNED");
        assertThat(stepDecisions(race.documentId())).containsExactly("APPROVED", "RETURNED", "SKIPPED");
    }

    @Test
    void returnThatWinsTheRaceMakesTheLaterApprovalFailWithAnExplanation() throws Exception {
        Race race = raceOnParallelStage(false);

        assertThat(race.erin().status()).as(race.erin().body()).isEqualTo(200);
        assertThat(race.carol().status()).as(race.carol().body()).isEqualTo(409);
        assertThat(race.carol().code()).isEqualTo("STEP_NOT_ACTIVE");
        assertThat(race.carol().json().path("message").asText()).isEqualTo("Документ уже возвращён");
        assertThat(status(race.documentId())).isEqualTo("RETURNED");
        assertThat(stepDecisions(race.documentId())).containsExactly("SKIPPED", "RETURNED", "SKIPPED");
    }

    @Test
    void doubleClickOnTheSameDecisionIsAppliedOnce() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null));
        long bobStep = stepOf(submitted, company.bob);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = holdLock("select id from document where id = ? for update", memo)) {
            Future<Resp> first = pool.submit(() -> decide(company.bob, bobStep, "APPROVE", null));
            waitForBlockedSessions(1);
            Future<Resp> second = pool.submit(() -> decide(company.bob, bobStep, "APPROVE", null));
            waitForBlockedSessions(2);
            holder.commit();
            assertThat(first.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(second.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(stepDecisions(memo)).containsExactly("APPROVED", "PENDING");
        assertThat(jdbc.queryForObject("select current_stage from document where id = ?", Integer.class, memo)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from approval_step where document_id = ? and activated_at is not null",
                Integer.class, memo)).isEqualTo(2);
    }

    @Test
    void twoApproversClosingAParallelStageAtOnceAdvanceTheDocumentExactlyOnce() throws Exception {
        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit",
                Map.of("selections", List.of(choice(1, company.roles.get("LAWYER"), company.carol.dbId)))));
        long carolStep = stepOf(submitted, company.carol);
        long erinStep = stepOf(submitted, company.erin);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = holdLock("select id from document where id = ? for update", support)) {
            Future<Resp> carol = pool.submit(() -> decide(company.carol, carolStep, "APPROVE", null));
            waitForBlockedSessions(1);
            Future<Resp> erin = pool.submit(() -> decide(company.erin, erinStep, "APPROVE", null));
            waitForBlockedSessions(2);
            holder.commit();
            assertThat(carol.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(erin.get(20, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(stepDecisions(support)).containsExactly("APPROVED", "APPROVED", "PENDING");
        assertThat(jdbc.queryForObject("select current_stage from document where id = ?", Integer.class, support)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select activated_at is not null from approval_step where document_id = ? and stage_order = 2",
                Boolean.class, support)).isTrue();
        assertThat(status(support)).isEqualTo("IN_APPROVAL");
    }

    // ============: найденные инварианты ============

    @Test
    void addedApproverWhoIsAlreadyInTheRouteIsRejectedAndNoStepIsDoubled() throws Exception {
        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        String submit = "/api/v1/documents/" + support + "/submit";
        long lawyer = company.roles.get("LAWYER");
        long accountant = company.roles.get("ACCOUNTANT");
        List<Object> carolChosen = List.of(choice(1, lawyer, company.carol.dbId));

        // Эрин подставлена автоматически (единственный бухгалтер): «добавить» её нельзя
        Resp auto = call(company.frank, "POST", submit, Map.of("selections", carolChosen,
                "extraApprovers", List.of(choice(1, accountant, company.erin.dbId))));
        assertThat(auto.status()).isEqualTo(400);
        assertThat(auto.code()).isEqualTo("VALIDATION_FAILED");
        // Карол выбрана автором в этой же отправке
        Resp selected = call(company.frank, "POST", submit, Map.of("selections", carolChosen,
                "extraApprovers", List.of(choice(1, lawyer, company.carol.dbId))));
        assertThat(selected.status()).isEqualTo(400);
        assertThat(selected.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(count("approval_step")).as("ничего не сохранено").isZero();
        assertThat(status(support)).isEqualTo("DRAFT");

        // Дэйв — второй юрист, не выбранный автором: настоящее добавление, шагов ровно четыре
        ok(call(company.frank, "POST", submit, Map.of("selections", carolChosen,
                "extraApprovers", List.of(choice(1, lawyer, company.dave.dbId)))));
        assertThat(count("approval_step")).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(distinct (stage_order, role_id, approver_id)) from approval_step"
                + " where document_id = ?", Integer.class, support)).as("одинаковых шагов нет").isEqualTo(4);
    }

    @Test
    void stageWaitsForEveryStepThatWasCreatedWhetherTheTemplateCallsItMandatoryOrNot() throws Exception {
        // этап из обязательного бухгалтера и необязательного юриста, у которого носитель нашёлся
        jdbc.update("update approval_route set is_mandatory = true where role_id = ?", company.roles.get("ACCOUNTANT"));
        List<Object> carolChosen = List.of(choice(1, company.roles.get("LAWYER"), company.carol.dbId));

        long first = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + first + "/submit",
                Map.of("selections", carolChosen)));
        long carolStep = stepOf(submitted, company.carol);
        long erinStep = stepOf(submitted, company.erin);
        assertThat(stageStates(ok(decide(company.erin, erinStep, "APPROVE", null))))
                .as("обязательный одобрил, необязательный с носителем ещё не решил").containsExactly("ACTIVE", "WAITING");
        assertThat(stageStates(ok(decide(company.carol, carolStep, "APPROVE", null)))).containsExactly("DONE", "ACTIVE");

        long second = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + second + "/submit", Map.of("selections", carolChosen)));
        assertThat(stageStates(ok(decide(company.carol, stepOf(submitted, company.carol), "APPROVE", null))))
                .as("необязательный одобрил, обязательный ещё нет").containsExactly("ACTIVE", "WAITING");
        assertThat(stageStates(ok(decide(company.erin, stepOf(submitted, company.erin), "APPROVE", null))))
                .containsExactly("DONE", "ACTIVE");
    }

    @Test
    void optionalRoleWithNoHolderIsSkippedAndTheStageClosesOnTheOthers() throws Exception {
        jdbc.update("delete from member_role where role_id = ?", company.roles.get("LAWYER"));
        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");

        JsonNode preview = ok(call(company.frank, "GET", "/api/v1/documents/" + support + "/route-preview", null));
        assertThat(preview.path("stages").get(0).path("participants").get(0).path("resolution").asText()).isEqualTo("SKIPPED");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit", null));
        assertThat(count("approval_step")).as("Эрин и Алиса, шага юриста нет").isEqualTo(2);

        assertThat(stageStates(ok(decide(company.erin, stepOf(submitted, company.erin), "APPROVE", null))))
                .containsExactly("DONE", "ACTIVE");
    }

    @Test
    void personWithoutCompanyCannotTellAnExistingDocumentFromAMissingOne() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        Person outsider = newUser();

        for (String tail : List.of("/submit", "/route-preview")) {
            Resp existing = call(outsider, tail.equals("/submit") ? "POST" : "GET", "/api/v1/documents/" + memo + tail, null);
            Resp missing = call(outsider, tail.equals("/submit") ? "POST" : "GET", "/api/v1/documents/987654321" + tail, null);
            assertThat(existing.status()).as(tail).isEqualTo(403);
            assertThat(missing.status()).as(tail).isEqualTo(403);
            assertThat(existing.body()).as("ответы неотличимы").isEqualTo(missing.body());
        }
        assertThat(status(memo)).isEqualTo("DRAFT");
    }

    @Test
    void firstRequestsFromTwoDocumentsCreateTheDefaultRouteOnceAndBothSucceed() throws Exception {
        long docA = createDocument(company.frank, "OFFICIAL_MEMO");
        long docB = createDocument(company.frank, "OFFICIAL_MEMO");
        long orgId = jdbc.queryForObject("select id from organization limit 1", Long.class);
        long typeId = jdbc.queryForObject("select id from document_type where code = 'OFFICIAL_MEMO'", Long.class);
        List<Map<String, Object>> template = jdbc.queryForList("select stage_order, role_id, is_mandatory from approval_route"
                + " where org_id = ? and document_type_id = ? order by stage_order, id", orgId, typeId);
        assertThat(template).hasSize(2);
        jdbc.update("delete from approval_route where org_id = ? and document_type_id = ?", orgId, typeId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        // «другая транзакция» уже вставила маршрут, но ещё не зафиксировала: обе проверки «маршрута нет» это не видят
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            for (Map<String, Object> row : template) {
                try (PreparedStatement insert = other.prepareStatement("insert into approval_route"
                        + " (org_id, document_type_id, stage_order, role_id, is_mandatory) values (?, ?, ?, ?, ?)")) {
                    insert.setLong(1, orgId);
                    insert.setLong(2, typeId);
                    insert.setInt(3, ((Number) row.get("stage_order")).intValue());
                    insert.setLong(4, ((Number) row.get("role_id")).longValue());
                    insert.setBoolean(5, (Boolean) row.get("is_mandatory"));
                    insert.executeUpdate();
                }
            }
            Future<Resp> first = pool.submit(() -> call(company.frank, "GET", "/api/v1/documents/" + docA + "/route-preview", null));
            Future<Resp> second = pool.submit(() -> call(company.frank, "GET", "/api/v1/documents/" + docB + "/route-preview", null));
            waitForBlockedSessions(2);
            other.commit();

            for (Future<Resp> response : List.of(first, second)) {
                Resp resp = response.get(20, TimeUnit.SECONDS);
                assertThat(resp.status()).as(resp.body()).isEqualTo(200);
                assertThat(resp.json().path("stages")).hasSize(2);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from approval_route where org_id = ? and document_type_id = ?",
                Integer.class, orgId, typeId)).isEqualTo(2);
    }

    @Test
    void routeMissingForAnEmptyCompanyIsCreatedOnFirstPreviewAndSubmitStillWorks() throws Exception {
        long orgId = jdbc.queryForObject("select id from organization limit 1", Long.class);
        jdbc.update("delete from approval_route where org_id = ?", orgId);
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");

        ok(call(company.frank, "GET", "/api/v1/documents/" + memo + "/route-preview", null));
        assertThat(jdbc.queryForObject("select count(*) from approval_route where org_id = ?", Integer.class, orgId))
                .as("маршруты по умолчанию всех пяти типов: 2 + 2 + 3 + 3 + 1").isEqualTo(11);
        assertThat(count("approval_step")).as("предпросмотр шагов не создаёт").isZero();
        assertThat(status(memo)).isEqualTo("DRAFT");
        assertThat(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null).status()).isEqualTo(200);
    }

    @Test
    void commentLengthIsMeasuredAfterTrimmingAndABlankCommentIsMissingNotTooLong() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null));
        long bobStep = stepOf(submitted, company.bob);
        String limit = "я".repeat(2000);

        Resp tooLong = decide(company.bob, bobStep, "RETURN", limit + "я");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("VALIDATION_FAILED");
        Resp blank = decide(company.bob, bobStep, "RETURN", " ".repeat(5000));
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.code()).isEqualTo("COMMENT_REQUIRED");
        assertThat(stepDecisions(memo)).containsExactly("PENDING", "PENDING");

        ok(decide(company.bob, bobStep, "RETURN", "  " + limit + "  "));
        assertThat(jdbc.queryForObject("select length(comment) from approval_step where id = ?", Integer.class, bobStep))
                .isEqualTo(2000);
    }

    // ============ напоминания о застрявших шагах  ============

    /**
     * Наивное {@code lastRemindedAt <= now - cooldown} отсекает законное повторное
     * напоминание, если время суток вчерашней отметки чуть позже, чем сегодняшний запуск планировщика
     * (дрожание момента старта {@code @Scheduled}) - тогда напоминание пришло бы через двое суток вместо
     * одних. Здесь разница ровно такая: вчерашняя отметка на 400 мс позже условного «сегодня 08:00».
     */
    @Test
    void stuckStepReminderSurvivesClockDriftAtTheCooldownBoundary() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null));
        long bobStep = stepOf(submitted, company.bob);

        Instant now = Instant.now();
        jdbc.update("update approval_step set activated_at = ?, last_reminded_at = ? where id = ?",
                Timestamp.from(now.minus(Duration.ofHours(60))),
                Timestamp.from(now.minus(Duration.ofHours(24)).plusMillis(400)),
                bobStep);

        reminderJob.remindStuckSteps();

        Instant remindedAt = lastRemindedAt(bobStep);
        assertThat(remindedAt).isAfter(now.minusSeconds(5));

        // Повторный запуск сразу же ничего не меняет - шаг только что отмечен, до cooldown ещё далеко.
        reminderJob.remindStuckSteps();
        assertThat(lastRemindedAt(bobStep)).isEqualTo(remindedAt);
    }

    @Test
    void stuckStepReminderIgnoresStepsBelowTheThresholdOrRemindedRecently() throws Exception {
        long memo = createDocument(company.frank, "OFFICIAL_MEMO");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + memo + "/submit", null));
        long bobStep = stepOf(submitted, company.bob);
        Instant now = Instant.now();

        // Активирован недавно - ещё не «застрял» (порог по умолчанию 48 часов).
        jdbc.update("update approval_step set activated_at = ?, last_reminded_at = null where id = ?",
                Timestamp.from(now.minus(Duration.ofHours(1))), bobStep);
        reminderJob.remindStuckSteps();
        assertThat(jdbc.queryForObject(
                "select last_reminded_at is null from approval_step where id = ?", Boolean.class, bobStep)).isTrue();

        // Застрял, но напомнили совсем недавно - до суток ещё далеко.
        jdbc.update("update approval_step set activated_at = ?, last_reminded_at = ? where id = ?",
                Timestamp.from(now.minus(Duration.ofHours(60))), Timestamp.from(now.minus(Duration.ofHours(1))),
                bobStep);
        Instant remindedRecently = lastRemindedAt(bobStep);
        reminderJob.remindStuckSteps();
        assertThat(lastRemindedAt(bobStep)).isEqualTo(remindedRecently);
    }

    private Instant lastRemindedAt(long stepId) {
        return jdbc.queryForObject(
                "select last_reminded_at from approval_step where id = ?", Timestamp.class, stepId).toInstant();
    }

    // ============ вспомогательное: гонка ============

    private record Race(long documentId, Resp carol, Resp erin) {
    }

    /** Карол одобряет, Эрин возвращает — на параллельном этапе; кто встал в очередь первым, тот и выиграл. */
    private Race raceOnParallelStage(boolean carolQueuesFirst) throws Exception {
        long support = createDocument(company.frank, "SUPPORT_MEASURE_REQUEST");
        JsonNode submitted = ok(call(company.frank, "POST", "/api/v1/documents/" + support + "/submit",
                Map.of("selections", List.of(choice(1, company.roles.get("LAWYER"), company.carol.dbId)))));
        long carolStep = stepOf(submitted, company.carol);
        long erinStep = stepOf(submitted, company.erin);
        Callable<Resp> approve = () -> decide(company.carol, carolStep, "APPROVE", null);
        Callable<Resp> returnIt = () -> decide(company.erin, erinStep, "RETURN", "Нужны уточнения");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = holdLock("select id from document where id = ? for update", support)) {
            Future<Resp> first = pool.submit(carolQueuesFirst ? approve : returnIt);
            waitForBlockedSessions(1);
            Future<Resp> second = pool.submit(carolQueuesFirst ? returnIt : approve);
            waitForBlockedSessions(2);
            holder.commit();
            Resp carol = (carolQueuesFirst ? first : second).get(20, TimeUnit.SECONDS);
            Resp erin = (carolQueuesFirst ? second : first).get(20, TimeUnit.SECONDS);
            return new Race(support, carol, erin);
        } finally {
            pool.shutdownNow();
        }
    }

    // ============ вспомогательное: компания и документы ============

    private record Person(long devId, long dbId) {
        String auth() {
            return "Dev " + devId;
        }
    }

    private record Company(Person alice, Person bob, Person carol, Person dave, Person erin, Person frank,
                           Map<String, Long> roles) {
    }

    /** Алиса — директор и администратор; Боб — руководитель отдела; Карол и Дэйв — юристы; Эрин — бухгалтер; Фрэнк — автор. */
    private Company newCompany(String name) throws Exception {
        Person alice = newUser();
        ok(call(alice, "POST", "/api/v1/orgs", Map.of("name", name)), 201);
        Map<String, Long> roles = new HashMap<>();
        for (JsonNode role : ok(call(alice, "GET", "/api/v1/orgs/current/roles", null))) {
            roles.put(role.path("code").asText(), role.path("id").asLong());
        }
        String code = ok(call(alice, "GET", "/api/v1/orgs/current/invite-code", null)).path("code").asText();
        Person bob = join(alice, code, roles.get("DEPARTMENT_HEAD"));
        Person carol = join(alice, code, roles.get("LAWYER"));
        Person dave = join(alice, code, roles.get("LAWYER"));
        Person erin = join(alice, code, roles.get("ACCOUNTANT"));
        Person frank = join(alice, code, roles.get("HR"));
        return new Company(alice, bob, carol, dave, erin, frank, roles);
    }

    private Person join(Person admin, String code, long roleId) throws Exception {
        Person person = newUser();
        long requestId = ok(call(person, "POST", "/api/v1/join-requests", Map.of("code", code)), 201).path("id").asLong();
        ok(call(admin, "POST", "/api/v1/orgs/current/join-requests/" + requestId + "/approve",
                Map.of("roleIds", List.of(roleId))));
        return person;
    }

    private Person newUser() throws Exception {
        long devId = MAX_IDS.incrementAndGet();
        // первый запрос создаёт пользователя в базе
        ok(call(new Person(devId, 0), "GET", "/api/v1/me", null));
        long dbId = jdbc.queryForObject("select id from app_user where max_user_id = ?", Long.class, devId);
        return new Person(devId, dbId);
    }

    private long createDocument(Person author, String typeCode) throws Exception {
        return createDocumentWith(author.auth(), null, typeCode);
    }

    private long createDocumentAs(Person reviewer, long actorId, String typeCode) throws Exception {
        return createDocumentWith(reviewer.auth(), actorId, typeCode);
    }

    private long createDocumentWith(String auth, Long actAs, String typeCode) throws Exception {
        long typeId = jdbc.queryForObject("select id from document_type where code = ?", Long.class, typeCode);
        Map<String, Object> meta = Map.of(
                "documentTypeId", typeId,
                "title", "Документ " + UUID.randomUUID(),
                "visibility", "PRIVATE",
                "containsSensitive", false,
                "fields", validFields(typeCode));
        MockHttpServletRequestBuilder request = multipart("/api/v1/documents")
                .file(new MockMultipartFile("meta", "", "application/json", MAPPER.writeValueAsBytes(meta)))
                .file(new MockMultipartFile("main", "doc.pdf", "application/pdf", pdf()))
                .header("Authorization", auth);
        if (actAs != null) {
            request.header("X-Demo-Act-As", actAs);
        }
        MvcResult result = mvc.perform(request).andReturn();
        Resp response = new Resp(result.getResponse().getStatus(), result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return ok(response, 201).path("id").asLong();
    }

    private static Map<String, String> validFields(String typeCode) {
        return switch (typeCode) {
            case "OFFICIAL_MEMO" -> Map.of(
                    "addressee", "Генеральному директору",
                    "doc_date", "01.01.2020",
                    "body", "Тестовый текст служебной записки достаточной длины для проверки маршрута согласования.");
            case "VACATION_REQUEST" -> Map.of(
                    "employee_name", "Иванов Иван Иванович",
                    "vacation_type", "ежегодный оплачиваемый",
                    "start_date", "01.10.2026",
                    "days_count", "14");
            case "SUPPORT_MEASURE_REQUEST" -> Map.of(
                    "applicant_name", "ООО Ромашка",
                    "support_type", "Консультационная поддержка",
                    "applicant_inn", "1234567890",
                    "smb_category", "малое предприятие");
            case "BUSINESS_TRIP_REQUEST" -> Map.of(
                    "employee_name", "Кузнецов Игорь Олегович",
                    "destination", "г. Казань",
                    "purpose", "участие в выставке",
                    "start_date", "05.10.2026",
                    "days_count", "5");
            default -> Map.of();
        };
    }

    private Resp newVersion(Person author, long documentId, long keepFileId, String typeCode) throws Exception {
        Map<String, Object> meta = Map.of(
                "containsSensitive", false,
                "keepFileIds", List.of(keepFileId),
                "fields", validFields(typeCode));
        MvcResult result = mvc.perform(multipart("/api/v1/documents/" + documentId + "/versions")
                .file(new MockMultipartFile("meta", "", "application/json", MAPPER.writeValueAsBytes(meta)))
                .header("Authorization", author.auth())).andReturn();
        return new Resp(result.getResponse().getStatus(), result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static long demoUser(JsonNode sandbox, String roleCode) {
        for (JsonNode user : sandbox.path("users")) {
            for (JsonNode role : user.path("roles")) {
                if (roleCode.equals(role.path("code").asText())) {
                    return user.path("user").path("id").asLong();
                }
            }
        }
        throw new IllegalStateException("В песочнице нет участника с ролью " + roleCode);
    }

    private JsonNode card(Person person, long documentId) throws Exception {
        return ok(call(person, "GET", "/api/v1/documents/" + documentId, null));
    }

    private Resp decide(Person person, long stepId, String decision, String comment) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        if (comment != null) {
            body.put("comment", comment);
        }
        return call(person, "POST", "/api/v1/approval-steps/" + stepId + "/decision", body);
    }

    private static Map<String, Object> person(Person person, long roleId) {
        return Map.of("userId", person.dbId, "roleId", roleId);
    }

    @SafeVarargs
    private static Map<String, Object> stage(Map<String, Object>... participants) {
        return Map.of("participants", List.of(participants));
    }

    private static Map<String, Object> choice(int stage, long roleId, long userId) {
        return Map.of("stageOrder", stage, "roleId", roleId, "userId", userId);
    }

    /** Шаг человека в текущей версии карточки. */
    private static long stepOf(JsonNode card, Person person) {
        for (JsonNode stage : card.path("route").path("stages")) {
            for (JsonNode step : stage.path("steps")) {
                if (step.path("approver").path("id").asLong() == person.dbId) {
                    return step.path("id").asLong();
                }
            }
        }
        throw new IllegalStateException("У пользователя нет шага в маршруте");
    }

    private static List<String> stageStates(JsonNode card) {
        List<String> states = new ArrayList<>();
        card.path("route").path("stages").forEach(stage -> states.add(stage.path("state").asText()));
        return states;
    }

    private static List<String> decisions(JsonNode card) {
        List<String> result = new ArrayList<>();
        card.path("route").path("stages").forEach(stage -> stage.path("steps")
                .forEach(step -> result.add(step.path("decision").asText())));
        return result;
    }

    private static List<Long> myActive(JsonNode card) {
        List<Long> ids = new ArrayList<>();
        card.path("myActiveStepIds").forEach(id -> ids.add(id.asLong()));
        return ids;
    }

    private int waitingMe(Person person) throws Exception {
        return ok(call(person, "GET", "/api/v1/documents?tab=WAITING_ME", null)).path("total").asInt();
    }

    private String status(long documentId) {
        return jdbc.queryForObject("select status from document where id = ?", String.class, documentId);
    }

    private List<String> stepDecisions(long documentId) {
        return jdbc.queryForList("select decision from approval_step where document_id = ? order by stage_order, id",
                String.class, documentId);
    }

    private long memberId(Person person) {
        return jdbc.queryForObject("select id from organization_member where user_id = ?", Long.class, person.dbId);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    // ============ вспомогательное: HTTP и блокировки ============

    private record Resp(int status, String body) {
        JsonNode json() {
            try {
                return body == null || body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
            } catch (Exception exception) {
                throw new IllegalStateException(body, exception);
            }
        }

        String code() {
            return json().path("code").asText();
        }
    }

    private static JsonNode ok(Resp response) {
        return ok(response, 200);
    }

    private static JsonNode ok(Resp response, int status) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        return response.json();
    }

    private Resp call(Person person, String method, String path, Object body) throws Exception {
        return exchange(person.auth(), null, method, path, body);
    }

    private Resp callAs(Person reviewer, long actorId, String method, String path, Object body) throws Exception {
        return exchange(reviewer.auth(), actorId, method, path, body);
    }

    private Resp exchange(String auth, Long actAs, String method, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path).header("Authorization", auth);
        if (actAs != null) {
            builder.header("X-Demo-Act-As", actAs);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsBytes(body));
        }
        MvcResult result = mvc.perform(builder).andReturn();
        return new Resp(result.getResponse().getStatus(), result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Connection holdLock(String sql, Object... args) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                statement.execute();
            }
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private void waitForBlockedSessions(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer blocked = jdbc.queryForObject("select count(*) from pg_stat_activity"
                    + " where datname = current_database() and wait_event_type = 'Lock'", Integer.class);
            if (blocked != null && blocked >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Не дождались заблокированных сессий: " + expected);
    }

    private static byte[] pdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
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

        @Override
        public String put(InputStream content, String fileName, String contentType, long size) {
            try {
                String key = UUID.randomUUID().toString();
                files.put(key, new Stored(content.readAllBytes(), fileName, contentType));
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
            return new StoredFile(new ByteArrayInputStream(stored.content()),
                    stored.fileName(), stored.contentType(), stored.content().length);
        }

        @Override
        public void delete(String storageKey) {
            files.remove(storageKey);
        }

        void clear() {
            files.clear();
        }

        private record Stored(byte[] content, String fileName, String contentType) {
        }
    }
}
