-- Store inventory, fixed assets, and auditable three-party manager handover.
-- Stock movements and handover actions are append-only business facts.

INSERT INTO permission
    (code, resource, action, description, delegable_to_position, function_category)
VALUES
    ('ui.module.store-resources', 'ui_module', 'view', '显示门店库存与资产模块', true, 'UI_MODULE'),
    ('store-resource.read', 'store_resource', 'read', '查看授权门店的库存、资产和移交记录', true, 'STORE_RESOURCE'),
    ('store-resource.item-manage', 'store_resource', 'item-manage', '维护授权门店的布草、耗品和物品配置', true, 'STORE_RESOURCE'),
    ('store-resource.movement-create', 'store_resource', 'movement-create', '登记授权门店的库存变动', true, 'STORE_RESOURCE'),
    ('store-resource.asset-manage', 'store_resource', 'asset-manage', '维护授权门店的固定资产', true, 'STORE_RESOURCE'),
    ('store-resource.handover-create', 'store_resource', 'handover-create', '发起授权门店的库存与资产移交', true, 'STORE_RESOURCE'),
    ('store-resource.handover-confirm', 'store_resource', 'handover-confirm', '按本人任职完成移交异议、盘点和确认', true, 'STORE_RESOURCE'),
    ('store-resource.handover-approve', 'store_resource', 'handover-approve', '按本人任职审批门店库存与资产移交', true, 'STORE_RESOURCE')
ON CONFLICT (code) DO UPDATE
SET resource = EXCLUDED.resource,
    action = EXCLUDED.action,
    description = EXCLUDED.description,
    delegable_to_position = EXCLUDED.delegable_to_position,
    function_category = EXCLUDED.function_category;

CREATE TABLE store_resource_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    hotel_org_unit_id UUID NOT NULL,
    sku VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    category VARCHAR(32) NOT NULL
        CHECK (category IN ('CONSUMABLE', 'LINEN', 'CLEANING_SUPPLY', 'OPERATING_ITEM')),
    unit VARCHAR(32) NOT NULL,
    unit_cost NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    minimum_quantity NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (minimum_quantity >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    row_version BIGINT NOT NULL DEFAULT 0,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, hotel_org_unit_id, id),
    UNIQUE (tenant_id, hotel_org_unit_id, sku),
    FOREIGN KEY (tenant_id, hotel_org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES user_account (tenant_id, id)
);

CREATE TABLE store_inventory_balance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    hotel_org_unit_id UUID NOT NULL,
    item_id UUID NOT NULL,
    quantity NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    inventory_value NUMERIC(20,4) NOT NULL DEFAULT 0 CHECK (inventory_value >= 0),
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, hotel_org_unit_id, item_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, item_id)
        REFERENCES store_resource_item (tenant_id, hotel_org_unit_id, id)
);

CREATE TABLE store_inventory_movement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    hotel_org_unit_id UUID NOT NULL,
    item_id UUID NOT NULL,
    movement_type VARCHAR(24) NOT NULL
        CHECK (movement_type IN ('RECEIPT', 'CONSUMPTION', 'RETURN', 'DAMAGE', 'ADJUSTMENT')),
    quantity_delta NUMERIC(18,4) NOT NULL CHECK (quantity_delta <> 0),
    quantity_before NUMERIC(18,4) NOT NULL CHECK (quantity_before >= 0),
    quantity_after NUMERIC(18,4) NOT NULL CHECK (quantity_after >= 0),
    unit_cost_snapshot NUMERIC(18,4) NOT NULL CHECK (unit_cost_snapshot >= 0),
    value_delta NUMERIC(20,4) NOT NULL,
    reason TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL'
        CHECK (source_type IN ('MANUAL', 'WORK_RECORD', 'HANDOVER', 'IMPORT')),
    source_id UUID,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    actor_assignment_id UUID,
    actor_account_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, item_id)
        REFERENCES store_resource_item (tenant_id, hotel_org_unit_id, id),
    FOREIGN KEY (tenant_id, actor_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, actor_account_id) REFERENCES user_account (tenant_id, id),
    CHECK (quantity_after = quantity_before + quantity_delta),
    CHECK (
        (movement_type IN ('RECEIPT', 'RETURN') AND quantity_delta > 0)
        OR (movement_type IN ('CONSUMPTION', 'DAMAGE') AND quantity_delta < 0)
        OR movement_type = 'ADJUSTMENT'
    )
);

