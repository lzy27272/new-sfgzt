-- Review activation is separate from publishing unconfirmed shift/breakfast schedules.
-- Only the explicit operations transaction inserts activations and pending-record snapshots.
CREATE TABLE work_review_policy_activation (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  target_org_unit_id uuid NOT NULL,
  work_package_item_id uuid NOT NULL,
  new_work_from date NOT NULL,
  authorization_reference text NOT NULL CHECK (length(btrim(authorization_reference)) > 0),
  reason text NOT NULL CHECK (length(btrim(reason)) > 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,id),
  UNIQUE (tenant_id,target_org_unit_id,work_package_item_id),
  FOREIGN KEY (tenant_id,target_org_unit_id) REFERENCES org_unit(tenant_id,id),
  FOREIGN KEY (tenant_id,work_package_item_id) REFERENCES work_package_item(tenant_id,id)
);
CREATE TABLE work_review_policy_correction (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  activation_id uuid NOT NULL,
  work_expectation_id uuid NOT NULL,
  work_record_id uuid NOT NULL,
  before_record jsonb NOT NULL,
  before_expectation jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,activation_id,work_expectation_id),
  FOREIGN KEY (tenant_id,activation_id) REFERENCES work_review_policy_activation(tenant_id,id),
  FOREIGN KEY (tenant_id,work_expectation_id) REFERENCES work_expectation(tenant_id,id),
  FOREIGN KEY (tenant_id,work_record_id) REFERENCES work_record(tenant_id,id)
);
DO $$ DECLARE tab text; BEGIN
  FOREACH tab IN ARRAY ARRAY['work_review_policy_activation','work_review_policy_correction'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
    EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
    EXECUTE format('CREATE TRIGGER review_policy_audit_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION protect_work_record_spot_check()',tab);
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
      EXECUTE format('REVOKE ALL ON %I FROM hotel_ai_os_app',tab);
      EXECUTE format('GRANT SELECT ON %I TO hotel_ai_os_app',tab);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_readonly') THEN
      EXECUTE format('REVOKE ALL ON %I FROM hotel_ai_os_readonly',tab);
      EXECUTE format('GRANT SELECT ON %I TO hotel_ai_os_readonly',tab);
    END IF;
  END LOOP;
END $$;

-- Bound to the existing item/version and hotel: a future published version keeps its own policy.
CREATE FUNCTION work_review_configured_mode(item work_package_item, expectation work_expectation)
RETURNS text LANGUAGE sql STABLE AS $$
  SELECT CASE WHEN item.item_code='HKA_CART_PREPARATION'
    AND item.tenant_id=expectation.tenant_id AND item.id=expectation.work_package_item_id
    AND EXISTS (
      SELECT 1 FROM work_review_policy_activation a
      WHERE a.tenant_id=item.tenant_id AND a.work_package_item_id=item.id
        AND a.target_org_unit_id=expectation.target_org_unit_id
        AND (expectation.business_date>=a.new_work_from OR EXISTS (
          SELECT 1 FROM work_review_policy_correction c
          WHERE c.tenant_id=a.tenant_id AND c.activation_id=a.id
            AND c.work_expectation_id=expectation.id))
    ) THEN 'NONE' ELSE item.review_mode END
$$;

-- Preserve V115's fact protection; recognize the audited policy when a corrected
-- record is later approved after repair and then fails another authorized spot check.
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
      JOIN work_expectation x ON x.tenant_id=OLD.tenant_id AND x.id=OLD.work_expectation_id
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
        AND i.required AND work_review_configured_mode(i,x)='NONE' AND i.item_type NOT IN ('REVIEW_APPROVAL','METRIC_REVIEW')
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
