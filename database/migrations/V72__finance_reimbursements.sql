-- Finance evidence is deliberately separate from the operational photo-retention tables.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category) VALUES
 ('ui.module.finance','ui_module','view','财务中心入口；不额外授予审核或付款权限',true,'UI_MODULE'),
 ('finance.submit','finance','submit','创建、修改及提交本人报销',true,'FINANCE'),
 ('finance.review','finance','review','按门店配置的审核链审核报销；禁止自审',true,'FINANCE'),
 ('finance.read','finance','read','读取授权门店财务台账和凭证',true,'FINANCE'),
 ('finance.pay','finance','pay','登记已审批报销的实际付款；不自动转账',true,'FINANCE'),
 ('finance.configure','finance','configure','配置法人公司、费用分类与门店审核人',false,'FINANCE')
ON CONFLICT(code) DO NOTHING;

-- Preserve the existing platform-admin wildcard contract. Other positions require explicit grants.
-- A wildcard still cannot approve unless the account is the configured reviewer for that stage.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$ DECLARE t RECORD; BEGIN
 FOR t IN SELECT id FROM tenant LOOP
  PERFORM set_config('app.tenant_id',t.id::text,true);
  INSERT INTO role_permission(tenant_id,role_id,permission_id)
   SELECT t.id,r.id,p.id FROM app_role r CROSS JOIN permission p
    WHERE r.tenant_id=t.id AND r.role_type='SYSTEM' AND r.code='PLATFORM_ADMIN'
      AND p.code IN ('ui.module.finance','finance.configure') ON CONFLICT DO NOTHING;
 END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

CREATE TABLE finance_company (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenant(id),
 name VARCHAR(160) NOT NULL, tax_number VARCHAR(32) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,tax_number)
);
CREATE TABLE finance_store_config (
 tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, company_id UUID NOT NULL,
 manager_account_id UUID NOT NULL, finance_account_id UUID NOT NULL, leader_account_id UUID NOT NULL,
 payable_account VARCHAR(120) NOT NULL DEFAULT '', version INTEGER NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT false, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,hotel_id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),
 FOREIGN KEY(tenant_id,manager_account_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,finance_account_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,leader_account_id) REFERENCES user_account(tenant_id,id),
 CHECK(manager_account_id<>finance_account_id AND manager_account_id<>leader_account_id AND finance_account_id<>leader_account_id)
);
CREATE TABLE finance_category (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, hotel_id UUID NOT NULL,
 name VARCHAR(80) NOT NULL, treatment VARCHAR(24) NOT NULL CHECK(treatment IN ('EXPENSE','INVENTORY','ASSET','PREPAID','ALREADY_RECORDED')),
 keywords VARCHAR(500) NOT NULL DEFAULT '', debit_account VARCHAR(120) NOT NULL DEFAULT '',
 enabled BOOLEAN NOT NULL DEFAULT true, version INTEGER NOT NULL DEFAULT 0,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,hotel_id,id), UNIQUE(tenant_id,hotel_id,name),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id)
);
CREATE TABLE finance_claim (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, applicant_id UUID NOT NULL, assignment_id UUID NOT NULL,
 hotel_id UUID NOT NULL, company_id UUID NOT NULL, applicant_name VARCHAR(120) NOT NULL,
 hotel_name VARCHAR(160) NOT NULL, company_name VARCHAR(160) NOT NULL, company_tax_number VARCHAR(32) NOT NULL,
 title VARCHAR(160) NOT NULL DEFAULT '', business_date DATE NOT NULL,
 category_id UUID, category_name VARCHAR(80) NOT NULL DEFAULT '', treatment VARCHAR(24),
 debit_account VARCHAR(120) NOT NULL DEFAULT '', payable_account VARCHAR(120) NOT NULL DEFAULT '',
 invoice_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(invoice_amount>=0),
 transaction_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(transaction_amount>=0),
 payment_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(payment_amount>=0),
 requested_amount NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(requested_amount>=0),
 approved_amount NUMERIC(18,2) CHECK(approved_amount>0),
 difference_note VARCHAR(2000) NOT NULL DEFAULT '', mismatch BOOLEAN NOT NULL DEFAULT false,
 status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','RETURNED','MANAGER_REVIEW','FINANCE_REVIEW','LEADER_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
 manager_account_id UUID NOT NULL, finance_account_id UUID NOT NULL, leader_account_id UUID NOT NULL,
 config_version INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 0,
 submitted_at TIMESTAMPTZ, approved_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,applicant_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),
 FOREIGN KEY(tenant_id,hotel_id,category_id) REFERENCES finance_category(tenant_id,hotel_id,id),
 FOREIGN KEY(tenant_id,manager_account_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,finance_account_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,leader_account_id) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_finance_claim_owner ON finance_claim(tenant_id,applicant_id,updated_at DESC,id);
CREATE INDEX ix_finance_claim_scope ON finance_claim(tenant_id,hotel_id,business_date,id);
CREATE INDEX ix_finance_claim_review ON finance_claim(tenant_id,status,submitted_at,id);

