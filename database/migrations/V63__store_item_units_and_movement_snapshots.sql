-- Base-unit inventory is authoritative; display/entry units are per-item aliases.
ALTER TABLE store_resource_item
    ADD COLUMN specification VARCHAR(240) NOT NULL DEFAULT '',
    ADD COLUMN purchase_unit VARCHAR(32),
    ADD COLUMN purchase_factor NUMERIC(18,6) NOT NULL DEFAULT 1,
    ADD COLUMN issue_unit VARCHAR(32),
    ADD COLUMN issue_factor NUMERIC(18,6) NOT NULL DEFAULT 1;

ALTER TABLE store_resource_item NO FORCE ROW LEVEL SECURITY;
UPDATE store_resource_item SET purchase_unit = unit, issue_unit = unit;
ALTER TABLE store_resource_item FORCE ROW LEVEL SECURITY;
ALTER TABLE store_resource_item
    ALTER COLUMN purchase_unit SET NOT NULL,
    ALTER COLUMN issue_unit SET NOT NULL,
    ADD CONSTRAINT store_item_positive_unit_factors CHECK (purchase_factor > 0 AND issue_factor > 0),
    ADD CONSTRAINT store_item_unit_names CHECK (btrim(unit) <> '' AND btrim(purchase_unit) <> '' AND btrim(issue_unit) <> ''),
    ADD CONSTRAINT store_item_consistent_unit_factors CHECK (
        (lower(purchase_unit) <> lower(unit) OR purchase_factor = 1)
        AND (lower(issue_unit) <> lower(unit) OR issue_factor = 1)
        AND (lower(purchase_unit) <> lower(issue_unit) OR purchase_factor = issue_factor)
    );

CREATE INDEX ix_store_inventory_movement_item_history
    ON store_inventory_movement (tenant_id, hotel_org_unit_id, item_id);

CREATE FUNCTION maintain_store_item_units() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Older API/import clients can still create base-unit-only items.
    NEW.purchase_unit := coalesce(NEW.purchase_unit, NEW.unit);
    NEW.issue_unit := coalesce(NEW.issue_unit, NEW.unit);
    IF TG_OP = 'UPDATE' AND OLD.unit IS DISTINCT FROM NEW.unit AND EXISTS (
        SELECT 1 FROM store_inventory_movement
        WHERE tenant_id = OLD.tenant_id AND hotel_org_unit_id = OLD.hotel_org_unit_id AND item_id = OLD.id
    ) THEN
        RAISE EXCEPTION 'An item with stock history cannot change its base unit' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_store_item_unit_guard
    BEFORE INSERT OR UPDATE ON store_resource_item
    FOR EACH ROW EXECUTE FUNCTION maintain_store_item_units();

-- Do not rewrite immutable historical facts. Null snapshots mean legacy base-unit entry.
ALTER TABLE store_inventory_movement
    ADD COLUMN base_unit_snapshot VARCHAR(32),
    ADD COLUMN input_unit VARCHAR(32),
    ADD COLUMN input_quantity NUMERIC(18,4),
    ADD COLUMN conversion_factor NUMERIC(18,6),
    ADD COLUMN input_unit_cost NUMERIC(18,4),
    ADD CONSTRAINT store_movement_unit_snapshot_complete CHECK (
        (base_unit_snapshot IS NULL AND input_unit IS NULL AND input_quantity IS NULL
            AND conversion_factor IS NULL AND input_unit_cost IS NULL)
        OR (base_unit_snapshot IS NOT NULL AND input_unit IS NOT NULL AND input_quantity IS NOT NULL
            AND conversion_factor IS NOT NULL AND conversion_factor > 0 AND input_quantity <> 0
            AND input_quantity * conversion_factor = quantity_delta
            AND (input_unit_cost IS NULL OR input_unit_cost >= 0))
    );
COMMENT ON COLUMN store_resource_item.purchase_factor IS 'One purchase unit equals this many base units.';
COMMENT ON COLUMN store_resource_item.issue_factor IS 'One issue/consumption unit equals this many base units.';
COMMENT ON COLUMN store_inventory_movement.conversion_factor IS 'Immutable factor at posting; later item changes never revalue history.';
