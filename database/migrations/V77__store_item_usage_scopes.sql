-- Descriptive usage configuration only. No stock, history, permissions or quick lists are changed.
-- Missing legacy configuration is deliberately NOT the same as explicitly selecting all departments.
ALTER TABLE store_resource_item ADD COLUMN usage_scope_mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY'
    CHECK (usage_scope_mode IN ('LEGACY', 'ALL_DEPARTMENTS', 'DEPARTMENTS'));

CREATE TABLE store_item_usage_department (
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    item_id UUID NOT NULL,
    department_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, hotel_org_unit_id, item_id, department_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, item_id)
        REFERENCES store_resource_item(tenant_id, hotel_org_unit_id, id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES org_unit(tenant_id, id)
);
CREATE INDEX ix_item_usage_department ON store_item_usage_department(tenant_id, department_id);

CREATE TABLE store_item_usage_position (
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    item_id UUID NOT NULL,
    position_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, hotel_org_unit_id, item_id, position_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, item_id)
        REFERENCES store_resource_item(tenant_id, hotel_org_unit_id, id),
    FOREIGN KEY (tenant_id, position_id) REFERENCES position_definition(tenant_id, id)
);
CREATE INDEX ix_item_usage_position ON store_item_usage_position(tenant_id, position_id);

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['store_item_usage_department', 'store_item_usage_position'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid)', table_name);
    END LOOP;
END $$;