CREATE TABLE store_fixed_asset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    hotel_org_unit_id UUID NOT NULL,
    asset_code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    category VARCHAR(80) NOT NULL,
    specification VARCHAR(240),
    serial_number VARCHAR(160),
    unit VARCHAR(32) NOT NULL DEFAULT '台',
    original_value NUMERIC(20,2) NOT NULL DEFAULT 0 CHECK (original_value >= 0),
    acquired_on DATE,
    location VARCHAR(160),
    responsible_assignment_id UUID,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'DAMAGED', 'MISSING', 'RETIRED')),
    row_version BIGINT NOT NULL DEFAULT 0,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, hotel_org_unit_id, id),
    UNIQUE (tenant_id, hotel_org_unit_id, asset_code),
    FOREIGN KEY (tenant_id, hotel_org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, responsible_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES user_account (tenant_id, id)
);

CREATE TABLE store_resource_handover (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    hotel_org_unit_id UUID NOT NULL,
    transferor_assignment_id UUID NOT NULL,
    recipient_assignment_id UUID NOT NULL,
    approver_assignment_id UUID NOT NULL,
    status VARCHAR(48) NOT NULL DEFAULT 'AWAITING_DISPUTE_DECISION'
        CHECK (status IN (
            'AWAITING_DISPUTE_DECISION', 'PHYSICAL_COUNT_REQUIRED',
            'AWAITING_TRANSFEROR_CONFIRMATION', 'AWAITING_RECIPIENT_CONFIRMATION',
            'AWAITING_APPROVAL', 'COMPLETED', 'REJECTED', 'CANCELLED'
        )),
    dispute_decision VARCHAR(16) CHECK (dispute_decision IN ('OBJECTION', 'NO_OBJECTION')),
    dispute_reason TEXT,
    snapshot_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, transferor_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, recipient_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, approver_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES user_account (tenant_id, id),
    CHECK (transferor_assignment_id <> recipient_assignment_id
       AND transferor_assignment_id <> approver_assignment_id
       AND recipient_assignment_id <> approver_assignment_id),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX ux_store_resource_handover_one_open
    ON store_resource_handover (tenant_id, hotel_org_unit_id)
    WHERE status IN (
        'AWAITING_DISPUTE_DECISION', 'PHYSICAL_COUNT_REQUIRED',
        'AWAITING_TRANSFEROR_CONFIRMATION', 'AWAITING_RECIPIENT_CONFIRMATION',
        'AWAITING_APPROVAL'
    );

CREATE OR REPLACE FUNCTION enforce_store_resource_handover_distinct_people() RETURNS trigger AS $$
DECLARE
    employee_count INTEGER;
BEGIN
    SELECT count(DISTINCT employee_id)
      INTO employee_count
      FROM employee_position_assignment
     WHERE tenant_id = NEW.tenant_id
       AND id IN (
           NEW.transferor_assignment_id,
           NEW.recipient_assignment_id,
           NEW.approver_assignment_id
       );
    IF employee_count <> 3 THEN
        RAISE EXCEPTION '移交人、签收人、审批人必须为三名不同员工'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_store_resource_handover_distinct_people
    BEFORE INSERT OR UPDATE OF transferor_assignment_id, recipient_assignment_id, approver_assignment_id
    ON store_resource_handover
    FOR EACH ROW EXECUTE FUNCTION enforce_store_resource_handover_distinct_people();

CREATE TABLE store_resource_handover_snapshot_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    handover_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    resource_type VARCHAR(16) NOT NULL CHECK (resource_type IN ('INVENTORY', 'ASSET')),
    item_id UUID,
    asset_id UUID,
    resource_code VARCHAR(80) NOT NULL,
    resource_name VARCHAR(160) NOT NULL,
    unit VARCHAR(32),
    book_quantity NUMERIC(18,4),
    book_state VARCHAR(24),
    book_value NUMERIC(20,4) NOT NULL DEFAULT 0,
    physical_quantity NUMERIC(18,4) CHECK (physical_quantity IS NULL OR physical_quantity >= 0),
    physical_state VARCHAR(24),
    difference_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, handover_id, resource_type, resource_code),
    FOREIGN KEY (tenant_id, handover_id) REFERENCES store_resource_handover (tenant_id, id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, item_id)
        REFERENCES store_resource_item (tenant_id, hotel_org_unit_id, id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, asset_id)
        REFERENCES store_fixed_asset (tenant_id, hotel_org_unit_id, id),
    CHECK (
        (resource_type = 'INVENTORY' AND item_id IS NOT NULL AND asset_id IS NULL
            AND book_quantity IS NOT NULL AND book_state IS NULL)
        OR (resource_type = 'ASSET' AND asset_id IS NOT NULL AND item_id IS NULL
            AND book_quantity IS NULL AND book_state IS NOT NULL)
    )
);

