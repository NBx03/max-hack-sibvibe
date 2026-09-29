--liquibase formatted sql
--changeset sibvibe:86-001-dates-and-other-document dbms:postgresql

-- Синхронизировано с testdata/rules.json.

-- 1. Даты — в любом виде, который допускает ГОСТ Р 7.0.97-2016, п. 5.10: цифровом
--    («23.09.2026») и словесно-цифровом («"23" сентября 2026 г.»). RU_DATE разбирает
--    RussianDates.
UPDATE requirement_rule r
SET expected = 'RU_DATE',
    description = 'Запишите дату полностью — число, месяц и год: цифрами (23.09.2026) или словесно-цифровым способом («23» сентября 2026 г.). Оба способа допускает ГОСТ.',
    source_title = 'ГОСТ Р 7.0.97-2016 «Организационно-распорядительная документация. Требования к оформлению документов» (утв. приказом Росстандарта от 08.12.2016 № 2004-ст, ред. от 14.05.2018), принят в компании как стандарт оформления',
    source_url = 'https://www.consultant.ru/document/cons_doc_LAW_216461/333ec0789d0d87106250cb3f90cac4294946a059/',
    source_ref = 'п. 5.10'
FROM document_type t
WHERE r.document_type_id = t.id AND t.code = 'OFFICIAL_MEMO'
  AND r.field_name = 'doc_date' AND r.check_type = 'DATE_FORMAT';

UPDATE requirement_rule r
SET expected = 'RU_DATE',
    description = 'Дата записки — это дата подписания, она не может быть позже сегодняшнего дня.'
FROM document_type t
WHERE r.document_type_id = t.id AND t.code = 'OFFICIAL_MEMO'
  AND r.field_name = 'doc_date' AND r.check_type = 'DATE_NOT_FUTURE';

UPDATE requirement_rule r
SET expected = 'RU_DATE',
    description = 'Дату начала отпуска пишут полностью, с годом: 01.10.2026 или 1 октября 2026 г. — без года кадровик не поймёт, о каком годе речь.'
FROM document_type t
WHERE r.document_type_id = t.id AND t.code = 'VACATION_REQUEST'
  AND r.field_name = 'start_date' AND r.check_type = 'DATE_FORMAT';

-- 2. Инициалы в подписи: объясняем, откуда требование, а не просто «И.О. Фамилия».
UPDATE requirement_rule r
SET description = 'По ГОСТ в расшифровке подписи сначала инициалы, потом фамилия: «А.С. Петрова», а не «Петрова А.С.» и не полное имя.'
FROM document_type t
WHERE r.document_type_id = t.id AND t.code = 'OFFICIAL_MEMO'
  AND r.field_name = 'signer_name' AND r.check_type = 'MATCHES_PATTERN';

-- 3. «Любой файл» → «Другой документ»: модель проверяет общие реквизиты любого документа.
--    Раньше тип был без схемы, и проверяющий, загрузивший свой файл, модель не видела вовсе.
--    Все правила — предупреждения: документ неизвестного вида ничто не блокирует.
UPDATE document_type SET name = 'Другой документ', is_generic = false WHERE code = 'GENERIC';

WITH seed(field_name, field_type, label, hint) AS (VALUES
    ('doc_kind', 'STRING', 'Вид документа', 'Вид документа, как он назван в самом документе: «Приказ», «Договор», «Акт», «Письмо». Пусто, если вид не указан.'),
    ('doc_date', 'DATE', 'Дата', 'Дата документа (подписания или составления) точно в том виде, как она написана, без приведения к другому формату.'),
    ('reg_number', 'STRING', 'Номер', 'Регистрационный номер документа после знака «№», без самого знака. Пусто, если номера нет.'),
    ('subject', 'STRING', 'Заголовок', 'Заголовок к тексту или название документа — о чём он, как в документе. Пусто, если заголовка нет.'),
    ('signer_position', 'STRING', 'Должность подписавшего', 'Должность в подписи. Пусто, если в подписи нет должности.'),
    ('signer_name', 'PERSON', 'Расшифровка подписи', 'Инициалы и фамилия в подписи точно как в документе. Пусто, если подписи нет.')
)
INSERT INTO document_type_field(document_type_id, field_name, field_type, label, hint)
SELECT type.id, seed.field_name, seed.field_type, seed.label, seed.hint
FROM seed
JOIN document_type type ON type.code = 'GENERIC'
ON CONFLICT (document_type_id, field_name) DO UPDATE SET
    field_type = EXCLUDED.field_type,
    label = EXCLUDED.label,
    hint = EXCLUDED.hint;

