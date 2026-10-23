-- Configuration is a submission prerequisite, not a prerequisite for preserving an employee's draft.
ALTER TABLE finance_claim ALTER COLUMN company_id DROP NOT NULL;
ALTER TABLE finance_claim ALTER COLUMN manager_account_id DROP NOT NULL;
ALTER TABLE finance_claim ALTER COLUMN finance_account_id DROP NOT NULL;
ALTER TABLE finance_claim ALTER COLUMN leader_account_id DROP NOT NULL;
ALTER TABLE finance_claim ALTER COLUMN config_version SET DEFAULT -1;
ALTER TABLE finance_claim ADD COLUMN draft_invoice_candidates jsonb;
ALTER TABLE finance_claim ADD COLUMN draft_line_candidates jsonb;
ALTER TABLE finance_claim DROP CONSTRAINT finance_claim_complete_chain;
ALTER TABLE finance_claim ADD CONSTRAINT finance_claim_complete_chain CHECK(status IN ('DRAFT','RETURNED','WITHDRAWN') OR workflow_version=1 OR (manager_account_id IS NOT NULL AND finance_account_id IS NOT NULL AND director_account_id IS NOT NULL AND leader_account_id IS NOT NULL AND cashier_account_id IS NOT NULL));
ALTER TABLE finance_payment DROP CONSTRAINT finance_payment_tenant_id_claim_id_key;
ALTER TABLE finance_payment ADD CONSTRAINT finance_payment_tenant_id_id_key UNIQUE(tenant_id,id);
CREATE TABLE finance_payment_receipt (
 tenant_id uuid NOT NULL, payment_id uuid NOT NULL, attachment_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,payment_id,attachment_id),
 FOREIGN KEY(tenant_id,payment_id) REFERENCES finance_payment(tenant_id,id), FOREIGN KEY(tenant_id,attachment_id) REFERENCES finance_attachment(tenant_id,id)
);
CREATE TABLE finance_claim_line (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, claim_id uuid NOT NULL, sequence_no integer NOT NULL,
 category_id uuid NOT NULL, category_name varchar(80) NOT NULL, treatment varchar(24) NOT NULL,
 debit_account varchar(120) NOT NULL, description varchar(500) NOT NULL,
 quantity numeric(16,4) NOT NULL CHECK(quantity>0), unit_price numeric(18,4) NOT NULL CHECK(unit_price>=0),
 amount numeric(18,2) NOT NULL CHECK(amount>0), department_id uuid, allocation_month date NOT NULL,
 invoice_ids jsonb NOT NULL DEFAULT '[]', UNIQUE(tenant_id,id), UNIQUE(tenant_id,claim_id,sequence_no),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id),
 FOREIGN KEY(tenant_id,category_id) REFERENCES finance_category(tenant_id,id),
 FOREIGN KEY(tenant_id,department_id) REFERENCES org_unit(tenant_id,id)
);
CREATE INDEX ix_finance_line_claim ON finance_claim_line(tenant_id,claim_id);
-- Preserve each approved line as an immutable economic fact; legacy single-line facts remain untouched.
CREATE TABLE finance_expense_line_entry (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, claim_id uuid NOT NULL, line_id uuid NOT NULL,
 hotel_id uuid NOT NULL, category_name varchar(80) NOT NULL, treatment varchar(24) NOT NULL,
 amount numeric(18,2) NOT NULL CHECK(amount>0), business_date date NOT NULL, allocation_month date NOT NULL,
 snapshot jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,line_id),
 FOREIGN KEY(tenant_id,claim_id) REFERENCES finance_claim(tenant_id,id),
 FOREIGN KEY(tenant_id,line_id) REFERENCES finance_claim_line(tenant_id,id)
);
CREATE TABLE finance_invoice_verification (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, invoice_id uuid NOT NULL,
 target_kind varchar(16) NOT NULL DEFAULT 'CLAIM' CHECK(target_kind IN ('CLAIM','INTAKE')),
 status varchar(32) NOT NULL CHECK(status IN ('MANUAL_CONFIRMED','EXTERNAL_VERIFIED','EXTERNAL_FAILED')),
 provider varchar(120) NOT NULL, external_reference varchar(180) NOT NULL DEFAULT '',
 note varchar(2000) NOT NULL, invoice_snapshot jsonb NOT NULL, actor_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id),
 CHECK(status='MANUAL_CONFIRMED' OR external_reference<>'')
);
CREATE TABLE finance_invoice_intake (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,owner_id uuid NOT NULL,assignment_id uuid NOT NULL,hotel_id uuid NOT NULL,
 invoice_code varchar(20) NOT NULL DEFAULT '',invoice_number varchar(24) NOT NULL DEFAULT '',amount numeric(18,2) CHECK(amount>0),
 buyer_tax_number varchar(32) NOT NULL DEFAULT '',confirmed boolean NOT NULL DEFAULT false,
 original jsonb,linked_claim_id uuid,status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','LINKED')),
 version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,owner_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,linked_claim_id) REFERENCES finance_claim(tenant_id,id)
);
CREATE INDEX ix_finance_intake_owner ON finance_invoice_intake(tenant_id,owner_id,created_at DESC,id);
CREATE INDEX ix_finance_intake_scope ON finance_invoice_intake(tenant_id,hotel_id,created_at DESC,id);
CREATE FUNCTION protect_finance_invoice_intake_original() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.original IS NOT NULL AND NEW.original IS DISTINCT FROM OLD.original THEN RAISE EXCEPTION 'Financial intake originals are immutable'; END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.owner_id<>OLD.owner_id OR NEW.hotel_id<>OLD.hotel_id OR NEW.assignment_id<>OLD.assignment_id THEN RAISE EXCEPTION 'Financial intake ownership is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_intake_original_immutable BEFORE UPDATE OR DELETE ON finance_invoice_intake FOR EACH ROW EXECUTE FUNCTION protect_finance_invoice_intake_original();
CREATE INDEX ix_finance_verification_invoice ON finance_invoice_verification(tenant_id,target_kind,invoice_id,created_at DESC);
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['finance_claim_line','finance_expense_line_entry','finance_invoice_verification','finance_payment_receipt','finance_invoice_intake'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
 END LOOP;
 CREATE TRIGGER finance_fact_immutable BEFORE UPDATE OR DELETE ON finance_expense_line_entry FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
 CREATE TRIGGER finance_fact_immutable BEFORE UPDATE OR DELETE ON finance_invoice_verification FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
 CREATE TRIGGER finance_fact_immutable BEFORE UPDATE OR DELETE ON finance_payment_receipt FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
  GRANT SELECT,INSERT,UPDATE,DELETE ON finance_claim_line TO hotel_ai_os_app;
  GRANT SELECT,INSERT ON finance_expense_line_entry,finance_invoice_verification TO hotel_ai_os_app;
  GRANT SELECT,INSERT ON finance_payment_receipt TO hotel_ai_os_app;
  REVOKE UPDATE,DELETE ON finance_expense_line_entry,finance_invoice_verification,finance_payment_receipt FROM hotel_ai_os_app;
  GRANT SELECT,INSERT,UPDATE ON finance_invoice_intake TO hotel_ai_os_app;
 END IF;
END $$;
