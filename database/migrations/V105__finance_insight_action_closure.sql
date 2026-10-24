-- Explicit human assignment and independent evidence verification. Never creates an execution/KPI task.
CREATE TABLE fi_action_task(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,report_id uuid NOT NULL,report_revision integer NOT NULL,recommendation_index integer NOT NULL,
 hotel_id uuid NOT NULL,company_id uuid NOT NULL,owner_id uuid NOT NULL,reviewer_id uuid NOT NULL,
 title varchar(240) NOT NULL,recommendation_text varchar(8000) NOT NULL,citations jsonb NOT NULL,source_report_version char(64) NOT NULL,
 due_date date NOT NULL,status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','IN_PROGRESS','SUBMITTED','REWORK','DONE','CANCELLED')),
 version integer NOT NULL DEFAULT 0,submission_version integer,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 submitted_by uuid,reviewed_by uuid,reviewed_at timestamptz,request_hash char(64) NOT NULL,
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,report_id,report_revision) REFERENCES fi_report_revision(tenant_id,report_id,version),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),
 FOREIGN KEY(tenant_id,owner_id) REFERENCES user_account(tenant_id,id),FOREIGN KEY(tenant_id,reviewer_id) REFERENCES user_account(tenant_id,id),CHECK(owner_id<>reviewer_id),
 CHECK(status<>'DONE' OR reviewed_by IS NOT NULL AND submitted_by IS NOT NULL AND reviewed_by=reviewer_id AND submitted_by=owner_id AND reviewed_by<>submitted_by AND reviewed_at IS NOT NULL AND submission_version IS NOT NULL)
);
CREATE UNIQUE INDEX fi_action_current_recommendation ON fi_action_task(tenant_id,report_id,report_revision,recommendation_index,hotel_id) WHERE status<>'CANCELLED';
CREATE INDEX fi_action_owner_queue ON fi_action_task(tenant_id,owner_id,status,due_date);
CREATE INDEX fi_action_review_queue ON fi_action_task(tenant_id,reviewer_id,status,due_date);
CREATE TABLE fi_action_evidence(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,task_id uuid NOT NULL,submission_version integer NOT NULL,
 source_type varchar(24) NOT NULL,source_id uuid NOT NULL,source_version varchar(500) NOT NULL,source_hash char(64) NOT NULL,
 snapshot jsonb NOT NULL,submitted_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,task_id,submission_version,source_type,source_id),FOREIGN KEY(tenant_id,task_id) REFERENCES fi_action_task(tenant_id,id)
);
CREATE TABLE fi_action_update(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,task_id uuid NOT NULL,from_status varchar(16),to_status varchar(16) NOT NULL,
 task_version integer NOT NULL,note varchar(4000) NOT NULL,actor_id uuid NOT NULL,request_hash char(64) NOT NULL,
 evidence_ids uuid[] NOT NULL DEFAULT '{}',created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,task_id,task_version),
 FOREIGN KEY(tenant_id,task_id) REFERENCES fi_action_task(tenant_id,id)
);
CREATE FUNCTION fi_protect_action() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Financial action tasks cannot be deleted';END IF;
 IF (to_jsonb(NEW)-'status'-'version'-'submission_version'-'updated_at'-'submitted_by'-'reviewed_by'-'reviewed_at') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'version'-'submission_version'-'updated_at'-'submitted_by'-'reviewed_by'-'reviewed_at') THEN RAISE EXCEPTION 'Financial action assignment and original recommendation are immutable';END IF;
 IF NEW.version<>OLD.version+1 OR NOT (
  OLD.status='OPEN' AND NEW.status IN ('IN_PROGRESS','SUBMITTED','CANCELLED') OR
  OLD.status IN ('IN_PROGRESS','REWORK') AND NEW.status IN ('SUBMITTED','CANCELLED') OR
  OLD.status='SUBMITTED' AND NEW.status IN ('REWORK','DONE')
 ) THEN RAISE EXCEPTION 'Invalid financial action state transition';END IF;
 RETURN NEW;
END $$;
-- Explicitly confirmed hours are a manual measurement source, never a claim of connected WeCom attendance.
ALTER TABLE fi_metric DROP CONSTRAINT fi_metric_kind_check;
ALTER TABLE fi_metric ADD CONSTRAINT fi_metric_kind_check CHECK(kind IN ('HEADCOUNT','WORK_HOURS','ENERGY_KWH','WATER_TON','ROOM_NIGHTS','CHANNEL_REVENUE'));
CREATE TRIGGER fi_action_state BEFORE UPDATE OR DELETE ON fi_action_task FOR EACH ROW EXECUTE FUNCTION fi_protect_action();
DO $$ DECLARE tab text;BEGIN
 FOREACH tab IN ARRAY ARRAY['fi_action_task','fi_action_evidence','fi_action_update'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO hotel_ai_os_app',tab);EXECUTE format('REVOKE DELETE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['fi_action_evidence','fi_action_update'] LOOP
  EXECUTE format('CREATE TRIGGER fi_action_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('REVOKE UPDATE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
END $$;
