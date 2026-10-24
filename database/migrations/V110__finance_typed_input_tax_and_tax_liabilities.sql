-- Real invoice tax confirmations and actual declared liabilities; no default tax rates or external execution.
CREATE TABLE ft_input_basis(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,hotel_id uuid NOT NULL,company_id uuid NOT NULL,
 document_kind varchar(8) NOT NULL CHECK(document_kind IN ('BLUE','RED')),
 source_kind varchar(24) NOT NULL CHECK(source_kind IN ('CLAIM','PROCUREMENT','PROCUREMENT_RED')),
 invoice_id uuid NOT NULL,source_parent_id uuid NOT NULL,invoice_key varchar(80) NOT NULL,red_of_basis_id uuid,
 net_amount numeric(18,2) NOT NULL CHECK(net_amount>=0),tax_amount numeric(18,2) NOT NULL CHECK(tax_amount>=0),gross_amount numeric(18,2) NOT NULL CHECK(gross_amount>0),eligible_tax numeric(18,2) NOT NULL CHECK(eligible_tax>=0 AND eligible_tax<=tax_amount),
 source_hash char(64) NOT NULL,source_snapshot jsonb NOT NULL,purpose varchar(500) NOT NULL,note varchar(2000) NOT NULL,request_hash char(64) NOT NULL,
 status varchar(12) NOT NULL DEFAULT 'REVIEW' CHECK(status IN ('REVIEW','CONFIRMED','REJECTED')),
 created_by uuid NOT NULL,reviewer_id uuid NOT NULL,reviewed_by uuid,review_note varchar(2000),reviewed_at timestamptz,version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),FOREIGN KEY(tenant_id,red_of_basis_id) REFERENCES ft_input_basis(tenant_id,id),
 CHECK(net_amount+tax_amount=gross_amount),CHECK((document_kind='BLUE' AND source_kind IN ('CLAIM','PROCUREMENT') AND red_of_basis_id IS NULL) OR(document_kind='RED' AND source_kind='PROCUREMENT_RED' AND red_of_basis_id IS NOT NULL)),CHECK(created_by<>reviewer_id)
);
CREATE UNIQUE INDEX ft_input_live_original ON ft_input_basis(tenant_id,invoice_key) WHERE status IN ('REVIEW','CONFIRMED');
CREATE INDEX ft_input_scope ON ft_input_basis(tenant_id,hotel_id,status,source_kind,invoice_id);
CREATE INDEX ft_input_red_parent ON ft_input_basis(tenant_id,red_of_basis_id,status);
ALTER TABLE fb_input_tax ADD COLUMN source_kind varchar(24) NOT NULL DEFAULT 'CLAIM' CHECK(source_kind IN ('CLAIM','PROCUREMENT'));
ALTER TABLE fb_input_tax ADD COLUMN basis_id uuid,ADD COLUMN invoice_key varchar(80),ADD COLUMN source_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE fb_input_tax ALTER COLUMN purpose TYPE varchar(500);
ALTER TABLE fb_input_tax ADD CONSTRAINT fb_input_tax_basis FOREIGN KEY(tenant_id,basis_id) REFERENCES ft_input_basis(tenant_id,id);
CREATE INDEX fb_input_tax_actual_source ON fb_input_tax(tenant_id,source_kind,invoice_id,month);
CREATE TABLE ft_tax_accrual(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,hotel_id uuid NOT NULL,company_id uuid NOT NULL,return_id uuid NOT NULL,policy_id uuid NOT NULL,source_hash char(64) NOT NULL,
 tax_kind varchar(100) NOT NULL,amount numeric(18,2) NOT NULL CHECK(amount>0),business_date date NOT NULL,accounting_on date NOT NULL,
 status varchar(12) NOT NULL DEFAULT 'REVIEW' CHECK(status IN ('REVIEW','CONFIRMED','REJECTED')),source_snapshot jsonb NOT NULL,request_hash char(64) NOT NULL,note varchar(2000) NOT NULL,
 created_by uuid NOT NULL,reviewer_id uuid NOT NULL,cashier_id uuid NOT NULL,reviewed_by uuid,review_note varchar(2000),reviewed_at timestamptz,version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,return_id) REFERENCES fb_tax_return(tenant_id,id),FOREIGN KEY(tenant_id,policy_id) REFERENCES fb_tax_policy(tenant_id,id),CHECK(created_by<>reviewer_id AND created_by<>cashier_id AND reviewer_id<>cashier_id)
);
CREATE UNIQUE INDEX ft_tax_accrual_once ON ft_tax_accrual(tenant_id,return_id) WHERE status IN ('REVIEW','CONFIRMED');
CREATE INDEX ft_tax_accrual_scope ON ft_tax_accrual(tenant_id,hotel_id,status,accounting_on);
CREATE TABLE ft_tax_payment(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,accrual_id uuid NOT NULL,hotel_id uuid NOT NULL,company_id uuid NOT NULL,tax_receipt_id uuid NOT NULL,
 bank_account_id uuid NOT NULL,bank_transaction_id uuid NOT NULL,amount numeric(18,2) NOT NULL CHECK(amount>0),occurred_on date NOT NULL,accounting_on date NOT NULL,
 source_snapshot jsonb NOT NULL,request_hash char(64) NOT NULL,note varchar(2000) NOT NULL,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,accrual_id) REFERENCES ft_tax_accrual(tenant_id,id),FOREIGN KEY(tenant_id,tax_receipt_id) REFERENCES fb_tax_receipt(tenant_id,id),FOREIGN KEY(tenant_id,bank_transaction_id) REFERENCES fo_bank_transaction(tenant_id,id),FOREIGN KEY(tenant_id,bank_account_id) REFERENCES fo_bank_account(tenant_id,id)
);
CREATE INDEX ft_tax_payment_source ON ft_tax_payment(tenant_id,accrual_id,accounting_on);
CREATE INDEX ft_tax_payment_bank ON ft_tax_payment(tenant_id,bank_transaction_id,accounting_on);
ALTER TABLE fb_bank_match DROP CONSTRAINT fb_bank_match_source_type_check;
ALTER TABLE fb_bank_match ADD CONSTRAINT fb_bank_match_source_type_check CHECK(source_type IN ('REVENUE','PAYROLL','OPENING','INTERCOMPANY','TAX'));
ALTER TABLE fb_cash_correction DROP CONSTRAINT fb_cash_correction_source_type_check;
ALTER TABLE fb_cash_correction ADD CONSTRAINT fb_cash_correction_source_type_check CHECK(source_type IN ('REVENUE','PAYROLL','OPENING','INTERCOMPANY','TAX'));
CREATE FUNCTION ft_tax_bank_allocated_amount(t uuid,b uuid,as_of date) RETURNS numeric LANGUAGE sql STABLE AS $$ SELECT coalesce(sum(m.amount-coalesce((SELECT sum(r.amount) FROM fb_bank_match_reversal r WHERE r.tenant_id=m.tenant_id AND r.match_id=m.id AND r.accounting_on<=as_of),0)),0) FROM fb_bank_match m WHERE m.tenant_id=t AND m.transaction_id=b AND m.source_type='TAX' AND (m.created_at AT TIME ZONE 'Asia/Shanghai')::date<=as_of $$;
CREATE FUNCTION ft_tax_paid_amount(t uuid,s uuid,as_of date) RETURNS numeric LANGUAGE sql STABLE AS $$ SELECT coalesce(sum(fb_cash_effective_amount(tenant_id,'TAX',id,amount,as_of)),0) FROM ft_tax_payment WHERE tenant_id=t AND accrual_id=s AND accounting_on<=as_of $$;
CREATE FUNCTION ft_freeze_reviewed() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (to_jsonb(NEW)-'status'-'reviewed_by'-'review_note'-'reviewed_at'-'version') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'reviewed_by'-'review_note'-'reviewed_at'-'version') OR OLD.status<>'REVIEW' THEN RAISE EXCEPTION 'Tax basis and liability facts are immutable; preserve original sources and independent decisions';END IF;RETURN NEW;END $$;
CREATE TRIGGER ft_input_basis_frozen BEFORE UPDATE ON ft_input_basis FOR EACH ROW EXECUTE FUNCTION ft_freeze_reviewed();
CREATE TRIGGER ft_tax_accrual_frozen BEFORE UPDATE ON ft_tax_accrual FOR EACH ROW EXECUTE FUNCTION ft_freeze_reviewed();
CREATE FUNCTION ft_no_delete_fact() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Tax original facts are immutable; preserve the original record and append a controlled correction';END $$;
CREATE TRIGGER ft_input_basis_no_delete BEFORE DELETE ON ft_input_basis FOR EACH ROW EXECUTE FUNCTION ft_no_delete_fact();
CREATE TRIGGER ft_tax_accrual_no_delete BEFORE DELETE ON ft_tax_accrual FOR EACH ROW EXECUTE FUNCTION ft_no_delete_fact();
CREATE TRIGGER ft_tax_payment_immutable BEFORE UPDATE OR DELETE ON ft_tax_payment FOR EACH ROW EXECUTE FUNCTION ft_no_delete_fact();
DO $$ DECLARE t text;BEGIN
 FOREACH t IN ARRAY ARRAY['ft_input_basis','ft_tax_accrual','ft_tax_payment'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',t);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT ON %I TO hotel_ai_os_app',t);EXECUTE format('REVOKE UPDATE,DELETE ON %I FROM hotel_ai_os_app',t);END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN GRANT UPDATE ON ft_input_basis,ft_tax_accrual TO hotel_ai_os_app;END IF;
END $$;
COMMENT ON TABLE ft_input_basis IS 'Explicit original-invoice net/tax split and permitted deductible basis; independently reviewed, never calculated from a guessed tax rate.';
COMMENT ON TABLE ft_tax_accrual IS 'Independently confirmed actual payable tax from a frozen return; confirmation is not external filing and ledger posting remains separate.';
