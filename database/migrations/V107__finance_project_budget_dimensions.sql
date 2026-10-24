-- Explicit references to existing approved contracts or existing construction-in-progress cards.
-- NONE with an empty snapshot is historical/unassigned, not evidence that a project was reviewed.
ALTER TABLE finance_category ADD COLUMN budget_code varchar(80);
ALTER TABLE finance_category ADD CONSTRAINT finance_category_budget_code_format CHECK(budget_code IS NULL OR budget_code ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$');
CREATE UNIQUE INDEX finance_category_budget_code_unique ON finance_category(tenant_id,hotel_id,budget_code) WHERE budget_code IS NOT NULL;
ALTER TABLE finance_claim_line ADD COLUMN financial_category_code varchar(80);
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['finance_claim_line','procurement_line','fs_procurement_finance_classification','fs_movement_finance_classification','store_inventory_movement','fbu_flow'] LOOP
  EXECUTE format('ALTER TABLE %I ADD COLUMN project_kind varchar(12) NOT NULL DEFAULT ''NONE'',ADD COLUMN project_id uuid,ADD COLUMN project_key varchar(64) NOT NULL DEFAULT ''NONE'',ADD COLUMN project_snapshot jsonb NOT NULL DEFAULT ''{}''::jsonb',t);
  EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK((project_kind=''NONE'' AND project_id IS NULL AND project_key=''NONE'') OR (project_kind IN (''CONTRACT'',''CIP'') AND project_id IS NOT NULL AND project_key=project_kind||'':''||project_id::text))',t,t||'_project_ref');
 END LOOP;
END $$;
ALTER TABLE fc_budget_usage ADD COLUMN project_key varchar(64) NOT NULL DEFAULT 'ALL';
CREATE INDEX fc_budget_usage_project ON fc_budget_usage(tenant_id,policy_id,project_key,state);
ALTER TABLE fbu_flow DROP CONSTRAINT fbu_flow_tenant_id_source_kind_source_id_line_id_month_key;
ALTER TABLE fbu_flow ADD CONSTRAINT fbu_flow_source_project_unique UNIQUE(tenant_id,source_kind,source_id,line_id,month,project_key);
CREATE INDEX fbu_scope_project ON fbu_flow(tenant_id,hotel_id,month,category_code,department_id,basis_kind,project_key);
CREATE INDEX finance_claim_line_project ON finance_claim_line(tenant_id,claim_id,project_key);
CREATE INDEX fo_approved_contract_project ON fo_document(tenant_id,hotel_id,company_id,id) WHERE type='CONTRACT' AND status='APPROVED';
COMMENT ON COLUMN finance_category.budget_code IS 'Explicitly configured stable financial code; not guessed from category UUID or display name.';
