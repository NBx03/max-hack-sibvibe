package ru.sibvibe.approval.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.sibvibe.approval.organization.service.RouteBackfillRunner;
import ru.sibvibe.approval.organization.service.RouteTemplateService;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Подключение компании на настоящем PostgreSQL: каждая обязательная проверка из docs/DESIGN-DECISIONS.md
 * и из, включая гонки при вступлении по личной ссылке и при перевыпуске кода.
 * Запускается только при заданных {@code SCHEMA_TEST_URL} и {@code SCHEMA_TEST_PASSWORD},
 * как остальные интеграционные тесты (backend/src/test/README.md).
 *
 * <p>Тест работает в собственной схеме {@value #SCHEMA}, которая при запуске создаётся заново, а миграции
 * выполняет настоящее приложение. Поэтому стандартные типы документов и маршруты здесь такие,
 * какими их делают миграции, а не какими их подготовил тест. Гонки проверяются детерминированно:
 * отдельная транзакция удерживает блокировку строки, и тест смотрит, что именно ждёт эту блокировку.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "SCHEMA_TEST_URL", matches = ".+")
class OnboardingIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong MAX_IDS = new AtomicLong(930_000_000L);
    private static final String SCHEMA = "onboarding_it";
    private static final List<String> TYPE_CODES = List.of(
            "OFFICIAL_MEMO", "VACATION_REQUEST", "SUPPORT_MEASURE_REQUEST", "BUSINESS_TRIP_REQUEST", "GENERIC");

    static {
        // Один раз до создания контекста: схема пересоздаётся, и миграции выполняются на пустой базе.
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
        registry.add("app.onboarding.join-attempts-per-hour", () -> "3");
        registry.add("max.bot.token", () -> "onboarding-test-token");
        // Тестовый токен нужен только для проверки initData - без этого флага бэкенд слал бы
        // настоящие HTTP-запросы на platform-api2.max.ru при каждом уведомлении (BotConfiguration).
        registry.add("max.bot.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude",
                () -> "chat.giga.springai.autoconfigure.GigaChatAutoConfiguration");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RouteTemplateService routeTemplateService;

    @Autowired
    private RouteBackfillRunner routeBackfillRunner;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void cleanBefore() {
        cleanDatabase();
    }

    /** Данные тестов удаляются, а справочник типов остаётся таким, каким его создали миграции. */
    @AfterEach
    void cleanDatabase() {
        for (String table : List.of("approval_step", "document_version", "document", "approval_route",
                "invite_role", "join_request", "invite", "member_role", "organization_member", "role",
                "organization", "app_user")) {
            jdbc.update("delete from " + table);
        }
    }

    // ---------- Создание компании ----------

    @Test
    void creatorBecomesAdministratorWithDirectorRoleAndCompanyGetsDefaults() {
        // Типы документов создала миграция, а не тест: без них не было бы и маршрутов по умолчанию.
        assertThat(jdbc.queryForObject("select count(*) from databasechangelog"
                + " where id = '17-001-default-document-types'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select code from document_type", String.class))
                .containsExactlyInAnyOrderElementsOf(TYPE_CODES);
        long alice = newUser();

        Resp created = call(alice, "POST", "/api/v1/orgs", Map.of("name", "  Ромашка ", "inn", ""));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.json().path("name").asText()).isEqualTo("Ромашка");
        long orgId = created.json().path("id").asLong();

        JsonNode me = ok(call(alice, "GET", "/api/v1/me", null));
        assertThat(me.path("membership").path("isAdmin").asBoolean()).isTrue();
        assertThat(me.path("membership").path("orgName").asText()).isEqualTo("Ромашка");
        assertThat(codes(me.path("membership").path("roles"))).containsExactly("DIRECTOR");

        Map<String, Long> roles = roleIds(alice);
        assertThat(roles.keySet()).containsExactlyInAnyOrder(
                "DIRECTOR", "DEPARTMENT_HEAD", "LAWYER", "ACCOUNTANT", "HR");

        JsonNode code = ok(call(alice, "GET", "/api/v1/orgs/current/invite-code", null));
        assertThat(code.path("code").asText()).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}");
        assertThat(code.path("link").asText())
                .isEqualTo("https://max.ru/t354_hakaton_max_bot?startapp=c_" + code.path("code").asText());

        // маршруты по умолчанию: записка 2 + отпуск 2 + мера поддержки 3 + командировка 3 + другой документ 1
        assertThat(jdbc.queryForObject("select count(*) from approval_route where org_id = ?",
                Integer.class, orgId)).isEqualTo(11);
        List<Map<String, Object>> memo = jdbc.queryForList("""
                select r.stage_order, ro.code, r.is_mandatory from approval_route r
                join role ro on ro.id = r.role_id join document_type t on t.id = r.document_type_id
                where r.org_id = ? and t.code = 'OFFICIAL_MEMO' order by r.stage_order""", orgId);
        assertThat(memo).hasSize(2);
        assertThat(memo.get(0)).containsEntry("code", "DEPARTMENT_HEAD").containsEntry("is_mandatory", false);
        assertThat(memo.get(1)).containsEntry("code", "DIRECTOR").containsEntry("is_mandatory", true);

        Resp again = call(alice, "POST", "/api/v1/orgs", Map.of("name", "Вторая"));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ALREADY_MEMBER");
    }

    @Test
    void blankCompanyNameIsAValidationError() {
        Resp response = call(newUser(), "POST", "/api/v1/orgs", Map.of("name", "   "));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
    }

    // ---------- Заявка по коду ----------

    @Test
    void applicantSeesOnlyCompanyNameHasOnePendingRequestAndCanCancel() {
        long alice = newUser();
        long bob = newUser();
        createCompany(alice, "Ромашка");
        String code = inviteCode(alice);

        // до одобрения виден только orgName
        JsonNode preview = ok(call(bob, "GET", "/api/v1/invites/code/" + code.toLowerCase(), null));
        assertThat(preview.fieldNames()).toIterable().containsExactly("orgName");
        assertThat(preview.path("orgName").asText()).isEqualTo("Ромашка");

        Resp submitted = call(bob, "POST", "/api/v1/join-requests", Map.of("code", " " + code.toLowerCase() + " "));
        assertThat(submitted.status()).as(submitted.body()).isEqualTo(201);
        assertThat(submitted.json().path("status").asText()).isEqualTo("PENDING");
        long requestId = submitted.json().path("id").asLong();

        Resp second = call(bob, "POST", "/api/v1/join-requests", Map.of("code", code));
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("JOIN_REQUEST_EXISTS");

        JsonNode me = ok(call(bob, "GET", "/api/v1/me", null));
        assertThat(me.path("membership").isNull()).isTrue();
        assertThat(me.path("pendingJoinRequest").path("id").asLong()).isEqualTo(requestId);
        assertThat(me.path("pendingJoinRequest").path("orgName").asText()).isEqualTo("Ромашка");

        // заявитель не видит ничего, кроме названия
        assertThat(call(bob, "GET", "/api/v1/orgs/current/roles", null).status()).isEqualTo(403);
        assertThat(call(bob, "GET", "/api/v1/orgs/current/members", null).status()).isEqualTo(403);
        assertThat(call(bob, "GET", "/api/v1/orgs/current/colleagues", null).status()).isEqualTo(403);
        assertThat(call(bob, "GET", "/api/v1/orgs/current/invite-code", null).status()).isEqualTo(403);

        // чужую заявку отменить нельзя, и она выглядит несуществующей
        Resp foreign = call(newUser(), "DELETE", "/api/v1/join-requests/" + requestId, null);
        assertThat(foreign.status()).isEqualTo(404);

        assertThat(call(bob, "DELETE", "/api/v1/join-requests/" + requestId, null).status()).isEqualTo(204);
        assertThat(call(bob, "DELETE", "/api/v1/join-requests/" + requestId, null).status()).isEqualTo(409);
        assertThat(call(bob, "POST", "/api/v1/join-requests", Map.of("code", code)).status()).isEqualTo(201);
    }

    @Test
    void adminApprovesWithRolesInOneRequestAndRightsApplyImmediately() {
        long alice = newUser();
        long bob = newUser();
        long carol = newUser();
        createCompany(alice, "Ромашка");
        Map<String, Long> roles = roleIds(alice);
        String code = inviteCode(alice);
        long requestId = submit(bob, code);

        JsonNode pending = ok(call(alice, "GET", "/api/v1/orgs/current/join-requests?status=PENDING", null));
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).path("user").path("fullName").asText()).isNotBlank();

        Resp approved = call(alice, "POST", "/api/v1/orgs/current/join-requests/" + requestId + "/approve",
                Map.of("roleIds", List.of(roles.get("LAWYER"), roles.get("ACCOUNTANT"))));
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(codes(approved.json().path("roles"))).containsExactlyInAnyOrder("LAWYER", "ACCOUNTANT");
        assertThat(approved.json().path("isAdmin").asBoolean()).isFalse();
        assertThat(approved.json().path("status").asText()).isEqualTo("ACTIVE");
        long bobMemberId = approved.json().path("memberId").asLong();

        JsonNode bobMe = ok(call(bob, "GET", "/api/v1/me", null));
        assertThat(codes(bobMe.path("membership").path("roles"))).containsExactlyInAnyOrder("LAWYER", "ACCOUNTANT");
        assertThat(bobMe.path("pendingJoinRequest").isNull()).isTrue();
        assertThat(call(bob, "GET", "/api/v1/orgs/current/roles", null).status()).isEqualTo(200);

        // сотрудников своей компании видит любой участник, а управлять ими — только администратор
        JsonNode colleagues = ok(call(bob, "GET", "/api/v1/orgs/current/colleagues", null));
        assertThat(colleagues).hasSize(2);
        assertThat(colleagues.get(0).path("roles").isArray()).isTrue();
        Resp notAdmin = call(bob, "GET", "/api/v1/orgs/current/members", null);
        assertThat(notAdmin.status()).isEqualTo(403);
        assertThat(notAdmin.code()).isEqualTo("NOT_ADMIN");

        // решённая заявка не решается повторно
        Resp again = call(alice, "POST", "/api/v1/orgs/current/join-requests/" + requestId + "/approve",
                Map.of("roleIds", List.of(roles.get("LAWYER"))));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INVALID_STATE");

        // принять без единой роли и без прав администратора нельзя
        long carolRequest = submit(carol, code);
        Resp noRoles = call(alice, "POST", "/api/v1/orgs/current/join-requests/" + carolRequest + "/approve",
                Map.of("roleIds", List.of()));
        assertThat(noRoles.status()).isEqualTo(400);

        // отклонение: заявитель может подать заявку заново
        assertThat(call(alice, "POST", "/api/v1/orgs/current/join-requests/" + carolRequest + "/reject", null)
                .status()).isEqualTo(200);
        assertThat(call(carol, "POST", "/api/v1/join-requests", Map.of("code", code)).status()).isEqualTo(201);

        // роль снята — действует сразу, без перезапуска сессии
        JsonNode members = ok(call(alice, "GET", "/api/v1/orgs/current/members", null));
        assertThat(members).hasSize(2);
        Resp promoted = call(alice, "PUT", "/api/v1/orgs/current/members/" + bobMemberId + "/admin",
                Map.of("isAdmin", true));
        assertThat(promoted.status()).as(promoted.body()).isEqualTo(200);
        assertThat(call(bob, "GET", "/api/v1/orgs/current/members", null).status()).isEqualTo(200);
        ok(call(alice, "PUT", "/api/v1/orgs/current/members/" + bobMemberId + "/admin", Map.of("isAdmin", false)));
        assertThat(call(bob, "GET", "/api/v1/orgs/current/members", null).status()).isEqualTo(403);
    }

    // ---------- Администратор управляет только своей компанией ----------

    @Test
    void administratorOfAnotherCompanyCannotTouchForeignRequestsMembersOrRoles() {
        long alice = newUser();
        long dave = newUser();
        long bob = newUser();
        long eve = newUser();
        createCompany(alice, "Ромашка");
        createCompany(dave, "Одуванчик");
        Map<String, Long> aliceRoles = roleIds(alice);
        long bobRequest = submit(bob, inviteCode(alice));
        long bobMember = ok(call(alice, "POST",
                "/api/v1/orgs/current/join-requests/" + bobRequest + "/approve",
                Map.of("roleIds", List.of(aliceRoles.get("LAWYER"))))).path("memberId").asLong();
        long eveRequest = submit(eve, inviteCode(dave));

        String foreignRequest = "/api/v1/orgs/current/join-requests/";
        long carolRequest = submit(newUser(), inviteCode(alice));
        assertThat(call(dave, "POST", foreignRequest + carolRequest + "/approve",
                Map.of("roleIds", List.of(roleIds(dave).get("LAWYER")))).status()).isEqualTo(404);
        assertThat(call(dave, "POST", foreignRequest + carolRequest + "/reject", null).status()).isEqualTo(404);
        assertThat(ok(call(dave, "GET", "/api/v1/orgs/current/join-requests?status=PENDING", null)))
                .extracting(node -> node.path("id").asLong()).containsExactly(eveRequest);

        String member = "/api/v1/orgs/current/members/" + bobMember;
        assertThat(call(dave, "PUT", member + "/roles", Map.of("roleIds", List.of())).status()).isEqualTo(404);
        assertThat(call(dave, "PUT", member + "/admin", Map.of("isAdmin", true)).status()).isEqualTo(404);
        assertThat(call(dave, "POST", member + "/disable", null).status()).isEqualTo(404);
        assertThat(call(dave, "PATCH", "/api/v1/orgs/current/roles/" + aliceRoles.get("LAWYER"),
                Map.of("name", "Взлом")).status()).isEqualTo(404);
        assertThat(call(dave, "GET", "/api/v1/orgs/current/roles/" + aliceRoles.get("LAWYER") + "/members", null)
                .status()).isEqualTo(404);

        // роль чужой компании при принятии своей заявки — тоже чужая
        Resp foreignRole = call(dave, "POST", foreignRequest + eveRequest + "/approve",
                Map.of("roleIds", List.of(aliceRoles.get("LAWYER"))));
        assertThat(foreignRole.status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from organization_member where user_id = "
                + "(select id from app_user where max_user_id = ?)", Integer.class, eve)).isZero();

        // и ничего в компании Alice не изменилось
        assertThat(codes(ok(call(alice, "GET", "/api/v1/orgs/current/members", null)).get(1).path("roles")))
                .containsExactly("LAWYER");
    }

    // ---------- Город и часовой пояс компании ----------

    @Test
    void companyKeepsItsCityAndTimeZoneAndOnlyAnAdministratorChangesThem() {
        long alice = newUser();
        long bob = newUser();
        long charlie = newUser();

        // пояс не из России (и не из списка) не принимается: «сегодня» считалось бы непонятно как
        Resp foreign = call(charlie, "POST", "/api/v1/orgs",
                Map.of("name", "Чужой пояс", "city", "Лондон", "timeZone", "Europe/London"));
        assertThat(foreign.status()).isEqualTo(400);

        Resp created = call(alice, "POST", "/api/v1/orgs",
                Map.of("name", "Ромашка", "city", "Новосибирск", "timeZone", "Asia/Novosibirsk"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        JsonNode membership = ok(call(alice, "GET", "/api/v1/me", null)).path("membership");
        assertThat(membership.path("city").asText()).isEqualTo("Новосибирск");
        assertThat(membership.path("timeZone").asText()).isEqualTo("Asia/Novosibirsk");

        // без города (старый клиент) — Москва
        createCompany(charlie, "Без города");
        assertThat(ok(call(charlie, "GET", "/api/v1/me", null)).path("membership").path("timeZone").asText())
                .isEqualTo("Europe/Moscow");

        // меняет только администратор
        long bobRequest = submit(bob, inviteCode(alice));
        ok(call(alice, "POST", "/api/v1/orgs/current/join-requests/" + bobRequest + "/approve",
                Map.of("roleIds", List.of(roleIds(alice).get("LAWYER")))));
        Resp byMember = call(bob, "PUT", "/api/v1/orgs/current/location",
                Map.of("city", "Омск", "timeZone", "Asia/Omsk"));
        assertThat(byMember.status()).isEqualTo(403);
        assertThat(byMember.code()).isEqualTo("NOT_ADMIN");

        JsonNode changed = ok(call(alice, "PUT", "/api/v1/orgs/current/location",
                Map.of("city", "Казань", "timeZone", "Europe/Moscow")));
        assertThat(changed.path("city").asText()).isEqualTo("Казань");
        assertThat(ok(call(bob, "GET", "/api/v1/me", null)).path("membership").path("timeZone").asText())
                .as("сотрудник видит пояс компании сразу").isEqualTo("Europe/Moscow");
        assertThat(call(alice, "PUT", "/api/v1/orgs/current/location",
                Map.of("city", "Где-то", "timeZone", "Mars/Olympus")).status()).isEqualTo(400);
        // пара не из списка: город с чужим поясом или город, которого нет на экране
        Resp mismatched = call(alice, "PUT", "/api/v1/orgs/current/location",
                Map.of("city", "Омск", "timeZone", "Europe/Moscow"));
        assertThat(mismatched.status()).isEqualTo(400);
        assertThat(mismatched.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(call(alice, "PUT", "/api/v1/orgs/current/location",
                Map.of("city", "Атлантида", "timeZone", "Europe/Moscow")).status()).isEqualTo(400);
        assertThat(ok(call(bob, "GET", "/api/v1/me", null)).path("membership").path("city").asText())
                .as("отклонённая правка ничего не меняет").isEqualTo("Казань");
    }

    // ---------- Последний администратор и незавершённые шаги ----------

    @Test
    void lastAdministratorCannotBeDemotedOrDisabledUntilAnotherOneExists() {
        long alice = newUser();
        long bob = newUser();
        createCompany(alice, "Ромашка");
        long aliceMember = memberIdOf(alice);

        Resp demote = call(alice, "PUT", "/api/v1/orgs/current/members/" + aliceMember + "/admin",
                Map.of("isAdmin", false));
        assertThat(demote.status()).isEqualTo(409);
        assertThat(demote.code()).isEqualTo("LAST_ADMIN");
        Resp disable = call(alice, "POST", "/api/v1/orgs/current/members/" + aliceMember + "/disable", null);
        assertThat(disable.status()).isEqualTo(409);
        assertThat(disable.code()).isEqualTo("LAST_ADMIN");

        long bobRequest = submit(bob, inviteCode(alice));
        long bobMember = ok(call(alice, "POST", "/api/v1/orgs/current/join-requests/" + bobRequest + "/approve",
                Map.of("roleIds", List.of(), "isAdmin", true))).path("memberId").asLong();

        // теперь администраторов двое: Alice может сложить права, а последним остаётся Bob
        assertThat(call(alice, "PUT", "/api/v1/orgs/current/members/" + aliceMember + "/admin",
                Map.of("isAdmin", false)).status()).isEqualTo(200);
        Resp last = call(bob, "PUT", "/api/v1/orgs/current/members/" + bobMember + "/admin",
                Map.of("isAdmin", false));
        assertThat(last.status()).isEqualTo(409);
        assertThat(last.code()).isEqualTo("LAST_ADMIN");
        // права Alice сняты и действуют сразу
        assertThat(call(alice, "GET", "/api/v1/orgs/current/members", null).code()).isEqualTo("NOT_ADMIN");
    }

    @Test
    void removingARoleOrTheMemberReturnsTheAwaitedDocumentToItsAuthorAtOnce() {
        long alice = newUser();
        long bob = newUser();
        createCompany(alice, "Ромашка");
        Map<String, Long> roles = roleIds(alice);
        long bobRequest = submit(bob, inviteCode(alice));
        long bobMember = ok(call(alice, "POST", "/api/v1/orgs/current/join-requests/" + bobRequest + "/approve",
                Map.of("roleIds", List.of(roles.get("LAWYER"), roles.get("ACCOUNTANT"))))).path("memberId").asLong();
        long bobUserId = ok(call(bob, "GET", "/api/v1/me", null)).path("user").path("id").asLong();
        long aliceUserId = ok(call(alice, "GET", "/api/v1/me", null)).path("user").path("id").asLong();
        long lawyerStep = insertPendingStep(aliceUserId, bobUserId, roles.get("LAWYER"));
        long accountantStep = insertPendingStep(aliceUserId, bobUserId, roles.get("ACCOUNTANT"));
        String base = "/api/v1/orgs/current/members/" + bobMember;

        // снять роль можно сразу: документ, где Боб юрист, вернулся автору
        Resp removeRole = call(alice, "PUT", base + "/roles", Map.of("roleIds", List.of(roles.get("ACCOUNTANT"))));
        assertThat(removeRole.status()).as(removeRole.body()).isEqualTo(200);
        assertThat(removeRole.json().path("returnedDocuments").asInt()).as("администратор видит, что задел документ").isEqualTo(1);
        assertThat(removeRole.json().path("memberId").asLong()).as("участник в том же ответе").isEqualTo(bobMember);
        assertThat(stepState(lawyerStep)).isEqualTo("SKIPPED/MEMBER_REMOVED/RETURNED");
        assertThat(stepState(accountantStep)).as("шаг в оставшейся роли не тронут").isEqualTo("PENDING/null/IN_APPROVAL");

        // исключить тоже сразу: и этот документ возвращается автору
        Resp disabled = call(alice, "POST", base + "/disable", null);
        assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);
        assertThat(disabled.json().path("status").asText()).isEqualTo("DISABLED");
        assertThat(disabled.json().path("returnedDocuments").asInt()).isEqualTo(1);
        // повторное исключение ничего не меняет и ничего не возвращает
        assertThat(ok(call(alice, "POST", base + "/disable", null)).path("returnedDocuments").asInt()).isZero();
        assertThat(stepState(accountantStep)).isEqualTo("SKIPPED/MEMBER_REMOVED/RETURNED");
        assertThat(ok(call(bob, "GET", "/api/v1/me", null)).path("membership").isNull()).isTrue();
    }

    private String stepState(long stepId) {
        return jdbc.queryForObject("select s.decision || '/' || coalesce(s.auto_reason, 'null') || '/' || d.status"
                + " from approval_step s join document d on d.id = s.document_id where s.id = ?", String.class, stepId);
    }

    @Test
    void administratorChangesOwnRoles() {
        // единственный администратор раньше не мог взять себе даже вторую роль
        long alice = newUser();
        createCompany(alice, "Ромашка");
        Map<String, Long> roles = roleIds(alice);

        Resp response = call(alice, "PUT", "/api/v1/orgs/current/members/" + memberIdOf(alice) + "/roles",
                Map.of("roleIds", List.of(roles.get("DIRECTOR"), roles.get("LAWYER"))));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    /** Название компании меняет только администратор; пустое — 400. */
    @Test
    void administratorRenamesTheCompany() {
        long alice = newUser();
        long bob = newUser();
        createCompany(alice, "Ромашкка");
        long bobRequest = submit(bob, inviteCode(alice));
        ok(call(alice, "POST", "/api/v1/orgs/current/join-requests/" + bobRequest + "/approve",
                Map.of("roleIds", List.of(roleIds(alice).get("LAWYER")))));

        assertThat(call(bob, "PUT", "/api/v1/orgs/current/name", Map.of("name", "Чужое")).status()).isEqualTo(403);
        assertThat(call(alice, "PUT", "/api/v1/orgs/current/name", Map.of("name", "  ")).status()).isEqualTo(400);
        assertThat(ok(call(alice, "PUT", "/api/v1/orgs/current/name", Map.of("name", " ООО «Ромашка» "))).path("name").asText())
                .isEqualTo("ООО «Ромашка»");
        assertThat(ok(call(bob, "GET", "/api/v1/me", null)).path("membership").path("orgName").asText())
                .as("сотрудник видит новое название сразу").isEqualTo("ООО «Ромашка»");
    }

    // ---------- Код компании ----------

    @Test
    void regeneratedCodeRevokesTheOldOneButKeepsPendingRequests() {
        long alice = newUser();
        long bob = newUser();
        long carol = newUser();
        createCompany(alice, "Ромашка");
        String oldCode = inviteCode(alice);
        long bobRequest = submit(bob, oldCode);

        JsonNode regenerated = ok(call(alice, "POST", "/api/v1/orgs/current/invite-code/regenerate", null));
        String newCode = regenerated.path("code").asText();
        assertThat(newCode).isNotEqualTo(oldCode).matches("[A-Z2-9]{8}");
        assertThat(inviteCode(alice)).isEqualTo(newCode);

        Resp oldOne = call(carol, "POST", "/api/v1/join-requests", Map.of("code", oldCode));
        assertThat(oldOne.status()).isEqualTo(404);
        assertThat(oldOne.code()).isEqualTo("INVITE_NOT_FOUND");
        assertThat(call(carol, "POST", "/api/v1/join-requests", Map.of("code", newCode)).status()).isEqualTo(201);

        // заявка, поданная по старому коду, осталась на рассмотрении
        JsonNode pending = ok(call(alice, "GET", "/api/v1/orgs/current/join-requests", null));
        assertThat(pending).extracting(node -> node.path("id").asLong()).contains(bobRequest);
        assertThat(jdbc.queryForObject("select count(*) from invite where org_id = "
                + "(select org_id from organization_member limit 1) and kind = 'ORG_CODE' and revoked_at is null",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void wrongCodesAreLimitedPerUserAndNotForEveryone() {
        long alice = newUser();
        long mallory = newUser();
        long bob = newUser();
        createCompany(alice, "Ромашка");
        String code = inviteCode(alice);

        for (int i = 0; i < 3; i++) {
            Resp miss = call(mallory, "GET", "/api/v1/invites/code/ZZZZ222" + i, null);
            assertThat(miss.status()).isEqualTo(404);
            assertThat(miss.code()).isEqualTo("INVITE_NOT_FOUND");
        }
        // лимит исчерпан: даже верный код теперь не проверить, иначе перебор продолжился бы
        Resp blocked = call(mallory, "GET", "/api/v1/invites/code/" + code, null);
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.code()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(blocked.json().path("details").path("retryAfterSeconds").asLong()).isBetween(1L, 3_601L);
        assertThat(call(mallory, "POST", "/api/v1/join-requests", Map.of("code", code)).status()).isEqualTo(429);

        // другой пользователь лимитом не задет
        assertThat(call(bob, "GET", "/api/v1/invites/code/" + code, null).status()).isEqualTo(200);
    }

    // ---------- Личная ссылка ----------

    @Test
    void personalLinkIsSingleUseNeverGrantsAdminAndCanBeRevokedOrExpire() {
        long alice = newUser();
        long bob = newUser();
        long carol = newUser();
        createCompany(alice, "Ромашка");
        Map<String, Long> roles = roleIds(alice);

        // роли выбираются при создании ссылки; пустой набор и чужая роль недопустимы
        assertThat(call(alice, "POST", "/api/v1/orgs/current/personal-invites", Map.of("roleIds", List.of()))
                .status()).isEqualTo(400);
        assertThat(call(alice, "POST", "/api/v1/orgs/current/personal-invites", Map.of("roleIds", List.of(999_999)))
                .status()).isEqualTo(404);

        JsonNode invite = ok(call(alice, "POST", "/api/v1/orgs/current/personal-invites",
                Map.of("roleIds", List.of(roles.get("LAWYER")))), 201);
        String token = tokenOf(invite.path("link").asText());
        assertThat(token.length()).isGreaterThanOrEqualTo(22);
        long inviteId = invite.path("id").asLong();
        assertThat(ok(call(alice, "GET", "/api/v1/orgs/current/personal-invites", null))).hasSize(1);

        // открыть ссылку — ничего не происходит, но человек видит компанию и роли
        JsonNode preview = ok(call(bob, "GET", "/api/v1/invites/personal/" + token, null));
        assertThat(preview.path("orgName").asText()).isEqualTo("Ромашка");
        assertThat(codes(preview.path("roles"))).containsExactly("LAWYER");
        assertThat(ok(call(bob, "GET", "/api/v1/me", null)).path("membership").isNull()).isTrue();

        // вступление: ответ — то же, что GET /me; права администратора не выданы
        JsonNode joined = ok(call(bob, "POST", "/api/v1/invites/personal/" + token + "/accept", null));
        assertThat(joined.path("membership").path("orgName").asText()).isEqualTo("Ромашка");
        assertThat(joined.path("membership").path("isAdmin").asBoolean()).isFalse();
        assertThat(codes(joined.path("membership").path("roles"))).containsExactly("LAWYER");
        assertThat(jdbc.queryForObject("select used_by is not null from invite where id = ?",
                Boolean.class, inviteId)).isTrue();
        assertThat(ok(call(alice, "GET", "/api/v1/orgs/current/personal-invites", null))).isEmpty();

        // вторая попытка — ссылка использована; отзыву использованная ссылка не подлежит
        Resp second = call(carol, "POST", "/api/v1/invites/personal/" + token + "/accept", null);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("INVITE_USED");
        assertThat(call(carol, "GET", "/api/v1/invites/personal/" + token, null).code()).isEqualTo("INVITE_USED");
        assertThat(call(alice, "DELETE", "/api/v1/orgs/current/personal-invites/" + inviteId, null).code())
                .isEqualTo("INVITE_USED");

        // отозванная ссылка
        JsonNode second2 = ok(call(alice, "POST", "/api/v1/orgs/current/personal-invites",
                Map.of("roleIds", List.of(roles.get("HR")))), 201);
        String revokedToken = tokenOf(second2.path("link").asText());
        assertThat(call(alice, "DELETE", "/api/v1/orgs/current/personal-invites/" + second2.path("id").asLong(), null)
                .status()).isEqualTo(204);
        Resp revoked = call(carol, "POST", "/api/v1/invites/personal/" + revokedToken + "/accept", null);
        assertThat(revoked.status()).isEqualTo(409);
        assertThat(revoked.code()).isEqualTo("INVITE_REVOKED");

        // просроченная ссылка
        JsonNode third = ok(call(alice, "POST", "/api/v1/orgs/current/personal-invites",
                Map.of("roleIds", List.of(roles.get("HR")))), 201);
        jdbc.update("update invite set created_at = now() - interval '5 days',"
                + " expires_at = now() - interval '1 day' where id = ?", third.path("id").asLong());
        Resp expired = call(carol, "POST", "/api/v1/invites/personal/" + tokenOf(third.path("link").asText())
                + "/accept", null);
        assertThat(expired.status()).isEqualTo(409);
        assertThat(expired.code()).isEqualTo("INVITE_EXPIRED");

        // неизвестный токен
        assertThat(call(carol, "GET", "/api/v1/invites/personal/no-such-token", null).code())
                .isEqualTo("INVITE_NOT_FOUND");
    }

    @Test
    void failedJoinKeepsTheLinkUnusedForMembersAndForApplicantsWithPendingRequest() {
        long alice = newUser();
        long bob = newUser();
        long carol = newUser();
        createCompany(alice, "Ромашка");
        createCompany(bob, "Одуванчик");
        Map<String, Long> roles = roleIds(alice);
        JsonNode invite = ok(call(alice, "POST", "/api/v1/orgs/current/personal-invites",
                Map.of("roleIds", List.of(roles.get("LAWYER")))), 201);
        String token = tokenOf(invite.path("link").asText());

        // Bob уже состоит в компании: вступить нельзя, а ссылка остаётся свободной
        Resp member = call(bob, "POST", "/api/v1/invites/personal/" + token + "/accept", null);
        assertThat(member.status()).isEqualTo(409);
        assertThat(member.code()).isEqualTo("ALREADY_MEMBER");

        // у Carol заявка на рассмотрении в другой компании
        submit(carol, inviteCode(bob));
        Resp pending = call(carol, "POST", "/api/v1/invites/personal/" + token + "/accept", null);
        assertThat(pending.status()).isEqualTo(409);
        assertThat(pending.code()).isEqualTo("JOIN_REQUEST_EXISTS");

        assertThat(jdbc.queryForObject("select used_at is null and used_by is null from invite where id = ?",
                Boolean.class, invite.path("id").asLong())).isTrue();
    }

    @Test
    void ofSeveralSimultaneousAttemptsOnOnePersonalLinkExactlyOnePersonJoins() throws Exception {
        long alice = newUser();
        createCompany(alice, "Ромашка");
        JsonNode invite = ok(call(alice, "POST", "/api/v1/orgs/current/personal-invites",
                Map.of("roleIds", List.of(roleIds(alice).get("LAWYER")))), 201);
        String token = tokenOf(invite.path("link").asText());

        int attempts = 8;
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            users.add(newUser());
        }
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        for (long user : users) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return call(user, "POST", "/api/v1/invites/personal/" + token + "/accept", null);
            }));
        }
        ready.await();
        go.countDown();
        List<Resp> results = new ArrayList<>();
        for (Future<Resp> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results.stream().filter(r -> r.status() == 200).count())
                .as(results.toString()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() == 409 && "INVITE_USED".equals(r.code())).count())
                .isEqualTo(attempts - 1);
        assertThat(jdbc.queryForObject("select count(*) from organization_member m join app_user u on u.id = m.user_id"
                + " where u.max_user_id <> ?", Integer.class, alice)).isEqualTo(1);
    }

    // ---------- Демо-режим и кто действует ----------

    @Test
    void demoCompanyCodeCannotBeUsedAndCompanyIsCreatedForTheRealPersonNotTheDemoActor() {
        long reviewer = newUser();
        long stranger = newUser();
        JsonNode sandbox = ok(call(reviewer, "POST", "/api/v1/demo/sandbox", null));
        long demoAdmin = 0;
        for (JsonNode user : sandbox.path("users")) {
            if (user.path("isAdmin").asBoolean()) {
                demoAdmin = user.path("user").path("id").asLong();
            }
        }
        assertThat(demoAdmin).isPositive();

        // администратор песочницы видит код, но по нему вступить нельзя: демо-компания закрыта
        Resp code = call(reviewer, "GET", "/api/v1/orgs/current/invite-code", null,
                "X-Demo-Act-As", Long.toString(demoAdmin));
        assertThat(code.status()).as(code.body()).isEqualTo(200);
        Resp join = call(stranger, "POST", "/api/v1/join-requests", Map.of("code", code.json().path("code").asText()));
        assertThat(join.status()).isEqualTo(404);
        assertThat(join.code()).isEqualTo("INVITE_NOT_FOUND");

        // «создать компанию» от имени демо-участника создаёт её для реального человека
        Resp created = call(reviewer, "POST", "/api/v1/orgs", Map.of("name", "Настоящая"),
                "X-Demo-Act-As", Long.toString(demoAdmin));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        JsonNode me = ok(call(reviewer, "GET", "/api/v1/me", null));
        assertThat(me.path("membership").path("orgName").asText()).isEqualTo("Настоящая");
        assertThat(me.path("membership").path("isAdmin").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from organization where is_demo = false", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void customRolesCanBeAddedAndRenamedAndCarriersAreListedForMembersOnly() {
        long alice = newUser();
        long bob = newUser();
        createCompany(alice, "Ромашка");
        Map<String, Long> roles = roleIds(alice);
        long bobRequest = submit(bob, inviteCode(alice));
        ok(call(alice, "POST", "/api/v1/orgs/current/join-requests/" + bobRequest + "/approve",
                Map.of("roleIds", List.of(roles.get("LAWYER")))));

        Resp created = call(alice, "POST", "/api/v1/orgs/current/roles", Map.of("name", "Секретарь"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(call(alice, "POST", "/api/v1/orgs/current/roles", Map.of("name", " секретарь ")).code())
                .isEqualTo("VALIDATION_FAILED");
        long roleId = created.json().path("id").asLong();
        Resp renamed = call(alice, "PATCH", "/api/v1/orgs/current/roles/" + roleId, Map.of("name", "Помощник"));
        assertThat(renamed.json().path("name").asText()).isEqualTo("Помощник");

        // обычный участник роли не создаёт, но видит носителей роли
        assertThat(call(bob, "POST", "/api/v1/orgs/current/roles", Map.of("name", "Взлом")).code())
                .isEqualTo("NOT_ADMIN");
        JsonNode carriers = ok(call(bob, "GET", "/api/v1/orgs/current/roles/" + roles.get("LAWYER") + "/members", null));
        assertThat(carriers).hasSize(1);
        assertThat(carriers.get(0).path("id").asLong())
                .isEqualTo(ok(call(bob, "GET", "/api/v1/me", null)).path("user").path("id").asLong());
        // у только что созданной роли носителей ещё нет
        assertThat(ok(call(bob, "GET", "/api/v1/orgs/current/roles/" + roleId + "/members", null))).isEmpty();
    }

    // ---------- Маршруты по умолчанию: добор для компаний, созданных до появления типов ----------

    @Test
    void routesAreBackfilledForCompaniesCreatedBeforeDocumentTypesExisted() {
        long alice = newUser();
        long reviewer = newUser();
        try {
            // Имитируем состояние до миграции справочника, не удаляя связанные поля и правила:
            // временные коды не распознаются каталогом стандартных типов.
            jdbc.update("delete from approval_route");
            hideDefaultTypes();
            long orgId = createCompany(alice, "Ромашка");
            assertThat(roleIds(alice)).hasSize(5);
            assertThat(countRoutes(orgId)).isZero();

            // типы появились (их создаёт миграция): при следующем старте компания получает маршруты
            showDefaultTypes();
            routeBackfillRunner.run(null);
            assertThat(countRoutes(orgId)).isEqualTo(11);
            // повторный запуск ничего не дублирует
            assertThat(routeTemplateService.backfillAllOrganizations()).isZero();
            assertThat(countRoutes(orgId)).isEqualTo(11);

            // У демо-песочницы уже есть специальный маршрут из Issue; backfill его не меняет.
            long sandboxOrgId = ok(call(reviewer, "POST", "/api/v1/demo/sandbox", null))
                    .path("orgId").asLong();
            assertThat(countRoutes(sandboxOrgId)).isEqualTo(3);
            routeTemplateService.backfillAllOrganizations();
            assertThat(jdbc.queryForObject("select count(*) from approval_route r"
                    + " join organization o on o.id = r.org_id where o.is_demo", Integer.class)).isEqualTo(3);
        } finally {
            showDefaultTypes();
        }
    }

    // ---------- Гонки: блокировку удерживает отдельная транзакция, тест смотрит, кто ждёт ----------

    @Test
    void acceptThatWinsTheRaceAgainstRevokeKeepsTheLinkUsedAndTheMemberJoined() throws Exception {
        LinkRace race = raceAcceptAndRevoke(true);

        assertThat(race.accept().status()).as(race.accept().body()).isEqualTo(200);
        assertThat(race.revoke().status()).as(race.revoke().body()).isEqualTo(409);
        assertThat(race.revoke().code()).isEqualTo("INVITE_USED");
        // ссылка использована и НЕ выглядит отозванной, участник вступил
        assertThat(linkState(race.inviteId())).containsEntry("used", true).containsEntry("revoked", false);
        assertThat(joinedFromLink(race.joiner())).isEqualTo(1);
    }

    @Test
    void revokeThatWinsTheRaceAgainstAcceptLeavesTheLinkRevokedAndNobodyJoined() throws Exception {
        LinkRace race = raceAcceptAndRevoke(false);

        assertThat(race.revoke().status()).as(race.revoke().body()).isEqualTo(204);
        assertThat(race.accept().status()).as(race.accept().body()).isEqualTo(409);
        assertThat(race.accept().code()).isEqualTo("INVITE_REVOKED");
        assertThat(linkState(race.inviteId())).containsEntry("used", false).containsEntry("revoked", true);
        assertThat(joinedFromLink(race.joiner())).isZero();
    }

    @Test
    void joinRequestWaitsForCodeReissueAndIsRejectedWhenTheCodeWasRevokedMeanwhile() throws Exception {
        long alice = newUser();
        long bob = newUser();
        long orgId = createCompany(alice, "Ромашка");
        String oldCode = inviteCode(alice);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection reissue = holdLock("select id from organization where id = ? for update", orgId)) {
            Future<Resp> submit = pool.submit(() -> call(bob, "POST", "/api/v1/join-requests", Map.of("code", oldCode)));
            waitForBlockedSessions(1);
            Thread.sleep(300);
            assertThat(submit.isDone()).as("подача заявки должна ждать блокировку компании").isFalse();

            // перевыпуск кода, как в InviteService.regenerate: отозвать старый и выпустить новый
            execute(reissue, "update invite set revoked_at = now() where org_id = ? and kind = 'ORG_CODE'"
                    + " and revoked_at is null", orgId);
            execute(reissue, "insert into invite(org_id, kind, code, created_by, created_at)"
                    + " values (?, 'ORG_CODE', 'NEWCODE2', (select created_by from organization where id = ?), now())",
                    orgId, orgId);
            reissue.commit();

            Resp response = submit.get(20, TimeUnit.SECONDS);
            assertThat(response.status()).as(response.body()).isEqualTo(404);
            assertThat(response.code()).isEqualTo("INVITE_NOT_FOUND");
        } finally {
            pool.shutdownNow();
        }
        // отозванный код не создал заявку
        assertThat(jdbc.queryForObject("select count(*) from join_request", Integer.class)).isZero();
    }

    @Test
    void joinRequestProceedsWhenTheCompanyLockIsReleasedWithoutReissue() throws Exception {
        long alice = newUser();
        long bob = newUser();
        long orgId = createCompany(alice, "Ромашка");
        String code = inviteCode(alice);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection reissue = holdLock("select id from organization where id = ? for update", orgId)) {
            Future<Resp> submit = pool.submit(() -> call(bob, "POST", "/api/v1/join-requests", Map.of("code", code)));
            waitForBlockedSessions(1);
            reissue.rollback();

            Resp response = submit.get(20, TimeUnit.SECONDS);
            assertThat(response.status()).as(response.body()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- Вспомогательное ----------

    private record LinkRace(Resp accept, Resp revoke, long inviteId, long joiner) {
    }

    /**
     * Вступление и отзыв одной личной ссылки в порядке очереди на блокировку строки: транзакция теста удерживает
     * строку ссылки, обе операции встают в очередь, затем блокировка снимается. Кто встал первым, тот и выиграл.
     */
    private LinkRace raceAcceptAndRevoke(boolean acceptQueuesFirst) throws Exception {
        long alice = newUser();
        long bob = newUser();
        createCompany(alice, "Ромашка");
        JsonNode invite = ok(call(alice, "POST", "/api/v1/orgs/current/personal-invites",
                Map.of("roleIds", List.of(roleIds(alice).get("LAWYER")))), 201);
        long inviteId = invite.path("id").asLong();
        String token = tokenOf(invite.path("link").asText());
        java.util.concurrent.Callable<Resp> accept = () ->
                call(bob, "POST", "/api/v1/invites/personal/" + token + "/accept", null);
        java.util.concurrent.Callable<Resp> revoke = () ->
                call(alice, "DELETE", "/api/v1/orgs/current/personal-invites/" + inviteId, null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = holdLock("select id from invite where id = ? for update", inviteId)) {
            Future<Resp> first = pool.submit(acceptQueuesFirst ? accept : revoke);
            waitForBlockedSessions(1);
            Future<Resp> second = pool.submit(acceptQueuesFirst ? revoke : accept);
            waitForBlockedSessions(2);
            holder.commit();
            Resp acceptResponse = (acceptQueuesFirst ? first : second).get(20, TimeUnit.SECONDS);
            Resp revokeResponse = (acceptQueuesFirst ? second : first).get(20, TimeUnit.SECONDS);
            return new LinkRace(acceptResponse, revokeResponse, inviteId, bob);
        } finally {
            pool.shutdownNow();
        }
    }

    private Map<String, Object> linkState(long inviteId) {
        return jdbc.queryForMap("select used_at is not null as used, revoked_at is not null as revoked"
                + " from invite where id = ?", inviteId);
    }

    private int joinedFromLink(long maxUserId) {
        return jdbc.queryForObject("select count(*) from organization_member m join app_user u on u.id = m.user_id"
                + " where u.max_user_id = ?", Integer.class, maxUserId);
    }

    private int countRoutes(long orgId) {
        return jdbc.queryForObject("select count(*) from approval_route where org_id = ?", Integer.class, orgId);
    }

    private void hideDefaultTypes() {
        jdbc.update("""
                update document_type set code = 'TEST_HIDDEN_' || code
                where code in ('OFFICIAL_MEMO', 'VACATION_REQUEST', 'SUPPORT_MEASURE_REQUEST', 'BUSINESS_TRIP_REQUEST', 'GENERIC')
                """);
    }

    private void showDefaultTypes() {
        jdbc.update("""
                update document_type set code = replace(code, 'TEST_HIDDEN_', '')
                where code like 'TEST_HIDDEN_%'
                """);
    }

    /** Открывает отдельную транзакцию и удерживает блокировку, пока её не завершат commit или rollback. */
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

    private void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    /** Ждёт, пока в базе будет не меньше {@code expected} сессий, ожидающих блокировку. */
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

    private long newUser() {
        return MAX_IDS.incrementAndGet();
    }

    private long createCompany(long user, String name) {
        Resp response = call(user, "POST", "/api/v1/orgs", Map.of("name", name));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json().path("id").asLong();
    }

    private String inviteCode(long admin) {
        return ok(call(admin, "GET", "/api/v1/orgs/current/invite-code", null)).path("code").asText();
    }

    private long submit(long user, String code) {
        Resp response = call(user, "POST", "/api/v1/join-requests", Map.of("code", code));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json().path("id").asLong();
    }

    private Map<String, Long> roleIds(long member) {
        Map<String, Long> ids = new HashMap<>();
        for (JsonNode role : ok(call(member, "GET", "/api/v1/orgs/current/roles", null))) {
            ids.put(role.path("code").asText(), role.path("id").asLong());
        }
        return ids;
    }

    private long memberIdOf(long admin) {
        JsonNode members = ok(call(admin, "GET", "/api/v1/orgs/current/members", null));
        long userId = ok(call(admin, "GET", "/api/v1/me", null)).path("user").path("id").asLong();
        for (JsonNode member : members) {
            if (member.path("user").path("id").asLong() == userId) {
                return member.path("memberId").asLong();
            }
        }
        throw new IllegalStateException("Участник не найден");
    }

    private long insertPendingStep(long authorUserId, long approverUserId, long roleId) {
        long typeId = jdbc.queryForObject("select id from document_type where code = 'GENERIC'", Long.class);
        long orgId = jdbc.queryForObject("select org_id from organization_member where user_id = ?",
                Long.class, approverUserId);
        Long documentId = jdbc.queryForObject("""
                insert into document(document_type_id, author_id, org_id, title, status, current_stage,
                    current_version_no, visibility, created_at, updated_at)
                values (?, ?, ?, 'Тест', 'IN_APPROVAL', 1, 1, 'PRIVATE', now(), now()) returning id
                """, Long.class, typeId, authorUserId, orgId);
        jdbc.update("insert into document_version(document_id, version_no, contains_sensitive, created_by, created_at)"
                + " values (?, 1, false, ?, now())", documentId, authorUserId);
        return jdbc.queryForObject("""
                insert into approval_step(document_id, version_no, approver_id, role_id, stage_order, origin,
                    decision, created_at, activated_at)
                values (?, 1, ?, ?, 1, 'TEMPLATE', 'PENDING', now(), now()) returning id
                """, Long.class, documentId, approverUserId, roleId);
    }

    private static String tokenOf(String link) {
        return link.substring(link.indexOf("startapp=p_") + "startapp=p_".length());
    }

    private static List<String> codes(JsonNode roles) {
        List<String> result = new ArrayList<>();
        roles.forEach(role -> result.add(role.path("code").asText()));
        return result;
    }

    private JsonNode ok(Resp response) {
        return ok(response, 200);
    }

    private JsonNode ok(Resp response, int status) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        return response.json();
    }

    private Resp call(long maxUserId, String method, String path, Object body, String... headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Dev " + maxUserId);
            for (int i = 0; i < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(response.statusCode(), response.body());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

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
}
