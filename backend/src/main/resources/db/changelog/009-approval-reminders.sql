--liquibase formatted sql
--changeset sibvibe:60-001-approval-reminders dbms:postgresql

-- Напоминание о застрявшем шаге (issue #60): когда согласующему в последний раз напоминали.
-- NULL - ни разу. Используется для отсечения "не чаще раза в сутки на шаг" тем же приёмом,
-- что и атомарное использование личной ссылки.
ALTER TABLE approval_step ADD COLUMN last_reminded_at TIMESTAMP WITH TIME ZONE;

-- Частичный индекс под выборку планировщика: активные шаги, ожидающие решения.
CREATE INDEX ix_step_reminder_candidates ON approval_step(activated_at) WHERE decision = 'PENDING';
