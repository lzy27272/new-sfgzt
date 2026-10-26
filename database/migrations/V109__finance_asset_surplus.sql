-- A physical registration is not proof of a financial surplus; values require original evidence and independent review.
CREATE TABLE fs_asset_gain (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, hotel_id uuid NOT NULL, company_id uuid NOT NULL,
 book_id uuid NOT NULL, physical_asset_id uuid NOT NULL, department_id uuid NOT NULL,
 financial_category_code varchar(80) NOT NULL, occurred_on date NOT NULL, accounting_on date NOT NULL,
 value numeric(18,2) NOT NULL CHECK(value>0), asset_account varchar(32) NOT NULL, credit_account varchar(32) NOT NULL,
 valuation_basis varchar(2000) NOT NULL, policy_reference varchar(2000) NOT NULL,
 applicant_id uuid NOT NULL, reviewer_id uuid NOT NULL, request_hash char(64) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PENDING','APPROVED','REJECTED','CANCELLED')),
 version integer NOT NULL DEFAULT 0, physical_snapshot jsonb NOT NULL, category_snapshot jsonb NOT NULL,
 evidence_snapshot jsonb NOT NULL DEFAULT '[]', review_note varchar(2000), reviewed_by uuid, reviewed_at timestamptz,
 source_event_id uuid,review_request_id uuid,review_hash char(64), created_at timestamptz NOT NULL DEFAULT now(), submitted_at timestamptz,
 UNIQUE(tenant_id,id), CHECK(applicant_id<>reviewer_id), CHECK(accounting_on>=occurred_on),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),
 FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),
 FOREIGN KEY(tenant_id,physical_asset_id) REFERENCES store_fixed_asset(tenant_id,id),
 FOREIGN KEY(tenant_id,department_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,applicant_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewer_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,source_event_id) REFERENCES fa_source_event(tenant_id,id)
);
CREATE UNIQUE INDEX fs_gain_live_physical ON fs_asset_gain(tenant_id,physical_asset_id) WHERE status NOT IN ('REJECTED','CANCELLED');
CREATE INDEX fs_gain_review_queue ON fs_asset_gain(tenant_id,reviewer_id,status,accounting_on);
CREATE TABLE fs_asset_gain_original (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,gain_id uuid NOT NULL,original_name varchar(180) NOT NULL,
 media_type varchar(120) NOT NULL,size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
 sha256 char(64) NOT NULL CHECK(sha256~'^[a-f0-9]{64}$'),object_key varchar(300) NOT NULL,
 scan_status varchar(32) NOT NULL CHECK(scan_status IN ('CLEAN','BYPASSED_DEVELOPMENT')),
 uploaded_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,gain_id,sha256),
 FOREIGN KEY(tenant_id,gain_id) REFERENCES fs_asset_gain(tenant_id,id)
);
ALTER TABLE fs_asset_card ADD COLUMN gain_id uuid;
ALTER TABLE fs_asset_card ADD CONSTRAINT fs_card_gain_source_fk FOREIGN KEY(tenant_id,gain_id) REFERENCES fs_asset_gain(tenant_id,id);
CREATE UNIQUE INDEX fs_card_one_gain ON fs_asset_card(tenant_id,gain_id) WHERE gain_id IS NOT NULL;
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='fs_asset_card'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%num_nonnulls%' LOOP
  EXECUTE format('ALTER TABLE fs_asset_card DROP CONSTRAINT %I',c.conname);
 END LOOP;
END $$;
ALTER TABLE fs_asset_card ADD CONSTRAINT fs_card_one_real_origin CHECK(num_nonnulls(source_document_id,opening_subledger_id,procurement_recognition_id,gain_id)=1);
CREATE FUNCTION fs_gain_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status IN ('APPROVED','REJECTED','CANCELLED') THEN RAISE EXCEPTION 'Surplus valuation/review facts are immutable'; END IF;
 IF (to_jsonb(NEW)-ARRAY['status','version','evidence_snapshot','review_note','reviewed_by','reviewed_at','source_event_id','submitted_at','review_request_id','review_hash']) IS DISTINCT FROM
    (to_jsonb(OLD)-ARRAY['status','version','evidence_snapshot','review_note','reviewed_by','reviewed_at','source_event_id','submitted_at','review_request_id','review_hash']) THEN RAISE EXCEPTION 'Surplus valuation snapshots cannot be overwritten; cancel and retain original'; END IF;
 IF NEW.version<>OLD.version+1 OR NOT ((OLD.status='DRAFT' AND NEW.status IN ('DRAFT','PENDING','CANCELLED')) OR (OLD.status='PENDING' AND NEW.status IN ('APPROVED','REJECTED','CANCELLED'))) THEN RAISE EXCEPTION 'Invalid surplus review transition'; END IF;
 IF OLD.status='PENDING' AND NEW.evidence_snapshot IS DISTINCT FROM OLD.evidence_snapshot THEN RAISE EXCEPTION 'Submitted original evidence snapshot is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fs_gain_immutable BEFORE UPDATE OR DELETE ON fs_asset_gain FOR EACH ROW EXECUTE FUNCTION fs_gain_guard();
CREATE TRIGGER fs_gain_original_immutable BEFORE UPDATE OR DELETE ON fs_asset_gain_original FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['fs_asset_gain','fs_asset_gain_original'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT ON %I TO hotel_ai_os_app',tab);END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN GRANT UPDATE ON fs_asset_gain TO hotel_ai_os_app; END IF;
END $$;
