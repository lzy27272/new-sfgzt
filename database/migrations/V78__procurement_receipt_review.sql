-- Procurement uses the existing stock ledger. Receiving alone never posts stock.
-- V78 follows the published finance, daily usage and inventory scope migrations V74-V77.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category) VALUES
 ('procurement.read','procurement','read','查看授权门店采购；业务办理仍校验门店配置的责任人',true,'STORE_RESOURCE'),
 ('procurement.request','procurement','request','发起本人门店采购需求并查看本人申请',true,'STORE_RESOURCE'),
 ('procurement.configure','procurement','configure','配置采购责任人、预警接收人及补货规则',false,'STORE_RESOURCE')
ON CONFLICT(code) DO NOTHING;

-- Only the administrative control plane is granted by default. Published business profiles are not rewritten.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$ DECLARE tenant_row record; BEGIN
 FOR tenant_row IN SELECT id FROM tenant LOOP
  PERFORM set_config('app.tenant_id',tenant_row.id::text,true);
  INSERT INTO role_permission(tenant_id,role_id,permission_id)
   SELECT tenant_row.id,r.id,p.id FROM app_role r CROSS JOIN permission p
   WHERE r.tenant_id=tenant_row.id AND r.code='PLATFORM_ADMIN' AND p.code IN ('procurement.read','procurement.request','procurement.configure')
   ON CONFLICT DO NOTHING;
 END LOOP;
END $$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

CREATE TABLE procurement_config (
 tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, enabled BOOLEAN NOT NULL DEFAULT false,
 manager_id UUID NOT NULL, buyer_id UUID NOT NULL, reviewer_id UUID NOT NULL,
 finance_id UUID NOT NULL, finance_director_id UUID NOT NULL, deputy_id UUID NOT NULL, general_id UUID NOT NULL,
 receiver_ids UUID[] NOT NULL DEFAULT '{}', version BIGINT NOT NULL DEFAULT 0,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,hotel_id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,manager_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,buyer_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewer_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,finance_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,finance_director_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,deputy_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,general_id) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE procurement_replenishment (
 tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, item_id UUID NOT NULL,
 target_quantity NUMERIC(18,4) NOT NULL CHECK(target_quantity>0), enabled BOOLEAN NOT NULL DEFAULT true,
 version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(tenant_id,hotel_id,item_id),
 FOREIGN KEY(tenant_id,hotel_id,item_id) REFERENCES store_resource_item(tenant_id,hotel_org_unit_id,id)
);
CREATE TABLE procurement_order (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, hotel_id UUID NOT NULL,
 source VARCHAR(16) NOT NULL CHECK(source IN ('LOW_STOCK','REQUEST')),
 status VARCHAR(16) NOT NULL CHECK(status IN ('DRAFT','RETURNED','SUBMITTED','ORDERED','PARTIAL','COMPLETED')),
 applicant_id UUID NOT NULL, title VARCHAR(160) NOT NULL, note VARCHAR(2000) NOT NULL DEFAULT '',
 supplier VARCHAR(200) NOT NULL DEFAULT '', expected_on DATE, buyer_id UUID,
 version BIGINT NOT NULL DEFAULT 0, edited BOOLEAN NOT NULL DEFAULT false, first_difference_at TIMESTAMPTZ, alerted_at TIMESTAMPTZ,
 ordered_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,hotel_id,id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,applicant_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,buyer_id) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_procurement_order_scope ON procurement_order(tenant_id,hotel_id,status,updated_at DESC,id);
CREATE INDEX ix_procurement_order_owner ON procurement_order(tenant_id,applicant_id,updated_at DESC,id);
CREATE INDEX ix_procurement_order_alert ON procurement_order(tenant_id,first_difference_at,id) WHERE status<>'COMPLETED' AND first_difference_at IS NOT NULL;
CREATE UNIQUE INDEX uq_procurement_low_stock_draft ON procurement_order(tenant_id,hotel_id) WHERE source='LOW_STOCK' AND status='DRAFT';

