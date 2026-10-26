-- V115: preserve submitted facts and the original approval; allow one audited failure
-- of an eligible exempt record in the same transaction as its authorization.
CREATE TABLE work_record_spot_check (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  work_record_id uuid NOT NULL,
  reviewer_account_id uuid NOT NULL,
  reviewer_assignment_id uuid NOT NULL,
  outcome varchar(16) NOT NULL CHECK (outcome IN ('APPROVED','REJECTED')),
  reason text NOT NULL CHECK (length(btrim(reason)) > 0),
  before_row_version bigint NOT NULL,
  before_review jsonb NOT NULL,
  created_txid bigint NOT NULL DEFAULT txid_current(),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,work_record_id,outcome),
  FOREIGN KEY (tenant_id,work_record_id) REFERENCES work_record(tenant_id,id),
  FOREIGN KEY (tenant_id,reviewer_account_id) REFERENCES user_account(tenant_id,id),
  FOREIGN KEY (tenant_id,reviewer_assignment_id) REFERENCES employee_position_assignment(tenant_id,id)
);
ALTER TABLE work_record_spot_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_record_spot_check FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_record_spot_check
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
    REVOKE ALL ON work_record_spot_check FROM hotel_ai_os_app;
    GRANT SELECT,INSERT ON work_record_spot_check TO hotel_ai_os_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_readonly') THEN
    REVOKE ALL ON work_record_spot_check FROM hotel_ai_os_readonly;
    GRANT SELECT ON work_record_spot_check TO hotel_ai_os_readonly;
  END IF;
END $$;

CREATE FUNCTION protect_work_record_spot_check() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'spot check audit events are immutable';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_work_record_spot_check_immutable BEFORE UPDATE OR DELETE ON work_record_spot_check
 FOR EACH ROW EXECUTE FUNCTION protect_work_record_spot_check();

CREATE OR REPLACE FUNCTION protect_submitted_work_record() RETURNS trigger AS $$
DECLARE authorized_spot_failure boolean := false;
BEGIN
  IF OLD.status='DRAFT' THEN
    IF NEW.status NOT IN ('DRAFT','SUBMITTED') THEN
      RAISE EXCEPTION 'draft work records may only remain draft or be submitted';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.status='SUBMITTED' AND NEW.status NOT IN ('SUBMITTED','APPROVED','REJECTED') THEN
    RAISE EXCEPTION 'submitted work records may only be approved or rejected';
  END IF;
  IF OLD.status='APPROVED' AND NEW.status='REJECTED' AND NOT OLD.repeatable_entry THEN
    SELECT EXISTS (
      SELECT 1 FROM work_record_spot_check s
      JOIN work_package_item i ON i.tenant_id=OLD.tenant_id AND i.id=OLD.work_package_item_id
      JOIN employee_position_assignment a ON a.tenant_id=s.tenant_id AND a.id=s.reviewer_assignment_id
      JOIN employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
      WHERE s.tenant_id=OLD.tenant_id AND s.work_record_id=OLD.id
        AND s.outcome='REJECTED'
        AND s.created_txid=txid_current() AND s.before_row_version=OLD.row_version
        AND s.before_review=jsonb_build_object('status',OLD.status,'reviewedBy',OLD.reviewed_by_account_id,
          'reviewedAt',OLD.reviewed_at,'reason',OLD.review_reason)
        AND s.reviewer_account_id=NEW.reviewed_by_account_id AND e.account_id=s.reviewer_account_id
        AND a.status='ACTIVE' AND a.valid_from<=current_date AND (a.valid_to IS NULL OR a.valid_to>=current_date)
        AND e.employment_status='ACTIVE' AND e.deleted_at IS NULL
        AND s.reviewer_account_id IS DISTINCT FROM OLD.submitted_by_account_id AND e.id<>OLD.employee_id
        AND NEW.row_version=OLD.row_version+1 AND NEW.reviewed_at IS NOT NULL AND NEW.review_reason=s.reason
        AND i.required AND i.review_mode='NONE' AND i.item_type NOT IN ('REVIEW_APPROVAL','METRIC_REVIEW')
        AND i.item_code NOT IN ('HKA_ITEM_ISSUE','HKA_USAGE_RETURN','HKA_DAILY_REPORT','HKA_LOST_PROPERTY','HKA_COMPLAINT_EVENT',
          'NSA_ITEM_ISSUE','NSA_DAILY_HANDOVER','NSA_LOST_PROPERTY')
        AND coalesce(i.execution_policy->>'handoverConfirmationRequired','false')<>'true'
        AND coalesce(i.execution_policy->>'highRiskImmediateEscalation','false')<>'true'
        AND coalesce(i.execution_policy->>'inventoryUsage','false')<>'true'
        AND coalesce(i.submission_policy->>'inventoryUsage','false')<>'true'
        AND nullif(btrim(i.execution_policy->>'inventoryMovement'),'') IS NULL
        AND NOT EXISTS (SELECT 1 FROM store_work_usage u WHERE u.tenant_id=OLD.tenant_id AND u.work_record_id=OLD.id)
    ) INTO authorized_spot_failure;
  END IF;
  IF OLD.status IN ('APPROVED','REJECTED') AND NEW.status<>OLD.status AND NOT authorized_spot_failure THEN
    RAISE EXCEPTION 'reviewed work records are final; create a new attempt';
  END IF;
  IF (to_jsonb(NEW)-ARRAY['status','reviewed_by_account_id','reviewed_at','review_reason','updated_at','row_version'])
    <> (to_jsonb(OLD)-ARRAY['status','reviewed_by_account_id','reviewed_at','review_reason','updated_at','row_version']) THEN
    RAISE EXCEPTION 'submitted work record facts are immutable';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
