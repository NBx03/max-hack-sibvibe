--liquibase formatted sql
--changeset sibvibe:14-005-indexes dbms:postgresql

CREATE UNIQUE INDEX uq_member_active_real ON organization_member(user_id)
    WHERE status = 'ACTIVE' AND NOT is_demo;
CREATE UNIQUE INDEX uq_join_pending ON join_request(user_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX uq_invite_active_org_code ON invite(org_id)
    WHERE kind = 'ORG_CODE' AND revoked_at IS NULL;
CREATE UNIQUE INDEX uq_file_main ON document_file(version_id) WHERE kind = 'MAIN';

-- Индексы PK/UNIQUE уже покрывают поиск по ведущим колонкам: не дублируем их.
CREATE INDEX ix_org_demo_owner ON organization(demo_owner_id) WHERE is_demo;
CREATE INDEX ix_member_user ON organization_member(user_id);
CREATE INDEX ix_member_role_role ON member_role(role_id);
CREATE INDEX ix_invite_org ON invite(org_id);
CREATE INDEX ix_invite_role_role ON invite_role(role_id);
CREATE INDEX ix_join_org_status ON join_request(org_id, status);
CREATE INDEX ix_join_invite ON join_request(invite_id);
CREATE INDEX ix_rule_type ON requirement_rule(document_type_id);
CREATE INDEX ix_route_type ON approval_route(document_type_id);
CREATE INDEX ix_route_role ON approval_route(role_id);
CREATE INDEX ix_document_org_status ON document(org_id, status);
CREATE INDEX ix_document_author ON document(author_id);
CREATE INDEX ix_document_status ON document(status);
CREATE INDEX ix_document_type ON document(document_type_id);
CREATE INDEX ix_file_storage_key ON document_file(storage_key);
CREATE INDEX ix_step_approver_decision ON approval_step(approver_id, decision);
CREATE INDEX ix_step_document_version ON approval_step(document_id, version_no);
CREATE INDEX ix_step_role ON approval_step(role_id);
