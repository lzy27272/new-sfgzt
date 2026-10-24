-- An error in recording cash is not a real refund. Preserve original payments and bank statements.
CREATE TABLE fx_cash_correction (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, source_kind VARCHAR(12) NOT NULL CHECK(source_kind IN ('CLAIM','OPERATION')),
 source_id UUID NOT NULL, document_id UUID NOT NULL, hotel_id UUID NOT NULL, company_id UUID NOT NULL,
 original_source_event_id UUID NOT NULL, original_date DATE NOT NULL, accounting_on DATE NOT NULL,
 original_amount NUMERIC(18,2) NOT NULL CHECK(original_amount>0), amount NUMERIC(18,2) NOT NULL CHECK(amount>0 AND amount<=original_amount),
 source_version INT NOT NULL, source_hash CHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL,
 reason VARCHAR(4000) NOT NULL CHECK(length(btrim(reason))>0), matching_reversals JSONB NOT NULL, snapshot JSONB NOT NULL,
 created_by UUID NOT NULL, reviewer_id UUID NOT NULL, reviewed_by UUID, review_note VARCHAR(4000),
 status VARCHAR(16) NOT NULL DEFAULT 'REVIEW' CHECK(status IN ('REVIEW','CONFIRMED','REJECTED')),
 version INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), reviewed_at TIMESTAMPTZ,
 UNIQUE(tenant_id,id), CHECK(accounting_on>=original_date), CHECK(reviewer_id<>created_by),
 FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id), FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),
 FOREIGN KEY(tenant_id,original_source_event_id) REFERENCES fa_source_event(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id), FOREIGN KEY(tenant_id,reviewer_id) REFERENCES user_account(tenant_id,id),
 CHECK(reviewed_by IS NULL OR reviewed_by=reviewer_id)
);
CREATE INDEX ix_fx_cash_source ON fx_cash_correction(tenant_id,source_kind,source_id,accounting_on) WHERE status='CONFIRMED';
CREATE INDEX ix_fx_cash_queue ON fx_cash_correction(tenant_id,hotel_id,status,reviewer_id,created_at);
CREATE TABLE fx_bank_match_reversal (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL,
 source_kind VARCHAR(12) NOT NULL CHECK(source_kind IN ('CLAIM','OPERATION')), match_id UUID NOT NULL,
 correction_id UUID NOT NULL, amount NUMERIC(18,2) NOT NULL CHECK(amount>0), accounting_on DATE NOT NULL,
 created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,source_kind,match_id,correction_id),
 FOREIGN KEY(tenant_id,correction_id) REFERENCES fx_cash_correction(tenant_id,id)
);
CREATE INDEX ix_fx_match_reversal ON fx_bank_match_reversal(tenant_id,source_kind,match_id,accounting_on);
ALTER TABLE fx_cash_correction ENABLE ROW LEVEL SECURITY;
ALTER TABLE fx_cash_correction FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fx_cash_correction USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::UUID) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::UUID);
ALTER TABLE fx_bank_match_reversal ENABLE ROW LEVEL SECURITY;
ALTER TABLE fx_bank_match_reversal FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fx_bank_match_reversal USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::UUID) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::UUID);
CREATE FUNCTION fx_protect_correction() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'REVIEW' OR
    (to_jsonb(OLD)-ARRAY['status','version','reviewed_by','review_note','reviewed_at']) IS DISTINCT FROM
    (to_jsonb(NEW)-ARRAY['status','version','reviewed_by','review_note','reviewed_at']) OR
    NEW.status NOT IN ('CONFIRMED','REJECTED') OR NEW.version<>OLD.version+1 OR NEW.reviewed_by IS DISTINCT FROM OLD.reviewer_id
 THEN RAISE EXCEPTION 'cash correction source is immutable; append independently reviewed correction'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fx_cash_immutable BEFORE UPDATE OR DELETE ON fx_cash_correction FOR EACH ROW EXECUTE FUNCTION fx_protect_correction();
