-- Published controls are versioned. Approval is not cash, and bank matching is not evidence archiving.
ALTER TABLE fa_voucher DROP CONSTRAINT fa_voucher_kind_check;
ALTER TABLE fa_voucher ADD CONSTRAINT fa_voucher_kind_check CHECK(kind IN ('MANUAL','SOURCE','REVERSAL','YEAR_CLOSE','YEAR_CLOSE_REVERSAL'));
-- Existing accounts have unknown historical entity ownership; never infer it from today's organization mapping.
ALTER TABLE fo_bank_account ADD COLUMN company_id UUID;
ALTER TABLE fo_bank_account ADD FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id);
CREATE INDEX ix_fc_bank_company ON fo_bank_account(tenant_id,company_id);
CREATE TABLE fc_policy (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id), hotel_id UUID NOT NULL,
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('ENTITY','TRAVEL','WORKFLOW','CASH_PLAN','RETENTION','CATEGORY','BUDGET')),
 code VARCHAR(80) NOT NULL, title VARCHAR(160) NOT NULL, effective_from DATE NOT NULL, effective_to DATE,
 spec JSONB NOT NULL, spec_hash CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','RETIRED')),
 created_by UUID NOT NULL, published_by UUID, version INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id), FOREIGN KEY(tenant_id,published_by) REFERENCES user_account(tenant_id,id),
 CHECK(effective_to IS NULL OR effective_to>=effective_from), CHECK(published_by IS NULL OR published_by<>created_by)
);
CREATE INDEX ix_fc_policy_scope ON fc_policy(tenant_id,hotel_id,kind,code,effective_from);
CREATE TABLE fc_ledger_change (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, book_id UUID NOT NULL, kind VARCHAR(12) NOT NULL CHECK(kind IN ('ACCOUNT','RULE')),
 payload JSONB NOT NULL, request_hash CHAR(64) NOT NULL, reason VARCHAR(2000) NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','REJECTED')),
 created_by UUID NOT NULL, published_by UUID, version INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),
 CHECK(published_by IS NULL OR published_by<>created_by)
);
CREATE INDEX ix_fc_ledger_book ON fc_ledger_change(tenant_id,book_id,status,created_at);
CREATE TABLE fc_contract_revision (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, document_id UUID NOT NULL, source_version INT NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0), retainage NUMERIC(18,2) NOT NULL CHECK(retainage>=0),
 nodes JSONB NOT NULL, reason VARCHAR(2000) NOT NULL, created_by UUID NOT NULL, reviewed_by UUID,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','APPROVED','REJECTED')),
 version INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), reviewed_at TIMESTAMPTZ,
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,document_id) REFERENCES fo_document(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id), FOREIGN KEY(tenant_id,reviewed_by) REFERENCES user_account(tenant_id,id),
 CHECK(retainage<amount), CHECK(reviewed_by IS NULL OR reviewed_by<>created_by)
);
CREATE INDEX ix_fc_contract_document ON fc_contract_revision(tenant_id,document_id,created_at DESC);
CREATE TABLE fc_cash_instruction (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, company_id UUID NOT NULL,
 source_kind VARCHAR(12) NOT NULL CHECK(source_kind IN ('CLAIM','OPERATION')), source_id UUID NOT NULL, source_version INT NOT NULL,
 action VARCHAR(16) NOT NULL CHECK(action IN ('PAYMENT','RECEIPT','REFUND','REPAYMENT')),
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0), occurred_on DATE NOT NULL, bank_account_id UUID NOT NULL,
 beneficiary_id UUID, beneficiary_snapshot JSONB NOT NULL, bank_snapshot JSONB NOT NULL, contract_node VARCHAR(80),
 reference VARCHAR(160) NOT NULL, note VARCHAR(2000) NOT NULL, snapshot_hash CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','APPROVED','EXECUTED','REJECTED','VOID')),
 created_by UUID NOT NULL, reviewer_id UUID NOT NULL, reviewed_by UUID, cashier_id UUID NOT NULL, applicant_id UUID NOT NULL,
 version INT NOT NULL DEFAULT 0, batch_id UUID, executed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),
 FOREIGN KEY(tenant_id,bank_account_id) REFERENCES fo_bank_account(tenant_id,id),
 FOREIGN KEY(tenant_id,beneficiary_id) REFERENCES fo_party(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id), FOREIGN KEY(tenant_id,reviewer_id) REFERENCES user_account(tenant_id,id), FOREIGN KEY(tenant_id,reviewed_by) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,cashier_id) REFERENCES user_account(tenant_id,id), FOREIGN KEY(tenant_id,applicant_id) REFERENCES user_account(tenant_id,id),
 CHECK(cashier_id<>applicant_id), CHECK(reviewer_id<>created_by AND reviewer_id<>applicant_id AND reviewer_id<>cashier_id), CHECK(reviewed_by IS NULL OR (reviewed_by=reviewer_id AND reviewed_by<>created_by AND reviewed_by<>applicant_id AND reviewed_by<>cashier_id))
);
CREATE INDEX ix_fc_cash_source ON fc_cash_instruction(tenant_id,source_kind,source_id,status);
CREATE INDEX ix_fc_cash_queue ON fc_cash_instruction(tenant_id,hotel_id,status,cashier_id,created_at);
CREATE TABLE fc_cash_batch (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, bank_account_id UUID NOT NULL,
 reference VARCHAR(160) NOT NULL, amount NUMERIC(18,2) NOT NULL CHECK(amount>0), instruction_ids JSONB NOT NULL,
 request_hash CHAR(64) NOT NULL, actor_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,bank_account_id,reference),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id), FOREIGN KEY(tenant_id,bank_account_id) REFERENCES fo_bank_account(tenant_id,id)
);
ALTER TABLE fc_cash_instruction ADD FOREIGN KEY(tenant_id,batch_id) REFERENCES fc_cash_batch(tenant_id,id);
CREATE INDEX ix_fc_cash_batch ON fc_cash_instruction(tenant_id,batch_id);
CREATE TABLE fc_travel (
 tenant_id UUID NOT NULL, claim_id UUID NOT NULL, destination VARCHAR(160) NOT NULL, trip_start DATE NOT NULL,
 trip_end DATE NOT NULL, travelers JSONB NOT NULL, hotel_nights INT NOT NULL CHECK(hotel_nights>=0),
 lodging_amount NUMERIC(18,2) NOT NULL CHECK(lodging_amount>=0), transport_amount NUMERIC(18,2) NOT NULL CHECK(transport_amount>=0),
 meal_amount NUMERIC(18,2) NOT NULL CHECK(meal_amount>=0), purpose VARCHAR(2000) NOT NULL,
 policy_id UUID, policy_snapshot JSONB NOT NULL DEFAULT '{}', exception_note VARCHAR(2000) NOT NULL DEFAULT '', version INT NOT NULL DEFAULT 0,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,claim_id),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id), FOREIGN KEY(tenant_id,policy_id) REFERENCES fc_policy(tenant_id,id), CHECK(trip_end>=trip_start)
);
CREATE TABLE fc_bank_import (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, bank_account_id UUID NOT NULL, original_name VARCHAR(180) NOT NULL,
 file_hash CHAR(64) NOT NULL, mapping JSONB NOT NULL, rows JSONB NOT NULL, errors JSONB NOT NULL, created_by UUID NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PREVIEW' CHECK(status IN ('PREVIEW','COMMITTED')), version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,bank_account_id,file_hash),
 FOREIGN KEY(tenant_id,bank_account_id) REFERENCES fo_bank_account(tenant_id,id)
);
CREATE INDEX ix_fc_bank_import_account ON fc_bank_import(tenant_id,bank_account_id);
CREATE TABLE fc_claim_bank_match (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, transaction_id UUID NOT NULL, instruction_id UUID NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0), note VARCHAR(2000) NOT NULL, created_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,transaction_id) REFERENCES fo_bank_transaction(tenant_id,id),
 FOREIGN KEY(tenant_id,instruction_id) REFERENCES fc_cash_instruction(tenant_id,id)
);
CREATE INDEX ix_fc_claim_bank_transaction ON fc_claim_bank_match(tenant_id,transaction_id);
CREATE INDEX ix_fc_claim_bank_instruction ON fc_claim_bank_match(tenant_id,instruction_id);
CREATE TABLE fc_claim_loan_clear (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, claim_id UUID NOT NULL, loan_id UUID NOT NULL,
 payment_id UUID NOT NULL, action_id UUID NOT NULL, amount NUMERIC(18,2) NOT NULL CHECK(amount>0), occurred_on DATE NOT NULL,
 note VARCHAR(2000) NOT NULL, actor_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,payment_id), UNIQUE(tenant_id,action_id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id), FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id),
 FOREIGN KEY(tenant_id,loan_id) REFERENCES fo_document(tenant_id,id), FOREIGN KEY(tenant_id,payment_id) REFERENCES finance_payment(tenant_id,id),
 FOREIGN KEY(tenant_id,action_id) REFERENCES fo_action(tenant_id,id)
);
CREATE INDEX ix_fc_loan_claim ON fc_claim_loan_clear(tenant_id,claim_id);
CREATE INDEX ix_fc_loan_source ON fc_claim_loan_clear(tenant_id,loan_id);
CREATE TABLE fc_budget_usage (
 tenant_id UUID NOT NULL, policy_id UUID NOT NULL, source_kind VARCHAR(12) NOT NULL, source_id UUID NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount>=0), state VARCHAR(16) NOT NULL CHECK(state IN ('INFLIGHT','COMMITTED','ACTUAL','RELEASED')),
 snapshot JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,policy_id,source_kind,source_id),
 FOREIGN KEY(tenant_id,policy_id) REFERENCES fc_policy(tenant_id,id)
);
CREATE INDEX ix_fc_budget_usage_source ON fc_budget_usage(tenant_id,source_kind,source_id);
CREATE TABLE fc_archive_note (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, source_kind VARCHAR(24) NOT NULL,
 source_id UUID NOT NULL, previous_id UUID, note VARCHAR(4000) NOT NULL, actor_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id), FOREIGN KEY(tenant_id,previous_id) REFERENCES fc_archive_note(tenant_id,id)
);
CREATE INDEX ix_fc_archive_source ON fc_archive_note(tenant_id,source_kind,source_id,created_at);
CREATE TABLE fc_event (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, source_kind VARCHAR(24) NOT NULL,
 source_id UUID NOT NULL, event VARCHAR(60) NOT NULL, snapshot JSONB NOT NULL, actor_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id)
);
CREATE INDEX ix_fc_event_source ON fc_event(tenant_id,source_kind,source_id,created_at);
DO $$ DECLARE tab TEXT; BEGIN
 FOREACH tab IN ARRAY ARRAY['fc_policy','fc_ledger_change','fc_contract_revision','fc_cash_instruction','fc_cash_batch','fc_travel','fc_bank_import','fc_claim_bank_match','fc_claim_loan_clear','fc_budget_usage','fc_archive_note','fc_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['fc_cash_batch','fc_claim_bank_match','fc_claim_loan_clear','fc_archive_note','fc_event'] LOOP
  EXECUTE format('CREATE TRIGGER fc_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
  GRANT SELECT,INSERT,UPDATE ON fc_policy,fc_ledger_change,fc_contract_revision,fc_cash_instruction,fc_travel,fc_bank_import,fc_budget_usage TO hotel_ai_os_app;
  GRANT SELECT,INSERT ON fc_cash_batch,fc_claim_bank_match,fc_claim_loan_clear,fc_archive_note,fc_event TO hotel_ai_os_app;
  REVOKE UPDATE,DELETE ON fc_cash_batch,fc_claim_bank_match,fc_claim_loan_clear,fc_archive_note,fc_event FROM hotel_ai_os_app;
  REVOKE DELETE ON fc_policy,fc_ledger_change,fc_contract_revision,fc_cash_instruction,fc_travel,fc_bank_import,fc_budget_usage FROM hotel_ai_os_app;
 END IF;
END $$;
CREATE FUNCTION fc_protect_published() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status IN ('PUBLISHED','RETIRED','APPROVED','EXECUTED') AND
    (TG_TABLE_NAME<>'fc_policy' OR OLD.status<>'PUBLISHED' OR NEW.status<>'RETIRED' OR
     (to_jsonb(OLD)-'status'-'published_by'-'version') IS DISTINCT FROM (to_jsonb(NEW)-'status'-'published_by'-'version')) THEN
  RAISE EXCEPTION 'published financial control is immutable; create a separately reviewed revision';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fc_policy_immutable BEFORE UPDATE ON fc_policy FOR EACH ROW EXECUTE FUNCTION fc_protect_published();
CREATE TRIGGER fc_ledger_immutable BEFORE UPDATE ON fc_ledger_change FOR EACH ROW EXECUTE FUNCTION fc_protect_published();
CREATE TRIGGER fc_contract_immutable BEFORE UPDATE ON fc_contract_revision FOR EACH ROW EXECUTE FUNCTION fc_protect_published();
