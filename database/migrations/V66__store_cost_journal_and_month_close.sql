-- Cost facts are written atomically with stock/laundry facts, not recomputed from live item prices.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category)
VALUES ('store-resource.cost-close','store_resource','cost-close','核对并锁定授权门店的成本月结',true,'STORE_RESOURCE')
ON CONFLICT(code) DO NOTHING;

CREATE TABLE store_cost_entry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    item_id UUID NOT NULL,
    item_name VARCHAR(160) NOT NULL,
    item_category VARCHAR(32) NOT NULL,
    base_unit VARCHAR(32) NOT NULL,
    cost_kind VARCHAR(32) NOT NULL CHECK(cost_kind IN ('CONSUMABLE','CLEANING_SUPPLY','OPERATING_ITEM','LINEN_USAGE','LAUNDRY','LOSS','ADJUSTMENT')),
    quantity NUMERIC(18,4) NOT NULL,
    amount NUMERIC(24,4) NOT NULL,
    business_date DATE NOT NULL,
    posting_date DATE NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK(source_kind IN ('INVENTORY','LAUNDRY')),
    source_key VARCHAR(80) NOT NULL,
    source_id UUID NOT NULL,
    source_parent_id UUID,
    direction SMALLINT NOT NULL CHECK(direction IN (1,-1)),
    reverses_entry_id UUID,
    reason TEXT NOT NULL,
    legacy_classification BOOLEAN NOT NULL DEFAULT false,
    actor_account_id UUID NOT NULL,
    source_recorded_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,id),
    UNIQUE(tenant_id,source_kind,source_key,direction),
    FOREIGN KEY(tenant_id,hotel_org_unit_id,item_id) REFERENCES store_resource_item(tenant_id,hotel_org_unit_id,id),
    FOREIGN KEY(tenant_id,actor_account_id) REFERENCES user_account(tenant_id,id),
    FOREIGN KEY(tenant_id,reverses_entry_id) REFERENCES store_cost_entry(tenant_id,id),
    CHECK((direction=1 AND reverses_entry_id IS NULL) OR (direction=-1 AND reverses_entry_id IS NOT NULL))
);
CREATE INDEX ix_store_cost_period ON store_cost_entry(tenant_id,hotel_org_unit_id,posting_date,id);
CREATE INDEX ix_store_cost_source ON store_cost_entry(tenant_id,source_kind,source_id);
CREATE INDEX ix_store_laundry_movement_source ON store_inventory_movement(tenant_id,source_id,item_id)
    WHERE source_type='LAUNDRY';
CREATE INDEX ix_store_cost_reversal ON store_cost_entry(tenant_id,reverses_entry_id) WHERE reverses_entry_id IS NOT NULL;

CREATE TABLE store_cost_month_close (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    month_start DATE NOT NULL CHECK(extract(day FROM month_start)=1),
    preview_token CHAR(64) NOT NULL,
    snapshot JSONB NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
    note VARCHAR(1000) NOT NULL CHECK(length(trim(note))>0),
    closed_by UUID NOT NULL,
    closed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,hotel_org_unit_id,month_start),
    FOREIGN KEY(tenant_id,hotel_org_unit_id) REFERENCES org_unit(tenant_id,id),
    FOREIGN KEY(tenant_id,closed_by) REFERENCES user_account(tenant_id,id)
);
CREATE TRIGGER trg_store_cost_entry_immutable BEFORE UPDATE OR DELETE ON store_cost_entry
 FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
CREATE TRIGGER trg_store_cost_close_immutable BEFORE UPDATE OR DELETE ON store_cost_month_close
 FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();

-- Shared locks allow simultaneous postings. Closing takes the corresponding exclusive lock, never
-- item/order locks: no lock upgrade, no multi-item posting deadlocks, no half-committed month snapshots.
CREATE FUNCTION route_store_cost_period() RETURNS trigger AS $$
DECLARE today DATE;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(hashtextextended(NEW.tenant_id::text || ':' || NEW.hotel_org_unit_id::text,66));
    SELECT (clock_timestamp() AT TIME ZONE timezone)::date INTO STRICT today FROM tenant WHERE id=NEW.tenant_id;
    IF NEW.posting_date>today AND NOT NEW.legacy_classification THEN
        RAISE EXCEPTION '成本不能计入未来日期' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT 1 FROM store_cost_month_close WHERE tenant_id=NEW.tenant_id
       AND hotel_org_unit_id=NEW.hotel_org_unit_id AND month_start=date_trunc('month',NEW.posting_date)::date) THEN
        NEW.posting_date=today;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_store_cost_period BEFORE INSERT ON store_cost_entry
 FOR EACH ROW EXECUTE FUNCTION route_store_cost_period();

