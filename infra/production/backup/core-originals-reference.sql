\set ON_ERROR_STOP on
CREATE TEMP TABLE original_references(domain text,source_table text,source_id text,object_key text,size_bytes bigint,sha256 text);
DO $$ DECLARE item record; BEGIN
 FOR item IN SELECT * FROM (VALUES
  ('WORK','attachment'),('WORK','task_evidence'),('WORK','daily_report_evidence'),
  ('WORK','procurement_attachment'),('WORK','daily_activity_photo'),('WORK','ai_evidence_sample'),('WORK','ai_evidence_photo'),
  ('ACADEMY','academy_course'),('FINANCE','finance_attachment'),('FINANCE','fo_attachment'),
  ('FINANCE_BUSINESS','fb_attachment'),('FINANCE','fs_asset_gain_original')) x(domain,tab) LOOP
  IF to_regclass(item.tab) IS NOT NULL THEN
   EXECUTE format('INSERT INTO original_references SELECT %L,%L,id::text,object_key,size_bytes,trim(sha256) FROM %I a WHERE object_key IS NOT NULL%s',item.domain,item.tab,item.tab,
    CASE WHEN item.tab IN ('task_evidence','daily_report_evidence') THEN ' AND evidence_type<>''LINK'''
         WHEN item.tab='procurement_attachment' THEN ' AND purged_at IS NULL' ELSE '' END);
  END IF;
 END LOOP;
 IF to_regclass('finance_invoice_intake') IS NOT NULL THEN
  INSERT INTO original_references
   SELECT 'FINANCE','finance_invoice_intake',id::text,original->>'objectKey',(original->>'sizeBytes')::bigint,original->>'sha256'
   FROM finance_invoice_intake WHERE original->>'objectKey' IS NOT NULL;
 END IF;
 IF to_regclass('image_retention_object') IS NOT NULL THEN
  DELETE FROM original_references r USING image_retention_object i
   WHERE r.domain='WORK' AND i.state='DELETED' AND i.object_key=r.object_key
    AND i.tenant_id::text=split_part(r.object_key,'/',1);
 END IF;
END $$;
SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY domain,source_table,source_id,object_key),'[]'::jsonb) FROM original_references r;
