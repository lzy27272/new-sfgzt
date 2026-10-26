-- Snapshot real auxiliary identities at the economic source and carry them to balanced voucher lines.
-- Persist the real immutable run before building its voucher, in the same atomic transaction.
DO $$ DECLARE item record; BEGIN
 FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='far_run'::regclass AND confrelid='fa_voucher'::regclass AND contype='f' LOOP
  EXECUTE format('ALTER TABLE far_run ALTER CONSTRAINT %I DEFERRABLE INITIALLY DEFERRED',item.conname);
 END LOOP;
END $$;
ALTER TABLE fa_account ADD COLUMN required_dimensions jsonb NOT NULL DEFAULT '[]'::jsonb
 CHECK(jsonb_typeof(required_dimensions)='array' AND required_dimensions <@ '["DEPARTMENT","EMPLOYEE","PARTY","PROJECT"]'::jsonb);
ALTER TABLE fa_source_event ADD COLUMN auxiliary_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(auxiliary_snapshot)='object');
ALTER TABLE fa_source_event DROP CONSTRAINT fa_source_event_status_check;
ALTER TABLE fa_source_event ADD CONSTRAINT fa_source_event_status_check CHECK(status IN ('PENDING_BOOK','PENDING_ENTITY_MISMATCH','PENDING_MAPPING','PENDING_PERIOD','PENDING_AUXILIARY','DRAFT','POSTED'));
ALTER TABLE fa_voucher_line ADD COLUMN auxiliary jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(auxiliary)='object');
CREATE INDEX fa_line_auxiliary ON fa_voucher_line USING gin(auxiliary jsonb_path_ops);
CREATE INDEX fa_line_department ON fa_voucher_line(tenant_id,book_id,(auxiliary#>>'{department,id}'));
CREATE INDEX fa_line_employee ON fa_voucher_line(tenant_id,book_id,(auxiliary#>>'{employee,accountId}'));
CREATE INDEX fa_line_project ON fa_voucher_line(tenant_id,book_id,(auxiliary#>>'{project,key}'));
CREATE FUNCTION fa_protect_auxiliary_source() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.auxiliary_snapshot IS DISTINCT FROM OLD.auxiliary_snapshot THEN RAISE EXCEPTION 'Frozen source auxiliary dimensions are immutable';END IF;RETURN NEW;
END $$;
CREATE TRIGGER fa_source_auxiliary_immutable BEFORE UPDATE ON fa_source_event FOR EACH ROW EXECUTE FUNCTION fa_protect_auxiliary_source();
COMMENT ON COLUMN fa_voucher_line.auxiliary IS 'Frozen real department, employee account, counterparty and project identities. Empty historical dimensions are not evidence of assignment.';

-- A later independent, immutable source classification may complete an unvouchered event.
-- The captured source snapshot remains untouched, and an existing voucher is never retrofitted.
CREATE TABLE fa_auxiliary_completion (
 event_id uuid NOT NULL,tenant_id uuid NOT NULL,source_request_hash char(64) NOT NULL,
 classification_kind varchar(32) NOT NULL CHECK(classification_kind IN ('MOVEMENT_CLASSIFICATION','PRICE_COST')),
 classification_id uuid NOT NULL,movement_id uuid,price_cost_event_id uuid,
 snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),actor_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,event_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES fa_source_event(tenant_id,id),
 FOREIGN KEY(tenant_id,movement_id) REFERENCES fs_movement_finance_classification(tenant_id,movement_id),
 FOREIGN KEY(tenant_id,price_cost_event_id) REFERENCES fs_price_cost_allocation(tenant_id,source_event_id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id),
 CHECK(classification_kind='MOVEMENT_CLASSIFICATION' AND movement_id=classification_id AND movement_id IS NOT NULL AND price_cost_event_id IS NULL
    OR classification_kind='PRICE_COST' AND price_cost_event_id=classification_id AND price_cost_event_id IS NOT NULL AND movement_id IS NULL)
);
ALTER TABLE fa_auxiliary_completion ENABLE ROW LEVEL SECURITY;
ALTER TABLE fa_auxiliary_completion FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fa_auxiliary_completion USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER auxiliary_completion_immutable BEFORE UPDATE OR DELETE ON fa_auxiliary_completion FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
CREATE FUNCTION fa_validate_auxiliary_completion() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE source fa_source_event%ROWTYPE;
 BEGIN
  SELECT * INTO source FROM fa_source_event WHERE tenant_id=NEW.tenant_id AND id=NEW.event_id FOR UPDATE;
  IF source.id IS NULL OR source.request_hash<>NEW.source_request_hash OR source.voucher_id IS NOT NULL THEN
   RAISE EXCEPTION 'Auxiliary completion requires the exact original unvouchered source';
  END IF;
  IF NEW.classification_kind='MOVEMENT_CLASSIFICATION' AND (source.source_type<>'FS_INVENTORY' OR source.source_id<>NEW.classification_id)
    OR NEW.classification_kind='PRICE_COST' AND (source.source_type<>'FS_PROCUREMENT' OR source.id<>NEW.classification_id OR coalesce(source.metadata->>'nature','')<>'CONSUMED_PRICE_ADJUSTMENT') THEN
   RAISE EXCEPTION 'Auxiliary completion must reference its actual immutable source classification';
  END IF;
  RETURN NEW;
 END $$;
CREATE TRIGGER auxiliary_completion_source_check BEFORE INSERT ON fa_auxiliary_completion FOR EACH ROW EXECUTE FUNCTION fa_validate_auxiliary_completion();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
 GRANT SELECT,INSERT ON fa_auxiliary_completion TO hotel_ai_os_app;
 REVOKE UPDATE,DELETE ON fa_auxiliary_completion FROM hotel_ai_os_app;
END IF;END $$;
