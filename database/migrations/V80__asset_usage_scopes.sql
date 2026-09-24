-- Usage scopes are descriptive selection metadata, separate from the asset custodian.
ALTER TABLE store_fixed_asset ADD COLUMN usage_scope_mode VARCHAR(24) NOT NULL DEFAULT 'LEGACY'
    CHECK (usage_scope_mode IN ('LEGACY','ALL_DEPARTMENTS','DEPARTMENTS'));
CREATE TABLE store_asset_usage_department (
    tenant_id UUID NOT NULL, hotel_org_unit_id UUID NOT NULL, asset_id UUID NOT NULL, department_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, hotel_org_unit_id, asset_id, department_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, asset_id) REFERENCES store_fixed_asset(tenant_id, hotel_org_unit_id, id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES org_unit(tenant_id,id)
);
CREATE TABLE store_asset_usage_position (
    tenant_id UUID NOT NULL, hotel_org_unit_id UUID NOT NULL, asset_id UUID NOT NULL, position_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, hotel_org_unit_id, asset_id, position_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id, asset_id) REFERENCES store_fixed_asset(tenant_id, hotel_org_unit_id, id),
    FOREIGN KEY (tenant_id, position_id) REFERENCES position_definition(tenant_id,id)
);
CREATE INDEX ix_asset_usage_department ON store_asset_usage_department(tenant_id,department_id);
CREATE INDEX ix_asset_usage_position ON store_asset_usage_position(tenant_id,position_id);
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['store_asset_usage_department','store_asset_usage_position'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',t);
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO hotel_ai_os_app',t);
        END IF;
    END LOOP;
END $$;
