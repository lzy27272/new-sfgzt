-- Correct a mistaken noncash write-off with a separately reviewed positive fact, never by editing the loss.
CREATE TABLE fb_receivable_recovery (
 id UUID PRIMARY KEY,tenant_id UUID NOT NULL,adjustment_id UUID NOT NULL,entry_id UUID NOT NULL,hotel_id UUID NOT NULL,company_id UUID NOT NULL,
 amount NUMERIC(18,2) NOT NULL CHECK(amount>0),business_date DATE NOT NULL,accounting_on DATE NOT NULL,
 source_version INT NOT NULL,source_hash CHAR(64) NOT NULL,request_hash CHAR(64) NOT NULL,proof_attachment_id UUID NOT NULL,
 reason VARCHAR(4000) NOT NULL CHECK(length(btrim(reason))>0),snapshot JSONB NOT NULL,created_by UUID NOT NULL,reviewer_id UUID NOT NULL,
 reviewed_by UUID,review_note VARCHAR(4000),status VARCHAR(16) NOT NULL DEFAULT 'REVIEW' CHECK(status IN ('REVIEW','CONFIRMED','REJECTED')),
 version INT NOT NULL DEFAULT 0,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),reviewed_at TIMESTAMPTZ,
 UNIQUE(tenant_id,id),CHECK(created_by<>reviewer_id),CHECK(accounting_on>=business_date),
 FOREIGN KEY(tenant_id,adjustment_id) REFERENCES fb_receivable_adjustment(tenant_id,id),FOREIGN KEY(tenant_id,entry_id) REFERENCES fb_revenue_entry(tenant_id,id),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),
 FOREIGN KEY(tenant_id,proof_attachment_id) REFERENCES fb_attachment(tenant_id,id)
);
CREATE INDEX ix_fb_recovery_original ON fb_receivable_recovery(tenant_id,adjustment_id,accounting_on) WHERE status='CONFIRMED';
ALTER TABLE fb_receivable_recovery ENABLE ROW LEVEL SECURITY;
ALTER TABLE fb_receivable_recovery FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fb_receivable_recovery USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::UUID) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::UUID);
CREATE FUNCTION fb_protect_recovery() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'REVIEW' OR NEW.status NOT IN ('CONFIRMED','REJECTED') OR NEW.version<>OLD.version+1
 OR NEW.reviewed_by IS DISTINCT FROM OLD.reviewer_id
 OR (to_jsonb(OLD)-ARRAY['status','version','reviewed_by','review_note','reviewed_at']) IS DISTINCT FROM (to_jsonb(NEW)-ARRAY['status','version','reviewed_by','review_note','reviewed_at'])
 THEN RAISE EXCEPTION 'receivable recovery is immutable; append an independently reviewed fact';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fb_recovery_immutable BEFORE UPDATE OR DELETE ON fb_receivable_recovery FOR EACH ROW EXECUTE FUNCTION fb_protect_recovery();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
 GRANT SELECT,INSERT,UPDATE ON fb_receivable_recovery TO hotel_ai_os_app;REVOKE DELETE ON fb_receivable_recovery FROM hotel_ai_os_app;
END IF;END $$;
CREATE OR REPLACE FUNCTION fb_receivable_adjusted_amount(t UUID,s UUID,as_of DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
 SELECT coalesce(sum(a.amount-coalesce((SELECT sum(r.amount) FROM fb_receivable_recovery r WHERE r.tenant_id=a.tenant_id AND r.adjustment_id=a.id AND r.status='CONFIRMED' AND r.accounting_on<=as_of),0)),0)
 FROM fb_receivable_adjustment a JOIN fb_revenue_entry e ON e.tenant_id=a.tenant_id AND e.id=a.entry_id
 JOIN fb_revenue_batch b ON b.tenant_id=e.tenant_id AND b.id=e.batch_id JOIN fb_revenue_entry current_entry ON current_entry.tenant_id=e.tenant_id AND current_entry.id=s
 JOIN fb_revenue_batch current_batch ON current_batch.tenant_id=current_entry.tenant_id AND current_batch.id=current_entry.batch_id
 WHERE a.tenant_id=t AND a.status='CONFIRMED' AND a.accounting_on<=as_of AND e.hotel_id=current_entry.hotel_id AND e.external_id=current_entry.external_id
 AND b.source=current_batch.source AND b.channel=current_batch.channel AND b.business_date=current_batch.business_date
$$;
