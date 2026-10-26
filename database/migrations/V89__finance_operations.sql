-- Financial operations are business facts; they are not a substitute for posted general-ledger entries.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category) VALUES
 ('finance.payroll','finance','payroll','读取及处理授权范围工资明细；不因财务或管理岗位默认授予',true,'FINANCE'),
 ('finance.tax','finance','tax','处理授权主体税务任务、销项开票及申报归档；与报销权限分开',true,'FINANCE')
ON CONFLICT(code) DO NOTHING;
CREATE TABLE fo_party (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id), hotel_id UUID NOT NULL,
 name VARCHAR(160) NOT NULL, kind VARCHAR(16) NOT NULL CHECK(kind IN ('SUPPLIER','CUSTOMER','EMPLOYEE')),
 tax_number VARCHAR(32) NOT NULL DEFAULT '', bank_name VARCHAR(160) NOT NULL DEFAULT '', bank_account VARCHAR(80) NOT NULL DEFAULT '',
 version INT NOT NULL DEFAULT 0, created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id)
);
CREATE INDEX ix_fo_party_scope ON fo_party(tenant_id,hotel_id,name);
CREATE TABLE fo_bank_account (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id), hotel_id UUID NOT NULL, name VARCHAR(160) NOT NULL,
 account_number VARCHAR(80) NOT NULL, bank_name VARCHAR(160) NOT NULL, opening_date DATE NOT NULL,
 opening_balance NUMERIC(18,2) NOT NULL, version INT NOT NULL DEFAULT 0, created_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,hotel_id,account_number),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id)
);
CREATE INDEX ix_fo_bank_scope ON fo_bank_account(tenant_id,hotel_id);
CREATE TABLE fo_budget (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id), hotel_id UUID NOT NULL, month DATE NOT NULL,
 category_code VARCHAR(80) NOT NULL, amount NUMERIC(18,2) NOT NULL CHECK(amount>=0),
 control VARCHAR(8) NOT NULL CHECK(control IN ('WARN','BLOCK')), version INT NOT NULL DEFAULT 0,
 created_by UUID NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,hotel_id,month,category_code),
 CHECK(extract(day from month)=1), FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id)
);
CREATE TABLE fo_document (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id), hotel_id UUID NOT NULL, applicant_id UUID NOT NULL,
 type VARCHAR(24) NOT NULL CHECK(type IN ('CONTRACT','PAYABLE','LOAN','PREPAYMENT','ASSET','PAYROLL','REVENUE','INTERCOMPANY','TAX_TASK')),
 title VARCHAR(160) NOT NULL, business_date DATE NOT NULL, due_date DATE, party_id UUID, budget_id UUID,
 company_id UUID, recognized_source_document_id UUID,
 source_type VARCHAR(80) NOT NULL DEFAULT 'MANUAL', source_id UUID, source_reference VARCHAR(240) NOT NULL DEFAULT '',
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0), details JSONB NOT NULL, lines JSONB NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','RETURNED','REVIEW','APPROVED','REJECTED','CANCELLED')),
 review_index INT NOT NULL DEFAULT 0, approval_route JSONB NOT NULL DEFAULT '[]', cashier_account_id UUID,
 party_snapshot JSONB NOT NULL DEFAULT '{}', version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,applicant_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,party_id) REFERENCES fo_party(tenant_id,id),
 FOREIGN KEY(tenant_id,budget_id) REFERENCES fo_budget(tenant_id,id),
 FOREIGN KEY(tenant_id,cashier_account_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),
 FOREIGN KEY(tenant_id,recognized_source_document_id) REFERENCES fo_document(tenant_id,id),
 CHECK(recognized_source_document_id IS NULL OR type IN ('ASSET','PREPAYMENT'))
);
CREATE UNIQUE INDEX ux_fo_document_source ON fo_document(tenant_id,source_type,source_id,type) WHERE source_id IS NOT NULL;
-- This version only supports full-value financial-card succession, not partial allocation.
-- A source payable remains the sole cash settlement object; one active card consumes its value.
CREATE UNIQUE INDEX ux_fo_recognized_source_card ON fo_document(tenant_id,recognized_source_document_id)
 WHERE recognized_source_document_id IS NOT NULL AND status NOT IN ('REJECTED','CANCELLED');
