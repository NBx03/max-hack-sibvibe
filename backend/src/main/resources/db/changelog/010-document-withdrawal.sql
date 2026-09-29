--liquibase formatted sql
--changeset sibvibe:56-001-document-withdrawal dbms:postgresql

-- Автор отозвал версию с согласования: документ возвращается к нему, как после возврата,
-- но причина — его собственное решение. NULL — не отзывалась. История остаётся честной: шаги версии
-- получают SKIPPED, а отметка показывает, почему.
ALTER TABLE document_version ADD COLUMN withdrawn_at TIMESTAMP WITH TIME ZONE;
