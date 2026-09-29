--liquibase formatted sql
--changeset sibvibe:90-001-company-rule-settings dbms:postgresql

-- Правила проверки компании (#90). Справочник requirement_rule — шаблон сервиса, одинаковый для всех. Компания
-- хранит только отличия от него: строки нет — правило как в шаблоне. Правила не копируются, поэтому rule_id в уже
-- выданных замечаниях остаётся прежним. NULL в колонке — «как в шаблоне». Если компания указала свой источник
-- (source_title), пункт берётся только из source_ref (NULL — без пункта), а ссылка шаблона не показывается.
CREATE TABLE organization_rule_setting (
    org_id BIGINT NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    rule_id BIGINT NOT NULL REFERENCES requirement_rule(id),
    enabled BOOLEAN NOT NULL,
    severity VARCHAR(32),
    description TEXT,
    source_title TEXT,
    source_ref TEXT,
    expected TEXT,
    updated_by BIGINT NOT NULL REFERENCES app_user(id),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_organization_rule_setting PRIMARY KEY (org_id, rule_id),
    CONSTRAINT ck_organization_rule_setting_severity CHECK (severity IS NULL OR severity IN ('BLOCKER', 'WARNING', 'INFO'))
);

-- Шаблон нейтральный: у настоящей компании замечания не должны ссылаться на локальные акты вымышленной
-- Демо-компании. Её инструкция и положение теперь — настройка демо-песочницы (DemoDocumentSeedService).
UPDATE requirement_rule rule
SET source_title = 'Типовые правила оформления служебных записок (шаблон сервиса на основе ГОСТ Р 7.0.97-2016; компания настраивает под свою инструкцию)',
    source_ref = CASE rule.field_name
        WHEN 'reg_number' THEN 'индекс «СЗ» для служебных записок; ГОСТ Р 7.0.97-2016, п. 5.11, оставляет индексы на усмотрение организации'
        ELSE 'служебные записки оформляются не на бланке должностного лица, поэтому должность в подписи указывается'
    END
FROM document_type type
WHERE type.id = rule.document_type_id
  AND type.code = 'OFFICIAL_MEMO'
  AND (rule.field_name, rule.check_type) IN (('reg_number', 'MATCHES_PATTERN'), ('signer_position', 'REQUIRED'))
  -- Точное прежнее название (007-reference-data.sql), не префикс: чужую или будущую строку не трогаем.
  AND rule.source_title = 'Инструкция по делопроизводству Демо-компании (вымышленный локальный акт демо-компании на основе ГОСТ Р 7.0.97-2016)';

UPDATE requirement_rule rule
SET source_title = 'Типовые правила оформления заявлений на отпуск (шаблон сервиса; компания настраивает под своё положение)',
    source_ref = NULL
FROM document_type type
WHERE type.id = rule.document_type_id
  AND type.code = 'VACATION_REQUEST'
  AND (rule.field_name, rule.check_type) IN (('employee_name', 'REQUIRED'), ('vacation_type', 'ONE_OF'),
                                             ('start_date', 'REQUIRED'), ('start_date', 'DATE_FORMAT'))
  AND rule.source_title = 'Положение об оформлении отпусков Демо-компании (вымышленный локальный акт демо-компании)';
