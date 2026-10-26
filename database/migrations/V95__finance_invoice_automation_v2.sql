CREATE TABLE fia_job (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,intake_id uuid NOT NULL,hotel_id uuid NOT NULL,operation varchar(8) NOT NULL CHECK(operation IN ('OCR','VERIFY')),
 intake_version integer NOT NULL,source_sha256 char(64) NOT NULL,invoice_snapshot jsonb NOT NULL,request_hash char(64) NOT NULL,
 consent_note varchar(1000) NOT NULL,consented_by uuid NOT NULL,consented_at timestamptz NOT NULL DEFAULT now(),provider varchar(120) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SUCCEEDED','FAILED','UNKNOWN')),
 outcome varchar(40),version integer NOT NULL DEFAULT 0,last_checked_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,intake_id) REFERENCES finance_invoice_intake(tenant_id,id),FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,consented_by) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX fia_job_intake ON fia_job(tenant_id,intake_id,created_at DESC);
CREATE UNIQUE INDEX fia_job_inflight ON fia_job(tenant_id,intake_id,operation) WHERE status IN ('PENDING','UNKNOWN');
CREATE TABLE fia_proof (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,job_id uuid NOT NULL,provider varchar(120) NOT NULL,external_reference varchar(180) NOT NULL,
 source_sha256 char(64) NOT NULL,invoice_snapshot jsonb NOT NULL,outcome varchar(40) NOT NULL,response jsonb NOT NULL,
 actor_id uuid NOT NULL,received_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,job_id),
 FOREIGN KEY(tenant_id,job_id) REFERENCES fia_job(tenant_id,id),FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE fia_adoption (
 tenant_id uuid NOT NULL,job_id uuid NOT NULL,intake_version integer NOT NULL,adopted_by uuid NOT NULL,adopted_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,job_id),FOREIGN KEY(tenant_id,job_id) REFERENCES fia_job(tenant_id,id)
);
CREATE FUNCTION fia_protect_request() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'External invoice consent cannot be deleted';END IF;
 IF (to_jsonb(NEW)-'status'-'outcome'-'version'-'last_checked_at') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'outcome'-'version'-'last_checked_at') THEN RAISE EXCEPTION 'External invoice source and consent are immutable';END IF;
 IF OLD.status IN ('SUCCEEDED','FAILED') THEN RAISE EXCEPTION 'Terminal external invoice result cannot be overwritten';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fia_job_request_immutable BEFORE UPDATE OR DELETE ON fia_job FOR EACH ROW EXECUTE FUNCTION fia_protect_request();
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['fia_job','fia_proof','fia_adoption'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO hotel_ai_os_app',tab);EXECUTE format('REVOKE DELETE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['fia_proof','fia_adoption'] LOOP
  EXECUTE format('CREATE TRIGGER fia_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('REVOKE UPDATE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
END $$;
