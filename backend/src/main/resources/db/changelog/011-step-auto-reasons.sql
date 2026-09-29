--liquibase formatted sql
--changeset sibvibe:56-002-step-auto-reasons dbms:postgresql

-- Два новых вида автоматических отметок шага.
--   CARRIED_OVER   — одобрение прошлой версии перенесено: документ с тех пор не менялся. Решение — APPROVED.
--   MEMBER_REMOVED — согласующего исключили из компании или сняли роль, документ вернулся автору.
--                    Шаг закрыт без решения — SKIPPED.
ALTER TABLE approval_step DROP CONSTRAINT ck_step_auto_reason_requires_approved;
ALTER TABLE approval_step DROP CONSTRAINT ck_step_auto_reason_value;
ALTER TABLE approval_step ADD CONSTRAINT ck_step_auto_reason_value
    CHECK (auto_reason IS NULL OR auto_reason IN ('AUTHOR_HOLDS_ROLE', 'CARRIED_OVER', 'MEMBER_REMOVED'));
ALTER TABLE approval_step ADD CONSTRAINT ck_step_auto_reason_matches_decision
    CHECK (auto_reason IS NULL
        OR (auto_reason IN ('AUTHOR_HOLDS_ROLE', 'CARRIED_OVER') AND decision = 'APPROVED')
        OR (auto_reason = 'MEMBER_REMOVED' AND decision = 'SKIPPED'));
