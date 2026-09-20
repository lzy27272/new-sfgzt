-- Opt-in per hotel. Existing free-text work records never create stock movements.
CREATE TABLE store_usage_review_config (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    hotel_org_unit_id UUID NOT NULL,
    reviewer_assignment_id UUID NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, hotel_org_unit_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id) REFERENCES org_unit(tenant_id,id),
    FOREIGN KEY (tenant_id, reviewer_assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES user_account(tenant_id,id)
);

CREATE TABLE store_work_usage (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    work_record_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    reviewer_assignment_id_snapshot UUID NOT NULL,
    no_usage BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','REVERSED')),
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    reversed_by UUID,
    reversed_at TIMESTAMPTZ,
    reversal_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR (status <> 'PENDING' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CHECK ((status = 'REVERSED' AND reversed_by IS NOT NULL AND reversed_at IS NOT NULL AND reversal_reason IS NOT NULL AND length(trim(reversal_reason)) > 0)
        OR (status <> 'REVERSED' AND reversed_by IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL)),
    PRIMARY KEY (tenant_id, work_record_id),
    UNIQUE (tenant_id, hotel_org_unit_id, work_record_id),
    FOREIGN KEY (tenant_id, work_record_id) REFERENCES work_record(tenant_id,id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id) REFERENCES org_unit(tenant_id,id),
    FOREIGN KEY (tenant_id, reviewer_assignment_id_snapshot) REFERENCES employee_position_assignment(tenant_id,id),
    FOREIGN KEY (tenant_id, reviewed_by) REFERENCES user_account(tenant_id,id),
    FOREIGN KEY (tenant_id, reversed_by) REFERENCES user_account(tenant_id,id)
);

CREATE TABLE store_work_usage_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    hotel_org_unit_id UUID NOT NULL,
    work_record_id UUID NOT NULL,
    item_id UUID NOT NULL,
    item_name VARCHAR(160) NOT NULL,
    category VARCHAR(32) NOT NULL CHECK (category IN ('CONSUMABLE','CLEANING_SUPPLY','LINEN')),
    base_unit VARCHAR(32) NOT NULL,
    input_unit VARCHAR(32) NOT NULL,
    input_quantity NUMERIC(18,4) NOT NULL CHECK (input_quantity > 0),
    conversion_factor NUMERIC(18,6) NOT NULL CHECK (conversion_factor > 0),
    base_quantity NUMERIC(18,4) NOT NULL CHECK (base_quantity > 0),
    item_version BIGINT NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, work_record_id, item_id),
    CHECK (input_quantity * conversion_factor = base_quantity),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, work_record_id)
        REFERENCES store_work_usage(tenant_id,hotel_org_unit_id,work_record_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, item_id)
        REFERENCES store_resource_item(tenant_id,hotel_org_unit_id,id)
);

CREATE INDEX ix_store_usage_hotel_pending ON store_work_usage(tenant_id,hotel_org_unit_id,status,created_at DESC);
CREATE INDEX ix_store_usage_line_item ON store_work_usage_line(tenant_id,hotel_org_unit_id,item_id);
CREATE INDEX ix_store_movement_work_source ON store_inventory_movement(tenant_id,source_id,item_id)
    WHERE source_type = 'WORK_RECORD';
CREATE TRIGGER trg_store_usage_line_immutable BEFORE UPDATE OR DELETE ON store_work_usage_line
    FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();

CREATE FUNCTION protect_store_usage_snapshot() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'work usage history cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.work_record_id IS DISTINCT FROM OLD.work_record_id
       OR NEW.hotel_org_unit_id IS DISTINCT FROM OLD.hotel_org_unit_id
       OR NEW.reviewer_assignment_id_snapshot IS DISTINCT FROM OLD.reviewer_assignment_id_snapshot
       OR NEW.no_usage IS DISTINCT FROM OLD.no_usage
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR (OLD.status = 'APPROVED' AND (NEW.reviewed_by IS DISTINCT FROM OLD.reviewed_by
            OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at))
       OR NOT ((OLD.status = 'PENDING' AND NEW.status IN ('APPROVED','REJECTED'))
            OR (OLD.status = 'APPROVED' AND NEW.status = 'REVERSED')) THEN
        RAISE EXCEPTION 'invalid work usage transition' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_store_usage_snapshot BEFORE UPDATE OR DELETE ON store_work_usage
    FOR EACH ROW EXECUTE FUNCTION protect_store_usage_snapshot();

CREATE FUNCTION protect_usage_item_base_unit() RETURNS trigger AS $$
BEGIN
    IF NEW.unit IS DISTINCT FROM OLD.unit AND EXISTS (
        SELECT 1 FROM store_work_usage_line
        WHERE tenant_id = OLD.tenant_id AND hotel_org_unit_id = OLD.hotel_org_unit_id AND item_id = OLD.id
    ) THEN
        RAISE EXCEPTION 'base unit is locked by work usage history' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_store_usage_item_base_unit BEFORE UPDATE ON store_resource_item
    FOR EACH ROW EXECUTE FUNCTION protect_usage_item_base_unit();

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['store_usage_review_config','store_work_usage','store_work_usage_line'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid)', table_name);
    END LOOP;
END $$;

COMMENT ON TABLE store_work_usage IS 'Work-record review and stock posting are atomic. Linen use records turnover only. Reversal never deletes original facts.';
