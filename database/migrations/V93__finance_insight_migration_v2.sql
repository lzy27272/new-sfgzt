-- Versioned financial evidence, model analysis and controlled legacy migration.
CREATE TABLE fi_metric (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,hotel_id uuid NOT NULL,month date NOT NULL,
 kind varchar(24) NOT NULL CHECK(kind IN ('HEADCOUNT','ENERGY_KWH','WATER_TON','ROOM_NIGHTS','CHANNEL_REVENUE')),
 dimension varchar(120) NOT NULL DEFAULT '',value numeric(20,4) NOT NULL CHECK(value>=0),source_reference varchar(500) NOT NULL,
 confirmed_by uuid NOT NULL,version integer NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,hotel_id,month,kind,dimension),FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),CHECK(extract(day from month)=1)
);
CREATE TABLE fi_snapshot (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,month date NOT NULL,hotel_ids uuid[] NOT NULL,report_version char(64) NOT NULL,
 facts jsonb NOT NULL,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,report_version)
);
CREATE TABLE fi_report (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,snapshot_id uuid NOT NULL,title varchar(200) NOT NULL,status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','REVIEWED','PUBLISHED')),
 version integer NOT NULL DEFAULT 0,created_by uuid NOT NULL,last_edited_by uuid NOT NULL,reviewed_by uuid,published_by uuid,
 model varchar(160) NOT NULL,model_request_hash char(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz,
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,snapshot_id) REFERENCES fi_snapshot(tenant_id,id)
);
CREATE TABLE fi_question (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,snapshot_id uuid NOT NULL,kind varchar(24) NOT NULL,question varchar(2000) NOT NULL,request_hash char(64) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','COMPLETE','FAILED')),model varchar(160),content jsonb,
 asked_by uuid NOT NULL,asked_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,snapshot_id) REFERENCES fi_snapshot(tenant_id,id),FOREIGN KEY(tenant_id,asked_by) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX fi_question_scope ON fi_question(tenant_id,snapshot_id,asked_at DESC);
CREATE FUNCTION fi_protect_question() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'PENDING' THEN RAISE EXCEPTION 'Financial question and answer history is immutable';END IF;
 IF (to_jsonb(NEW)-'status'-'model'-'content'-'completed_at') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'model'-'content'-'completed_at') THEN RAISE EXCEPTION 'Financial question source cannot be changed';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fi_question_history_immutable BEFORE UPDATE OR DELETE ON fi_question FOR EACH ROW EXECUTE FUNCTION fi_protect_question();
