-- Budget projections do not post accounting facts. Real cost events retain immutable, period-specific deltas.
CREATE TABLE fbu_flow (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,hotel_id UUID NOT NULL,company_id UUID NOT NULL,
 source_kind VARCHAR(20) NOT NULL CHECK(source_kind IN ('PROCUREMENT','RECEIPT','INVENTORY','RETURN','ACCRUAL','PRICE_ADJUSTMENT')),
 source_id UUID NOT NULL,line_id UUID NOT NULL,document_id UUID NOT NULL,month DATE NOT NULL,
 category_code VARCHAR(80) NOT NULL,department_id UUID,basis_kind VARCHAR(16) NOT NULL CHECK(basis_kind IN ('EXPENSE','CAPITAL','INVENTORY')),
 inflight NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(inflight>=0),committed NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK(committed>=0),
 actual NUMERIC(18,2) NOT NULL DEFAULT 0,inventory_value NUMERIC(18,2) NOT NULL DEFAULT 0,
 source_version BIGINT NOT NULL,source_hash CHAR(64) NOT NULL,snapshot JSONB NOT NULL,
 version INT NOT NULL DEFAULT 0,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,source_kind,source_id,line_id,month),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),
 CHECK(extract(day from month)=1),CHECK(basis_kind<>'INVENTORY' OR actual=0)
);
CREATE INDEX ix_fbu_budget_scope ON fbu_flow(tenant_id,hotel_id,month,category_code,department_id,basis_kind);
CREATE INDEX ix_fbu_source ON fbu_flow(tenant_id,document_id,source_kind,line_id);
COMMENT ON COLUMN fbu_flow.inventory_value IS 'Cumulative financially recognized inventory purchase funding, less supplier returns; NOT current physical inventory value, and NOT expense actual.';
CREATE TABLE fbu_change (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id UUID NOT NULL,flow_id UUID NOT NULL,actor_id UUID NOT NULL,
 prior_snapshot JSONB NOT NULL,new_snapshot JSONB NOT NULL,note VARCHAR(2000) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,flow_id) REFERENCES fbu_flow(tenant_id,id)
);
CREATE TABLE fbu_issue (
 tenant_id UUID NOT NULL,source_kind VARCHAR(20) NOT NULL,source_id UUID NOT NULL,hotel_id UUID NOT NULL,
 message VARCHAR(2000) NOT NULL,status VARCHAR(12) NOT NULL CHECK(status IN ('OPEN','RESOLVED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,source_kind,source_id),FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id)
);
CREATE INDEX ix_fbu_issue_scope ON fbu_issue(tenant_id,hotel_id,status);
CREATE FUNCTION fbu_protect_projection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.source_kind<>'PROCUREMENT' THEN RAISE EXCEPTION 'Real budget source deltas are append-only';END IF;
 IF (to_jsonb(OLD)-ARRAY['inflight','committed','source_version','source_hash','snapshot','version','updated_at']) IS DISTINCT FROM
    (to_jsonb(NEW)-ARRAY['inflight','committed','source_version','source_hash','snapshot','version','updated_at'])
 THEN RAISE EXCEPTION 'Budget source dimensions and real costs cannot be rewritten';END IF;RETURN NEW;
END $$;
CREATE TRIGGER fbu_projection_immutable BEFORE UPDATE OR DELETE ON fbu_flow FOR EACH ROW EXECUTE FUNCTION fbu_protect_projection();
CREATE TRIGGER fbu_history_immutable BEFORE UPDATE OR DELETE ON fbu_change FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
DO $$ DECLARE tab TEXT;BEGIN
 FOREACH tab IN ARRAY ARRAY['fbu_flow','fbu_change','fbu_issue'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
  GRANT SELECT,INSERT,UPDATE ON fbu_flow,fbu_issue TO hotel_ai_os_app;GRANT SELECT,INSERT ON fbu_change TO hotel_ai_os_app;
  REVOKE DELETE ON fbu_flow,fbu_issue FROM hotel_ai_os_app;REVOKE UPDATE,DELETE ON fbu_change FROM hotel_ai_os_app;
 END IF;
END $$;
