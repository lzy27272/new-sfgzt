-- Tenant-owned data retention defaults. This migration is intentionally
-- additive: it records policy and retention metadata but never removes an
-- attachment, database row, or stored object.
CREATE TABLE data_retention_policy (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id),
    image_retention_days INTEGER NOT NULL DEFAULT 5
        CHECK (image_retention_days BETWEEN 1 AND 365),
    document_retention_days INTEGER NOT NULL DEFAULT 30
        CHECK (document_retention_days BETWEEN 1 AND 3650),
    employee_metric_retention_months INTEGER NOT NULL DEFAULT 36
        CHECK (employee_metric_retention_months BETWEEN 1 AND 120),
    pms_retention_months INTEGER NOT NULL DEFAULT 36
        CHECK (pms_retention_months BETWEEN 1 AND 120),
    ota_retention_months INTEGER NOT NULL DEFAULT 36
        CHECK (ota_retention_months BETWEEN 1 AND 120),
    kpi_retention_months INTEGER NOT NULL DEFAULT 36
        CHECK (kpi_retention_months BETWEEN 1 AND 120),
    raw_payload_retention_days INTEGER NOT NULL DEFAULT 90
        CHECK (raw_payload_retention_days BETWEEN 1 AND 3650),
    image_cleanup_enabled BOOLEAN NOT NULL DEFAULT false,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    updated_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES user_account (tenant_id, id)
);

CREATE TRIGGER trg_data_retention_policy_updated_at
    BEFORE UPDATE ON data_retention_policy
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX ix_data_retention_policy_updated_by
    ON data_retention_policy (tenant_id, updated_by)
    WHERE updated_by IS NOT NULL;

-- Keep the object metadata after a future lifecycle cleanup. No cleanup is
-- enabled by this migration; every existing object remains AVAILABLE.
ALTER TABLE attachment
    ADD COLUMN retention_basis_at TIMESTAMPTZ,
    ADD COLUMN retention_until TIMESTAMPTZ,
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN legal_hold_reason TEXT,
    ADD COLUMN legal_hold_set_at TIMESTAMPTZ,
    ADD COLUMN legal_hold_set_by UUID,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deletion_reason TEXT,
    ADD COLUMN storage_status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE';

ALTER TABLE attachment
    ADD CONSTRAINT ck_attachment_storage_status
        CHECK (storage_status IN ('AVAILABLE', 'DELETION_PENDING', 'DELETED', 'DELETE_FAILED')),
    ADD CONSTRAINT ck_attachment_retention_window
        CHECK (
            (retention_basis_at IS NULL AND retention_until IS NULL)
            OR
            (retention_basis_at IS NOT NULL AND retention_until IS NOT NULL
                AND retention_until >= retention_basis_at)
        ),
    ADD CONSTRAINT ck_attachment_legal_hold_metadata
        CHECK (
            (legal_hold = false AND legal_hold_reason IS NULL
                AND legal_hold_set_at IS NULL AND legal_hold_set_by IS NULL)
            OR
            (legal_hold = true AND legal_hold_reason IS NOT NULL
                AND btrim(legal_hold_reason) <> '' AND legal_hold_set_at IS NOT NULL)
        ),
    ADD CONSTRAINT ck_attachment_deletion_metadata
        CHECK (
            (storage_status = 'DELETED' AND deleted_at IS NOT NULL
                AND deletion_reason IS NOT NULL AND btrim(deletion_reason) <> '')
            OR
            (storage_status <> 'DELETED' AND deleted_at IS NULL)
        ),
    ADD CONSTRAINT fk_attachment_legal_hold_actor
        FOREIGN KEY (tenant_id, legal_hold_set_by) REFERENCES user_account (tenant_id, id);

CREATE INDEX ix_attachment_retention_cleanup_candidate
    ON attachment (tenant_id, retention_until, id)
    WHERE storage_status = 'AVAILABLE'
      AND deleted_at IS NULL
      AND legal_hold = false
      AND media_type LIKE 'image/%';

CREATE INDEX ix_attachment_legal_hold_actor
    ON attachment (tenant_id, legal_hold_set_by)
    WHERE legal_hold_set_by IS NOT NULL;

