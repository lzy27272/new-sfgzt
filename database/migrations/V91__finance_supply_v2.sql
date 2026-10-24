-- Supplement existing procurement/stock facts; never create a second physical inventory.
ALTER TABLE procurement_line ALTER COLUMN item_id DROP NOT NULL;
ALTER TABLE procurement_line ADD COLUMN line_kind varchar(16) NOT NULL DEFAULT 'INVENTORY' CHECK(line_kind IN ('INVENTORY','SERVICE','ASSET','PROJECT'));
ALTER TABLE procurement_line ADD CONSTRAINT procurement_line_nature CHECK((line_kind='INVENTORY' AND item_id IS NOT NULL) OR (line_kind<>'INVENTORY' AND item_id IS NULL));
ALTER TABLE procurement_receipt_line ADD COLUMN accepted_value numeric(20,4) CHECK(accepted_value>=0);
UPDATE procurement_receipt_line r SET accepted_value=m.value_delta FROM store_inventory_movement m WHERE m.tenant_id=r.tenant_id AND m.id=r.movement_id;
ALTER TABLE store_inventory_movement DROP CONSTRAINT store_inventory_movement_source_type_check;
ALTER TABLE store_inventory_movement ADD CONSTRAINT store_inventory_movement_source_type_check
 CHECK(source_type IN ('MANUAL','WORK_RECORD','HANDOVER','IMPORT','LAUNDRY','WORK_USAGE_REVISION','PROCUREMENT','PROCUREMENT_RETURN'));