CREATE FUNCTION guard_store_cost_close() RETURNS trigger AS $$
DECLARE today DATE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::text || ':' || NEW.hotel_org_unit_id::text,66));
    SELECT (clock_timestamp() AT TIME ZONE timezone)::date INTO STRICT today FROM tenant WHERE id=NEW.tenant_id;
    IF NEW.month_start>=date_trunc('month',today)::date THEN
        RAISE EXCEPTION '只能关账已经结束的月份' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_store_cost_close_guard BEFORE INSERT ON store_cost_month_close
 FOR EACH ROW EXECUTE FUNCTION guard_store_cost_close();

CREATE FUNCTION capture_store_movement_cost(m store_inventory_movement, legacy BOOLEAN DEFAULT false) RETURNS void AS $$
DECLARE i store_resource_item; prior store_cost_entry; kind TEXT; business_day DATE; event_day DATE;
        item_label TEXT; category_label TEXT; zone TEXT; is_reversal BOOLEAN;
BEGIN
    is_reversal=m.movement_type='RETURN' AND m.source_type IN ('WORK_RECORD','LAUNDRY');
    IF m.movement_type NOT IN ('CONSUMPTION','DAMAGE','ADJUSTMENT') AND NOT is_reversal THEN RETURN; END IF;
    SELECT * INTO STRICT i FROM store_resource_item WHERE tenant_id=m.tenant_id AND id=m.item_id;
    SELECT timezone INTO STRICT zone FROM tenant WHERE id=m.tenant_id;
    business_day=(m.occurred_at AT TIME ZONE zone)::date;
    item_label=i.name; category_label=i.category;
    IF m.source_type='WORK_RECORD' THEN
        SELECT r.business_date,l.item_name,l.category INTO business_day,item_label,category_label
          FROM work_record r JOIN store_work_usage_line l ON l.tenant_id=r.tenant_id AND l.work_record_id=r.id AND l.item_id=m.item_id
          WHERE r.tenant_id=m.tenant_id AND r.id=m.source_id;
    ELSIF m.source_type='LAUNDRY' THEN
        SELECT received_on INTO business_day FROM store_laundry_receipt WHERE tenant_id=m.tenant_id AND id=m.source_id;
    END IF;
    business_day=coalesce(business_day,(m.occurred_at AT TIME ZONE zone)::date);
    item_label=coalesce(item_label,i.name); category_label=coalesce(category_label,i.category);
    event_day=business_day;
    kind=CASE WHEN m.movement_type='DAMAGE' THEN 'LOSS' WHEN m.movement_type='ADJUSTMENT' THEN 'ADJUSTMENT'
         WHEN category_label='LINEN' THEN 'LINEN_USAGE' ELSE category_label END;
    IF is_reversal THEN
        SELECT c.* INTO STRICT prior FROM store_cost_entry c JOIN store_inventory_movement old
          ON old.tenant_id=c.tenant_id AND old.id=c.source_id AND c.source_kind='INVENTORY'
          WHERE old.tenant_id=m.tenant_id AND old.hotel_org_unit_id=m.hotel_org_unit_id AND old.item_id=m.item_id
            AND old.source_type=m.source_type AND old.source_id=m.source_id AND old.movement_type IN ('CONSUMPTION','DAMAGE');
        kind=prior.cost_kind; item_label=prior.item_name; category_label=prior.item_category;
        business_day=prior.business_date; event_day=(m.occurred_at AT TIME ZONE zone)::date;
    END IF;
    INSERT INTO store_cost_entry(tenant_id,hotel_org_unit_id,item_id,item_name,item_category,base_unit,cost_kind,
        quantity,amount,business_date,posting_date,source_kind,source_key,source_id,source_parent_id,direction,
        reverses_entry_id,reason,legacy_classification,actor_account_id,source_recorded_at)
    VALUES(m.tenant_id,m.hotel_org_unit_id,m.item_id,item_label,category_label,coalesce(m.base_unit_snapshot,i.unit),kind,
        -m.quantity_delta,-m.value_delta,business_day,event_day,'INVENTORY',m.id::text,m.id,m.source_id,
        CASE WHEN is_reversal THEN -1 ELSE 1 END,prior.id,m.reason,legacy AND m.source_type='MANUAL',m.actor_account_id,m.created_at)
    ON CONFLICT(tenant_id,source_kind,source_key,direction) DO NOTHING;
END $$ LANGUAGE plpgsql;
CREATE FUNCTION on_store_movement_cost() RETURNS trigger AS $$
BEGIN PERFORM capture_store_movement_cost(NEW); RETURN NEW; END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_store_movement_cost AFTER INSERT ON store_inventory_movement
 FOR EACH ROW EXECUTE FUNCTION on_store_movement_cost();

