package ru.sibvibe.approval.schema;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import ru.sibvibe.approval.ApprovalApplication;
import ru.sibvibe.approval.approval.entity.ApprovalStep;
import ru.sibvibe.approval.document.entity.DocumentVersion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Запускается только на отдельной пустой тестовой БД; инструкции в src/test/README.md. */
@EnabledIfEnvironmentVariable(named = "SCHEMA_TEST_URL", matches = ".+")
class DatabaseSchemaTest {

    @Test
    void emptyDatabaseMigratesValidatesAndRestartsWithoutReapplyingChanges() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", System.getenv("SCHEMA_TEST_URL"));
        properties.put("spring.datasource.username", "schema_test");
        properties.put("spring.datasource.password", System.getenv("SCHEMA_TEST_PASSWORD"));
        properties.put("server.port", "0");
        // Проверяется настоящий backend; внешний AI не нужен для проверки схемы.
        properties.put("spring.autoconfigure.exclude", "chat.giga.springai.autoconfigure.GigaChatAutoConfiguration");
        // Document API зависит от FileStorage; для validate достаточно корректной конфигурации клиента,
        // сетевых вызовов к MinIO этот тест не выполняет.
        properties.put("minio.endpoint", "http://127.0.0.1:9000");
        properties.put("minio.access-key", "schema-test-access-key");
        properties.put("minio.secret-key", "schema-test-secret-key");
        properties.put("spring.main.banner-mode", "off");