-- Daily and monthly employee completion summaries are append-by-revision. KPI
-- scoring continues to reuse kpi_responsibility_snapshot and
-- kpi_scorecard_revision; this table captures the broader work-completion
-- history needed for employee growth analysis.
CREATE TABLE employee_performance_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    employee_id UUID NOT NULL,
    position_assignment_id UUID NOT NULL,
    org_unit_id UUID NOT NULL,
    period_type VARCHAR(8) NOT NULL CHECK (period_type IN ('DAY', 'MONTH')),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    revision_no INTEGER NOT NULL DEFAULT 1 CHECK (revision_no > 0),
    supersedes_snapshot_id UUID,
    source_rule_versions JSONB NOT NULL DEFAULT '[]'::jsonb,
    calculation_version VARCHAR(80) NOT NULL,
    expected_count INTEGER NOT NULL DEFAULT 0 CHECK (expected_count >= 0),
    completed_count INTEGER NOT NULL DEFAULT 0 CHECK (completed_count >= 0),
    on_time_count INTEGER NOT NULL DEFAULT 0 CHECK (on_time_count >= 0),
    overdue_count INTEGER NOT NULL DEFAULT 0 CHECK (overdue_count >= 0),
    missed_count INTEGER NOT NULL DEFAULT 0 CHECK (missed_count >= 0),
    rejected_count INTEGER NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
    supplemented_count INTEGER NOT NULL DEFAULT 0 CHECK (supplemented_count >= 0),
    remediation_count INTEGER NOT NULL DEFAULT 0 CHECK (remediation_count >= 0),
    completion_rate NUMERIC(7,4) NOT NULL DEFAULT 0
        CHECK (completion_rate BETWEEN 0 AND 100),
    on_time_rate NUMERIC(7,4) NOT NULL DEFAULT 0
        CHECK (on_time_rate BETWEEN 0 AND 100),
    evidence_complete_rate NUMERIC(7,4) NOT NULL DEFAULT 0
        CHECK (evidence_complete_rate BETWEEN 0 AND 100),
    remediation_rate NUMERIC(7,4) NOT NULL DEFAULT 0
        CHECK (remediation_rate BETWEEN 0 AND 100),
    metric_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_hash CHAR(64) NOT NULL,
    generated_by UUID,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (
        tenant_id, employee_id, position_assignment_id,
        period_type, period_start, revision_no
    ),
    CHECK (period_end >= period_start),
    CHECK (
        (period_type = 'DAY' AND period_end = period_start)
        OR
        (period_type = 'MONTH'
            AND period_start = date_trunc('month', period_start)::date
            AND period_end = (period_start + INTERVAL '1 month - 1 day')::date)
    ),
    CHECK (jsonb_typeof(source_rule_versions) = 'array'),
    CHECK (jsonb_typeof(metric_snapshot) = 'object'),
    CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CHECK (
        (revision_no = 1 AND supersedes_snapshot_id IS NULL)
        OR (revision_no > 1 AND supersedes_snapshot_id IS NOT NULL)
    ),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employee (tenant_id, id),
    FOREIGN KEY (tenant_id, position_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, supersedes_snapshot_id)
        REFERENCES employee_performance_snapshot (tenant_id, id),
    FOREIGN KEY (tenant_id, generated_by) REFERENCES user_account (tenant_id, id)
);

CREATE INDEX ix_employee_performance_snapshot_employee_period
    ON employee_performance_snapshot
       (tenant_id, employee_id, period_type, period_start DESC, revision_no DESC);

CREATE INDEX ix_employee_performance_snapshot_org_period
    ON employee_performance_snapshot
       (tenant_id, org_unit_id, period_type, period_start DESC);

CREATE INDEX ix_employee_performance_snapshot_assignment
    ON employee_performance_snapshot
       (tenant_id, position_assignment_id, period_start DESC);

CREATE INDEX ix_employee_performance_snapshot_supersedes
    ON employee_performance_snapshot (tenant_id, supersedes_snapshot_id)
    WHERE supersedes_snapshot_id IS NOT NULL;

CREATE INDEX ix_employee_performance_snapshot_generated_by
    ON employee_performance_snapshot (tenant_id, generated_by)
    WHERE generated_by IS NOT NULL;

CREATE OR REPLACE FUNCTION prevent_employee_performance_snapshot_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'employee performance snapshots are versioned; insert a new revision';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_employee_performance_snapshot_no_update
    BEFORE UPDATE ON employee_performance_snapshot
    FOR EACH ROW EXECUTE FUNCTION prevent_employee_performance_snapshot_update();

-- Compute an attachment's retention window only when its work record reaches
-- final approval. Rejected/rework records intentionally remain outside the
-- cleanup candidate set.
CREATE OR REPLACE FUNCTION set_approved_attachment_retention()
RETURNS TRIGGER AS $$
DECLARE
    policy data_retention_policy%ROWTYPE;
    basis_at TIMESTAMPTZ;
BEGIN
    IF NEW.status <> 'APPROVED' OR OLD.status = 'APPROVED' THEN
        RETURN NEW;
    END IF;

    INSERT INTO data_retention_policy (tenant_id)
    VALUES (NEW.tenant_id)
    ON CONFLICT (tenant_id) DO NOTHING;

    SELECT * INTO policy
    FROM data_retention_policy
    WHERE tenant_id = NEW.tenant_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'data retention policy is missing for tenant %', NEW.tenant_id;
    END IF;

    basis_at := coalesce(NEW.reviewed_at, now());
    UPDATE attachment
    SET retention_basis_at = basis_at,
        retention_until = basis_at + make_interval(days => CASE
            WHEN media_type LIKE 'image/%'
                THEN policy.image_retention_days
            ELSE policy.document_retention_days
        END)
    WHERE tenant_id = NEW.tenant_id
      AND work_record_id = NEW.id
      AND storage_status = 'AVAILABLE'
      AND deleted_at IS NULL;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Application upload paths reject additions after APPROVED, but retain a
