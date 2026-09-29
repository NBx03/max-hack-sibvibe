package ru.sibvibe.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "SCHEMA_TEST_URL", matches = ".+")
class SecurityIntegrationTest {

    private static final String OWNER_ONE = "910000001";
    private static final String OWNER_TWO = "910000002";
    private static final String TEST_TOKEN = "integration-test-token";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("SCHEMA_TEST_URL"));
        registry.add("spring.datasource.username", () -> "schema_test");
        registry.add("spring.datasource.password", () -> System.getenv("SCHEMA_TEST_PASSWORD"));
        registry.add("app.demo-mode", () -> "true");
        registry.add("max.bot.token", () -> TEST_TOKEN);
        // Тестовый токен нужен только для проверки initData - без этого флага бэкенд слал бы
        // настоящие HTTP-запросы на platform-api2.max.ru при каждом уведомлении (BotConfiguration).
        registry.add("max.bot.enabled", () -> "false");
        registry.add("minio.endpoint", () -> "http://127.0.0.1:9000");
        registry.add("minio.access-key", () -> "security-test-access-key");
        registry.add("minio.secret-key", () -> "security-test-secret-key");
        registry.add("spring.autoconfigure.exclude",
                () -> "chat.giga.springai.autoconfigure.GigaChatAutoConfiguration");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanDatabase() {
        for (String table : List.of("approval_step", "document_file", "document_version", "document",
                "approval_route", "invite_role", "join_request", "invite", "member_role",
                "organization_member", "role", "organization", "app_user")) {
            jdbc.update("delete from " + table);
        }
    }

    @Test
    void onboardingAndPersonalSandboxesStayIsolatedAcrossRequests() throws Exception {
        JsonNode firstMe = json(sendAuthorized("GET", "/api/v1/me", signedAuthorization(), null));
        assertThat(firstMe.path("membership").isNull()).isTrue();
        assertThat(firstMe.path("sandbox").isNull()).isTrue();
        assertThat(firstMe.path("actingAs").isNull()).isTrue();
        assertThat(firstMe.path("demoMode").asBoolean()).isTrue();
        assertThat(firstMe.path("startParam").asText()).isEqualTo("d_123");

        JsonNode firstSandbox = json(post("/api/v1/demo/sandbox", OWNER_ONE, null));
        JsonNode secondSandbox = json(post("/api/v1/demo/sandbox", OWNER_TWO, null));
        assertThat(firstSandbox.path("users").size()).isEqualTo(5);
        assertThat(secondSandbox.path("users").size()).isEqualTo(5);
        assertDemoContent(firstSandbox.path("orgId").asLong());
        assertDemoContent(secondSandbox.path("orgId").asLong());
        long firstActor = firstSandbox.path("users").get(0).path("user").path("id").asLong();
        long secondActor = secondSandbox.path("users").get(0).path("user").path("id").asLong();
        assertThat(firstActor).isNotEqualTo(secondActor);

        JsonNode demoMetrics = json(get("/api/v1/orgs/current/metrics", OWNER_ONE, Long.toString(firstActor)));
        assertThat(demoMetrics.path("documentsCount").asLong()).isEqualTo(4);
        assertThat(demoMetrics.path("medianApprovalHours").isNumber()).isTrue();
        assertThat(demoMetrics.path("returnRate").asDouble()).isEqualTo(0.25);

        JsonNode acting = json(get("/api/v1/me", OWNER_ONE, Long.toString(firstActor)));
        assertThat(acting.path("user").path("id").asLong()).isEqualTo(firstMe.path("user").path("id").asLong());
        assertThat(acting.path("actingAs").path("user").path("id").asLong()).isEqualTo(firstActor);
        assertThat(acting.path("membership").isNull()).isTrue();

        HttpResponse<String> foreign = get("/api/v1/me", OWNER_ONE, Long.toString(secondActor));
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(foreign.body()).contains("DEMO_ACT_AS_FORBIDDEN");

        long secondOwnerUserId = json(get("/api/v1/me", OWNER_TWO, null)).path("user").path("id").asLong();
        createRealMembership(secondOwnerUserId);
        HttpResponse<String> realUser = get("/api/v1/me", OWNER_ONE, Long.toString(secondOwnerUserId));
        assertThat(realUser.statusCode()).isEqualTo(403);

        JsonNode reset = json(post("/api/v1/demo/sandbox", OWNER_ONE, null));
        assertDemoContent(reset.path("orgId").asLong());
        Set<Long> resetIds = new HashSet<>();
        reset.path("users").forEach(user -> resetIds.add(user.path("user").path("id").asLong()));
        assertThat(resetIds).doesNotContain(firstActor);
        assertThat(get("/api/v1/me", OWNER_ONE, Long.toString(firstActor)).statusCode()).isEqualTo(403);

        JsonNode secondStillOwn = json(get("/api/v1/demo/sandbox", OWNER_TWO, null));
        assertThat(secondStillOwn.path("orgId").asLong()).isEqualTo(secondSandbox.path("orgId").asLong());
        assertThat(json(get("/api/v1/me", OWNER_ONE, null)).path("actingAs").isNull()).isTrue();
    }

    private void assertDemoContent(long orgId) {
        assertThat(jdbc.queryForObject("select count(*) from role where org_id = ?", Integer.class, orgId))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                select count(*) from organization_member
                where org_id = ? and status = 'ACTIVE' and is_demo = true
                """, Integer.class, orgId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                select count(*) from join_request
                where org_id = ? and status = 'PENDING'
                """, Integer.class, orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*)
                from app_user user_account
                where user_account.id in (
                    select member.user_id from organization_member member where member.org_id = ?
                    union
                    select request.user_id from join_request request where request.org_id = ?
                ) and user_account.max_user_id >= 0
                """, Integer.class, orgId, orgId)).isZero();
        assertThat(jdbc.queryForList("""
                select route.stage_order, role.code
                from approval_route route
                join role on role.id = route.role_id
                join document_type type on type.id = route.document_type_id
                where route.org_id = ? and type.code = 'OFFICIAL_MEMO'
                order by route.stage_order, role.code
                """, orgId))
                .containsExactly(
                        Map.of("stage_order", 1, "code", "ACCOUNTANT"),
                        Map.of("stage_order", 1, "code", "LAWYER"),
                        Map.of("stage_order", 2, "code", "DIRECTOR"));
        assertThat(jdbc.queryForList("""
                select status, count(*) as amount
                from document where org_id = ? group by status order by status
                """, orgId))
                .containsExactlyInAnyOrder(
                        Map.of("status", "DRAFT", "amount", 1L),
                        Map.of("status", "IN_APPROVAL", "amount", 1L),
                        Map.of("status", "APPROVED", "amount", 1L),
                        Map.of("status", "RETURNED", "amount", 1L),
                        Map.of("status", "REJECTED", "amount", 1L));
        assertThat(jdbc.queryForObject("""
                select count(*) from document_version version
                join document on document.id = version.document_id
                where document.org_id = ? and version.check_status = 'CHECKED'
                """, Integer.class, orgId)).isEqualTo(5);
    }

    @Test
    void allBackendErrorsUseApiErrorFormatAndPreserveStatus() throws Exception {
        HttpResponse<String> missing = sendAuthorized(
                "GET", "/api/v1/missing", signedAuthorization(), null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertApiError(missing, "NOT_FOUND");

        HttpResponse<String> wrongMethod = sendAuthorized(
                "POST", "/api/v1/me", signedAuthorization(), null);
        assertThat(wrongMethod.statusCode()).isEqualTo(405);
        assertApiError(wrongMethod, "METHOD_NOT_ALLOWED");
    }

    private void createRealMembership(long userId) {
        Long orgId = jdbc.queryForObject("""
                insert into organization(name, created_by, created_at, is_demo)
                values ('Реальная компания', ?, current_timestamp, false)
                returning id
                """, Long.class, userId);
        jdbc.update("""
                insert into organization_member(org_id, user_id, status, is_admin, joined_at, is_demo)
                values (?, ?, 'ACTIVE', true, current_timestamp, false)
                """, orgId, userId);
    }

    private HttpResponse<String> get(String path, String maxUserId, String actorId) throws Exception {
        return send("GET", path, maxUserId, actorId);
    }

    private HttpResponse<String> post(String path, String maxUserId, String actorId) throws Exception {
        return send("POST", path, maxUserId, actorId);
    }

    private HttpResponse<String> send(String method, String path, String maxUserId, String actorId) throws Exception {
        return sendAuthorized(method, path, "Dev " + maxUserId, actorId);
    }

    private HttpResponse<String> sendAuthorized(String method, String path, String authorization, String actorId)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authorization);
        if (actorId != null) {
            request.header("X-Demo-Act-As", actorId);
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private void assertApiError(HttpResponse<String> response, String code) throws Exception {
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("details").isObject()).isTrue();
        assertThat(body.has("timestamp")).isFalse();
        assertThat(body.has("path")).isFalse();
    }

    private String signedAuthorization() throws Exception {
        Map<String, String> values = new TreeMap<>();
        values.put("auth_date", Long.toString(Instant.now().getEpochSecond()));
        values.put("query_id", "security-integration");
        values.put("start_param", "d_123");
        values.put("user", "{\"id\":" + OWNER_ONE + ",\"first_name\":\"Integration\",\"last_name\":\"User\"}");
        String data = values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        Mac first = Mac.getInstance("HmacSHA256");
        first.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] secret = first.doFinal(TEST_TOKEN.getBytes(StandardCharsets.UTF_8));
        Mac second = Mac.getInstance("HmacSHA256");
        second.init(new SecretKeySpec(secret, "HmacSHA256"));
        String hash = HexFormat.of().formatHex(second.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        String raw = values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow() + "&hash=" + hash;
        return "MaxInitData " + raw;
    }
}