        Map<String, String> firstChecksums;
        try (ConfigurableApplicationContext context = start(properties)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select count(*) from databasechangelog", Integer.class)).isEqualTo(20);
            assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname = 'public'", Integer.class))
                    .isEqualTo(19);
            assertThat(context.getBeansOfType(JpaRepository.class)).hasSize(17);
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            checkReferenceData(jdbc);
            checkReferenceDataMatchesTestdata(jdbc);
            firstChecksums = checksums(jdbc);
            assertHealthy(context);
            checkMappingsAndConstraints(context);
        }
        try (ConfigurableApplicationContext context = start(properties)) {
            assertHealthy(context);
            assertThat(checksums(context.getBean(JdbcTemplate.class))).isEqualTo(firstChecksums);
            assertThat(context.getBean(JdbcTemplate.class)
                    .queryForObject("select count(*) from app_user", Integer.class)).isZero();
        }
    }

    private void checkReferenceData(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForMap("""
                select
                    count(distinct type.id) as types,
                    count(distinct field.id) as fields,
                    count(distinct rule.id) as rules
                from document_type type
                left join document_type_field field on field.document_type_id = type.id
                left join requirement_rule rule on rule.document_type_id = type.id
                """))
                .containsEntry("types", 5L)
                .containsEntry("fields", 28L)
                .containsEntry("rules", 32L);
        assertThat(jdbc.queryForList("""
                select type.code, count(distinct field.id) as fields, count(distinct rule.id) as rules
                from document_type type
                left join document_type_field field on field.document_type_id = type.id
                left join requirement_rule rule on rule.document_type_id = type.id
                group by type.code
                order by type.code
                """))
                .containsExactly(
                        Map.of("code", "BUSINESS_TRIP_REQUEST", "fields", 5L, "rules", 6L),
                        Map.of("code", "GENERIC", "fields", 6L, "rules", 5L),
                        Map.of("code", "OFFICIAL_MEMO", "fields", 8L, "rules", 10L),
                        Map.of("code", "SUPPORT_MEASURE_REQUEST", "fields", 4L, "rules", 6L),
                        Map.of("code", "VACATION_REQUEST", "fields", 5L, "rules", 5L));
        assertThat(jdbc.queryForMap("""
                select check_type, expected, kind, severity, source_ref
                from requirement_rule rule
                join document_type type on type.id = rule.document_type_id
                where type.code = 'SUPPORT_MEASURE_REQUEST'
                  and rule.field_name = 'smb_category'
                  and rule.check_type = 'ONE_OF'
                """))
                .containsEntry("check_type", "ONE_OF")
                .containsEntry("expected", "[\"микропредприятие\",\"малое предприятие\",\"среднее предприятие\"]")
                .containsEntry("kind", "LEGAL")
                .containsEntry("severity", "BLOCKER")
                .containsEntry("source_ref", "ст. 4.1, ч. 3, п. 5");
    }

    /**
     * Справочник в базе построчно совпадает с testdata/rules.json — источником RULES.md и тестов правил.
     */
    private void checkReferenceDataMatchesTestdata(JdbcTemplate jdbc) throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("..", "testdata", "rules.json").toFile());
        String checkedAt = root.get("sourceCheckedAt").asText();
        List<String> expectedTypes = new ArrayList<>();
        List<String> expectedFields = new ArrayList<>();
        List<String> expectedRules = new ArrayList<>();
        for (JsonNode type : root.get("types")) {
            String code = type.get("code").asText();
            expectedTypes.add(code + " | " + type.get("name").asText());
            for (JsonNode field : type.get("fields")) {
                expectedFields.add(String.join(" | ", code, field.get("name").asText(), field.get("type").asText(),
                        field.get("label").asText(), text(field, "hint")));
            }
            for (JsonNode rule : type.get("rules")) {
                JsonNode source = root.get("sources").get(rule.get("source").asText());
                expectedRules.add(String.join(" | ", code, rule.get("field").asText(), rule.get("check").asText(),
                        text(rule, "expected"), rule.get("kind").asText(), rule.get("severity").asText(),
                        text(source, "title"), text(source, "url"), text(rule, "sourceRef"),
                        rule.get("description").asText(), checkedAt));
            }
        }
        assertThat(jdbc.queryForList("select code || ' | ' || name from document_type", String.class))
                .containsExactlyInAnyOrderElementsOf(expectedTypes);
        // Порядок полей внутри типа — как в rules.json: в нём же их показывает экран проверки.
        // Сортировка потока устойчива, поэтому внутри типа порядок rules.json сохраняется.
        assertThat(jdbc.queryForList("""
                select concat_ws(' | ', type.code, field.field_name, field.field_type, field.label,
                                 coalesce(field.hint, '<null>'))
                from document_type_field field
                join document_type type on type.id = field.document_type_id
                order by type.code, field.position, field.id
                """, String.class))
                .containsExactlyElementsOf(expectedFields.stream()
                        .sorted(Comparator.comparing(line -> line.substring(0, line.indexOf(" | "))))
                        .toList());
        assertThat(jdbc.queryForList("""
                select concat_ws(' | ', type.code, rule.field_name, rule.check_type, coalesce(rule.expected, '<null>'),
                                 rule.kind, rule.severity, coalesce(rule.source_title, '<null>'),
                                 coalesce(rule.source_url, '<null>'), coalesce(rule.source_ref, '<null>'),
                                 rule.description, rule.source_checked_at::date::text)
                from requirement_rule rule
                join document_type type on type.id = rule.document_type_id
                """, String.class))
                .containsExactlyInAnyOrderElementsOf(expectedRules);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? "<null>" : value.asText();
    }

    private ConfigurableApplicationContext start(Map<String, Object> properties) {
        return new SpringApplicationBuilder(ApprovalApplication.class)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("schema-test", properties)))
                .run("--logging.level.root=INFO", "--logging.level.org.springframework=INFO",
                        "--logging.level.org.hibernate=INFO", "--logging.level.com.zaxxer.hikari=INFO",
                        "--spring.main.banner-mode=off");
    }

    private void assertHealthy(ConfigurableApplicationContext context) throws Exception {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        }
    }

    private Map<String, String> checksums(JdbcTemplate jdbc) {
        Map<String, String> result = new HashMap<>();
        jdbc.query("select id, md5sum from databasechangelog", row -> {
            result.put(row.getString("id"), row.getString("md5sum"));
        });
        return result;
    }

    private void checkMappingsAndConstraints(ConfigurableApplicationContext context) {
        EntityManagerFactory factory = context.getBean(EntityManagerFactory.class);
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            try {
                em.unwrap(Session.class).doWork(connection ->
                        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema-fixture.sql")));
                assertThat(factory.getMetamodel().getEntities()).hasSize(17);
                // Чтение каждой сущности проверяет не только DDL, но и enum/JSON/составные ключи.
                for (var entity : factory.getMetamodel().getEntities()) {
                    assertThat(em.createQuery("select e from " + entity.getName() + " e", entity.getJavaType())
                            .getResultList()).isNotEmpty();
                }
                DocumentVersion version = em.find(DocumentVersion.class, 1L);
                assertThat(version.getContent().get("test").asText()).isEqualTo("значение");
                assertThat(version.getCheckStatus()).isEqualTo("CHECKED");
                assertThat(em.find(ApprovalStep.class, 1L).getActivatedAt()).isNotNull();
                version.setContent(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("test", "новое"));
                em.flush();
                em.clear();
                assertThat(em.find(DocumentVersion.class, 1L).getContent().get("test").asText()).isEqualTo("новое");
                em.unwrap(Session.class).doWork(this::checkConstraints);
            } finally {
                em.getTransaction().rollback();
            }
        }
    }

    private void checkConstraints(Connection connection) throws SQLException {
        rejected(connection, "23505", "INSERT INTO app_user VALUES (10, 1, 'Тест', CURRENT_TIMESTAMP)");
        rejected(connection, "23505", "INSERT INTO organization_member VALUES (10, 3, 1, 'ACTIVE', false, CURRENT_TIMESTAMP, false)");
        // Ложный is_demo не может обойти ограничение активного членства.
        rejected(connection, "23503", "INSERT INTO organization_member VALUES (10, 3, 1, 'ACTIVE', false, CURRENT_TIMESTAMP, true)");
        accepted(connection, "INSERT INTO organization_member VALUES (10, 3, 1, 'DISABLED', false, CURRENT_TIMESTAMP, false)");
        rejected(connection, "23505", "INSERT INTO organization_member VALUES (11, 1, 1, 'DISABLED', false, CURRENT_TIMESTAMP, false)");
        rejected(connection, "23505", "INSERT INTO join_request(id,org_id,user_id,invite_id,status,created_at) VALUES (10,3,2,2,'PENDING',CURRENT_TIMESTAMP)");
        accepted(connection, "INSERT INTO join_request(id,org_id,user_id,invite_id,status,created_at) VALUES (10,3,2,2,'REJECTED',CURRENT_TIMESTAMP)");
        rejected(connection, "23503", "INSERT INTO join_request(id,org_id,user_id,invite_id,status,created_at) VALUES (11,3,2,1,'REJECTED',CURRENT_TIMESTAMP)");
        rejected(connection, "23505", "INSERT INTO invite(id,org_id,kind,code,created_by,created_at) VALUES (10,1,'ORG_CODE','TEST0010',1,CURRENT_TIMESTAMP)");
        accepted(connection, "INSERT INTO invite(id,org_id,kind,code,created_by,created_at,revoked_at) VALUES (10,1,'ORG_CODE','TEST0010',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        rejected(connection, "23514", "UPDATE document SET status = 'UNKNOWN' WHERE id = 1");
        // Часовой пояс компании — только пояса России; существующим компаниям — Москва.
        rejected(connection, "23514", "UPDATE organization SET time_zone = 'Europe/London' WHERE id = 1");
        accepted(connection, "UPDATE organization SET time_zone = 'Asia/Novosibirsk', city = 'Новосибирск' WHERE id = 1");
        rejected(connection, "23505", "INSERT INTO document_version(id,document_id,version_no,contains_sensitive,created_by,created_at) VALUES (10,1,1,false,1,CURRENT_TIMESTAMP)");
        accepted(connection, "UPDATE document_version SET check_status='FAILED' WHERE id=1");
        accepted(connection, "UPDATE document_version SET check_status=NULL WHERE id=1");
        accepted(connection, "UPDATE document_version SET ai_summary='Краткая сводка' WHERE id=1");
        rejected(connection, "23514", "UPDATE document_version SET check_status='UNKNOWN' WHERE id=1");
        rejected(connection, "23505", file(10, 1, "MAIN", 1));
        rejected(connection, "23503", file(10, 999, "ATTACHMENT", 1));
        accepted(connection, file(10, 1, "ATTACHMENT", 1));
        rejected(connection, "23505", file(11, 1, "ATTACHMENT", 1));
        rejected(connection, "23514", "UPDATE document_file SET file_size = -1 WHERE id = 1");
        rejected(connection, "23503", "UPDATE approval_step SET version_no = 999 WHERE id = 1");
        accepted(connection, "INSERT INTO approval_step(id,document_id,version_no,approver_id,role_id,stage_order,origin,decision,created_at)"
                + " VALUES (10,1,1,2,1,2,'ADDED_BY_AUTHOR','PENDING',CURRENT_TIMESTAMP)");
        rejected(connection, "23502", "INSERT INTO approval_step(id,document_id,version_no,approver_id,role_id,stage_order,origin,decision,created_at)"
                + " VALUES (11,1,1,2,NULL,2,'ADDED_BY_AUTHOR','PENDING',CURRENT_TIMESTAMP)");
        // Параллельная стадия и несколько ролей одного участника допустимы.
        accepted(connection, "INSERT INTO role VALUES (2,1,'TEST2','Тестовая роль 2')");
        accepted(connection, "INSERT INTO member_role VALUES (1,2,1,CURRENT_TIMESTAMP)");
        accepted(connection, "INSERT INTO approval_route VALUES (2,1,1,1,2,true)");
        rejected(connection, "23503", "INSERT INTO approval_route VALUES (3,1,3,1,1,true)");
        accepted(connection, "INSERT INTO document_version(id,document_id,version_no,contains_sensitive,created_by,created_at) VALUES (2,1,2,false,1,CURRENT_TIMESTAMP)");
        // Один storage_key переиспользуется версиями; старый файл остаётся на месте.
        accepted(connection, file(12, 2, "MAIN", 0));
        checkAutoReason(connection);
        checkRuleField(connection);
        checkInviteCodeHistory(connection);
    }

    private void checkAutoReason(Connection connection) throws SQLException {
        rejected(connection, "23514", "UPDATE approval_step SET decision='APPROVED', auto_reason='UNKNOWN' WHERE id=1");
        for (String decision : new String[]{"PENDING", "RETURNED", "REJECTED", "SKIPPED"}) {
            rejected(connection, "23514", "UPDATE approval_step SET decision='" + decision
                    + "', auto_reason='AUTHOR_HOLDS_ROLE' WHERE id=1");
        }
        accepted(connection, "UPDATE approval_step SET decision='APPROVED', auto_reason='AUTHOR_HOLDS_ROLE' WHERE id=1");
        accepted(connection, "UPDATE approval_step SET decision='APPROVED', auto_reason=NULL WHERE id=1");
    }

    private void checkRuleField(Connection connection) throws SQLException {
        accepted(connection, rule(10, 1, "test"));
        rejected(connection, "23503", rule(11, 1, "missing"));
        accepted(connection, "INSERT INTO document_type VALUES (2,'Другой тип','OTHER',false)");
        accepted(connection, "INSERT INTO document_type_field(id,document_type_id,field_name,field_type,label)"
                + " VALUES (2,2,'other_field','STRING','Другое поле')");
        accepted(connection, rule(12, 2, "other_field"));
        // Совпадение имени поля без совпадения типа документа недостаточно.
        rejected(connection, "23503", rule(13, 1, "other_field"));
    }

    private String rule(long id, long type, String field) {
        return "INSERT INTO requirement_rule(id,document_type_id,field_name,description,check_type,kind,severity,"
                + "source_title,source_checked_at) VALUES (" + id + "," + type + ",'" + field
                + "','Тест','REQUIRED','PRODUCT_RULE','INFO','Тестовый источник',CURRENT_TIMESTAMP)";
    }

    private void checkInviteCodeHistory(Connection connection) throws SQLException {
        accepted(connection, "UPDATE invite SET revoked_at=CURRENT_TIMESTAMP WHERE id IN (1,2)");
        // Обе организации свободны от активного кода: отказ вызван исторической уникальностью code.
        rejected(connection, "23505", "INSERT INTO invite(id,org_id,kind,code,created_by,created_at)"
                + " VALUES (20,3,'ORG_CODE','TEST0001',1,CURRENT_TIMESTAMP)");
        rejected(connection, "23505", "INSERT INTO invite(id,org_id,kind,code,created_by,created_at)"
                + " VALUES (20,1,'ORG_CODE','TEST0001',1,CURRENT_TIMESTAMP)");
        accepted(connection, "INSERT INTO invite(id,org_id,kind,code,created_by,created_at)"
                + " VALUES (20,1,'ORG_CODE','NEWCODE1',1,CURRENT_TIMESTAMP)");
        // Несколько NULL code у личных ссылок допустимы.
        accepted(connection, "INSERT INTO invite(id,org_id,kind,token,created_by,created_at,expires_at)"
                + " VALUES (21,1,'PERSONAL_LINK','synthetic-second-token',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '72 hours')");
    }

    private String file(long id, long version, String kind, int position) {
        return "INSERT INTO document_file VALUES (" + id + "," + version + ",'" + kind + "'," + position
                + ",'synthetic-storage-key','test.pdf','application/pdf',0,CURRENT_TIMESTAMP)";
    }

    private void accepted(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void rejected(Connection connection, String sqlState, String sql) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            assertThatThrownBy(() -> accepted(connection, sql)).isInstanceOf(SQLException.class)
                    .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo(sqlState));
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }
}
