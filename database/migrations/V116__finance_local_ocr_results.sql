-- Local recognition observations append to, never replace, immutable evidence metadata.
CREATE TABLE finance_local_ocr_result (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, attachment_id UUID, intake_id UUID,
 source_sha256 CHAR(64) NOT NULL CHECK(source_sha256 ~ '^[a-f0-9]{64}$'),
 kind VARCHAR(20) NOT NULL CHECK(kind IN ('INVOICE','TRANSACTION','PAYMENT')),
 extraction JSONB NOT NULL, phash VARCHAR(16), dhash VARCHAR(16),
 created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK ((attachment_id IS NULL)<>(intake_id IS NULL)),
 CHECK (jsonb_typeof(extraction)='object' AND extraction ? 'sourceSha256' AND extraction->>'sourceSha256'=source_sha256 AND extraction @> '{"confirmationRequired":true}'),
 CHECK ((phash IS NULL AND dhash IS NULL) OR (phash IS NOT NULL AND dhash IS NOT NULL AND phash ~ '^[a-f0-9]{16}$' AND dhash ~ '^[a-f0-9]{16}$')),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,attachment_id) REFERENCES finance_attachment(tenant_id,id),
 FOREIGN KEY(tenant_id,intake_id) REFERENCES finance_invoice_intake(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX finance_local_ocr_attachment ON finance_local_ocr_result(tenant_id,attachment_id,created_at DESC,id DESC);
CREATE INDEX finance_local_ocr_intake ON finance_local_ocr_result(tenant_id,intake_id,created_at DESC,id DESC);
CREATE INDEX finance_local_ocr_images ON finance_local_ocr_result(tenant_id) WHERE phash IS NOT NULL;
ALTER TABLE finance_local_ocr_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_local_ocr_result FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON finance_local_ocr_result
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE FUNCTION finance_local_ocr_check_source() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.attachment_id IS NOT NULL THEN
  IF NOT EXISTS(SELECT 1 FROM finance_attachment a WHERE a.tenant_id=NEW.tenant_id AND a.id=NEW.attachment_id
   AND a.sha256=NEW.source_sha256 AND a.kind=NEW.kind AND a.scan_status='CLEAN' AND a.retired_at IS NULL
   AND a.media_type IN ('image/jpeg','image/png')) THEN RAISE EXCEPTION 'Local recognition must bind the exact scanned original'; END IF;
 ELSE
  IF NEW.kind<>'INVOICE' OR NOT EXISTS(SELECT 1 FROM finance_invoice_intake i WHERE i.tenant_id=NEW.tenant_id AND i.id=NEW.intake_id
   AND i.original->>'sha256'=NEW.source_sha256 AND i.original->>'scanStatus'='CLEAN'
   AND i.original->>'mediaType' IN ('image/jpeg','image/png')) THEN RAISE EXCEPTION 'Local recognition must bind the exact scanned intake original'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_local_ocr_source BEFORE INSERT ON finance_local_ocr_result FOR EACH ROW EXECUTE FUNCTION finance_local_ocr_check_source();
CREATE TRIGGER finance_local_ocr_immutable BEFORE UPDATE OR DELETE ON finance_local_ocr_result FOR EACH ROW EXECUTE FUNCTION prevent_store_resource_fact_mutation();
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
 GRANT SELECT,INSERT ON finance_local_ocr_result TO hotel_ai_os_app;
 REVOKE UPDATE,DELETE ON finance_local_ocr_result FROM hotel_ai_os_app;
END IF; END $$;
