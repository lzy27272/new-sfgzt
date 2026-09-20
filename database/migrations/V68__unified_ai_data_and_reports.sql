-- Two independent sources, one scoped gateway. Never grant a raw SQL tool.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category)
VALUES ('ui.module.ai-data','ui_module','view','AI数据与复盘入口；不额外授予业务数据权限',true,'UI_MODULE')
ON CONFLICT(code) DO NOTHING;
-- Enable the new entry for the existing account-level administrators only.
-- Other positions remain governed by the existing draft/publish permission workflow.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$ DECLARE t RECORD; BEGIN
  FOR t IN SELECT id FROM tenant LOOP
    PERFORM set_config('app.tenant_id',t.id::text,true);
    INSERT INTO role_permission(tenant_id,role_id,permission_id)
    SELECT t.id,r.id,p.id FROM app_role r CROSS JOIN permission p
      WHERE r.tenant_id=t.id AND r.role_type='SYSTEM' AND r.code IN ('CEO','PLATFORM_ADMIN')
        AND p.code='ui.module.ai-data' ON CONFLICT DO NOTHING;
  END LOOP;
END $$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

CREATE TABLE ai_data_connection (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenant(id),
    account_id UUID NOT NULL, assignment_id UUID, name VARCHAR(80) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE, allow_report_write BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    FOREIGN KEY (tenant_id,account_id) REFERENCES user_account(tenant_id,id),
    FOREIGN KEY (tenant_id,assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '31 days')
);
CREATE INDEX ix_ai_connection_owner ON ai_data_connection(tenant_id,account_id,created_at DESC);

CREATE TABLE ai_data_source_binding (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenant(id),
    org_unit_id UUID NOT NULL, source_tenant_id TEXT NOT NULL, source_hotel_id TEXT NOT NULL,
    source_name VARCHAR(160) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id,org_unit_id) REFERENCES org_unit(tenant_id,id),
    UNIQUE(tenant_id,source_tenant_id,source_hotel_id)
);

CREATE TABLE ai_data_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenant(id),
    account_id UUID NOT NULL, scope_hash CHAR(64) NOT NULL, dataset VARCHAR(40) NOT NULL,
    source_binding_id UUID REFERENCES ai_data_source_binding(id),
    query_json JSONB NOT NULL, data_json JSONB NOT NULL, data_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL DEFAULT now()+interval '30 days',
    FOREIGN KEY (tenant_id,account_id) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_ai_snapshot_owner ON ai_data_snapshot(tenant_id,account_id,created_at DESC);

CREATE TABLE ai_analysis_report (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenant(id),
    account_id UUID NOT NULL, scope_hash CHAR(64) NOT NULL, request_id UUID NOT NULL,
    title VARCHAR(160) NOT NULL, body TEXT NOT NULL CHECK (length(body) <= 60000),
    source_type VARCHAR(16) NOT NULL CHECK (source_type IN ('PLATFORM','OTA','COMBINED')),
    period_start DATE NOT NULL, period_end DATE NOT NULL CHECK (period_end>=period_start),
    evidence JSONB NOT NULL, request_hash CHAR(64) NOT NULL,
    authoring_tool VARCHAR(120) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,account_id,request_id),
    FOREIGN KEY (tenant_id,account_id) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_ai_report_owner ON ai_analysis_report(tenant_id,account_id,created_at DESC);

