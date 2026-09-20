-- Laundry is a custody ledger, not a second linen consumption ledger.
CREATE TABLE store_laundry_rate (
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    item_id UUID NOT NULL,
    base_unit VARCHAR(32) NOT NULL,
    unit_price NUMERIC(18,4) NOT NULL CHECK (unit_price >= 0),
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, hotel_org_unit_id, item_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, item_id) REFERENCES store_resource_item(tenant_id,hotel_org_unit_id,id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE store_laundry_order (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    supplier VARCHAR(160) NOT NULL CHECK (length(trim(supplier)) > 0),
    dispatched_on DATE NOT NULL,
    note VARCHAR(1000) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CANCELLED')),
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_by UUID,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(1000),
    UNIQUE (tenant_id,hotel_org_unit_id,id),
    UNIQUE (tenant_id,idempotency_key),
    CHECK ((status='ACTIVE' AND cancelled_by IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status='CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND length(trim(cancellation_reason)) > 0)),
    FOREIGN KEY (tenant_id,hotel_org_unit_id) REFERENCES org_unit(tenant_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES user_account(tenant_id,id),
    FOREIGN KEY (tenant_id,cancelled_by) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE store_laundry_line (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    order_id UUID NOT NULL,
    item_id UUID NOT NULL,
    item_name VARCHAR(160) NOT NULL,
    base_unit VARCHAR(32) NOT NULL,
    quantity NUMERIC(18,4) NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(18,4) NOT NULL CHECK (unit_price >= 0),
    rate_version BIGINT NOT NULL,
    UNIQUE (tenant_id,hotel_org_unit_id,order_id,id),
    UNIQUE (tenant_id,order_id,item_id),
    FOREIGN KEY (tenant_id,hotel_org_unit_id,order_id) REFERENCES store_laundry_order(tenant_id,hotel_org_unit_id,id),
    FOREIGN KEY (tenant_id,hotel_org_unit_id,item_id) REFERENCES store_resource_item(tenant_id,hotel_org_unit_id,id)
);
CREATE TABLE store_laundry_receipt (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    order_id UUID NOT NULL,
    received_on DATE NOT NULL,
    note VARCHAR(1000) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','REVERSED')),
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reversed_by UUID,
    reversed_at TIMESTAMPTZ,
    reversal_reason VARCHAR(1000),
    UNIQUE (tenant_id,hotel_org_unit_id,order_id,id),
    UNIQUE (tenant_id,idempotency_key),
    CHECK ((status='POSTED' AND reversed_by IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL)
        OR (status='REVERSED' AND reversed_by IS NOT NULL AND reversed_at IS NOT NULL AND length(trim(reversal_reason)) > 0)),
    FOREIGN KEY (tenant_id,hotel_org_unit_id,order_id) REFERENCES store_laundry_order(tenant_id,hotel_org_unit_id,id),
    FOREIGN KEY (tenant_id,created_by) REFERENCES user_account(tenant_id,id),
    FOREIGN KEY (tenant_id,reversed_by) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE store_laundry_receipt_line (
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    order_id UUID NOT NULL,
    receipt_id UUID NOT NULL,
    order_line_id UUID NOT NULL,
    accepted_quantity NUMERIC(18,4) NOT NULL CHECK (accepted_quantity >= 0),
    rewash_quantity NUMERIC(18,4) NOT NULL CHECK (rewash_quantity >= 0),
    written_off_quantity NUMERIC(18,4) NOT NULL CHECK (written_off_quantity >= 0),
    fee NUMERIC(20,4) NOT NULL CHECK (fee >= 0),
    difference_reason VARCHAR(1000) NOT NULL DEFAULT '',
    PRIMARY KEY (tenant_id,receipt_id,order_line_id),
    CHECK (accepted_quantity + rewash_quantity + written_off_quantity > 0),
    CHECK (written_off_quantity + rewash_quantity = 0 OR length(trim(difference_reason)) > 0),
    FOREIGN KEY (tenant_id,hotel_org_unit_id,order_id,receipt_id) REFERENCES store_laundry_receipt(tenant_id,hotel_org_unit_id,order_id,id),
    FOREIGN KEY (tenant_id,hotel_org_unit_id,order_id,order_line_id) REFERENCES store_laundry_line(tenant_id,hotel_org_unit_id,order_id,id)
);
CREATE INDEX ix_laundry_order_hotel_date ON store_laundry_order(tenant_id,hotel_org_unit_id,dispatched_on DESC,id);
CREATE INDEX ix_laundry_line_item ON store_laundry_line(tenant_id,hotel_org_unit_id,item_id,order_id);
CREATE INDEX ix_laundry_receipt_order ON store_laundry_receipt(tenant_id,hotel_org_unit_id,order_id,received_on);
CREATE INDEX ix_laundry_receipt_line_order_line ON store_laundry_receipt_line(tenant_id,hotel_org_unit_id,order_line_id);
CREATE TRIGGER trg_laundry_line_immutable BEFORE UPDATE OR DELETE ON store_laundry_line
    FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
CREATE TRIGGER trg_laundry_receipt_line_immutable BEFORE UPDATE OR DELETE ON store_laundry_receipt_line
    FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();

CREATE FUNCTION protect_laundry_history() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'laundry history cannot be deleted' USING ERRCODE = '55000';
    END IF;
    IF TG_TABLE_NAME = 'store_laundry_order' THEN
        IF (to_jsonb(NEW) - ARRAY['status','row_version','cancelled_by','cancelled_at','cancellation_reason'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status','row_version','cancelled_by','cancelled_at','cancellation_reason'])
            OR OLD.status <> 'ACTIVE' OR NEW.row_version <> OLD.row_version + 1 THEN
            RAISE EXCEPTION 'invalid laundry order transition' USING ERRCODE = '55000';
        END IF;
    ELSE
        IF (to_jsonb(NEW) - ARRAY['status','reversed_by','reversed_at','reversal_reason'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status','reversed_by','reversed_at','reversal_reason'])
            OR OLD.status <> 'POSTED' OR NEW.status <> 'REVERSED' THEN
            RAISE EXCEPTION 'invalid laundry receipt transition' USING ERRCODE = '55000';
        END IF;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_laundry_order_history BEFORE UPDATE OR DELETE ON store_laundry_order
    FOR EACH ROW EXECUTE FUNCTION protect_laundry_history();
CREATE TRIGGER trg_laundry_receipt_history BEFORE UPDATE OR DELETE ON store_laundry_receipt
    FOR EACH ROW EXECUTE FUNCTION protect_laundry_history();

CREATE FUNCTION protect_laundry_item_unit() RETURNS trigger AS $$
BEGIN
    IF (NEW.unit IS DISTINCT FROM OLD.unit OR NEW.category IS DISTINCT FROM OLD.category) AND EXISTS (
        SELECT 1 FROM store_laundry_rate WHERE tenant_id=OLD.tenant_id AND hotel_org_unit_id=OLD.hotel_org_unit_id AND item_id=OLD.id
    ) THEN
        RAISE EXCEPTION 'unit and category are locked by laundry configuration' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_laundry_item_unit BEFORE UPDATE ON store_resource_item
    FOR EACH ROW EXECUTE FUNCTION protect_laundry_item_unit();

ALTER TABLE store_inventory_movement DROP CONSTRAINT store_inventory_movement_source_type_check;
ALTER TABLE store_inventory_movement ADD CONSTRAINT store_inventory_movement_source_type_check
    CHECK (source_type IN ('MANUAL','WORK_RECORD','HANDOVER','IMPORT','LAUNDRY'));

DO $$ DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['store_laundry_rate','store_laundry_order','store_laundry_line','store_laundry_receipt','store_laundry_receipt_line'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid)', table_name);
    END LOOP;
END $$;
