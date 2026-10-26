-- Formal accounting is a separate immutable ledger. Legacy drafts remain untouched.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category) VALUES
 ('finance.accounting.read','finance','accounting.read','读取授权账套账簿和财务报表',true,'FINANCE'),
 ('finance.accounting.write','finance','accounting.write','编制手工及来源凭证',true,'FINANCE'),
 ('finance.accounting.review','finance','accounting.review','审核非本人编制的凭证',true,'FINANCE'),
 ('finance.accounting.post','finance','accounting.post','凭证过账和红冲',true,'FINANCE'),
 ('finance.accounting.close','finance','accounting.close','月结、年结及经授权重开期间',true,'FINANCE') ON CONFLICT(code) DO NOTHING;

CREATE TABLE fa_book (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL REFERENCES tenant(id),
 hotel_id uuid NOT NULL,company_id uuid NOT NULL,name varchar(160) NOT NULL,currency varchar(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'),
 start_month date NOT NULL CHECK(extract(day from start_month)=1),enabled boolean NOT NULL DEFAULT true,
 version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id)
);
CREATE UNIQUE INDEX fa_book_active_hotel ON fa_book(tenant_id,hotel_id) WHERE enabled;
CREATE TABLE fa_account (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL,book_id uuid NOT NULL,
 code varchar(32) NOT NULL,name varchar(120) NOT NULL,category varchar(20) NOT NULL CHECK(category IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
 normal_side varchar(6) NOT NULL CHECK(normal_side IN ('DEBIT','CREDIT')),
 report_line varchar(80) NOT NULL,cash_account boolean NOT NULL DEFAULT false,enabled boolean NOT NULL DEFAULT true,version integer NOT NULL DEFAULT 0,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,book_id,code),UNIQUE(tenant_id,book_id,id),FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id)
);
CREATE TABLE fa_period (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,month date NOT NULL CHECK(extract(day from month)=1),
 status varchar(12) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED')),version integer NOT NULL DEFAULT 0,
 closed_by uuid,closed_at timestamptz,close_snapshot jsonb,PRIMARY KEY(tenant_id,book_id,month),
 FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),FOREIGN KEY(tenant_id,closed_by) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE fa_voucher (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,month date NOT NULL,
 business_date date NOT NULL,accounting_date date NOT NULL,summary varchar(500) NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN ('MANUAL','SOURCE','REVERSAL','YEAR_CLOSE')),
 status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','REVIEWED','POSTED')),
 created_by uuid NOT NULL,reviewed_by uuid,posted_by uuid,created_at timestamptz NOT NULL DEFAULT now(),reviewed_at timestamptz,posted_at timestamptz,
 reverses_id uuid,reversed_by_id uuid,rule_version integer,version integer NOT NULL DEFAULT 0,request_hash varchar(64) NOT NULL,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,book_id,id),UNIQUE(tenant_id,reverses_id),
 FOREIGN KEY(tenant_id,book_id,month) REFERENCES fa_period(tenant_id,book_id,month),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id),FOREIGN KEY(tenant_id,reviewed_by) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,posted_by) REFERENCES user_account(tenant_id,id),FOREIGN KEY(tenant_id,reverses_id) REFERENCES fa_voucher(tenant_id,id),
 FOREIGN KEY(tenant_id,reversed_by_id) REFERENCES fa_voucher(tenant_id,id),CHECK(reviewed_by IS NULL OR reviewed_by<>created_by),
 CHECK(month=date_trunc('month',accounting_date)::date),CHECK(status='DRAFT' OR reviewed_by IS NOT NULL),CHECK(status<>'POSTED' OR posted_at IS NOT NULL)
);
CREATE INDEX fa_voucher_book_month ON fa_voucher(tenant_id,book_id,month,status,accounting_date,id);
CREATE TABLE fa_voucher_line (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL,book_id uuid NOT NULL,voucher_id uuid NOT NULL,line_no integer NOT NULL,
 account_id uuid NOT NULL,debit numeric(18,2) NOT NULL DEFAULT 0,credit numeric(18,2) NOT NULL DEFAULT 0,summary varchar(500) NOT NULL DEFAULT '',
 cash_flow_category varchar(20) CHECK(cash_flow_category IN ('OPERATING','INVESTING','FINANCING','TRANSFER')),
 UNIQUE(tenant_id,voucher_id,line_no),FOREIGN KEY(tenant_id,book_id,voucher_id) REFERENCES fa_voucher(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,account_id) REFERENCES fa_account(tenant_id,book_id,id),
 CHECK((debit>0 AND credit=0) OR (credit>0 AND debit=0))
);
CREATE INDEX fa_voucher_line_account ON fa_voucher_line(tenant_id,book_id,account_id,voucher_id);
CREATE TABLE fa_posting_rule (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL,book_id uuid NOT NULL,source_type varchar(60) NOT NULL,event_key varchar(120) NOT NULL,
 debit_account_id uuid NOT NULL,credit_account_id uuid NOT NULL,cash_flow_category varchar(20) CHECK(cash_flow_category IN ('OPERATING','INVESTING','FINANCING','TRANSFER')),
 version integer NOT NULL DEFAULT 0,enabled boolean NOT NULL DEFAULT true,confirmed_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,book_id,source_type,event_key),FOREIGN KEY(tenant_id,book_id,debit_account_id) REFERENCES fa_account(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,credit_account_id) REFERENCES fa_account(tenant_id,book_id,id),FOREIGN KEY(tenant_id,confirmed_by) REFERENCES user_account(tenant_id,id),CHECK(debit_account_id<>credit_account_id)
);
CREATE TABLE fa_source_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL,hotel_id uuid NOT NULL,book_id uuid,company_id uuid,source_type varchar(60) NOT NULL,
 source_id uuid NOT NULL,event_key varchar(120) NOT NULL,business_date date NOT NULL,accounting_date date NOT NULL,amount numeric(18,2) NOT NULL CHECK(amount>0),
 summary varchar(500) NOT NULL,metadata jsonb NOT NULL DEFAULT '{}',request_hash varchar(64) NOT NULL,
 status varchar(24) NOT NULL CHECK(status IN ('PENDING_BOOK','PENDING_ENTITY_MISMATCH','PENDING_MAPPING','PENDING_PERIOD','DRAFT','POSTED')),
 voucher_id uuid,rule_snapshot jsonb,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,source_type,source_id,event_key),UNIQUE(tenant_id,voucher_id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),FOREIGN KEY(tenant_id,voucher_id) REFERENCES fa_voucher(tenant_id,id)
);
CREATE INDEX fa_source_event_month ON fa_source_event(tenant_id,hotel_id,accounting_date,status);
CREATE TABLE fa_import_batch (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,source_reference varchar(160) NOT NULL,request_hash varchar(64) NOT NULL,
 imported_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),reconciliation jsonb NOT NULL,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,book_id),FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),FOREIGN KEY(tenant_id,imported_by) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE fa_opening_balance (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,account_id uuid NOT NULL,batch_id uuid NOT NULL,
 debit numeric(18,2) NOT NULL CHECK(debit>=0),credit numeric(18,2) NOT NULL CHECK(credit>=0),
 PRIMARY KEY(tenant_id,book_id,account_id),FOREIGN KEY(tenant_id,book_id,account_id) REFERENCES fa_account(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,batch_id) REFERENCES fa_import_batch(tenant_id,id),CHECK(debit=0 OR credit=0)
);
CREATE TABLE fa_source_check (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL,book_id uuid NOT NULL,month date NOT NULL,source_type varchar(60) NOT NULL,
 complete boolean NOT NULL DEFAULT false,expected_count integer CHECK(expected_count>=0),actual_count integer CHECK(actual_count>=0),
 note varchar(2000) NOT NULL,confirmed_by uuid NOT NULL,version integer NOT NULL DEFAULT 0,
 UNIQUE(tenant_id,book_id,month,source_type),FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),FOREIGN KEY(tenant_id,confirmed_by) REFERENCES user_account(tenant_id,id),
 CHECK(extract(day from month)=1),CHECK(NOT complete OR expected_count IS NOT NULL AND actual_count=expected_count)
);
CREATE TABLE fa_recurring_plan (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN ('DEPRECIATION','AMORTIZATION','ALLOCATION','ACCRUAL')),
 source_type varchar(60) NOT NULL,source_id uuid NOT NULL,source_reference varchar(240) NOT NULL,
 start_month date NOT NULL,end_month date NOT NULL,monthly_amount numeric(18,2) NOT NULL CHECK(monthly_amount>0),total_amount numeric(18,2) NOT NULL CHECK(total_amount>0),
 debit_account_id uuid NOT NULL,credit_account_id uuid NOT NULL,summary varchar(500) NOT NULL,active boolean NOT NULL DEFAULT true,
 request_hash varchar(64) NOT NULL,source_snapshot jsonb NOT NULL DEFAULT '{}',created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),version integer NOT NULL DEFAULT 0,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,book_id,source_type,source_id,kind),
 FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),FOREIGN KEY(tenant_id,book_id,debit_account_id) REFERENCES fa_account(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,credit_account_id) REFERENCES fa_account(tenant_id,book_id,id),FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id),
 CHECK(extract(day from start_month)=1 AND extract(day from end_month)=1 AND end_month>=start_month),CHECK(monthly_amount<=total_amount),CHECK(debit_account_id<>credit_account_id)
);
CREATE INDEX fa_recurring_plan_period ON fa_recurring_plan(tenant_id,book_id,start_month,end_month) WHERE active;
CREATE TABLE fa_recurring_run (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,plan_id uuid NOT NULL,month date NOT NULL,amount numeric(18,2) NOT NULL CHECK(amount>0),
 voucher_id uuid NOT NULL,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,plan_id,month),UNIQUE(tenant_id,voucher_id),
 FOREIGN KEY(tenant_id,plan_id) REFERENCES fa_recurring_plan(tenant_id,id),FOREIGN KEY(tenant_id,book_id,voucher_id) REFERENCES fa_voucher(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id)
);
-- Guard persisted accounting facts even if a future caller bypasses service checks.
CREATE FUNCTION fa_protect_voucher() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' OR NEW.reviewed_by IS NOT NULL OR NEW.posted_by IS NOT NULL OR NEW.reviewed_at IS NOT NULL OR NEW.posted_at IS NOT NULL THEN RAISE EXCEPTION 'Accounting voucher must start as an unreviewed draft'; END IF;
  IF (SELECT status FROM fa_period WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND month=NEW.month FOR UPDATE)<>'OPEN' THEN RAISE EXCEPTION 'Open accounting period required'; END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Accounting vouchers cannot be deleted'; END IF;
 IF OLD.status='POSTED' AND (to_jsonb(NEW)-'reversed_by_id'-'version') IS DISTINCT FROM (to_jsonb(OLD)-'reversed_by_id'-'version') THEN
  RAISE EXCEPTION 'Posted accounting voucher is immutable';
 END IF;
 IF OLD.status='POSTED' AND (OLD.reversed_by_id IS NOT NULL OR NEW.reversed_by_id IS NULL) THEN RAISE EXCEPTION 'Reversal link is immutable'; END IF;
 IF NEW.status='POSTED' AND OLD.status<>'POSTED' THEN
  IF OLD.status<>'REVIEWED' OR (SELECT status FROM fa_period WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND month=NEW.month)<>'OPEN' THEN RAISE EXCEPTION 'Reviewed voucher and open period required'; END IF;
  IF (SELECT count(*)<2 OR coalesce(sum(debit),0)<>coalesce(sum(credit),0) FROM fa_voucher_line WHERE tenant_id=NEW.tenant_id AND voucher_id=NEW.id) THEN RAISE EXCEPTION 'Unbalanced accounting voucher'; END IF;
  IF EXISTS(SELECT 1 FROM fa_voucher_line l JOIN fa_account a ON a.tenant_id=l.tenant_id AND a.id=l.account_id WHERE l.tenant_id=NEW.tenant_id AND l.voucher_id=NEW.id AND (a.cash_account AND l.cash_flow_category IS NULL OR NOT a.cash_account AND l.cash_flow_category IS NOT NULL)) THEN RAISE EXCEPTION 'Cash flow classification required before posting'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fa_voucher_protection BEFORE INSERT OR UPDATE OR DELETE ON fa_voucher FOR EACH ROW EXECUTE FUNCTION fa_protect_voucher();
CREATE FUNCTION fa_protect_line_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (SELECT status FROM fa_voucher WHERE tenant_id=NEW.tenant_id AND id=NEW.voucher_id)<>'DRAFT' THEN RAISE EXCEPTION 'Cannot append to reviewed or posted voucher'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fa_line_insert_guard BEFORE INSERT ON fa_voucher_line FOR EACH ROW EXECUTE FUNCTION fa_protect_line_insert();
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['fa_book','fa_account','fa_period','fa_voucher','fa_voucher_line','fa_posting_rule','fa_source_event','fa_import_batch','fa_opening_balance','fa_source_check','fa_recurring_plan','fa_recurring_run'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO hotel_ai_os_app',tab); EXECUTE format('REVOKE DELETE ON %I FROM hotel_ai_os_app',tab); END IF;
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['fa_voucher_line','fa_import_batch','fa_opening_balance','fa_recurring_run'] LOOP
  EXECUTE format('CREATE TRIGGER fa_fact_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('REVOKE UPDATE ON %I FROM hotel_ai_os_app',tab); END IF;
 END LOOP;
END $$;