-- Supplier returns reduce stock and settlement, not operating consumption a second time.
CREATE OR REPLACE FUNCTION on_store_movement_cost() RETURNS trigger AS $$
BEGIN
 IF NEW.source_type='PROCUREMENT_RETURN' THEN RETURN NEW; END IF;
 PERFORM capture_store_movement_cost(NEW);RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TABLE fs_recognition (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,hotel_id uuid NOT NULL,company_id uuid NOT NULL,receipt_id uuid NOT NULL,
 applicant_id uuid NOT NULL,confirmer_id uuid NOT NULL,cashier_id uuid NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','ESTIMATED','CONFIRMED','REPLACED')),
 estimated_amount numeric(18,2) NOT NULL CHECK(estimated_amount>=0),invoice_amount numeric(18,2),
 invoice_ids jsonb NOT NULL,lines jsonb NOT NULL,differences jsonb NOT NULL,note varchar(2000) NOT NULL,
 accounting_date date NOT NULL,version integer NOT NULL DEFAULT 0,confirmed_by uuid,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,receipt_id),
 FOREIGN KEY(tenant_id,receipt_id) REFERENCES procurement_receipt(tenant_id,id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id)
);
CREATE TABLE fs_invoice_use (
 tenant_id uuid NOT NULL,invoice_id uuid NOT NULL,recognition_id uuid NOT NULL,invoice_key varchar(70) NOT NULL,sha256 char(64) NOT NULL,invoice_snapshot jsonb NOT NULL,
 UNIQUE(tenant_id,invoice_key),UNIQUE(tenant_id,sha256),
 PRIMARY KEY(tenant_id,invoice_id),FOREIGN KEY(tenant_id,invoice_id) REFERENCES finance_invoice_intake(tenant_id,id),
 FOREIGN KEY(tenant_id,recognition_id) REFERENCES fs_recognition(tenant_id,id)
);
CREATE TABLE fs_return (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,recognition_id uuid NOT NULL,hotel_id uuid NOT NULL,
 line_id uuid NOT NULL,quantity numeric(18,4) NOT NULL CHECK(quantity>0),kind varchar(12) NOT NULL CHECK(kind IN ('RETURN','EXCHANGE')),
 applicant_id uuid NOT NULL,reviewer_id uuid NOT NULL,status varchar(16) NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 supplier_credit numeric(18,2) NOT NULL DEFAULT 0 CHECK(supplier_credit>=0),inventory_value numeric(20,4),movement_id uuid,
 note varchar(2000) NOT NULL,accounting_date date NOT NULL,version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,recognition_id) REFERENCES fs_recognition(tenant_id,id),
 FOREIGN KEY(tenant_id,line_id) REFERENCES procurement_line(tenant_id,id),FOREIGN KEY(tenant_id,movement_id) REFERENCES store_inventory_movement(tenant_id,id)
);
CREATE INDEX fs_return_recognition ON fs_return(tenant_id,recognition_id,line_id,status);
CREATE TABLE fs_settlement (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,return_id uuid NOT NULL,kind varchar(12) NOT NULL CHECK(kind IN ('REFUND','CREDIT','REPLACEMENT')),
 amount numeric(18,2) NOT NULL CHECK(amount>=0),bank_transaction_id uuid,target_recognition_id uuid,replacement_receipt_id uuid,
 actor_id uuid NOT NULL,note varchar(2000) NOT NULL,business_date date NOT NULL DEFAULT CURRENT_DATE,accounting_date date NOT NULL DEFAULT CURRENT_DATE,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,return_id) REFERENCES fs_return(tenant_id,id),
 FOREIGN KEY(tenant_id,bank_transaction_id) REFERENCES fo_bank_transaction(tenant_id,id),
 FOREIGN KEY(tenant_id,target_recognition_id) REFERENCES fs_recognition(tenant_id,id),
 FOREIGN KEY(tenant_id,replacement_receipt_id) REFERENCES procurement_receipt(tenant_id,id)
);
CREATE UNIQUE INDEX fs_return_replacement ON fs_settlement(tenant_id,return_id) WHERE kind='REPLACEMENT';
CREATE UNIQUE INDEX fs_replacement_receipt ON fs_settlement(tenant_id,replacement_receipt_id) WHERE replacement_receipt_id IS NOT NULL;
CREATE TABLE fs_inventory_event (
 tenant_id uuid NOT NULL,movement_id uuid NOT NULL,source_event_id uuid,book_amount numeric(18,2) NOT NULL,
 source_amount numeric(20,4) NOT NULL,company_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,movement_id),FOREIGN KEY(tenant_id,movement_id) REFERENCES store_inventory_movement(tenant_id,id),
 FOREIGN KEY(tenant_id,source_event_id) REFERENCES fa_source_event(tenant_id,id)
);
CREATE TABLE fs_stock_valuation (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,recognition_id uuid NOT NULL,item_id uuid NOT NULL,hotel_id uuid NOT NULL,
 amount numeric(18,2) NOT NULL,cost_adjustment numeric(18,2) NOT NULL,before_value numeric(20,4) NOT NULL,after_value numeric(20,4) NOT NULL,
 source_line_id uuid NOT NULL,accounting_date date NOT NULL,actor_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,recognition_id,source_line_id),
 FOREIGN KEY(tenant_id,recognition_id) REFERENCES fs_recognition(tenant_id,id),FOREIGN KEY(tenant_id,source_line_id) REFERENCES procurement_line(tenant_id,id),
 FOREIGN KEY(tenant_id,hotel_id,item_id) REFERENCES store_inventory_balance(tenant_id,hotel_org_unit_id,item_id),CHECK(after_value>=0)
);
CREATE TABLE fs_opening_stock (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,subledger_id uuid NOT NULL,book_id uuid NOT NULL,company_id uuid NOT NULL,
 hotel_id uuid NOT NULL,item_id uuid NOT NULL,quantity numeric(20,4) NOT NULL,before_value numeric(20,4) NOT NULL,opening_value numeric(18,2) NOT NULL,
 actor_id uuid NOT NULL,note varchar(2000) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,subledger_id),UNIQUE(tenant_id,book_id,item_id),
 FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),FOREIGN KEY(tenant_id,hotel_id,item_id) REFERENCES store_inventory_balance(tenant_id,hotel_org_unit_id,item_id)
);
CREATE TABLE fs_asset_card (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,hotel_id uuid NOT NULL,company_id uuid NOT NULL,physical_asset_id uuid NOT NULL,
 source_document_id uuid,opening_subledger_id uuid,procurement_recognition_id uuid,procurement_line_id uuid,opening_depreciation numeric(18,2) NOT NULL DEFAULT 0 CHECK(opening_depreciation>=0),depreciation_start date,
 book_id uuid NOT NULL,asset_code varchar(80) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('CIP','ACTIVE','DISPOSED','LOST')),
 original_value numeric(18,2) NOT NULL CHECK(original_value>0),residual_value numeric(18,2) NOT NULL CHECK(residual_value>=0),
 useful_months integer NOT NULL CHECK(useful_months BETWEEN 1 AND 1200),remaining_useful_months integer CHECK(remaining_useful_months>=0),in_service_date date,
 asset_account varchar(32) NOT NULL,cip_account varchar(32) NOT NULL,depreciation_account varchar(32) NOT NULL,expense_account varchar(32) NOT NULL,
 created_by uuid NOT NULL,version integer NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,source_document_id),UNIQUE(tenant_id,opening_subledger_id),UNIQUE(tenant_id,physical_asset_id),
 FOREIGN KEY(tenant_id,source_document_id) REFERENCES fo_document(tenant_id,id),
 FOREIGN KEY(tenant_id,procurement_recognition_id) REFERENCES fs_recognition(tenant_id,id),FOREIGN KEY(tenant_id,procurement_line_id) REFERENCES procurement_line(tenant_id,id),
 FOREIGN KEY(tenant_id,physical_asset_id) REFERENCES store_fixed_asset(tenant_id,id),FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),
 CHECK(residual_value<=original_value),CHECK(num_nonnulls(source_document_id,opening_subledger_id,procurement_recognition_id)=1),CHECK((procurement_recognition_id IS NULL)=(procurement_line_id IS NULL)),CHECK(opening_depreciation<=original_value-residual_value)
);
CREATE INDEX fs_asset_procurement_line ON fs_asset_card(tenant_id,procurement_recognition_id,procurement_line_id);
CREATE TABLE fs_asset_event (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,asset_id uuid NOT NULL,action varchar(24) NOT NULL,
 occurred_on date NOT NULL,amount numeric(18,2) NOT NULL DEFAULT 0,voucher_id uuid,snapshot jsonb NOT NULL,actor_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,asset_id) REFERENCES fs_asset_card(tenant_id,id),
 FOREIGN KEY(tenant_id,voucher_id) REFERENCES fa_voucher(tenant_id,id)
);
CREATE UNIQUE INDEX fs_asset_depreciation_month ON fs_asset_event(tenant_id,asset_id,date_trunc('month',occurred_on::timestamp)) WHERE action='DEPRECIATE';
CREATE TABLE fs_command (
 tenant_id uuid NOT NULL,id uuid NOT NULL,operation varchar(80) NOT NULL,request_hash char(64) NOT NULL,result_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,id)
);
-- Historical handover lines keep their original hotel snapshot; asset identity survives a later transfer.
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='store_resource_handover_snapshot_line'::regclass AND confrelid='store_fixed_asset'::regclass LOOP
  EXECUTE format('ALTER TABLE store_resource_handover_snapshot_line DROP CONSTRAINT %I',c.conname);
 END LOOP;
