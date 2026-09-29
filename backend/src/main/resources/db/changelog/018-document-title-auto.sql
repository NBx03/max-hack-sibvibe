--liquibase formatted sql
--changeset sibvibe:56-002-document-title-auto dbms:postgresql

-- Название документа следует за его заголовком: исправили «Заголовок» — поменялось и
-- название в списках, если автор не вводил название сам. true — название взято из документа или имени файла;
-- у существующих документов false: их названия не меняются неожиданно.
ALTER TABLE document ADD COLUMN title_auto BOOLEAN NOT NULL DEFAULT false;
