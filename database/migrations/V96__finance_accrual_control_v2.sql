CREATE TABLE far_policy (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,company_id uuid NOT NULL,name varchar(160) NOT NULL,
 kind varchar(24) NOT NULL CHECK(kind IN ('PREPAID','LEASE_ACCRUAL')),method varchar(40) NOT NULL CHECK(method='MONTHLY_STRAIGHT_LINE'),
 policy_reference varchar(2000) NOT NULL,status varchar(16) NOT NULL CHECK(status IN ('DRAFT','PUBLISHED')),
 reviewer_id uuid NOT NULL,created_by uuid NOT NULL,published_by uuid,version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id)
);
CREATE TABLE far_plan (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,hotel_id uuid NOT NULL,company_id uuid NOT NULL,
 source_document_id uuid,opening_subledger_id uuid,policy_id uuid NOT NULL,title varchar(160) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('DRAFT','ACTIVE','SUSPENDED','TERMINATING','TERMINATED')),active_revision_id uuid,
 created_by uuid NOT NULL,created_assignment_id uuid,version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,source_document_id),UNIQUE(tenant_id,opening_subledger_id),
 FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),FOREIGN KEY(tenant_id,source_document_id) REFERENCES fo_document(tenant_id,id),
 FOREIGN KEY(tenant_id,opening_subledger_id) REFERENCES fm_subledger(tenant_id,id),FOREIGN KEY(tenant_id,policy_id) REFERENCES far_policy(tenant_id,id),
 CHECK((source_document_id IS NULL)<>(opening_subledger_id IS NULL))
);
CREATE TABLE far_revision (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,plan_id uuid NOT NULL,kind varchar(16) NOT NULL CHECK(kind IN ('INITIAL','REVISE','TERMINATE')),
 effective_month date NOT NULL,end_month date NOT NULL,total_basis numeric(18,2) NOT NULL CHECK(total_basis>=0),
 debit_account varchar(32) NOT NULL,credit_account varchar(32) NOT NULL,allocations jsonb NOT NULL,
 source_snapshot jsonb NOT NULL,status varchar(16) NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 applicant_id uuid NOT NULL,reviewer_id uuid NOT NULL,reviewed_by uuid,note varchar(2000) NOT NULL,version integer NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,plan_id) REFERENCES far_plan(tenant_id,id),
 CHECK(extract(day from effective_month)=1 AND extract(day from end_month)=1 AND end_month>=effective_month),CHECK(kind<>'TERMINATE' OR total_basis=0)
);
ALTER TABLE far_plan ADD CONSTRAINT far_active_revision_fk FOREIGN KEY(tenant_id,active_revision_id) REFERENCES far_revision(tenant_id,id);
CREATE UNIQUE INDEX far_single_pending_revision ON far_revision(tenant_id,plan_id) WHERE status='PENDING';
CREATE TABLE far_run (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,plan_id uuid NOT NULL,revision_id uuid NOT NULL,month date NOT NULL,
 amount numeric(18,2) NOT NULL CHECK(amount>=0),voucher_id uuid,source_event_id uuid,allocation_snapshot jsonb NOT NULL,created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,plan_id,month),
 FOREIGN KEY(tenant_id,plan_id) REFERENCES far_plan(tenant_id,id),FOREIGN KEY(tenant_id,revision_id) REFERENCES far_revision(tenant_id,id),
 FOREIGN KEY(tenant_id,voucher_id) REFERENCES fa_voucher(tenant_id,id),FOREIGN KEY(tenant_id,source_event_id) REFERENCES fa_source_event(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE far_command (
 tenant_id uuid NOT NULL,id uuid NOT NULL,operation varchar(80) NOT NULL,request_hash char(64) NOT NULL,result_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,id)
);
CREATE TABLE far_schedule_issue (
 tenant_id uuid NOT NULL,plan_id uuid NOT NULL,month date NOT NULL,status varchar(16) NOT NULL CHECK(status IN ('PENDING','RESOLVED')),
 message varchar(2000) NOT NULL,attempts integer NOT NULL DEFAULT 1,updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,plan_id,month),FOREIGN KEY(tenant_id,plan_id) REFERENCES far_plan(tenant_id,id)
);
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['far_policy','far_plan','far_revision','far_run','far_command','far_schedule_issue'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO hotel_ai_os_app',tab);EXECUTE format('REVOKE DELETE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['far_run','far_command'] LOOP EXECUTE format('CREATE TRIGGER immutable_accrual_fact BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);END LOOP;
END $$;