CREATE FUNCTION capture_store_laundry_cost(x store_laundry_receipt_line, reversal BOOLEAN DEFAULT false) RETURNS void AS $$
DECLARE r store_laundry_receipt; l store_laundry_line; prior store_cost_entry; day DATE;
BEGIN
    IF x.accepted_quantity=0 THEN RETURN; END IF;
    SELECT * INTO STRICT r FROM store_laundry_receipt WHERE tenant_id=x.tenant_id AND id=x.receipt_id;
    SELECT * INTO STRICT l FROM store_laundry_line WHERE tenant_id=x.tenant_id AND id=x.order_line_id;
    day=r.received_on;
    IF reversal THEN
        SELECT * INTO STRICT prior FROM store_cost_entry WHERE tenant_id=x.tenant_id AND source_kind='LAUNDRY'
          AND source_key=x.receipt_id::text || ':' || x.order_line_id::text AND direction=1;
        SELECT (r.reversed_at AT TIME ZONE timezone)::date INTO STRICT day FROM tenant WHERE id=x.tenant_id;
    END IF;
    INSERT INTO store_cost_entry(tenant_id,hotel_org_unit_id,item_id,item_name,item_category,base_unit,cost_kind,
        quantity,amount,business_date,posting_date,source_kind,source_key,source_id,source_parent_id,direction,
        reverses_entry_id,reason,actor_account_id,source_recorded_at)
    VALUES(x.tenant_id,x.hotel_org_unit_id,l.item_id,l.item_name,'LINEN',l.base_unit,'LAUNDRY',
        x.accepted_quantity*(CASE WHEN reversal THEN -1 ELSE 1 END),x.fee*(CASE WHEN reversal THEN -1 ELSE 1 END),
        r.received_on,day,'LAUNDRY',x.receipt_id::text || ':' || x.order_line_id::text,r.id,r.order_id,
        CASE WHEN reversal THEN -1 ELSE 1 END,prior.id,CASE WHEN reversal THEN r.reversal_reason ELSE '合格回收：' || r.note END,
        CASE WHEN reversal THEN r.reversed_by ELSE r.created_by END,CASE WHEN reversal THEN r.reversed_at ELSE r.created_at END)
    ON CONFLICT(tenant_id,source_kind,source_key,direction) DO NOTHING;
END $$ LANGUAGE plpgsql;
CREATE FUNCTION on_store_laundry_cost() RETURNS trigger AS $$
BEGIN PERFORM capture_store_laundry_cost(NEW); RETURN NEW; END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_store_laundry_cost AFTER INSERT ON store_laundry_receipt_line
 FOR EACH ROW EXECUTE FUNCTION on_store_laundry_cost();
CREATE FUNCTION on_store_laundry_cost_reversal() RETURNS trigger AS $$
DECLARE line store_laundry_receipt_line;
BEGIN
    IF NEW.status='REVERSED' AND OLD.status='POSTED' THEN
        FOR line IN SELECT * FROM store_laundry_receipt_line WHERE tenant_id=NEW.tenant_id AND receipt_id=NEW.id LOOP
            PERFORM capture_store_laundry_cost(line,true);
        END LOOP;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_store_laundry_cost_reversal AFTER UPDATE ON store_laundry_receipt
 FOR EACH ROW EXECUTE FUNCTION on_store_laundry_cost_reversal();

DO $$ DECLARE tab TEXT;
BEGIN
    FOREACH tab IN ARRAY ARRAY['store_cost_entry','store_cost_month_close'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
    END LOOP;
END $$;

-- Backfill preserves ledger amounts and units; legacy manual names/categories can only use the
-- migration-time item configuration, explicitly labelled for review. No stock balance is changed.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$ DECLARE t RECORD; m store_inventory_movement; x store_laundry_receipt_line;
BEGIN
    FOR t IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id',t.id::text,true);
        FOR m IN SELECT * FROM store_inventory_movement WHERE tenant_id=t.id
          ORDER BY CASE WHEN movement_type='RETURN' THEN 1 ELSE 0 END,created_at,id LOOP
            PERFORM capture_store_movement_cost(m,true);
        END LOOP;
        FOR x IN SELECT * FROM store_laundry_receipt_line WHERE tenant_id=t.id LOOP
            PERFORM capture_store_laundry_cost(x);
            IF EXISTS(SELECT 1 FROM store_laundry_receipt WHERE tenant_id=t.id AND id=x.receipt_id AND status='REVERSED') THEN
                PERFORM capture_store_laundry_cost(x,true);
            END IF;
        END LOOP;
    END LOOP;
END $$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
