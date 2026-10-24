-- Preserve every original invoice use. Only independently approved pre-recognition corrections may release occupancy.
ALTER TABLE fs_invoice_use ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE fs_invoice_use ADD COLUMN released_at timestamptz;
ALTER TABLE fs_invoice_use ADD COLUMN release_id uuid;
ALTER TABLE fs_invoice_use DROP CONSTRAINT fs_invoice_use_pkey;
ALTER TABLE fs_invoice_use DROP CONSTRAINT fs_invoice_use_tenant_id_invoice_key_key;
ALTER TABLE fs_invoice_use DROP CONSTRAINT fs_invoice_use_tenant_id_sha256_key;
ALTER TABLE fs_invoice_use ADD PRIMARY KEY(id);
ALTER TABLE fs_invoice_use ADD UNIQUE(tenant_id,id);
CREATE UNIQUE INDEX fs_active_invoice_id ON fs_invoice_use(tenant_id,invoice_id) WHERE released_at IS NULL;
CREATE UNIQUE INDEX fs_active_invoice_key ON fs_invoice_use(tenant_id,invoice_key) WHERE released_at IS NULL;
CREATE UNIQUE INDEX fs_active_invoice_hash ON fs_invoice_use(tenant_id,sha256) WHERE released_at IS NULL;
ALTER TABLE fs_recognition DROP CONSTRAINT fs_recognition_status_check;
ALTER TABLE fs_recognition ADD CONSTRAINT fs_recognition_status_check CHECK(status IN ('UNMATCHED','PENDING','ESTIMATED','CONFIRMED','REPLACED'));
CREATE TABLE fs_invoice_release (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,recognition_id uuid NOT NULL,recognition_version integer NOT NULL,
 request_hash char(64) NOT NULL,reason varchar(2000) NOT NULL,snapshot jsonb NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'REVIEW' CHECK(status IN ('REVIEW','APPROVED','REJECTED')),
 applicant_id uuid NOT NULL,reviewer_id uuid NOT NULL,reviewed_by uuid,review_note varchar(2000),version integer NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),reviewed_at timestamptz,UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,recognition_id) REFERENCES fs_recognition(tenant_id,id),
 FOREIGN KEY(tenant_id,applicant_id) REFERENCES user_account(tenant_id,id),FOREIGN KEY(tenant_id,reviewer_id) REFERENCES user_account(tenant_id,id),
 CHECK(applicant_id<>reviewer_id),CHECK(reviewed_by IS NULL OR reviewed_by=reviewer_id)
);
CREATE UNIQUE INDEX fs_single_invoice_release_pending ON fs_invoice_release(tenant_id,recognition_id) WHERE status='REVIEW';
ALTER TABLE fs_invoice_use ADD FOREIGN KEY(tenant_id,release_id) REFERENCES fs_invoice_release(tenant_id,id);
DROP TRIGGER immutable_finance_supply ON fs_invoice_use;
CREATE FUNCTION fs_protect_invoice_use() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.released_at IS NOT NULL OR NEW.released_at IS NULL OR NEW.release_id IS NULL OR
 (to_jsonb(NEW)-ARRAY['released_at','release_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['released_at','release_id']) OR
 NOT EXISTS(SELECT 1 FROM fs_invoice_release r WHERE r.tenant_id=OLD.tenant_id AND r.id=NEW.release_id AND r.recognition_id=OLD.recognition_id AND r.status='APPROVED')
 THEN RAISE EXCEPTION 'Invoice use is immutable; an independent pre-recognition release is required';END IF;RETURN NEW;END $$;
CREATE TRIGGER fs_invoice_use_immutable BEFORE UPDATE OR DELETE ON fs_invoice_use FOR EACH ROW EXECUTE FUNCTION fs_protect_invoice_use();
CREATE FUNCTION fs_protect_invoice_release() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'REVIEW' OR NEW.status NOT IN ('APPROVED','REJECTED') OR NEW.reviewed_by IS DISTINCT FROM OLD.reviewer_id OR
 (to_jsonb(NEW)-ARRAY['status','reviewed_by','review_note','reviewed_at','version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','reviewed_by','review_note','reviewed_at','version'])
 THEN RAISE EXCEPTION 'Invoice release evidence and independent decision are immutable';END IF;RETURN NEW;END $$;
CREATE TRIGGER fs_invoice_release_immutable BEFORE UPDATE OR DELETE ON fs_invoice_release FOR EACH ROW EXECUTE FUNCTION fs_protect_invoice_release();
ALTER TABLE fs_invoice_release ENABLE ROW LEVEL SECURITY;
ALTER TABLE fs_invoice_release FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fs_invoice_release USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN GRANT SELECT,INSERT,UPDATE ON fs_invoice_release TO hotel_ai_os_app;REVOKE DELETE ON fs_invoice_release FROM hotel_ai_os_app;END IF;END $$;