CREATE TABLE store_resource_handover_action (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    handover_id UUID NOT NULL,
    action_type VARCHAR(40) NOT NULL
        CHECK (action_type IN (
            'CREATED', 'DISPUTE_DECIDED', 'PHYSICAL_COUNT_RECORDED',
            'TRANSFEROR_CONFIRMED', 'RECIPIENT_CONFIRMED', 'APPROVED', 'REJECTED'
        )),
    from_status VARCHAR(48),
    to_status VARCHAR(48) NOT NULL,
    actor_assignment_id UUID,
    actor_account_id UUID NOT NULL,
    note TEXT,
    action_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, handover_id) REFERENCES store_resource_handover (tenant_id, id),
    FOREIGN KEY (tenant_id, actor_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, actor_account_id) REFERENCES user_account (tenant_id, id)
);

CREATE INDEX ix_store_resource_item_hotel
    ON store_resource_item (tenant_id, hotel_org_unit_id, status, category, name);
CREATE INDEX ix_store_inventory_balance_hotel
    ON store_inventory_balance (tenant_id, hotel_org_unit_id, item_id);
CREATE INDEX ix_store_inventory_movement_hotel_time
    ON store_inventory_movement (tenant_id, hotel_org_unit_id, occurred_at DESC, item_id);
CREATE INDEX ix_store_fixed_asset_hotel
    ON store_fixed_asset (tenant_id, hotel_org_unit_id, status, category, asset_code);
CREATE INDEX ix_store_resource_handover_hotel_time
    ON store_resource_handover (tenant_id, hotel_org_unit_id, created_at DESC);
CREATE INDEX ix_store_resource_handover_line_parent
    ON store_resource_handover_snapshot_line (tenant_id, handover_id, resource_type);
CREATE INDEX ix_store_resource_handover_action_parent
    ON store_resource_handover_action (tenant_id, handover_id, created_at, id);

CREATE TRIGGER trg_store_resource_item_updated_at
    BEFORE UPDATE ON store_resource_item FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_store_inventory_balance_updated_at
    BEFORE UPDATE ON store_inventory_balance FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_store_fixed_asset_updated_at
    BEFORE UPDATE ON store_fixed_asset FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_store_resource_handover_updated_at
    BEFORE UPDATE ON store_resource_handover FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_store_resource_handover_snapshot_line_updated_at
    BEFORE UPDATE ON store_resource_handover_snapshot_line FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE OR REPLACE FUNCTION prevent_store_resource_fact_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_store_inventory_movement_immutable
    BEFORE UPDATE OR DELETE ON store_inventory_movement
    FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
CREATE TRIGGER trg_store_resource_handover_action_immutable
    BEFORE UPDATE OR DELETE ON store_resource_handover_action
    FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();

CREATE OR REPLACE FUNCTION protect_store_resource_snapshot_book_fields() RETURNS trigger AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.handover_id IS DISTINCT FROM OLD.handover_id
       OR NEW.hotel_org_unit_id IS DISTINCT FROM OLD.hotel_org_unit_id
       OR NEW.resource_type IS DISTINCT FROM OLD.resource_type
       OR NEW.item_id IS DISTINCT FROM OLD.item_id
       OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
       OR NEW.resource_code IS DISTINCT FROM OLD.resource_code
       OR NEW.resource_name IS DISTINCT FROM OLD.resource_name
       OR NEW.unit IS DISTINCT FROM OLD.unit
       OR NEW.book_quantity IS DISTINCT FROM OLD.book_quantity
       OR NEW.book_state IS DISTINCT FROM OLD.book_state
       OR NEW.book_value IS DISTINCT FROM OLD.book_value
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'handover book snapshot is immutable' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_store_resource_snapshot_book_immutable
    BEFORE UPDATE ON store_resource_handover_snapshot_line
    FOR EACH ROW EXECUTE FUNCTION protect_store_resource_snapshot_book_fields();

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'store_resource_item', 'store_inventory_balance', 'store_inventory_movement',
        'store_fixed_asset', 'store_resource_handover',
        'store_resource_handover_snapshot_line', 'store_resource_handover_action'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    END LOOP;
END $$;

-- Explicitly attach the new permissions to existing administration roles.
-- Platform administrators also retain the fail-safe wildcard permission.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role_item.id, permission_item.id
        FROM app_role role_item
        CROSS JOIN permission permission_item
        WHERE role_item.tenant_id = tenant_record.id
          AND role_item.code IN ('PLATFORM_ADMIN', 'GROUP_ADMIN')
          AND permission_item.code IN (
              'ui.module.store-resources', 'store-resource.read',
              'store-resource.item-manage', 'store-resource.movement-create',
              'store-resource.asset-manage', 'store-resource.handover-create',
              'store-resource.handover-confirm', 'store-resource.handover-approve'
          )
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

COMMENT ON TABLE store_inventory_movement IS
    'Append-only hotel inventory ledger. Current balance is changed in the same transaction.';
COMMENT ON TABLE store_resource_handover_snapshot_line IS
    'Immutable book snapshot plus optional physical count used by the three-party handover state machine.';