CREATE TABLE fi_report_revision (
 tenant_id uuid NOT NULL,report_id uuid NOT NULL,version integer NOT NULL,content jsonb NOT NULL,edited_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,report_id,version),FOREIGN KEY(tenant_id,report_id) REFERENCES fi_report(tenant_id,id)
);
CREATE TABLE fi_recommendation (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,report_id uuid NOT NULL,report_version integer NOT NULL,recommendation_index integer NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('ADOPTED','IN_PROGRESS','DONE','DECLINED')),owner_id uuid NOT NULL,due_date date,note varchar(2000) NOT NULL,
 version integer NOT NULL DEFAULT 0,updated_by uuid NOT NULL,updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,report_id,report_version,recommendation_index),
 FOREIGN KEY(tenant_id,report_id,report_version) REFERENCES fi_report_revision(tenant_id,report_id,version),FOREIGN KEY(tenant_id,owner_id) REFERENCES user_account(tenant_id,id)
);
CREATE TABLE fm_batch (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,kind varchar(16) NOT NULL CHECK(kind IN ('OPENING','OPEN_ITEM','ASSET','INVENTORY','PREPAID')),
 original_name varchar(240) NOT NULL,source_reference varchar(500) NOT NULL,file_sha256 char(64) NOT NULL,mapping jsonb NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PREVIEW' CHECK(status IN ('PREVIEW','STAGED','ACTIVE','CANCELLED')),version integer NOT NULL DEFAULT 0,
 created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),committed_at timestamptz,activated_at timestamptz,
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id)
);
CREATE INDEX fm_batch_book ON fm_batch(tenant_id,book_id,created_at DESC);
CREATE TABLE fm_sourcefile (
 tenant_id uuid NOT NULL,batch_id uuid NOT NULL,file_bytes bytea NOT NULL CHECK(octet_length(file_bytes) BETWEEN 1 AND 20971520),
 PRIMARY KEY(tenant_id,batch_id),FOREIGN KEY(tenant_id,batch_id) REFERENCES fm_batch(tenant_id,id)
);
CREATE TABLE fm_row (
 tenant_id uuid NOT NULL,batch_id uuid NOT NULL,row_no integer NOT NULL,source_key varchar(160) NOT NULL,values jsonb NOT NULL,errors jsonb NOT NULL,
 PRIMARY KEY(tenant_id,batch_id,row_no),FOREIGN KEY(tenant_id,batch_id) REFERENCES fm_batch(tenant_id,id)
);
-- Explicit typed opening subledgers, not opaque JSON counted as a migrated asset/inventory.
CREATE TABLE fm_subledger (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,batch_id uuid NOT NULL,kind varchar(16) NOT NULL,
 source_key varchar(160) NOT NULL,account_code varchar(32) NOT NULL,accumulated_account_code varchar(32),physical_asset_id uuid,inventory_item_id uuid,in_service_date date,original_useful_months integer,name varchar(240) NOT NULL,
 direction varchar(6) NOT NULL CHECK(direction IN ('DEBIT','CREDIT')),business_date date NOT NULL,due_date date,
 party_name varchar(160),employee_reference varchar(160),original_amount numeric(18,2) NOT NULL CHECK(original_amount>=0),
 remaining_amount numeric(18,2) NOT NULL CHECK(remaining_amount>=0),accumulated_amount numeric(18,2) NOT NULL DEFAULT 0 CHECK(accumulated_amount>=0),
 quantity numeric(20,4),unit varchar(40),residual_amount numeric(18,2),remaining_months integer,benefit_end date,
 status varchar(16) NOT NULL DEFAULT 'STAGED' CHECK(status IN ('STAGED','ACTIVE','CANCELLED')),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,batch_id) REFERENCES fm_batch(tenant_id,id),FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),
 FOREIGN KEY(tenant_id,physical_asset_id) REFERENCES store_fixed_asset(tenant_id,id),
 FOREIGN KEY(tenant_id,inventory_item_id) REFERENCES store_resource_item(tenant_id,id),
 CHECK(remaining_amount<=original_amount),CHECK(quantity IS NULL OR quantity>=0),CHECK(remaining_months IS NULL OR remaining_months>=0),CHECK(original_useful_months IS NULL OR original_useful_months>0),
 CHECK(remaining_months IS NULL OR remaining_months>0 OR kind='ASSET' AND remaining_amount=residual_amount)
);
CREATE UNIQUE INDEX fm_live_source ON fm_subledger(tenant_id,book_id,kind,source_key) WHERE status<>'CANCELLED';
CREATE UNIQUE INDEX fm_live_physical_asset ON fm_subledger(tenant_id,physical_asset_id) WHERE kind='ASSET' AND status<>'CANCELLED';
CREATE INDEX fm_active_subledger ON fm_subledger(tenant_id,book_id,kind,business_date) WHERE status='ACTIVE';
ALTER TABLE fs_asset_card ADD CONSTRAINT fs_asset_opening_source_fk FOREIGN KEY(tenant_id,opening_subledger_id) REFERENCES fm_subledger(tenant_id,id);
ALTER TABLE fs_opening_stock ADD CONSTRAINT fs_inventory_opening_source_fk FOREIGN KEY(tenant_id,subledger_id) REFERENCES fm_subledger(tenant_id,id);
CREATE FUNCTION fm_protect_subledger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (to_jsonb(NEW)-'status') IS DISTINCT FROM (to_jsonb(OLD)-'status') THEN RAISE EXCEPTION 'Migrated opening subledger values are immutable';END IF;
 IF OLD.status<>'STAGED' OR NEW.status NOT IN ('ACTIVE','CANCELLED') THEN RAISE EXCEPTION 'Active or cancelled opening facts cannot be overwritten';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fm_subledger_immutable BEFORE UPDATE OR DELETE ON fm_subledger FOR EACH ROW EXECUTE FUNCTION fm_protect_subledger();
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['fi_metric','fi_snapshot','fi_report','fi_report_revision','fi_recommendation','fi_question','fm_batch','fm_row','fm_subledger','fm_sourcefile'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO hotel_ai_os_app',tab);EXECUTE format('REVOKE DELETE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['fi_snapshot','fi_report_revision','fm_row','fm_sourcefile'] LOOP
  EXECUTE format('CREATE TRIGGER finance_v2_fact_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation()',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN EXECUTE format('REVOKE UPDATE ON %I FROM hotel_ai_os_app',tab);END IF;
 END LOOP;
END $$;