END $$;
ALTER TABLE store_resource_handover_snapshot_line ADD CONSTRAINT asset_snapshot_identity_fk FOREIGN KEY(tenant_id,asset_id) REFERENCES store_fixed_asset(tenant_id,id);
CREATE TABLE fs_physical_transfer (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,physical_asset_id uuid NOT NULL,source_hotel_id uuid NOT NULL,target_hotel_id uuid NOT NULL,
 requester_id uuid NOT NULL,receiver_id uuid NOT NULL,expected_version bigint NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('REQUESTED','CONFIRMED','CANCELLED')),snapshot jsonb NOT NULL,note varchar(2000) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),confirmed_at timestamptz,UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,physical_asset_id) REFERENCES store_fixed_asset(tenant_id,id),CHECK(source_hotel_id<>target_hotel_id)
);
CREATE UNIQUE INDEX fs_physical_transfer_pending ON fs_physical_transfer(tenant_id,physical_asset_id) WHERE status='REQUESTED';
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['fs_recognition','fs_invoice_use','fs_return','fs_settlement','fs_inventory_event','fs_stock_valuation','fs_opening_stock','fs_asset_card','fs_asset_event','fs_command','fs_physical_transfer'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO hotel_ai_os_app',tab);EXECUTE format('REVOKE DELETE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['fs_invoice_use','fs_settlement','fs_stock_valuation','fs_opening_stock','fs_asset_event','fs_command'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_finance_supply BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);
 END LOOP;
END $$;
CREATE FUNCTION fs_link_inventory_event_once() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.source_event_id IS NOT NULL OR NEW.source_event_id IS NULL OR (to_jsonb(NEW)-'source_event_id') IS DISTINCT FROM (to_jsonb(OLD)-'source_event_id') THEN RAISE EXCEPTION 'Inventory capture facts are immutable; only the initial accounting link may be filled'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fs_inventory_capture_guard BEFORE UPDATE OR DELETE ON fs_inventory_event FOR EACH ROW EXECUTE FUNCTION fs_link_inventory_event_once();
