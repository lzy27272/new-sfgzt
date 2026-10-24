-- Daily reports are separately frozen business-date facts, never monthly values relabelled as a day.
ALTER TABLE fi_snapshot ADD COLUMN period_kind varchar(8) NOT NULL DEFAULT 'MONTH', ADD COLUMN period_date date;
ALTER TABLE fi_snapshot ADD CONSTRAINT fi_snapshot_period_kind CHECK((period_kind='MONTH' AND period_date IS NULL) OR (period_kind='DAY' AND period_date IS NOT NULL AND month=date_trunc('month',period_date)::date));
CREATE INDEX fi_snapshot_day_scope ON fi_snapshot(tenant_id,period_kind,period_date,created_at);

-- A closed accounting report can be an archive source without an AI report or a configured model.
CREATE TABLE fac_report_source (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,book_id uuid NOT NULL,hotel_id uuid NOT NULL,company_id uuid NOT NULL,
 month date NOT NULL,report_version varchar(200) NOT NULL,payload_hash char(64) NOT NULL,payload jsonb NOT NULL,
 sensitive_payroll boolean NOT NULL DEFAULT false,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,book_id,month,report_version,payload_hash),
 FOREIGN KEY(tenant_id,book_id) REFERENCES fa_book(tenant_id,id),FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES finance_company(tenant_id,id),FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id)
);
ALTER TABLE fac_report_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE fac_report_source FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fac_report_source USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER fac_report_source_immutable BEFORE UPDATE OR DELETE ON fac_report_source FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
 GRANT SELECT,INSERT ON fac_report_source TO hotel_ai_os_app;REVOKE UPDATE,DELETE ON fac_report_source FROM hotel_ai_os_app;
END IF;END $$;