CREATE TRIGGER fx_match_immutable BEFORE UPDATE OR DELETE ON fx_bank_match_reversal FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
 GRANT SELECT,INSERT,UPDATE ON fx_cash_correction TO hotel_ai_os_app;
 GRANT SELECT,INSERT ON fx_bank_match_reversal TO hotel_ai_os_app;
 REVOKE DELETE ON fx_cash_correction FROM hotel_ai_os_app;
 REVOKE UPDATE,DELETE ON fx_bank_match_reversal FROM hotel_ai_os_app;
END IF; END $$;
-- All helpers are SECURITY INVOKER: a caller cannot use a different tenant to bypass forced RLS.
CREATE FUNCTION fx_cash_effective_amount(t UUID,k TEXT,s UUID,original NUMERIC,as_of DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
 SELECT original-coalesce(sum(amount),0) FROM fx_cash_correction WHERE tenant_id=t AND source_kind=k AND source_id=s AND status='CONFIRMED' AND accounting_on<=as_of
$$;
CREATE FUNCTION fx_bank_match_effective_amount(t UUID,k TEXT,m UUID,original NUMERIC,as_of DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
 SELECT original-coalesce(sum(amount),0) FROM fx_bank_match_reversal WHERE tenant_id=t AND source_kind=k AND match_id=m AND accounting_on<=as_of
$$;
CREATE FUNCTION fx_bank_action_matched_amount(t UUID,s UUID,as_of DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
 SELECT coalesce(sum(fx_bank_match_effective_amount(tenant_id,'OPERATION',id,amount,as_of)),0) FROM fo_bank_match WHERE tenant_id=t AND action_id=s AND (created_at AT TIME ZONE 'Asia/Shanghai')::DATE<=as_of
$$;
CREATE FUNCTION fx_bank_instruction_matched_amount(t UUID,s UUID,as_of DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
 SELECT coalesce(sum(fx_bank_match_effective_amount(tenant_id,'CLAIM',id,amount,as_of)),0) FROM fc_claim_bank_match WHERE tenant_id=t AND instruction_id=s AND (created_at AT TIME ZONE 'Asia/Shanghai')::DATE<=as_of
$$;
CREATE FUNCTION fx_bank_allocated_amount(t UUID,b UUID,as_of DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
 SELECT coalesce(sum(value),0) FROM (
 SELECT fx_bank_match_effective_amount(tenant_id,'OPERATION',id,amount,as_of) value FROM fo_bank_match WHERE tenant_id=t AND transaction_id=b AND (created_at AT TIME ZONE 'Asia/Shanghai')::DATE<=as_of
 UNION ALL SELECT fx_bank_match_effective_amount(tenant_id,'CLAIM',id,amount,as_of) FROM fc_claim_bank_match WHERE tenant_id=t AND transaction_id=b AND (created_at AT TIME ZONE 'Asia/Shanghai')::DATE<=as_of) facts
$$;
CREATE FUNCTION fx_operation_action_amount(t UUID,d UUID,k TEXT,as_of DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
 SELECT coalesce(sum(CASE WHEN action IN ('PAYMENT','RECEIPT','REFUND','REPAYMENT') THEN fx_cash_effective_amount(tenant_id,'OPERATION',id,amount,as_of) ELSE amount END),0) FROM fo_action WHERE tenant_id=t AND document_id=d AND action=k AND occurred_on<=as_of
$$;
CREATE FUNCTION fx_claim_paid_amount(t UUID,c UUID,as_of DATE) RETURNS NUMERIC LANGUAGE sql STABLE AS $$
 SELECT coalesce(sum(fx_cash_effective_amount(tenant_id,'CLAIM',id,amount,as_of)),0) FROM finance_payment WHERE tenant_id=t AND claim_id=c AND paid_on<=as_of
$$;
