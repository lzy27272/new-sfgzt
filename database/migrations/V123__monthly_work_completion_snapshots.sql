-- Reporting archives preserve observed corrections; source work, task deadlines and payroll remain unchanged.
CREATE TABLE operations_monthly_work_snapshot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    subject_assignment_id uuid NOT NULL,
    month_start date NOT NULL CHECK(extract(day FROM month_start)=1),
    scope_key varchar(128) NOT NULL,
    revision_no integer NOT NULL CHECK(revision_no>0),
    content_hash char(64) NOT NULL,
    report jsonb NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(),
    captured_by_account_id uuid NOT NULL,
    change_reason varchar(240) NOT NULL,
    UNIQUE(tenant_id,id),
    UNIQUE(tenant_id,subject_assignment_id,month_start,scope_key,revision_no),
    FOREIGN KEY(tenant_id,subject_assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,captured_by_account_id) REFERENCES user_account(tenant_id,id)
);
ALTER TABLE operations_monthly_work_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE operations_monthly_work_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY operations_monthly_work_snapshot_tenant ON operations_monthly_work_snapshot
    USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE FUNCTION protect_operations_monthly_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Monthly work snapshots are immutable; append a new revision'; END $$;
CREATE TRIGGER immutable_operations_monthly_snapshot BEFORE UPDATE OR DELETE ON operations_monthly_work_snapshot
    FOR EACH ROW EXECUTE FUNCTION protect_operations_monthly_snapshot();
CREATE INDEX ix_monthly_work_due ON work_expectation(tenant_id,position_assignment_id,due_at,id)
    WHERE status NOT IN ('WAIVED','CANCELLED');
CREATE INDEX ix_monthly_task_due ON management_task(tenant_id,due_at,id)
    WHERE lifecycle_status NOT IN ('PROPOSED','CANCELLED');
CREATE INDEX ix_monthly_task_submission ON task_transition(tenant_id,task_id,task_version DESC)
    WHERE command='SUBMIT_RESULT';
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
        GRANT SELECT,INSERT ON operations_monthly_work_snapshot TO hotel_ai_os_app;
    END IF;
END $$;
