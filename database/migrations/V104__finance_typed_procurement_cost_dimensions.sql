-- Explicit finance dimensions, separate from physical resource categories and menu/role permissions.
ALTER TABLE procurement_line ADD COLUMN financial_category_code varchar(80);
ALTER TABLE procurement_line ADD COLUMN budget_unit_price numeric(18,4) CHECK(budget_unit_price>=0);
ALTER TABLE procurement_line ADD COLUMN financial_department_id uuid;
ALTER TABLE procurement_line ADD FOREIGN KEY(tenant_id,financial_department_id) REFERENCES org_unit(tenant_id,id);
ALTER TABLE store_resource_item ADD COLUMN financial_category_code varchar(80);
-- A recommendation only. It is NOT evidence of the actual consuming department.
ALTER TABLE store_resource_item ADD COLUMN financial_department_id uuid;
ALTER TABLE store_resource_item ADD FOREIGN KEY(tenant_id,financial_department_id) REFERENCES org_unit(tenant_id,id);
ALTER TABLE store_inventory_movement ADD COLUMN financial_category_code varchar(80);
ALTER TABLE store_inventory_movement ADD COLUMN financial_department_id uuid;
ALTER TABLE store_inventory_movement ADD COLUMN financial_department_source varchar(40);
ALTER TABLE store_inventory_movement ADD FOREIGN KEY(tenant_id,financial_department_id) REFERENCES org_unit(tenant_id,id);
CREATE TABLE fs_movement_finance_classification (
 movement_id uuid NOT NULL,tenant_id uuid NOT NULL,hotel_id uuid NOT NULL,financial_category_code varchar(80) NOT NULL,
 financial_department_id uuid NOT NULL,actor_id uuid NOT NULL,note varchar(2000) NOT NULL,source_snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,movement_id),
 FOREIGN KEY(tenant_id,movement_id) REFERENCES store_inventory_movement(tenant_id,id),
 FOREIGN KEY(tenant_id,financial_department_id) REFERENCES org_unit(tenant_id,id),FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id)
);
-- Historical orders are not rewritten after approval. An independent finance classification is an appended, sourced fact.
CREATE TABLE fs_procurement_finance_classification (
 line_id uuid NOT NULL,tenant_id uuid NOT NULL,hotel_id uuid NOT NULL,order_id uuid NOT NULL,
 financial_category_code varchar(80) NOT NULL,financial_department_id uuid NOT NULL,actor_id uuid NOT NULL,
 note varchar(2000) NOT NULL,source_snapshot jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,line_id),FOREIGN KEY(tenant_id,order_id,line_id) REFERENCES procurement_line(tenant_id,order_id,id),
 FOREIGN KEY(tenant_id,financial_department_id) REFERENCES org_unit(tenant_id,id),FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id)
);
ALTER TABLE fs_procurement_finance_classification ENABLE ROW LEVEL SECURITY;
ALTER TABLE fs_procurement_finance_classification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fs_procurement_finance_classification USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER procurement_finance_classification_immutable BEFORE UPDATE OR DELETE ON fs_procurement_finance_classification FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
CREATE TABLE fs_price_cost_allocation (
 source_event_id uuid NOT NULL,tenant_id uuid NOT NULL,hotel_id uuid NOT NULL,actor_id uuid NOT NULL,
 allocations jsonb NOT NULL CHECK(jsonb_typeof(allocations)='array' AND jsonb_array_length(allocations)>0),
 note varchar(2000) NOT NULL,source_snapshot jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,source_event_id),FOREIGN KEY(tenant_id,source_event_id) REFERENCES fa_source_event(tenant_id,id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id)
);
ALTER TABLE fs_price_cost_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE fs_price_cost_allocation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fs_price_cost_allocation USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER price_cost_allocation_immutable BEFORE UPDATE OR DELETE ON fs_price_cost_allocation FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
ALTER TABLE fs_movement_finance_classification ENABLE ROW LEVEL SECURITY;
ALTER TABLE fs_movement_finance_classification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fs_movement_finance_classification USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER finance_classification_immutable BEFORE UPDATE OR DELETE ON fs_movement_finance_classification FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
CREATE FUNCTION fs_freeze_movement_finance_dimensions() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE assignment uuid;actual_department uuid;
 BEGIN
  IF NEW.financial_category_code IS NULL THEN SELECT financial_category_code INTO NEW.financial_category_code FROM store_resource_item WHERE tenant_id=NEW.tenant_id AND id=NEW.item_id;END IF;
  IF NEW.source_type='WORK_RECORD' THEN SELECT position_assignment_id INTO assignment FROM work_record WHERE tenant_id=NEW.tenant_id AND id=NEW.source_id;
  ELSIF NEW.source_type='WORK_USAGE_REVISION' THEN SELECT r.position_assignment_id INTO assignment FROM store_work_usage_correction c JOIN work_record r ON r.tenant_id=c.tenant_id AND r.id=c.work_record_id WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.source_id;END IF;
  IF assignment IS NOT NULL THEN
   SELECT d.id INTO actual_department FROM employee_position_assignment a JOIN org_unit_closure path ON path.tenant_id=a.tenant_id AND path.descendant_id=a.org_unit_id JOIN org_unit d ON d.tenant_id=path.tenant_id AND d.id=path.ancestor_id WHERE a.tenant_id=NEW.tenant_id AND a.id=assignment AND d.unit_type='DEPARTMENT' AND EXISTS(SELECT 1 FROM org_unit_closure c WHERE c.tenant_id=d.tenant_id AND c.ancestor_id=NEW.hotel_org_unit_id AND c.descendant_id=d.id) ORDER BY path.depth LIMIT 1;
   IF actual_department IS NOT NULL THEN NEW.financial_department_id=actual_department;NEW.financial_department_source='WORK_SOURCE_ASSIGNMENT';END IF;
  END IF;
  -- Do not replace missing actual usage information with item.financial_department_id.
  RETURN NEW;
 END $$;
CREATE TRIGGER finance_dimensions_at_original_movement BEFORE INSERT ON store_inventory_movement FOR EACH ROW EXECUTE FUNCTION fs_freeze_movement_finance_dimensions();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN GRANT SELECT,INSERT ON fs_movement_finance_classification,fs_procurement_finance_classification,fs_price_cost_allocation TO hotel_ai_os_app;REVOKE UPDATE,DELETE ON fs_movement_finance_classification,fs_procurement_finance_classification,fs_price_cost_allocation FROM hotel_ai_os_app;END IF;END $$;
