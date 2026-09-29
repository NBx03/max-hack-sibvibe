--liquibase formatted sql
--changeset sibvibe:92-001-document-ai-summary dbms:postgresql

-- Краткая сводка относится к конкретному содержимому версии. NULL означает, что модель
-- недоступна, документ чувствительный или проверка фактов отклонила ответ.
ALTER TABLE document_version ADD COLUMN ai_summary TEXT;
