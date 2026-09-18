-- Unified tabular-import foundation and the first production adapter:
-- new-store item, opening inventory and fixed-asset initialization.

INSERT INTO permission
    (code, resource, action, description, delegable_to_position, function_category)
VALUES
    ('store-resource.import-preview', 'store_resource', 'import-preview',
     '下载模板并预检授权门店的库存与资产导入文件', true, 'STORE_RESOURCE'),
    ('store-resource.import-apply', 'store_resource', 'import-apply',
     '确认写入已通过预检的库存与资产导入任务', true, 'STORE_RESOURCE')
ON CONFLICT (code) DO UPDATE
SET resource = EXCLUDED.resource,
    action = EXCLUDED.action,
    description = EXCLUDED.description,
    delegable_to_position = EXCLUDED.delegable_to_position,
    function_category = EXCLUDED.function_category;

CREATE TABLE tabular_import_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    target_org_unit_id UUID,
    import_type VARCHAR(80) NOT NULL,
    template_version INTEGER NOT NULL CHECK (template_version > 0),
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 CHAR(64) NOT NULL,
    scan_status VARCHAR(24) NOT NULL CHECK (scan_status IN ('CLEAN', 'BYPASSED_DEV')),
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('INVALID', 'READY', 'APPLIED', 'CANCELLED')),
    parsed_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    result_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    row_count INTEGER NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    valid_row_count INTEGER NOT NULL DEFAULT 0 CHECK (valid_row_count >= 0),
    error_count INTEGER NOT NULL DEFAULT 0 CHECK (error_count >= 0),
    created_by UUID NOT NULL,
    applied_by UUID,
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, target_org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, applied_by) REFERENCES user_account (tenant_id, id),
    CHECK ((status = 'APPLIED') = (applied_at IS NOT NULL)),
    CHECK (valid_row_count <= row_count),
    CHECK (error_count = jsonb_array_length(validation_errors))
);

CREATE TABLE tabular_import_result_link (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    job_id UUID NOT NULL,
    sheet_name VARCHAR(120) NOT NULL,
    row_number INTEGER NOT NULL CHECK (row_number >= 2),
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID NOT NULL,
    resource_code VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, job_id, sheet_name, row_number, resource_type),
    FOREIGN KEY (tenant_id, job_id) REFERENCES tabular_import_job (tenant_id, id)
);

CREATE INDEX ix_tabular_import_job_scope_time
    ON tabular_import_job (tenant_id, target_org_unit_id, import_type, created_at DESC);
CREATE UNIQUE INDEX ux_tabular_import_job_file
    ON tabular_import_job (
        tenant_id,
        coalesce(target_org_unit_id, '00000000-0000-0000-0000-000000000000'::uuid),
        import_type,
        sha256
    );
CREATE INDEX ix_tabular_import_result_job
    ON tabular_import_result_link (tenant_id, job_id, sheet_name, row_number);

CREATE TRIGGER trg_tabular_import_job_updated_at
    BEFORE UPDATE ON tabular_import_job FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_tabular_import_result_link_immutable
    BEFORE UPDATE OR DELETE ON tabular_import_result_link
    FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();

ALTER TABLE tabular_import_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE tabular_import_job FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tabular_import_job
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE tabular_import_result_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE tabular_import_result_link FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tabular_import_result_link
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Opening balances are business facts, not generic adjustments. Keep their
-- explicit semantic in the immutable inventory ledger.
ALTER TABLE store_inventory_movement
    DROP CONSTRAINT store_inventory_movement_movement_type_check;
ALTER TABLE store_inventory_movement
    ADD CONSTRAINT store_inventory_movement_movement_type_check
    CHECK (movement_type IN ('OPENING', 'RECEIPT', 'CONSUMPTION', 'RETURN', 'DAMAGE', 'ADJUSTMENT'));
ALTER TABLE store_inventory_movement
    DROP CONSTRAINT store_inventory_movement_check1;
ALTER TABLE store_inventory_movement
    ADD CONSTRAINT store_inventory_movement_direction_check
    CHECK (
        (movement_type IN ('OPENING', 'RECEIPT', 'RETURN') AND quantity_delta > 0)
        OR (movement_type IN ('CONSUMPTION', 'DAMAGE') AND quantity_delta < 0)
        OR movement_type = 'ADJUSTMENT'
    );

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
              'store-resource.import-preview', 'store-resource.import-apply'
          )
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

COMMENT ON TABLE tabular_import_job IS
    'Common preview-before-apply import job. Parsed business rows are retained; uploaded workbook bytes are not retained.';
COMMENT ON TABLE tabular_import_result_link IS
    'Immutable trace from an applied workbook row to the records created by that row.';
