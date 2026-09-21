-- Images have one lifecycle per stored object, even when referenced by several
-- work records, tasks or daily reports. This migration never deletes files and
-- never enables the worker. Production activation is a separate, audited step.
CREATE TABLE image_retention_object (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    object_key VARCHAR(500) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id UUID NOT NULL,
    original_name VARCHAR(240) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL,
    legal_hold BOOLEAN NOT NULL DEFAULT false,
    hold_reason TEXT,
    hold_updated_by UUID,
    hold_updated_at TIMESTAMPTZ,
    state VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (state IN ('AVAILABLE', 'DELETE_FAILED', 'DELETED')),
    deleted_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_error VARCHAR(160),
    row_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, object_key),
    FOREIGN KEY (tenant_id, hold_updated_by) REFERENCES user_account(tenant_id, id),
    CHECK (NOT legal_hold OR (hold_reason IS NOT NULL AND btrim(hold_reason) <> '')),
    CHECK ((state = 'DELETED') = (deleted_at IS NOT NULL))
);
CREATE INDEX ix_image_retention_due ON image_retention_object(tenant_id, uploaded_at, id)
    WHERE NOT legal_hold AND state <> 'DELETED';

CREATE FUNCTION register_image_retention_object() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    evidence JSONB := to_jsonb(NEW);
BEGIN
    IF NEW.media_type IS NULL OR NEW.media_type NOT LIKE 'image/%' OR NEW.object_key IS NULL THEN RETURN NEW; END IF;
    -- A successful upload has already written/scanned its bytes before inserting
    -- the metadata. Copies/references never refresh this original server clock.
    INSERT INTO image_retention_object
        (tenant_id, object_key, source_type, source_id, original_name, size_bytes,
         sha256, uploaded_at, legal_hold, hold_reason, hold_updated_at)
    VALUES (NEW.tenant_id, NEW.object_key, TG_TABLE_NAME, NEW.id,
            coalesce(NEW.original_name, 'image'), coalesce(NEW.size_bytes, 0),
            coalesce(NEW.sha256, repeat('0',64)), statement_timestamp(),
            coalesce((evidence->>'legal_hold')::boolean, false),
            evidence->>'legal_hold_reason', (evidence->>'legal_hold_set_at')::timestamptz)
    ON CONFLICT (tenant_id, object_key) DO NOTHING;
    -- Historical report revisions may still reference expired image metadata.
    -- They must not resurrect bytes, extend the clock, or clear a hold.
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_attachment_image_lifecycle AFTER INSERT ON attachment
    FOR EACH ROW EXECUTE FUNCTION register_image_retention_object();
CREATE TRIGGER trg_task_image_lifecycle AFTER INSERT ON task_evidence
    FOR EACH ROW EXECUTE FUNCTION register_image_retention_object();
CREATE TRIGGER trg_daily_report_image_lifecycle AFTER INSERT ON daily_report_evidence
    FOR EACH ROW EXECUTE FUNCTION register_image_retention_object();

-- Preserve old important-evidence flags and reject direct legacy flag changes
-- after deletion. The object row serializes holds against the deletion worker.
CREATE FUNCTION sync_attachment_image_hold() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE object_state TEXT;
BEGIN
    IF NEW.media_type LIKE 'image/%' THEN
        SELECT state INTO object_state FROM image_retention_object
        WHERE tenant_id = NEW.tenant_id AND object_key = NEW.object_key FOR UPDATE;
        IF object_state = 'DELETED' THEN
            RAISE EXCEPTION 'deleted image cannot be placed on hold';
        END IF;
        UPDATE image_retention_object
        SET legal_hold = NEW.legal_hold, hold_reason = NEW.legal_hold_reason,
            hold_updated_at = now(), hold_updated_by = NEW.legal_hold_set_by,
            row_version = row_version + 1
        WHERE tenant_id = NEW.tenant_id AND object_key = NEW.object_key;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_attachment_image_hold AFTER UPDATE OF legal_hold ON attachment
    FOR EACH ROW WHEN (OLD.legal_hold IS DISTINCT FROM NEW.legal_hold)
    EXECUTE FUNCTION sync_attachment_image_hold();

