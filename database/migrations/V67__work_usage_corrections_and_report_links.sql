-- Corrections append new facts; original submissions, evidence and financial closes are never rewritten.
ALTER TABLE store_inventory_movement DROP CONSTRAINT store_inventory_movement_source_type_check;
ALTER TABLE store_inventory_movement ADD CONSTRAINT store_inventory_movement_source_type_check
 CHECK(source_type IN ('MANUAL','WORK_RECORD','HANDOVER','IMPORT','LAUNDRY','WORK_USAGE_REVISION'));
CREATE TABLE store_work_usage_correction (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    work_record_id UUID NOT NULL,
    revision_no INTEGER NOT NULL CHECK (revision_no > 0),
    no_usage BOOLEAN NOT NULL,
    reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason)) > 0),
    request_hash CHAR(64) NOT NULL,
    actor_account_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,work_record_id,revision_no),
    FOREIGN KEY (tenant_id,work_record_id) REFERENCES store_work_usage(tenant_id,work_record_id),
    FOREIGN KEY (tenant_id,actor_account_id) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE store_work_usage_correction_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    correction_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    work_record_id UUID NOT NULL,
    item_id UUID NOT NULL,
    item_name VARCHAR(160) NOT NULL,
    category VARCHAR(32) NOT NULL CHECK (category IN ('CONSUMABLE','CLEANING_SUPPLY','LINEN')),
    base_unit VARCHAR(32) NOT NULL,
    input_unit VARCHAR(32) NOT NULL,
    input_quantity NUMERIC(18,4) NOT NULL CHECK (input_quantity>0),
    conversion_factor NUMERIC(18,6) NOT NULL CHECK (conversion_factor>0),
    base_quantity NUMERIC(18,4) NOT NULL CHECK (base_quantity>0 AND base_quantity=input_quantity*conversion_factor),
    item_version BIGINT NOT NULL,
    UNIQUE (tenant_id,correction_id,item_id),
    FOREIGN KEY (tenant_id,correction_id) REFERENCES store_work_usage_correction(tenant_id,id),
    FOREIGN KEY (tenant_id,work_record_id) REFERENCES store_work_usage(tenant_id,work_record_id),
    FOREIGN KEY (tenant_id,hotel_org_unit_id,item_id) REFERENCES store_resource_item(tenant_id,hotel_org_unit_id,id)
);
CREATE TABLE store_work_usage_report_link (
    tenant_id UUID NOT NULL,
    work_record_id UUID NOT NULL,
    usage_record_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,work_record_id),
    CHECK (work_record_id<>usage_record_id),
    FOREIGN KEY (tenant_id,work_record_id) REFERENCES work_record(tenant_id,id),
    FOREIGN KEY (tenant_id,usage_record_id) REFERENCES store_work_usage(tenant_id,work_record_id)
);
CREATE INDEX ix_usage_correction_item ON store_work_usage_correction_line(tenant_id,item_id);
CREATE INDEX ix_usage_link_source ON store_work_usage_report_link(tenant_id,usage_record_id);
CREATE INDEX ix_cost_business_period ON store_cost_entry(tenant_id,hotel_org_unit_id,business_date,id);
CREATE INDEX ix_work_usage_employee_date ON work_record(tenant_id,employee_id,business_date,id);
CREATE INDEX ix_correction_movement ON store_inventory_movement(tenant_id,source_id,item_id)
    WHERE source_type='WORK_USAGE_REVISION';