CREATE TABLE procurement_line (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, hotel_id UUID NOT NULL, order_id UUID NOT NULL, item_id UUID NOT NULL,
 item_name VARCHAR(160) NOT NULL, sku VARCHAR(80) NOT NULL, specification VARCHAR(240) NOT NULL DEFAULT '',
 base_unit VARCHAR(32) NOT NULL, input_unit VARCHAR(32) NOT NULL, factor NUMERIC(18,6) NOT NULL CHECK(factor>0),
 input_quantity NUMERIC(18,4) NOT NULL CHECK(input_quantity>0), quantity NUMERIC(18,4) NOT NULL CHECK(quantity>0),
 unit_price NUMERIC(18,4) CHECK(unit_price>=0), approved_quantity NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK(approved_quantity>=0 AND approved_quantity<=quantity),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,order_id,id), UNIQUE(tenant_id,order_id,item_id),
 FOREIGN KEY(tenant_id,hotel_id,order_id) REFERENCES procurement_order(tenant_id,hotel_id,id),
 FOREIGN KEY(tenant_id,hotel_id,item_id) REFERENCES store_resource_item(tenant_id,hotel_org_unit_id,id)
);
CREATE INDEX ix_procurement_line_item ON procurement_line(tenant_id,hotel_id,item_id,order_id);
CREATE TABLE procurement_receipt (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, order_id UUID NOT NULL, receiver_id UUID NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('DRAFT','SUBMITTED','RETURNED','APPROVED','VOIDED')),
 note VARCHAR(2000) NOT NULL DEFAULT '', review_note VARCHAR(2000) NOT NULL DEFAULT '',
 reviewer_id UUID, version BIGINT NOT NULL DEFAULT 0, submitted_at TIMESTAMPTZ, reviewed_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,order_id,id),
 FOREIGN KEY(tenant_id,order_id) REFERENCES procurement_order(tenant_id,id),
 FOREIGN KEY(tenant_id,receiver_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewer_id) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_procurement_receipt_order ON procurement_receipt(tenant_id,order_id,status,created_at);
CREATE TABLE procurement_receipt_line (
 tenant_id UUID NOT NULL, order_id UUID NOT NULL, receipt_id UUID NOT NULL, line_id UUID NOT NULL,
 arrived NUMERIC(18,4) NOT NULL CHECK(arrived>0), accepted NUMERIC(18,4) NOT NULL CHECK(accepted>=0),
 rejected NUMERIC(18,4) NOT NULL CHECK(rejected>=0), movement_id UUID,
 PRIMARY KEY(tenant_id,receipt_id,line_id), CHECK(arrived=accepted+rejected),
 FOREIGN KEY(tenant_id,order_id,receipt_id) REFERENCES procurement_receipt(tenant_id,order_id,id),
 FOREIGN KEY(tenant_id,order_id,line_id) REFERENCES procurement_line(tenant_id,order_id,id),
 FOREIGN KEY(tenant_id,movement_id) REFERENCES store_inventory_movement(tenant_id,id)
);
CREATE INDEX ix_procurement_receipt_line_order ON procurement_receipt_line(tenant_id,order_id,line_id);
CREATE TABLE procurement_attachment (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, order_id UUID NOT NULL, receipt_id UUID,
 original_name VARCHAR(180) NOT NULL, media_type VARCHAR(120) NOT NULL,
 size_bytes BIGINT NOT NULL CHECK(size_bytes>0 AND size_bytes<=20971520), sha256 CHAR(64) NOT NULL,
 object_key VARCHAR(250) NOT NULL, scan_status VARCHAR(24) NOT NULL, uploaded_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), purged_at TIMESTAMPTZ,
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,order_id) REFERENCES procurement_order(tenant_id,id),
 FOREIGN KEY(tenant_id,order_id,receipt_id) REFERENCES procurement_receipt(tenant_id,order_id,id),
 FOREIGN KEY(tenant_id,uploaded_by) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_procurement_attachment_order ON procurement_attachment(tenant_id,order_id,receipt_id);
CREATE TABLE procurement_event (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL, order_id UUID,
 actor_id UUID, event_type VARCHAR(40) NOT NULL, detail JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), FOREIGN KEY(tenant_id,order_id) REFERENCES procurement_order(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_procurement_event_order ON procurement_event(tenant_id,order_id,created_at,id);
CREATE TABLE procurement_command (
 tenant_id UUID NOT NULL, actor_id UUID NOT NULL, request_id UUID NOT NULL, request_hash CHAR(64) NOT NULL,
 result_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,actor_id,request_id)
);
ALTER TABLE store_inventory_movement DROP CONSTRAINT store_inventory_movement_source_type_check;
ALTER TABLE store_inventory_movement ADD CONSTRAINT store_inventory_movement_source_type_check
 CHECK(source_type IN ('MANUAL','WORK_RECORD','HANDOVER','IMPORT','LAUNDRY','WORK_USAGE_REVISION','PROCUREMENT'));

DO $$ DECLARE tab TEXT; BEGIN
 FOREACH tab IN ARRAY ARRAY['procurement_config','procurement_replenishment','procurement_order','procurement_line',
  'procurement_receipt','procurement_receipt_line','procurement_attachment','procurement_event','procurement_command'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
   EXECUTE format('REVOKE ALL ON %I FROM PUBLIC',tab);
   EXECUTE format('REVOKE ALL ON %I FROM hotel_ai_os_app',tab);
   EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO hotel_ai_os_app',tab);
  END IF;
 END LOOP;
END $$;
CREATE TRIGGER procurement_events_immutable BEFORE UPDATE OR DELETE ON procurement_event
 FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
CREATE TRIGGER procurement_commands_immutable BEFORE UPDATE OR DELETE ON procurement_command
 FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