-- Keep legacy attachment retention metadata upload-based as well. Review events
-- must not extend image life. The worker uses the object ledger as authority.
CREATE OR REPLACE FUNCTION set_approved_attachment_retention() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'APPROVED' AND OLD.status IS DISTINCT FROM NEW.status THEN
        UPDATE attachment a
        SET retention_basis_at = coalesce(NEW.reviewed_at, now()),
            retention_until = coalesce(NEW.reviewed_at, now()) + p.document_retention_days * interval '24 hours'
        FROM data_retention_policy p
        WHERE a.tenant_id = NEW.tenant_id AND a.work_record_id = NEW.id
          AND p.tenant_id = a.tenant_id AND a.media_type NOT LIKE 'image/%'
          AND a.storage_status = 'AVAILABLE' AND a.deleted_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;
CREATE OR REPLACE FUNCTION set_new_attachment_retention() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE policy data_retention_policy%ROWTYPE; record_state RECORD;
BEGIN
    SELECT * INTO policy FROM data_retention_policy WHERE tenant_id = NEW.tenant_id;
    IF NEW.media_type LIKE 'image/%' THEN
        NEW.retention_basis_at := statement_timestamp();
        NEW.retention_until := NEW.retention_basis_at + policy.image_retention_days * interval '24 hours';
    ELSE
        SELECT status, reviewed_at INTO record_state FROM work_record
        WHERE tenant_id = NEW.tenant_id AND id = NEW.work_record_id FOR UPDATE;
        IF record_state.status = 'APPROVED' THEN
            NEW.retention_basis_at := coalesce(record_state.reviewed_at, now());
            NEW.retention_until := NEW.retention_basis_at + policy.document_retention_days * interval '24 hours';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$
DECLARE t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);
        INSERT INTO image_retention_object
            (tenant_id,object_key,source_type,source_id,original_name,size_bytes,sha256,
             uploaded_at,legal_hold,hold_reason,hold_updated_at,hold_updated_by,state,deleted_at)
        SELECT tenant_id,object_key,'attachment',id,original_name,size_bytes,sha256,
               coalesce(received_at,created_at),legal_hold,legal_hold_reason,legal_hold_set_at,
               legal_hold_set_by,CASE WHEN deleted_at IS NULL THEN 'AVAILABLE' ELSE 'DELETED' END,deleted_at
        FROM attachment WHERE tenant_id=t.id AND media_type LIKE 'image/%'
        ON CONFLICT (tenant_id,object_key) DO NOTHING;
        INSERT INTO image_retention_object
            (tenant_id,object_key,source_type,source_id,original_name,size_bytes,sha256,uploaded_at)
        SELECT tenant_id,object_key,'task_evidence',id,coalesce(original_name,'image'),
               coalesce(size_bytes,0),coalesce(sha256,repeat('0',64)),created_at
        FROM task_evidence WHERE tenant_id=t.id AND media_type LIKE 'image/%' AND object_key IS NOT NULL
        ON CONFLICT (tenant_id,object_key) DO NOTHING;
        INSERT INTO image_retention_object
            (tenant_id,object_key,source_type,source_id,original_name,size_bytes,sha256,uploaded_at)
        SELECT tenant_id,object_key,'daily_report_evidence',id,coalesce(original_name,'image'),
               coalesce(size_bytes,0),coalesce(sha256,repeat('0',64)),created_at
        FROM daily_report_evidence WHERE tenant_id=t.id AND media_type LIKE 'image/%' AND object_key IS NOT NULL
        ON CONFLICT (tenant_id,object_key) DO NOTHING;
        UPDATE attachment a SET retention_basis_at = o.uploaded_at,
            retention_until = o.uploaded_at + p.image_retention_days * interval '24 hours'
        FROM image_retention_object o, data_retention_policy p
        WHERE a.tenant_id=t.id AND o.tenant_id=a.tenant_id AND o.object_key=a.object_key
          AND p.tenant_id=a.tenant_id AND a.media_type LIKE 'image/%' AND a.deleted_at IS NULL;
    END LOOP;
END;
$$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
ALTER TABLE image_retention_object ENABLE ROW LEVEL SECURITY;
ALTER TABLE image_retention_object FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON image_retention_object
    USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
        GRANT SELECT, INSERT, UPDATE ON image_retention_object TO hotel_ai_os_app;
        REVOKE DELETE ON image_retention_object FROM hotel_ai_os_app;
    END IF;
END $$;
