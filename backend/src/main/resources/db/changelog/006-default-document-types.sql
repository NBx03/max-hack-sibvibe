--liquibase formatted sql
--changeset sibvibe:17-001-default-document-types dbms:postgresql

-- Стандартные типы документов нужны маршрутам по умолчанию: они создаются вместе с компанией
-- (ARCHITECTURE.md, раздел 5), а без строки в document_type у маршрута нет типа.
-- Здесь только сами типы. Поля и правила проверки добавляет справочник (#11) по коду типа:
-- ON CONFLICT делает вставку безопасной в любом порядке слияния задач.
INSERT INTO document_type(name, code, is_generic) VALUES
    ('Служебная записка', 'OFFICIAL_MEMO', false),
    ('Заявление на отпуск', 'VACATION_REQUEST', false),
    ('Заявка на меру поддержки', 'SUPPORT_MEASURE_REQUEST', false),
    ('Любой файл', 'GENERIC', true)
ON CONFLICT (code) DO NOTHING;
