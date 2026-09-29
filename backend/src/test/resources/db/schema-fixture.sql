-- Синтетические данные только для проверки структуры, транзакция теста откатывается.
-- Миграции уже создали стандартные типы документов (17-001), а справочник добавит к ним поля и правила.
-- В транзакции теста всё это убирается, чтобы фикстура сама задавала id; после отката всё возвращается.
DELETE FROM requirement_rule;
DELETE FROM document_type_field;
DELETE FROM approval_route;
DELETE FROM document_type;
INSERT INTO app_user(id, max_user_id, full_name, created_at) VALUES
    (1, 1, 'Тест A', CURRENT_TIMESTAMP), (2, 2, 'Тест B', CURRENT_TIMESTAMP);
INSERT INTO organization(id, name, created_by, created_at, is_demo, demo_owner_id) VALUES
    (1, 'Тестовая компания', 1, CURRENT_TIMESTAMP, false, NULL),
    (2, 'Тестовая песочница', 1, CURRENT_TIMESTAMP, true, 1),
    (3, 'Другая компания', 1, CURRENT_TIMESTAMP, false, NULL);
INSERT INTO organization_member(id, org_id, user_id, status, is_admin, joined_at, is_demo) VALUES
    (1, 1, 1, 'ACTIVE', true, CURRENT_TIMESTAMP, false),
    (2, 2, 1, 'ACTIVE', true, CURRENT_TIMESTAMP, true);
INSERT INTO role(id, org_id, code, name) VALUES (1, 1, 'TEST', 'Тестовая роль');
INSERT INTO member_role(member_id, role_id, granted_by, granted_at) VALUES (1, 1, 1, CURRENT_TIMESTAMP);
INSERT INTO invite(id, org_id, kind, code, created_by, created_at) VALUES
    (1, 1, 'ORG_CODE', 'TEST0001', 1, CURRENT_TIMESTAMP),
    (2, 3, 'ORG_CODE', 'TEST0002', 1, CURRENT_TIMESTAMP);
INSERT INTO invite(id, org_id, kind, token, created_by, created_at, expires_at) VALUES
    (3, 1, 'PERSONAL_LINK', 'synthetic-invite-token', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '72 hours');
INSERT INTO invite_role(invite_id, role_id) VALUES (3, 1);
INSERT INTO join_request(id, org_id, user_id, invite_id, status, created_at) VALUES
    (1, 1, 2, 1, 'PENDING', CURRENT_TIMESTAMP);
INSERT INTO document_type(id, name, code, is_generic) VALUES (1, 'Тестовый тип', 'TEST', false);
INSERT INTO document_type_field(id, document_type_id, field_name, field_type, label) VALUES
    (1, 1, 'test', 'STRING', 'Тестовое поле');
INSERT INTO requirement_rule(id, document_type_id, field_name, description, check_type,
    kind, severity, source_title, source_checked_at) VALUES
    (1, 1, 'test', 'Тестовое описание', 'REQUIRED', 'INTERNAL_POLICY', 'BLOCKER', 'Тестовый источник', CURRENT_TIMESTAMP);
INSERT INTO document(id, document_type_id, author_id, org_id, title, status,
    current_version_no, visibility, created_at, updated_at) VALUES
    (1, 1, 1, 1, 'Тестовый документ', 'DRAFT', 1, 'PRIVATE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO document_version(id, document_id, version_no, contains_sensitive, content,
    created_by, created_at, extracted_fields, validation_issues, check_status) VALUES
    (1, 1, 1, false, '{"test":"значение"}', 1, CURRENT_TIMESTAMP, '{"test":"значение"}', '[]', 'CHECKED');
INSERT INTO document_file(id, version_id, kind, position, storage_key, file_name, mime_type, file_size, created_at) VALUES
    (1, 1, 'MAIN', 0, 'synthetic-storage-key', 'test.pdf', 'application/pdf', 0, CURRENT_TIMESTAMP);
INSERT INTO approval_route(id, document_type_id, org_id, stage_order, role_id, is_mandatory) VALUES
    (1, 1, 1, 1, 1, true);
INSERT INTO approval_step(id, document_id, version_no, approver_id, role_id, stage_order, origin, decision, created_at, activated_at) VALUES
    (1, 1, 1, 2, 1, 1, 'TEMPLATE', 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO organization_rule_setting(org_id, rule_id, enabled, severity, description, source_title, source_ref, expected, updated_by, updated_at) VALUES
    (1, 1, true, 'WARNING', 'Текст компании', 'Инструкция компании', 'п. 1', NULL, 1, CURRENT_TIMESTAMP);