WITH seed(field_name, description, check_type, expected, severity) AS (VALUES
    ('doc_date', 'Не нашли дату документа. Без даты непонятно, какая редакция документа согласована.', 'REQUIRED', NULL, 'WARNING'),
    ('doc_date', 'Дату не удалось прочитать: запишите её полностью — например, 23.09.2026 или «23» сентября 2026 г.', 'DATE_FORMAT', 'RU_DATE', 'WARNING'),
    ('subject', 'Не нашли заголовок: по нему согласующие сразу понимают, о чём документ.', 'REQUIRED', NULL, 'INFO'),
    ('signer_name', 'Не нашли подпись с расшифровкой: из документа должно быть понятно, кто его подписывает.', 'REQUIRED', NULL, 'WARNING'),
    ('reg_number', 'Не нашли регистрационный номер. Если документы в компании регистрируют, номер присвоят при регистрации.', 'REQUIRED', NULL, 'INFO')
)
-- Дата проверки источника — та же, что sourceCheckedAt в testdata/rules.json (по нему собран RULES.md).
INSERT INTO requirement_rule(
    document_type_id, field_name, description, check_type, expected, kind, severity,
    source_title, source_url, source_ref, source_checked_at
)
SELECT type.id, seed.field_name, seed.description, seed.check_type, seed.expected,
       'PRODUCT_RULE', seed.severity, 'Правило продукта', NULL, NULL, '2026-09-21'::date::timestamptz
FROM seed
JOIN document_type type ON type.code = 'GENERIC';

-- 4. Тип документа определён моделью при загрузке — показываем это автору.
ALTER TABLE document ADD COLUMN type_auto_detected BOOLEAN NOT NULL DEFAULT false;

-- 5. Явный порядок полей. INSERT ... SELECT с JOIN в 007 не сохраняет порядок строк: на базах
--    поля служебной записки получили id в обратном порядке, и форма начиналась с подписи, а
--    не с адресата. Порядок — как в testdata/rules.json.
ALTER TABLE document_type_field ADD COLUMN position INT NOT NULL DEFAULT 0;

WITH ordered(type_code, field_name, position) AS (VALUES
    ('OFFICIAL_MEMO', 'addressee', 1), ('OFFICIAL_MEMO', 'author_name', 2), ('OFFICIAL_MEMO', 'doc_date', 3),
    ('OFFICIAL_MEMO', 'reg_number', 4), ('OFFICIAL_MEMO', 'subject', 5), ('OFFICIAL_MEMO', 'body', 6),
    ('OFFICIAL_MEMO', 'signer_position', 7), ('OFFICIAL_MEMO', 'signer_name', 8),
    ('VACATION_REQUEST', 'employee_name', 1), ('VACATION_REQUEST', 'employee_position', 2),
    ('VACATION_REQUEST', 'vacation_type', 3), ('VACATION_REQUEST', 'start_date', 4),
    ('VACATION_REQUEST', 'days_count', 5),
    ('SUPPORT_MEASURE_REQUEST', 'applicant_name', 1), ('SUPPORT_MEASURE_REQUEST', 'applicant_inn', 2),
    ('SUPPORT_MEASURE_REQUEST', 'smb_category', 3), ('SUPPORT_MEASURE_REQUEST', 'support_type', 4),
    ('GENERIC', 'doc_kind', 1), ('GENERIC', 'doc_date', 2), ('GENERIC', 'reg_number', 3),
    ('GENERIC', 'subject', 4), ('GENERIC', 'signer_position', 5), ('GENERIC', 'signer_name', 6)
)
UPDATE document_type_field f
SET position = ordered.position
FROM ordered
JOIN document_type t ON t.code = ordered.type_code
WHERE f.document_type_id = t.id AND f.field_name = ordered.field_name;
