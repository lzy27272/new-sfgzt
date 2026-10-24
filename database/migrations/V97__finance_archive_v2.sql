-- Financial archives retain immutable, authorized source versions. No automatic purge is enabled.
CREATE TABLE fac_archive(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,source_type varchar(24) NOT NULL,source_id uuid NOT NULL,
 hotel_ids uuid[] NOT NULL,company_ids uuid[] NOT NULL,source_version varchar(500) NOT NULL,snapshot_hash char(64) NOT NULL,
 snapshot jsonb NOT NULL,title varchar(240) NOT NULL,restricted boolean NOT NULL DEFAULT true,sensitive_payroll boolean NOT NULL DEFAULT false,
 created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),
 UNIQUE(tenant_id,source_type,source_id,snapshot_hash),FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id),CHECK(cardinality(hotel_ids)>0)
);
CREATE INDEX fac_archive_scope ON fac_archive USING gin(hotel_ids);
CREATE INDEX fac_archive_source ON fac_archive(tenant_id,source_type,source_id,created_at DESC);
CREATE TABLE fac_note(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,archive_id uuid NOT NULL,body varchar(4000) NOT NULL,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,archive_id) REFERENCES fac_archive(tenant_id,id));
CREATE TABLE fac_request(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,archive_id uuid NOT NULL,applicant_id uuid NOT NULL,reason varchar(2000) NOT NULL,
 requested_hours integer NOT NULL CHECK(requested_hours BETWEEN 1 AND 72),status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED','REVOKED')),
 reviewer_id uuid,decision_note varchar(2000),expires_at timestamptz,version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),reviewed_at timestamptz,
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,archive_id) REFERENCES fac_archive(tenant_id,id),FOREIGN KEY(tenant_id,applicant_id) REFERENCES user_account(tenant_id,id),
 CHECK(reviewer_id IS NULL OR reviewer_id<>applicant_id),CHECK(status<>'APPROVED' OR expires_at IS NOT NULL)
);
CREATE INDEX fac_request_pending ON fac_request(tenant_id,status,archive_id,applicant_id);
CREATE UNIQUE INDEX fac_request_inflight ON fac_request(tenant_id,archive_id,applicant_id) WHERE status='PENDING';
CREATE TABLE fac_decision(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,request_id uuid NOT NULL,from_status varchar(16) NOT NULL,to_status varchar(16) NOT NULL,note varchar(2000) NOT NULL,expires_at timestamptz,reviewer_id uuid NOT NULL,request_version integer NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),FOREIGN KEY(tenant_id,request_id) REFERENCES fac_request(tenant_id,id),UNIQUE(tenant_id,request_id,request_version));
CREATE TABLE fac_access(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,archive_id uuid NOT NULL,actor_id uuid NOT NULL,action varchar(24) NOT NULL,reference varchar(500) NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),FOREIGN KEY(tenant_id,archive_id) REFERENCES fac_archive(tenant_id,id));
CREATE TABLE fac_retention(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,company_id uuid NOT NULL,years integer NOT NULL CHECK(years BETWEEN 1 AND 50),legal_hold boolean NOT NULL,
 reason varchar(2000) NOT NULL,status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),created_by uuid NOT NULL,published_by uuid,created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz,
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),CHECK(published_by IS NULL OR published_by<>created_by)
);
CREATE FUNCTION fac_protect_request() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Archive access history is immutable';END IF;
 IF (to_jsonb(NEW)-'status'-'reviewer_id'-'decision_note'-'expires_at'-'reviewed_at'-'version') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'reviewer_id'-'decision_note'-'expires_at'-'reviewed_at'-'version') THEN RAISE EXCEPTION 'Archive application inputs are immutable';END IF;
 IF NOT (OLD.status='PENDING' AND NEW.status IN ('APPROVED','REJECTED') OR OLD.status='APPROVED' AND NEW.status='REVOKED') THEN RAISE EXCEPTION 'Invalid archive grant transition';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fac_request_history BEFORE UPDATE OR DELETE ON fac_request FOR EACH ROW EXECUTE FUNCTION fac_protect_request();
CREATE FUNCTION fac_protect_retention() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'DRAFT' OR NEW.status<>'PUBLISHED' OR (to_jsonb(NEW)-'status'-'published_by'-'published_at') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'published_by'-'published_at') THEN RAISE EXCEPTION 'Published retention policies are immutable';END IF;RETURN NEW;
END $$;
CREATE TRIGGER fac_retention_history BEFORE UPDATE OR DELETE ON fac_retention FOR EACH ROW EXECUTE FUNCTION fac_protect_retention();
DO $$ DECLARE tab text;BEGIN
 FOREACH tab IN ARRAY ARRAY['fac_archive','fac_note','fac_request','fac_decision','fac_access','fac_retention'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO hotel_ai_os_app',tab);EXECUTE format('REVOKE DELETE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['fac_archive','fac_note','fac_access','fac_decision'] LOOP
  EXECUTE format('CREATE TRIGGER finance_archive_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('REVOKE UPDATE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
END $$;