DO $$ DECLARE name TEXT; BEGIN
    FOREACH name IN ARRAY ARRAY['store_work_usage_correction','store_work_usage_correction_line','store_work_usage_report_link'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',name);
        EXECUTE format('CREATE TRIGGER immutable_fact BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',name);
    END LOOP;
END $$;

-- Explicit tenant predicates are essential on PostgreSQL 14 owner-executed views.
CREATE VIEW store_work_usage_current_line WITH (security_barrier=true) AS
SELECT l.*, 'WORK_RECORD'::text AS posting_source_type,l.work_record_id AS posting_source_id
FROM store_work_usage_line l
WHERE l.tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
 AND NOT EXISTS (SELECT 1 FROM store_work_usage_correction c WHERE c.tenant_id=l.tenant_id AND c.work_record_id=l.work_record_id)
UNION ALL
SELECT l.id,l.tenant_id,l.hotel_org_unit_id,l.work_record_id,l.item_id,l.item_name,l.category,
       l.base_unit,l.input_unit,l.input_quantity,l.conversion_factor,l.base_quantity,l.item_version,
       'WORK_USAGE_REVISION'::text,c.id
FROM store_work_usage_correction_line l JOIN store_work_usage_correction c ON c.tenant_id=l.tenant_id AND c.id=l.correction_id
WHERE l.tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid
 AND NOT EXISTS (SELECT 1 FROM store_work_usage_correction newer WHERE newer.tenant_id=c.tenant_id
       AND newer.work_record_id=c.work_record_id AND newer.revision_no>c.revision_no);

CREATE FUNCTION protect_corrected_usage_base_unit() RETURNS trigger AS $$ BEGIN
 IF NEW.unit IS DISTINCT FROM OLD.unit AND EXISTS(SELECT 1 FROM store_work_usage_correction_line
      WHERE tenant_id=OLD.tenant_id AND item_id=OLD.id) THEN
    RAISE EXCEPTION 'base unit is locked by corrected usage history' USING ERRCODE='55000';
 END IF;
 RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_corrected_usage_base_unit BEFORE UPDATE ON store_resource_item
 FOR EACH ROW EXECUTE FUNCTION protect_corrected_usage_base_unit();

-- Preserve V66 handling, adding a distinct immutable source for each replacement revision.
ALTER FUNCTION capture_store_movement_cost(store_inventory_movement,BOOLEAN) RENAME TO capture_store_movement_cost_v66;
CREATE FUNCTION capture_store_movement_cost(m store_inventory_movement, legacy BOOLEAN DEFAULT false) RETURNS void AS $$
DECLARE l store_work_usage_correction_line; c store_work_usage_correction; prior store_cost_entry;
        day DATE; posting DATE; reversal BOOLEAN;
BEGIN
 IF m.source_type<>'WORK_USAGE_REVISION' THEN PERFORM capture_store_movement_cost_v66(m,legacy); RETURN; END IF;
 SELECT * INTO STRICT c FROM store_work_usage_correction WHERE tenant_id=m.tenant_id AND id=m.source_id;
 SELECT * INTO STRICT l FROM store_work_usage_correction_line WHERE tenant_id=m.tenant_id AND correction_id=c.id AND item_id=m.item_id;
 SELECT business_date INTO STRICT day FROM work_record WHERE tenant_id=m.tenant_id AND id=c.work_record_id;
 posting=day; reversal=m.movement_type='RETURN';
 IF reversal THEN
    SELECT e.* INTO STRICT prior FROM store_cost_entry e JOIN store_inventory_movement old
      ON old.tenant_id=e.tenant_id AND old.id=e.source_id AND e.source_kind='INVENTORY'
      WHERE old.tenant_id=m.tenant_id AND old.source_type=m.source_type AND old.source_id=m.source_id
        AND old.item_id=m.item_id AND old.movement_type='CONSUMPTION';
    SELECT (m.occurred_at AT TIME ZONE timezone)::date INTO STRICT posting FROM tenant WHERE id=m.tenant_id;
 END IF;
 INSERT INTO store_cost_entry(tenant_id,hotel_org_unit_id,item_id,item_name,item_category,base_unit,cost_kind,
    quantity,amount,business_date,posting_date,source_kind,source_key,source_id,source_parent_id,direction,
    reverses_entry_id,reason,actor_account_id,source_recorded_at)
 VALUES(m.tenant_id,m.hotel_org_unit_id,m.item_id,l.item_name,l.category,l.base_unit,l.category,
    -m.quantity_delta,-m.value_delta,day,posting,'INVENTORY',m.id::text,m.id,c.work_record_id,
    CASE WHEN reversal THEN -1 ELSE 1 END,prior.id,m.reason,m.actor_account_id,m.created_at)
 ON CONFLICT(tenant_id,source_kind,source_key,direction) DO NOTHING;
END $$ LANGUAGE plpgsql;