-- database-level fallback so imports or future integrations cannot create an
-- approved attachment without a retention window.
CREATE OR REPLACE FUNCTION set_new_attachment_retention()
RETURNS TRIGGER AS $$
DECLARE
    record_state RECORD;
    policy data_retention_policy%ROWTYPE;
BEGIN
    SELECT status, reviewed_at
    INTO record_state
    FROM work_record
    WHERE tenant_id = NEW.tenant_id AND id = NEW.work_record_id
    FOR UPDATE;

    IF record_state.status IS DISTINCT FROM 'APPROVED' THEN
        RETURN NEW;
    END IF;

    INSERT INTO data_retention_policy (tenant_id)
    VALUES (NEW.tenant_id)
    ON CONFLICT (tenant_id) DO NOTHING;

    SELECT * INTO policy
    FROM data_retention_policy
    WHERE tenant_id = NEW.tenant_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'data retention policy is missing for tenant %', NEW.tenant_id;
    END IF;

    NEW.retention_basis_at := coalesce(record_state.reviewed_at, now());
    NEW.retention_until := NEW.retention_basis_at + make_interval(days => CASE
        WHEN NEW.media_type LIKE 'image/%' THEN policy.image_retention_days
        ELSE policy.document_retention_days
    END);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_new_attachment_retention
    BEFORE INSERT ON attachment
    FOR EACH ROW EXECUTE FUNCTION set_new_attachment_retention();

CREATE TRIGGER trg_work_record_attachment_retention
    AFTER UPDATE OF status ON work_record
    FOR EACH ROW
    WHEN (NEW.status = 'APPROVED' AND OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION set_approved_attachment_retention();

-- The migration owner is NOBYPASSRLS and tenant is FORCE protected. Temporarily
-- relax FORCE only to enumerate tenants, restoring it before migration exit.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;

INSERT INTO data_retention_policy (tenant_id)
SELECT id FROM tenant
ON CONFLICT (tenant_id) DO NOTHING;

DO $$
DECLARE
    tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        UPDATE attachment attachment_item
        SET retention_basis_at = record.reviewed_at,
            retention_until = record.reviewed_at + make_interval(days => CASE
                WHEN attachment_item.media_type LIKE 'image/%'
                    THEN policy.image_retention_days
                ELSE policy.document_retention_days
            END)
        FROM work_record record, data_retention_policy policy
        WHERE attachment_item.tenant_id = tenant_record.id
          AND record.tenant_id = attachment_item.tenant_id
          AND record.id = attachment_item.work_record_id
          AND record.status = 'APPROVED'
          AND record.reviewed_at IS NOT NULL
          AND policy.tenant_id = attachment_item.tenant_id
          AND attachment_item.retention_until IS NULL;
    END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

ALTER TABLE data_retention_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_retention_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON data_retention_policy
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE employee_performance_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_performance_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_performance_snapshot
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Every future tenant receives the safe defaults at creation time. The
-- function restores the caller's RLS context after inserting the child row.
CREATE OR REPLACE FUNCTION create_default_data_retention_policy_for_tenant()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    previous_tenant TEXT;
BEGIN
    previous_tenant := current_setting('app.tenant_id', true);
    PERFORM set_config('app.tenant_id', NEW.id::text, true);
    INSERT INTO data_retention_policy (tenant_id)
    VALUES (NEW.id)
    ON CONFLICT (tenant_id) DO NOTHING;
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
    RETURN NEW;
EXCEPTION
    WHEN OTHERS THEN
        PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
        RAISE;
END;
$$;

-- The function is invoked only by the tenant trigger. Direct calls are not
-- part of the application contract, especially because it runs as its owner.
REVOKE ALL ON FUNCTION create_default_data_retention_policy_for_tenant() FROM PUBLIC;

CREATE TRIGGER trg_tenant_default_data_retention_policy
    AFTER INSERT ON tenant
    FOR EACH ROW EXECUTE FUNCTION create_default_data_retention_policy_for_tenant();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hotel_ai_os_app') THEN
        -- V4 grants CRUD on future tables through ALTER DEFAULT PRIVILEGES;
        -- explicitly narrow these two tables back to their intended contract.
        REVOKE DELETE ON data_retention_policy FROM hotel_ai_os_app;
        REVOKE UPDATE, DELETE ON employee_performance_snapshot FROM hotel_ai_os_app;
        GRANT SELECT, INSERT, UPDATE ON data_retention_policy TO hotel_ai_os_app;
        GRANT SELECT, INSERT ON employee_performance_snapshot TO hotel_ai_os_app;
    END IF;
END $$;

COMMENT ON TABLE data_retention_policy IS
    'Tenant retention settings; attachment cleanup remains disabled unless a later release supplies an independently gated worker.';
COMMENT ON TABLE employee_performance_snapshot IS
    'Versioned daily/monthly completion history. KPI score history remains in kpi_responsibility_snapshot and kpi_scorecard_revision.';
