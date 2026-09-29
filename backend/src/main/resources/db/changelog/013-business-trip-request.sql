--liquibase formatted sql
--changeset sibvibe:61-001-business-trip-request dbms:postgresql

-- Четвёртый тип документа (#61): «Заявка на командировку». Только данные справочника — ни строки
-- кода: форма проверки, извлечение полей моделью, правила и проверка берутся из этих таблиц.
-- Шаблон нейтральный, как у остальных типов (#90): свои акты компания задаёт в «Правилах проверки».
-- Маршрут по умолчанию — DefaultRouteTemplates, он добирается компаниям при первом обращении.
-- Синхронизировано с testdata/rules.json (DatabaseSchemaTest сверяет построчно).

INSERT INTO document_type(name, code, is_generic) VALUES
    ('Заявка на командировку', 'BUSINESS_TRIP_REQUEST', false)
ON CONFLICT (code) DO NOTHING;

WITH seed(field_name, field_type, label, hint, position) AS (VALUES
    ('employee_name', 'PERSON', 'Сотрудник', 'ФИО сотрудника, которого направляют в командировку, полностью, в именительном падеже.', 1),
    ('destination', 'STRING', 'Место командировки', 'Город, куда направляется сотрудник, и организация, если она названа, как в документе. Пусто, если место не указано.', 2),
    ('purpose', 'STRING', 'Цель командировки', 'Цель поездки одной фразой, как в документе: «участие в выставке», «переговоры с заказчиком». Пусто, если цель не указана.', 3),
    ('start_date', 'DATE', 'Начало командировки', 'Дата начала командировки точно в том виде, как она написана, без предлога «с». Пусто, если даты нет.', 4),
    ('days_count', 'NUMBER', 'Календарных дней', 'Срок командировки в календарных днях точно как в документе: цифрами или словами.', 5)
)
INSERT INTO document_type_field(document_type_id, field_name, field_type, label, hint, position)
SELECT type.id, seed.field_name, seed.field_type, seed.label, seed.hint, seed.position
FROM seed JOIN document_type type ON type.code = 'BUSINESS_TRIP_REQUEST'
ON CONFLICT (document_type_id, field_name) DO UPDATE SET
    field_type = EXCLUDED.field_type,
    label = EXCLUDED.label,
    hint = EXCLUDED.hint,
    position = EXCLUDED.position;

WITH seed(field_name, description, check_type, expected, kind, severity,
          source_title, source_url, source_ref, source_checked_at) AS (VALUES
    ('employee_name', 'Укажите ФИО сотрудника, которого направляют в командировку.', 'REQUIRED', NULL, 'INTERNAL_POLICY', 'BLOCKER', 'Типовые правила оформления заявок на командировку (шаблон сервиса; компания настраивает под своё положение)', NULL, NULL, '2026-09-21'),
    ('destination', 'Укажите место командировки — город и, если известно, организацию: по нему бухгалтерия считает расходы.', 'REQUIRED', NULL, 'INTERNAL_POLICY', 'BLOCKER', 'Типовые правила оформления заявок на командировку (шаблон сервиса; компания настраивает под своё положение)', NULL, NULL, '2026-09-21'),
    ('purpose', 'Укажите цель командировки: по ней директор решает, нужна ли поездка.', 'REQUIRED', NULL, 'INTERNAL_POLICY', 'BLOCKER', 'Типовые правила оформления заявок на командировку (шаблон сервиса; компания настраивает под своё положение)', NULL, NULL, '2026-09-21'),
    ('start_date', 'Укажите дату начала командировки.', 'REQUIRED', NULL, 'INTERNAL_POLICY', 'BLOCKER', 'Типовые правила оформления заявок на командировку (шаблон сервиса; компания настраивает под своё положение)', NULL, NULL, '2026-09-21'),
    ('start_date', 'Дату начала командировки пишут полностью, с годом: 05.10.2026 или 5 октября 2026 г. — без года не понять, о каком годе речь.', 'DATE_FORMAT', 'RU_DATE', 'INTERNAL_POLICY', 'BLOCKER', 'Типовые правила оформления заявок на командировку (шаблон сервиса; компания настраивает под своё положение)', NULL, NULL, '2026-09-21'),
    ('days_count', 'Срок командировки — целым положительным числом календарных дней, цифрами, например 5: число прописью проверить нельзя.', 'MATCHES_PATTERN', '[1-9][0-9]*', 'PRODUCT_RULE', 'BLOCKER', 'Правило продукта', NULL, NULL, '2026-09-21')
)
INSERT INTO requirement_rule(
    document_type_id, field_name, description, check_type, expected, kind, severity,
    source_title, source_url, source_ref, source_checked_at
)
SELECT type.id, seed.field_name, seed.description, seed.check_type, seed.expected,
       seed.kind, seed.severity, seed.source_title, seed.source_url, seed.source_ref,
       seed.source_checked_at::date::timestamptz
FROM seed JOIN document_type type ON type.code = 'BUSINESS_TRIP_REQUEST';
