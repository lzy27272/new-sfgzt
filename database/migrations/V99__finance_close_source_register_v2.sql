-- A complete period register is independently confirmed; a single free-form complete=true row is not coverage.
CREATE TABLE fcl_register (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,month date NOT NULL,
 entries jsonb NOT NULL CHECK(jsonb_typeof(entries)='array'),request_hash char(64) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','CONFIRMED','REJECTED')),
 created_by uuid NOT NULL,reviewer_id uuid NOT NULL,reviewed_by uuid,note varchar(2000) NOT NULL,
 version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),reviewed_at timestamptz,
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id),FOREIGN KEY(tenant_id,reviewer_id) REFERENCES user_account(tenant_id,id),
 CHECK(extract(day from month)=1),CHECK(created_by<>reviewer_id),CHECK(reviewed_by IS NULL OR reviewed_by=reviewer_id)
);
CREATE INDEX fcl_register_period ON fcl_register(tenant_id,book_id,month,created_at DESC,id);
CREATE UNIQUE INDEX fcl_register_pending ON fcl_register(tenant_id,book_id,month) WHERE status='DRAFT';
CREATE TABLE fcl_bank_explanation (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,transaction_id uuid NOT NULL,as_of date NOT NULL,
 allocated_amount numeric(18,2) NOT NULL CHECK(allocated_amount>=0),allocation_hash char(64) NOT NULL,
 snapshot jsonb NOT NULL,note varchar(2000) NOT NULL,status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','CONFIRMED','REJECTED')),
 created_by uuid NOT NULL,reviewer_id uuid NOT NULL,reviewed_by uuid,version integer NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),reviewed_at timestamptz,UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),FOREIGN KEY(tenant_id,transaction_id) REFERENCES fo_bank_transaction(tenant_id,id),
 CHECK(created_by<>reviewer_id),CHECK(reviewed_by IS NULL OR reviewed_by=reviewer_id)
);
CREATE INDEX fcl_explanation_scope ON fcl_bank_explanation(tenant_id,book_id,as_of,transaction_id);
CREATE FUNCTION fcl_protect_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'DRAFT' THEN RAISE EXCEPTION 'Confirmed close source evidence is immutable';END IF;
 IF (to_jsonb(NEW)-'status'-'reviewed_by'-'reviewed_at'-'version') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'reviewed_by'-'reviewed_at'-'version') THEN RAISE EXCEPTION 'Close source evidence cannot be rewritten during review';END IF;
 IF NEW.status NOT IN ('CONFIRMED','REJECTED') THEN RAISE EXCEPTION 'Only independent close confirmation is allowed';END IF;
 RETURN NEW;END $$;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['fcl_register','fcl_bank_explanation'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  EXECUTE format('CREATE TRIGGER close_source_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION fcl_protect_confirmation()',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO hotel_ai_os_app',tab);EXECUTE format('REVOKE DELETE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
END $$;
