-- Only mapping metadata. No backfill of business records runs during deployment.
CREATE INDEX ix_work_record_assignment_day ON work_record(tenant_id,position_assignment_id,business_date)
    WHERE status IN ('SUBMITTED','APPROVED');
CREATE TABLE store_department_prefix_config (
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    qt_department_id UUID,
    kf_department_id UUID,
    wx_department_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    updated_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,hotel_org_unit_id),
    FOREIGN KEY (tenant_id,hotel_org_unit_id) REFERENCES org_unit(tenant_id,id),
    FOREIGN KEY (tenant_id,qt_department_id) REFERENCES org_unit(tenant_id,id),
    FOREIGN KEY (tenant_id,kf_department_id) REFERENCES org_unit(tenant_id,id),
    FOREIGN KEY (tenant_id,wx_department_id) REFERENCES org_unit(tenant_id,id)
);
ALTER TABLE store_department_prefix_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE store_department_prefix_config FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON store_department_prefix_config
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
        REVOKE ALL PRIVILEGES ON store_department_prefix_config FROM hotel_ai_os_app;
        GRANT SELECT,INSERT,UPDATE ON store_department_prefix_config TO hotel_ai_os_app;
    END IF;
END $$;