CREATE TABLE finance_attachment (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, claim_id UUID NOT NULL,
 kind VARCHAR(20) NOT NULL CHECK(kind IN ('INVOICE','TRANSACTION','PAYMENT')),
 original_name VARCHAR(180) NOT NULL, media_type VARCHAR(80) NOT NULL,
 size_bytes BIGINT NOT NULL CHECK(size_bytes>0 AND size_bytes<=20971520),
 sha256 CHAR(64) NOT NULL, object_key VARCHAR(400) NOT NULL,
 scan_status VARCHAR(24) NOT NULL, extraction JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), retired_at TIMESTAMPTZ,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,claim_id,id),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id)
);
CREATE INDEX ix_finance_attachment_claim ON finance_attachment(tenant_id,claim_id);
CREATE UNIQUE INDEX uq_finance_attachment_active_hash ON finance_attachment(tenant_id,claim_id,kind,sha256) WHERE retired_at IS NULL;
CREATE TABLE finance_invoice (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, claim_id UUID NOT NULL, attachment_id UUID NOT NULL,
 invoice_code VARCHAR(20) NOT NULL DEFAULT '', invoice_number VARCHAR(24) NOT NULL,
 invoice_key VARCHAR(70) NOT NULL, amount NUMERIC(18,2) NOT NULL CHECK(amount>0),
 buyer_tax_number VARCHAR(32) NOT NULL, confirmed BOOLEAN NOT NULL DEFAULT false,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,claim_id,invoice_key),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id),
 FOREIGN KEY(tenant_id,claim_id,attachment_id) REFERENCES finance_attachment(tenant_id,claim_id,id)
);
CREATE INDEX ix_finance_invoice_claim ON finance_invoice(tenant_id,claim_id);

-- A shared registry also supports already-paid historical imports without inventing a live claim.
CREATE TABLE finance_invoice_reservation (
 tenant_id UUID NOT NULL, invoice_key VARCHAR(70) NOT NULL, claim_id UUID,
 historical_reference VARCHAR(160), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,invoice_key),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id),
 CHECK((claim_id IS NULL)<>(historical_reference IS NULL))
);
CREATE INDEX ix_finance_invoice_reservation_claim ON finance_invoice_reservation(tenant_id,claim_id) WHERE claim_id IS NOT NULL;
CREATE TABLE finance_evidence_reservation (
 tenant_id UUID NOT NULL, sha256 CHAR(64) NOT NULL, claim_id UUID NOT NULL,
 PRIMARY KEY(tenant_id,sha256), FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id)
);
CREATE INDEX ix_finance_evidence_reservation_claim ON finance_evidence_reservation(tenant_id,claim_id);
CREATE TABLE finance_review (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, claim_id UUID NOT NULL,
 actor_id UUID NOT NULL, stage VARCHAR(24) NOT NULL, decision VARCHAR(16) NOT NULL,
 note VARCHAR(2000) NOT NULL, request_id UUID NOT NULL, request_hash CHAR(64) NOT NULL,
 review_snapshot JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,claim_id,request_id),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_finance_review_claim ON finance_review(tenant_id,claim_id,created_at);
CREATE TABLE finance_expense_entry (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, claim_id UUID NOT NULL,
 hotel_id UUID NOT NULL, company_name VARCHAR(160) NOT NULL, category_name VARCHAR(80) NOT NULL,
 treatment VARCHAR(24) NOT NULL, amount NUMERIC(18,2) NOT NULL CHECK(amount>0),
 business_date DATE NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,claim_id), FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id)
);
CREATE INDEX ix_finance_expense_period ON finance_expense_entry(tenant_id,hotel_id,business_date,id);
CREATE TABLE finance_voucher_draft (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, claim_id UUID NOT NULL,
 snapshot JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,claim_id), FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id)
);
CREATE TABLE finance_payment (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, claim_id UUID NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0), reference VARCHAR(160) NOT NULL,
 paid_on DATE NOT NULL, actor_id UUID NOT NULL, request_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,claim_id), UNIQUE(tenant_id,request_id),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id)
);

DO $$ DECLARE tab TEXT; BEGIN
 FOREACH tab IN ARRAY ARRAY['finance_company','finance_store_config','finance_category','finance_claim','finance_attachment',
  'finance_invoice','finance_invoice_reservation','finance_evidence_reservation','finance_review','finance_expense_entry',
  'finance_voucher_draft','finance_payment'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['finance_review','finance_expense_entry','finance_voucher_draft','finance_payment'] LOOP
  EXECUTE format('CREATE TRIGGER finance_fact_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);
 END LOOP;
END $$;

-- Removing a draft attachment only retires it from the current form. Prior review snapshots retain a readable original.
CREATE FUNCTION protect_finance_evidence_original() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Finance evidence originals cannot be deleted'; END IF;
 IF (to_jsonb(NEW)-'retired_at') IS DISTINCT FROM (to_jsonb(OLD)-'retired_at') OR OLD.retired_at IS NOT NULL OR NEW.retired_at IS NULL THEN
  RAISE EXCEPTION 'Finance evidence original metadata is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_evidence_original_immutable BEFORE UPDATE OR DELETE ON finance_attachment FOR EACH ROW EXECUTE FUNCTION protect_finance_evidence_original();

DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
  GRANT SELECT,INSERT ON finance_review,finance_expense_entry,finance_voucher_draft,finance_payment TO hotel_ai_os_app;
  REVOKE UPDATE,DELETE ON finance_review,finance_expense_entry,finance_voucher_draft,finance_payment FROM hotel_ai_os_app;
  GRANT SELECT,INSERT,UPDATE ON finance_attachment TO hotel_ai_os_app;
  REVOKE DELETE ON finance_attachment FROM hotel_ai_os_app;
  GRANT SELECT,INSERT,UPDATE,DELETE ON finance_company,finance_store_config,finance_category,finance_claim,
    finance_invoice,finance_invoice_reservation,finance_evidence_reservation TO hotel_ai_os_app;
 END IF;
END $$;
