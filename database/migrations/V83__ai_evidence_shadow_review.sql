-- AI observations are deliberately separate from work_record, payroll and KPI state.
CREATE TABLE ai_evidence_rule (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, position_id UUID NOT NULL,
 version_no INTEGER NOT NULL CHECK(version_no>0),
 state VARCHAR(16) NOT NULL CHECK(state IN ('DRAFT','SHADOW','RETIRED')),
 configuration JSONB NOT NULL, created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,position_id,version_no),
 FOREIGN KEY(tenant_id,position_id) REFERENCES position_definition(tenant_id,id)
);
CREATE UNIQUE INDEX uq_ai_evidence_active_rule ON ai_evidence_rule(tenant_id,position_id) WHERE state='SHADOW';
CREATE TABLE ai_evidence_sample (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, position_id UUID NOT NULL,
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('PASS','CONCERN')),
 object_key TEXT NOT NULL, original_name TEXT NOT NULL, media_type TEXT NOT NULL,
 size_bytes BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL, scan_status VARCHAR(32) NOT NULL,
 created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,position_id) REFERENCES position_definition(tenant_id,id)
);
CREATE TRIGGER ai_sample_retention AFTER INSERT ON ai_evidence_sample
 FOR EACH ROW EXECUTE FUNCTION register_image_retention_object();
-- Supplemental photos belong to the shadow check, not the locked formal work record.
CREATE TABLE ai_evidence_photo (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, work_record_id UUID NOT NULL,
 object_key TEXT NOT NULL, original_name TEXT NOT NULL, media_type TEXT NOT NULL,
 size_bytes BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL, scan_status VARCHAR(32) NOT NULL,
 capture_source VARCHAR(16) NOT NULL CHECK(capture_source IN ('CAMERA','FILE_PICKER')),
 created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,work_record_id) REFERENCES work_record(tenant_id,id)
);
CREATE INDEX ix_ai_evidence_photo_work ON ai_evidence_photo(tenant_id,work_record_id,created_at);
CREATE TRIGGER ai_photo_retention AFTER INSERT ON ai_evidence_photo
 FOR EACH ROW EXECUTE FUNCTION register_image_retention_object();
CREATE TABLE ai_evidence_check (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, work_record_id UUID NOT NULL,
 subject_assignment_id UUID NOT NULL, rule_id UUID NOT NULL, requested_by UUID NOT NULL,
 requested_assignment_id UUID, evidence JSONB NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL CHECK(state IN ('QUEUED','RUNNING','COMPLETE','UNAVAILABLE','FAILED','STALE')),
 result JSONB NOT NULL DEFAULT '{}', failure_code VARCHAR(48),
 human_outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(human_outcome IN ('PENDING','PASS','RETAKE','OVERTURNED')),
 human_reason VARCHAR(2000), human_actor UUID, human_at TIMESTAMPTZ, appeal_reason VARCHAR(2000),
 supersedes_id UUID, attempt_count INTEGER NOT NULL DEFAULT 0,
 lease_id UUID, lease_until TIMESTAMPTZ, input_tokens INTEGER NOT NULL DEFAULT 0 CHECK(input_tokens>=0),
 output_tokens INTEGER NOT NULL DEFAULT 0 CHECK(output_tokens>=0), model_name VARCHAR(120),
 row_version INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,fingerprint),
 FOREIGN KEY(tenant_id,work_record_id) REFERENCES work_record(tenant_id,id),
 FOREIGN KEY(tenant_id,subject_assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
 FOREIGN KEY(tenant_id,rule_id) REFERENCES ai_evidence_rule(tenant_id,id),
 FOREIGN KEY(tenant_id,supersedes_id) REFERENCES ai_evidence_check(tenant_id,id)
);
CREATE INDEX ix_ai_evidence_work ON ai_evidence_check(tenant_id,work_record_id,created_at DESC);
CREATE UNIQUE INDEX uq_ai_evidence_successor ON ai_evidence_check(tenant_id,supersedes_id) WHERE supersedes_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ai_evidence_root ON ai_evidence_check(tenant_id,work_record_id,subject_assignment_id) WHERE supersedes_id IS NULL;
CREATE INDEX ix_ai_evidence_queue ON ai_evidence_check(tenant_id,state,created_at) WHERE state IN ('QUEUED','RUNNING');
CREATE TABLE ai_evidence_event (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, check_id UUID NOT NULL,
 action VARCHAR(32) NOT NULL, actor_id UUID NOT NULL, reason VARCHAR(2000),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,check_id) REFERENCES ai_evidence_check(tenant_id,id)
);
CREATE INDEX ix_ai_evidence_events ON ai_evidence_event(tenant_id,check_id,created_at);
DO $$ DECLARE tab TEXT; BEGIN
 FOREACH tab IN ARRAY ARRAY['ai_evidence_rule','ai_evidence_sample','ai_evidence_photo','ai_evidence_check','ai_evidence_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  -- Production default privileges may be broader than the permissions below.
  EXECUTE format('REVOKE ALL ON %I FROM PUBLIC',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
   EXECUTE format('REVOKE ALL ON %I FROM hotel_ai_os_app',tab);
   EXECUTE format('GRANT SELECT,INSERT ON %I TO hotel_ai_os_app',tab);
   IF tab IN ('ai_evidence_rule','ai_evidence_check') THEN
    EXECUTE format('GRANT UPDATE ON %I TO hotel_ai_os_app',tab);
   END IF;
   IF tab='ai_evidence_sample' THEN
    EXECUTE format('GRANT DELETE ON %I TO hotel_ai_os_app',tab);
   END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_readonly') THEN
   EXECUTE format('REVOKE ALL ON %I FROM hotel_ai_os_readonly',tab);
   EXECUTE format('GRANT SELECT ON %I TO hotel_ai_os_readonly',tab);
  END IF;
 END LOOP;
END $$;
