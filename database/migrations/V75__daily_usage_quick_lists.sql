-- Per-hotel, per-position ordered shortcuts. No historical report or inventory facts change.
CREATE TABLE store_usage_quick_list (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    hotel_org_unit_id UUID NOT NULL,
    position_id UUID NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, hotel_org_unit_id, position_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id) REFERENCES org_unit(tenant_id,id),
    FOREIGN KEY (tenant_id, position_id) REFERENCES position_definition(tenant_id,id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE store_usage_quick_list_item (
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    position_id UUID NOT NULL,
    item_id UUID NOT NULL,
    display_order INTEGER NOT NULL CHECK (display_order BETWEEN 0 AND 99),
    PRIMARY KEY (tenant_id, hotel_org_unit_id, position_id, item_id),
    UNIQUE (tenant_id, hotel_org_unit_id, position_id, display_order),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, position_id)
        REFERENCES store_usage_quick_list(tenant_id,hotel_org_unit_id,position_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, item_id)
        REFERENCES store_resource_item(tenant_id,hotel_org_unit_id,id)
);
CREATE INDEX ix_usage_quick_list_item ON store_usage_quick_list_item(tenant_id,hotel_org_unit_id,item_id);
DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['store_usage_quick_list','store_usage_quick_list_item'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid)', table_name);
    END LOOP;
END $$;
