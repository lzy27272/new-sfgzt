CREATE INDEX ix_fo_actual_loan_borrower ON fo_document(tenant_id,(details->>'borrowerAccountId'),id) WHERE type='LOAN' AND status='APPROVED';
CREATE TABLE fb_receivable_adjustment (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,entry_id UUID NOT NULL,hotel_id UUID NOT NULL,company_id UUID NOT NULL,
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('BAD_DEBT','CHANNEL_DIFFERENCE')),amount NUMERIC(18,2) NOT NULL CHECK(amount>0),
 business_date DATE NOT NULL,accounting_on DATE NOT NULL,source_version INT NOT NULL,source_hash CHAR(64) NOT NULL,request_hash CHAR(64) NOT NULL,
 proof_attachment_id UUID NOT NULL,reason VARCHAR(4000) NOT NULL CHECK(length(btrim(reason))>0),snapshot JSONB NOT NULL,
 created_by UUID NOT NULL,reviewer_id UUID NOT NULL,reviewed_by UUID,review_note VARCHAR(4000),
 status VARCHAR(16) NOT NULL DEFAULT 'REVIEW' CHECK(status IN ('REVIEW','CONFIRMED','REJECTED')),version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),reviewed_at TIMESTAMPTZ,UNIQUE(tenant_id,id),CHECK(created_by<>reviewer_id),CHECK(accounting_on>=business_date),
 FOREIGN KEY(tenant_id,entry_id) REFERENCES fb_revenue_entry(tenant_id,id),FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),FOREIGN KEY(tenant_id,proof_attachment_id) REFERENCES fb_attachment(tenant_id,id)
);
CREATE INDEX ix_fb_receivable_adjustment_source ON fb_receivable_adjustment(tenant_id,entry_id,accounting_on) WHERE status='CONFIRMED';
CREATE TABLE fb_tax_calendar (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,hotel_id UUID NOT NULL,company_id UUID NOT NULL,policy_id UUID NOT NULL,
 tax_kind VARCHAR(80) NOT NULL,title VARCHAR(160) NOT NULL,month DATE NOT NULL,due_on DATE NOT NULL,
 obligation VARCHAR(12) NOT NULL CHECK(obligation IN ('FILING','PAYMENT')),official_reference VARCHAR(2000) NOT NULL,
 request_hash CHAR(64) NOT NULL,created_by UUID NOT NULL,reviewer_id UUID NOT NULL,reviewed_by UUID,review_note VARCHAR(4000),
 status VARCHAR(16) NOT NULL DEFAULT 'REVIEW' CHECK(status IN ('REVIEW','ACTIVE','CANCELLED')),version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),reviewed_at TIMESTAMPTZ,UNIQUE(tenant_id,id),CHECK(created_by<>reviewer_id),
 CHECK(extract(day FROM month)=1),CHECK(due_on>=month),FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),FOREIGN KEY(tenant_id,policy_id) REFERENCES fb_tax_policy(tenant_id,id)
);
CREATE UNIQUE INDEX ux_fb_tax_calendar_active ON fb_tax_calendar(tenant_id,company_id,tax_kind,month,obligation) WHERE status IN ('REVIEW','ACTIVE');
CREATE TABLE fb_tax_calendar_link (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,calendar_id UUID NOT NULL,return_id UUID NOT NULL,return_snapshot JSONB NOT NULL,
 actor_id UUID NOT NULL,note VARCHAR(4000) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),UNIQUE(tenant_id,calendar_id),
 FOREIGN KEY(tenant_id,calendar_id) REFERENCES fb_tax_calendar(tenant_id,id),FOREIGN KEY(tenant_id,return_id) REFERENCES fb_tax_return(tenant_id,id)
);
DO $$ DECLARE tab TEXT; BEGIN
 FOREACH tab IN ARRAY ARRAY['fb_receivable_adjustment','fb_tax_calendar','fb_tax_calendar_link'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
 EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
 END LOOP;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
 GRANT SELECT,INSERT,UPDATE ON fb_receivable_adjustment,fb_tax_calendar TO hotel_ai_os_app;
 GRANT SELECT,INSERT ON fb_tax_calendar_link TO hotel_ai_os_app;
 REVOKE DELETE ON fb_receivable_adjustment,fb_tax_calendar FROM hotel_ai_os_app;
 REVOKE UPDATE,DELETE ON fb_tax_calendar_link FROM hotel_ai_os_app;
 END IF;
END $$;
CREATE FUNCTION fb_protect_exception() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR (TG_TABLE_NAME='fb_receivable_adjustment' AND (OLD.status<>'REVIEW' OR NEW.status NOT IN ('CONFIRMED','REJECTED')))
 OR (TG_TABLE_NAME='fb_tax_calendar' AND NOT ((OLD.status='REVIEW' AND NEW.status IN ('ACTIVE','CANCELLED')) OR (OLD.status='ACTIVE' AND NEW.status='CANCELLED')))
 OR (to_jsonb(OLD)-ARRAY['status','version','reviewed_by','review_note','reviewed_at']) IS DISTINCT FROM (to_jsonb(NEW)-ARRAY['status','version','reviewed_by','review_note','reviewed_at'])
 OR NEW.version<>OLD.version+1 OR NEW.reviewed_by IS DISTINCT FROM OLD.reviewer_id
 THEN RAISE EXCEPTION 'confirmed business exception is immutable; retain original and independently review a new record';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fb_adjustment_immutable BEFORE UPDATE OR DELETE ON fb_receivable_adjustment FOR EACH ROW EXECUTE FUNCTION fb_protect_exception();
CREATE TRIGGER fb_calendar_immutable BEFORE UPDATE OR DELETE ON fb_tax_calendar FOR EACH ROW EXECUTE FUNCTION fb_protect_exception();
CREATE TRIGGER fb_calendar_link_immutable BEFORE UPDATE OR DELETE ON fb_tax_calendar_link FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
CREATE FUNCTION fb_receivable_adjusted_amount(t UUID,s UUID,as_of DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
 SELECT coalesce(sum(a.amount),0) FROM fb_receivable_adjustment a JOIN fb_revenue_entry e ON e.tenant_id=a.tenant_id AND e.id=a.entry_id
 JOIN fb_revenue_batch b ON b.tenant_id=e.tenant_id AND b.id=e.batch_id JOIN fb_revenue_entry current_entry ON current_entry.tenant_id=e.tenant_id AND current_entry.id=s
 JOIN fb_revenue_batch current_batch ON current_batch.tenant_id=current_entry.tenant_id AND current_batch.id=current_entry.batch_id
 WHERE a.tenant_id=t AND a.status='CONFIRMED' AND a.accounting_on<=as_of AND e.hotel_id=current_entry.hotel_id AND e.external_id=current_entry.external_id
 AND b.source=current_batch.source AND b.channel=current_batch.channel AND b.business_date=current_batch.business_date
$$;