CREATE INDEX ix_fo_document_scope ON fo_document(tenant_id,hotel_id,type,business_date DESC,id);
CREATE INDEX ix_fo_document_owner ON fo_document(tenant_id,applicant_id,status,updated_at DESC);
CREATE INDEX ix_fo_document_due ON fo_document(tenant_id,due_date) WHERE status NOT IN ('REJECTED','CANCELLED');
CREATE TABLE fo_budget_usage (
 tenant_id UUID NOT NULL, budget_id UUID NOT NULL, document_id UUID NOT NULL,
 committed NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(committed>=0), inflight NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(inflight>=0),
 actual NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(actual>=0), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,document_id), FOREIGN KEY(tenant_id,budget_id) REFERENCES fo_budget(tenant_id,id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES fo_document(tenant_id,id)
);
CREATE INDEX ix_fo_budget_usage_budget ON fo_budget_usage(tenant_id,budget_id);
CREATE TABLE fo_action (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, document_id UUID NOT NULL, actor_id UUID NOT NULL,
 action VARCHAR(32) NOT NULL, amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(amount>=0), occurred_on DATE NOT NULL,
 reference VARCHAR(240) NOT NULL DEFAULT '', note VARCHAR(2000) NOT NULL DEFAULT '', receipt_reference VARCHAR(240) NOT NULL DEFAULT '',
 bank_account_id UUID, related_document_id UUID, request_id UUID NOT NULL, snapshot JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,request_id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES fo_document(tenant_id,id),
 FOREIGN KEY(tenant_id,bank_account_id) REFERENCES fo_bank_account(tenant_id,id),
 FOREIGN KEY(tenant_id,related_document_id) REFERENCES fo_document(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_fo_action_document ON fo_action(tenant_id,document_id,created_at,id);
CREATE TABLE fo_command (
 tenant_id UUID NOT NULL, request_id UUID NOT NULL, resource_id UUID NOT NULL, operation VARCHAR(40) NOT NULL,
 request_hash CHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,request_id)
);
CREATE TABLE fo_bank_transaction (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, bank_account_id UUID NOT NULL, batch_id UUID NOT NULL,
 external_id VARCHAR(160) NOT NULL, occurred_on DATE NOT NULL, direction VARCHAR(3) NOT NULL CHECK(direction IN ('IN','OUT')),
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0), counterparty VARCHAR(160) NOT NULL DEFAULT '', reference VARCHAR(240) NOT NULL DEFAULT '',
 source_hash CHAR(64) NOT NULL, created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,bank_account_id,external_id),
 FOREIGN KEY(tenant_id,bank_account_id) REFERENCES fo_bank_account(tenant_id,id)
);
CREATE INDEX ix_fo_bank_transaction_account ON fo_bank_transaction(tenant_id,bank_account_id,occurred_on,id);
CREATE TABLE fo_bank_match (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, transaction_id UUID NOT NULL, action_id UUID NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0), request_id UUID NOT NULL, note VARCHAR(2000) NOT NULL,
 actor_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,request_id,action_id),
 FOREIGN KEY(tenant_id,transaction_id) REFERENCES fo_bank_transaction(tenant_id,id),
 FOREIGN KEY(tenant_id,action_id) REFERENCES fo_action(tenant_id,id)
);
CREATE INDEX ix_fo_bank_match_transaction ON fo_bank_match(tenant_id,transaction_id);
CREATE INDEX ix_fo_bank_match_action ON fo_bank_match(tenant_id,action_id);
CREATE TABLE fo_bank_explanation (
 tenant_id UUID NOT NULL, transaction_id UUID NOT NULL, reason VARCHAR(2000) NOT NULL CHECK(length(btrim(reason))>0),
 transaction_hash_snapshot CHAR(64) NOT NULL, matched_amount_snapshot NUMERIC(18,2) NOT NULL CHECK(matched_amount_snapshot>=0),
 match_count_snapshot BIGINT NOT NULL CHECK(match_count_snapshot>=0), version INT NOT NULL DEFAULT 0,
 actor_id UUID NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,transaction_id),
 FOREIGN KEY(tenant_id,transaction_id) REFERENCES fo_bank_transaction(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE fo_attachment (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, document_id UUID NOT NULL, action_id UUID,
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('CONTRACT','INVOICE','ACCEPTANCE','TRANSACTION','PAYMENT','PAYROLL','OTHER')),
 original_name VARCHAR(180) NOT NULL, media_type VARCHAR(80) NOT NULL, size_bytes BIGINT NOT NULL CHECK(size_bytes>0 AND size_bytes<=20971520),
 sha256 CHAR(64) NOT NULL, object_key VARCHAR(240) NOT NULL, scan_status VARCHAR(40) NOT NULL,
 extraction JSONB NOT NULL DEFAULT '{}', uploaded_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,object_key), FOREIGN KEY(tenant_id,document_id) REFERENCES fo_document(tenant_id,id),
 FOREIGN KEY(tenant_id,action_id) REFERENCES fo_action(tenant_id,id)
);
CREATE INDEX ix_fo_attachment_document ON fo_attachment(tenant_id,document_id,created_at,id);
CREATE INDEX ix_fo_attachment_receipt ON fo_attachment(tenant_id,action_id) WHERE action_id IS NOT NULL;
-- All tenant tables have forced RLS. Immutable audit/settlement facts cannot be edited even by application bugs.
DO $$ DECLARE tab TEXT; BEGIN
 FOREACH tab IN ARRAY ARRAY['fo_party','fo_bank_account','fo_budget','fo_document','fo_budget_usage','fo_action','fo_command','fo_bank_transaction','fo_bank_match','fo_bank_explanation','fo_attachment'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['fo_action','fo_command','fo_bank_transaction','fo_bank_match','fo_attachment'] LOOP
  EXECUTE format('CREATE TRIGGER finance_operations_fact_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);
 END LOOP;
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
  GRANT SELECT,INSERT,UPDATE ON fo_party,fo_bank_account,fo_budget,fo_document,fo_budget_usage,fo_bank_explanation TO hotel_ai_os_app;
  GRANT SELECT,INSERT ON fo_action,fo_command,fo_bank_transaction,fo_bank_match,fo_attachment TO hotel_ai_os_app;
  REVOKE DELETE ON fo_party,fo_bank_account,fo_budget,fo_document,fo_budget_usage,fo_bank_explanation FROM hotel_ai_os_app;
  REVOKE UPDATE,DELETE ON fo_action,fo_command,fo_bank_transaction,fo_bank_match,fo_attachment FROM hotel_ai_os_app;
 END IF;
END $$;
