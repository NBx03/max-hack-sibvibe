--liquibase formatted sql
--changeset sibvibe:108-001-route-endorsement dbms:postgresql

-- Этап утверждения в маршруте (#108).
--
-- Последний шаг маршрута — утверждение (гриф «УТВЕРЖДАЮ»), ровно один утверждающий. Вид шага хранится на шаге:
-- APPROVAL — согласование на этапе, ENDORSEMENT — утверждение. Статусы «На утверждении» и «Утверждён» не хранятся —
-- их вычисляет по шагам одно выражение на бэкенде (DocumentReadRepository.DISPLAY_STATUS).
ALTER TABLE approval_step ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'APPROVAL';
ALTER TABLE approval_step ADD CONSTRAINT ck_approval_step_kind CHECK (kind IN ('APPROVAL', 'ENDORSEMENT'));

-- Шаблон только предзаполняет маршрут, обязательные — минимум. У «Другого документа» обязательных нет:
-- Директор лишь подставляется. Редактора шаблонов нет, никто их не настраивал — меняем у всех компаний.
UPDATE approval_route route
SET is_mandatory = FALSE
FROM document_type type
WHERE type.id = route.document_type_id
  AND type.code = 'GENERIC';

-- В демо-компании у служебной записки обязателен только Директор; Юрист и Бухгалтер — предзаполнение.
UPDATE approval_route route
SET is_mandatory = FALSE
FROM document_type type, role r, organization org
WHERE type.id = route.document_type_id
  AND type.code = 'OFFICIAL_MEMO'
  AND r.id = route.role_id
  AND r.code IN ('LAWYER', 'ACCOUNTANT')
  AND org.id = route.org_id
  AND org.is_demo = TRUE;