DO $$ DECLARE tab TEXT; BEGIN
  FOREACH tab IN ARRAY ARRAY['ai_data_connection','ai_data_source_binding','ai_data_snapshot','ai_analysis_report'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
    EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  END LOOP;
END $$;
CREATE TRIGGER ai_report_immutable BEFORE UPDATE OR DELETE ON ai_analysis_report
    FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
-- A binding may be disabled, but never silently repointed to a different source/scope.
CREATE FUNCTION guard_ai_source_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (OLD.tenant_id,OLD.org_unit_id,OLD.source_tenant_id,OLD.source_hotel_id)
      IS DISTINCT FROM (NEW.tenant_id,NEW.org_unit_id,NEW.source_tenant_id,NEW.source_hotel_id) THEN
    RAISE EXCEPTION 'create a new binding instead of changing source identity' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ai_binding_identity BEFORE UPDATE ON ai_data_source_binding
    FOR EACH ROW EXECUTE FUNCTION guard_ai_source_binding();

-- The source schema is independently installed by the OTA project. This bridge
-- only reads an explicitly provisioned mapping; no source grants are inferred by name.
CREATE FUNCTION ai_read_ota_daily(binding UUID, day_from DATE, day_to DATE, row_limit INTEGER, row_offset INTEGER)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE b public.ai_data_source_binding; result JSONB;
BEGIN
  IF row_limit IS NULL OR row_offset IS NULL OR row_limit NOT BETWEEN 1 AND 501 OR row_offset NOT BETWEEN 0 AND 100000
     OR day_from IS NULL OR day_to IS NULL OR day_to<day_from OR day_to-day_from>731 THEN
    RAISE EXCEPTION 'invalid bounded query' USING ERRCODE='22023';
  END IF;
  SELECT * INTO STRICT b FROM public.ai_data_source_binding
    WHERE id=binding AND tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid AND enabled;
  SELECT coalesce(jsonb_agg(to_jsonb(rows)),'[]'::jsonb) INTO result FROM (
    SELECT * FROM (
      SELECT DISTINCT ON (business_date) business_date,observed_at,finalized_at,revision,source_system,completeness,
        room_revenue,sold_room_nights,effective_sellable_room_nights,available_rooms,occupancy_rate,adr,revpar,content_hash
      FROM ota_analytics.daily_operating_fact
      WHERE tenant_id=b.source_tenant_id AND hotel_id=b.source_hotel_id
        AND business_date BETWEEN day_from AND day_to
      ORDER BY business_date,revision DESC
    ) latest ORDER BY business_date LIMIT row_limit OFFSET row_offset
  ) rows;
  RETURN result;
END $$;
REVOKE ALL ON FUNCTION ai_read_ota_daily(UUID,DATE,DATE,INTEGER,INTEGER) FROM PUBLIC;
CREATE FUNCTION ai_read_ota_history(binding UUID, dataset TEXT, day_from DATE, day_to DATE, row_limit INTEGER, row_offset INTEGER)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE b public.ai_data_source_binding; result JSONB;
BEGIN
  IF row_limit IS NULL OR row_offset IS NULL OR row_limit NOT BETWEEN 1 AND 501 OR row_offset NOT BETWEEN 0 AND 100000
     OR day_from IS NULL OR day_to IS NULL OR day_to<day_from OR day_to-day_from>1830
     OR dataset IS NULL OR dataset NOT IN ('OTA_HOURLY','OTA_PERIODS') THEN
    RAISE EXCEPTION 'invalid bounded query' USING ERRCODE='22023';
  END IF;
  SELECT * INTO STRICT b FROM public.ai_data_source_binding
    WHERE id=binding AND tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid AND enabled;
  IF dataset='OTA_HOURLY' THEN
    SELECT coalesce(jsonb_agg(to_jsonb(rows)),'[]'::jsonb) INTO result FROM (
      SELECT id,business_date,observed_at,source_system,completeness,room_revenue,sold_room_nights,
        effective_sellable_room_nights,available_rooms,occupancy_rate,adr,revpar,content_hash
      FROM ota_analytics.hourly_operating_fact WHERE tenant_id=b.source_tenant_id AND hotel_id=b.source_hotel_id
        AND business_date BETWEEN day_from AND day_to ORDER BY business_date,observed_at,id LIMIT row_limit OFFSET row_offset
    ) rows;
  ELSE
    SELECT coalesce(jsonb_agg(to_jsonb(rows)),'[]'::jsonb) INTO result FROM (
      SELECT period_type,period_start,period_end,available_day_count,expected_day_count,room_revenue,sold_room_nights,
        effective_sellable_room_nights,occupancy_rate,adr,revpar,completeness,refreshed_at
      FROM ota_analytics.period_rollup WHERE tenant_id=b.source_tenant_id AND hotel_id=b.source_hotel_id
        AND period_start>=day_from AND period_end<=day_to ORDER BY period_start,period_type LIMIT row_limit OFFSET row_offset
    ) rows;
  END IF;
  RETURN result;
END $$;
REVOKE ALL ON FUNCTION ai_read_ota_history(UUID,TEXT,DATE,DATE,INTEGER,INTEGER) FROM PUBLIC;
DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
    GRANT SELECT,INSERT,UPDATE ON ai_data_connection TO hotel_ai_os_app;
    GRANT SELECT ON ai_data_source_binding TO hotel_ai_os_app;
    GRANT SELECT,INSERT,DELETE ON ai_data_snapshot TO hotel_ai_os_app;
    GRANT SELECT,INSERT ON ai_analysis_report TO hotel_ai_os_app;
    GRANT EXECUTE ON FUNCTION ai_read_ota_daily(UUID,DATE,DATE,INTEGER,INTEGER) TO hotel_ai_os_app;
    GRANT EXECUTE ON FUNCTION ai_read_ota_history(UUID,TEXT,DATE,DATE,INTEGER,INTEGER) TO hotel_ai_os_app;
  END IF;
END $$;
