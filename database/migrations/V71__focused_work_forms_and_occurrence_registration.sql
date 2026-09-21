-- New published versions; never change historical forms, evidence, reports or KPI settlements.
SELECT set_config('app.tenant_id', '10000000-0000-0000-0000-000000000001', true);

CREATE FUNCTION pg_temp.focused_work_schema(source jsonb, code text) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE props jsonb; required_fields jsonb; allowed text[];
BEGIN
  SELECT coalesce(jsonb_object_agg(key,value),'{}') INTO props FROM jsonb_each(coalesce(source->'properties','{}'))
  WHERE key NOT IN ('workSummary','issueFound','workResult','hasException')
    AND coalesce(value->>'title','') NOT IN ('工作结果','是否有异常','是否发现问题','班次工作结果','夜班工作结果','服务结果','完成情况');
  allowed := CASE code
    WHEN 'HKA_ITEM_ISSUE' THEN ARRAY['inventoryQuantities']
    WHEN 'HKA_CART_PREPARATION' THEN ARRAY['notes']
    WHEN 'HKA_USAGE_RETURN' THEN ARRAY['inventoryQuantities','notes']
    WHEN 'HKA_DAILY_REPORT' THEN ARRAY['checkoutRooms','stayoverRooms','inventoryQuantities']
    ELSE NULL END;
  IF allowed IS NOT NULL THEN
    SELECT coalesce(jsonb_object_agg(key,value),'{}') INTO props FROM jsonb_each(props) WHERE key=ANY(allowed);
  END IF;
  IF code IN ('HKA_CART_PREPARATION','HKA_USAGE_RETURN') THEN
    props := props || '{"notes":{"type":"string","title":"补充说明（选填）","maxLength":2000}}';
  END IF;
  SELECT coalesce(jsonb_agg(value),'[]') INTO required_fields
    FROM jsonb_array_elements(coalesce(source->'required','[]')) WHERE props ? (value #>> '{}');
  IF code='HKA_LOST_PROPERTY' THEN
    props := '{"roomNumber":{"type":"string","title":"房号","minLength":1,"maxLength":32},"itemName":{"type":"string","title":"遗留物品名称","minLength":1,"maxLength":200},"quantity":{"type":"integer","title":"数量","minimum":1},"foundAt":{"type":"string","title":"发现时间","minLength":1,"maxLength":80},"foundLocation":{"type":"string","title":"发现位置","minLength":1,"maxLength":200},"handoverRecipientAssignmentId":{"type":"string","title":"遗留物接收人","minLength":1,"maxLength":200},"notes":{"type":"string","title":"交接说明（选填）","maxLength":2000}}';
    required_fields := '["roomNumber","itemName","quantity","foundAt","foundLocation","handoverRecipientAssignmentId"]';
  END IF;
  RETURN source || jsonb_build_object('properties',props,'required',required_fields);
END $$;

-- Include explicitly allocated versions and each published variant (e.g. assistant modes).
CREATE TEMP TABLE v71_versions ON COMMIT DROP AS
SELECT v.id AS old_id,gen_random_uuid() AS new_id,v.tenant_id,
       (SELECT max(allv.version_no) FROM work_package_version allv WHERE allv.tenant_id=v.tenant_id
          AND allv.work_package_definition_id=v.work_package_definition_id)
       + row_number() OVER (PARTITION BY v.work_package_definition_id ORDER BY v.version_no) AS next_version
FROM work_package_version v
WHERE v.tenant_id='10000000-0000-0000-0000-000000000001' AND v.lifecycle_status='PUBLISHED'
  AND (v.effective_to IS NULL OR v.effective_to::date>=current_date);

INSERT INTO work_package_version
SELECT (jsonb_populate_record(null::work_package_version,to_jsonb(v)||jsonb_build_object(
  'id',m.new_id,'version_no',m.next_version,'lifecycle_status','DRAFT','published_at',null,
  'effective_from',null,'effective_to',v.effective_to,'created_at',now(),'updated_at',now(),'row_version',0))).*
FROM v71_versions m JOIN work_package_version v ON v.id=m.old_id AND v.tenant_id=m.tenant_id;

CREATE TEMP TABLE v71_items ON COMMIT DROP AS
SELECT i.id AS old_id,gen_random_uuid() AS new_id,i.tenant_id,m.new_id AS version_id,
       gen_random_uuid() AS form_version_id,i.item_code,
       (SELECT max(fv.version_no) FROM form_version fv WHERE fv.tenant_id=f.tenant_id AND fv.form_id=f.form_id)
         + row_number() OVER (PARTITION BY f.form_id ORDER BY i.id) AS form_version_no
FROM v71_versions m JOIN work_package_item i ON i.tenant_id=m.tenant_id AND i.work_package_version_id=m.old_id
JOIN form_version f ON f.tenant_id=i.tenant_id AND f.id=i.form_version_id;

INSERT INTO form_version
SELECT (jsonb_populate_record(null::form_version,to_jsonb(f)||jsonb_build_object(
  'id',m.form_version_id,'version_no',m.form_version_no,'json_schema',pg_temp.focused_work_schema(f.json_schema,m.item_code),
  'created_at',now(),'published_at',now(),'row_version',0))).*
FROM v71_items m JOIN work_package_item i ON i.tenant_id=m.tenant_id AND i.id=m.old_id
JOIN form_version f ON f.tenant_id=i.tenant_id AND f.id=i.form_version_id;

INSERT INTO work_package_item
SELECT (jsonb_populate_record(null::work_package_item,to_jsonb(i)||jsonb_build_object(
  'id',m.new_id,'work_package_version_id',m.version_id,'form_version_id',m.form_version_id,
  'description',CASE WHEN i.item_code='HKA_CART_PREPARATION' THEN '按工作清单拍摄布草车和工作车；遗留物发生时另行登记移交。'
    WHEN i.item_code IN ('HKA_GUEST_SERVICE_EVENT','HKA_COMPLAINT_EVENT','HKA_LOST_PROPERTY')
      THEN i.description||' 发生时登记，未发生无需提交；每次记录独立保存并汇总到日报。' ELSE i.description END,
  'submission_policy',i.submission_policy||'{"completionStatementRequired":false}'::jsonb,
  'required',CASE WHEN i.item_code IN ('HKA_GUEST_SERVICE_EVENT','HKA_COMPLAINT_EVENT','HKA_LOST_PROPERTY') THEN false ELSE i.required END,
  'item_type',CASE WHEN i.item_code='HKA_LOST_PROPERTY' THEN 'EVENT_RECORD' ELSE i.item_type END,
  'reminder_policy',CASE WHEN i.item_code IN ('HKA_GUEST_SERVICE_EVENT','HKA_COMPLAINT_EVENT','HKA_LOST_PROPERTY') THEN '{}'::jsonb ELSE i.reminder_policy END,
  'applicability_policy',CASE WHEN i.item_code='HKA_LOST_PROPERTY' THEN '{}'::jsonb ELSE i.applicability_policy END,
  'execution_policy',CASE WHEN i.item_code IN ('HKA_GUEST_SERVICE_EVENT','HKA_COMPLAINT_EVENT','HKA_LOST_PROPERTY')
    THEN i.execution_policy||'{"registrationMode":"WHEN_OCCURRED","repeatableDailyRecords":true}'::jsonb ELSE i.execution_policy END,
  'report_policy',CASE WHEN i.item_code IN ('HKA_GUEST_SERVICE_EVENT','HKA_COMPLAINT_EVENT','HKA_LOST_PROPERTY')
    THEN i.report_policy||'{"aggregateAllRecords":true,"noRecordLabel":"未登记发生记录"}'::jsonb ELSE i.report_policy END))).*
FROM v71_items m JOIN work_package_item i ON i.tenant_id=m.tenant_id AND i.id=m.old_id;

INSERT INTO work_package_scope
SELECT (jsonb_populate_record(null::work_package_scope,to_jsonb(s)||jsonb_build_object('id',gen_random_uuid(),'work_package_version_id',m.new_id))).*
FROM v71_versions m JOIN work_package_scope s ON s.tenant_id=m.tenant_id AND s.work_package_version_id=m.old_id;
INSERT INTO work_package_item_standard
SELECT (jsonb_populate_record(null::work_package_item_standard,to_jsonb(s)||jsonb_build_object('id',gen_random_uuid(),'work_package_item_id',m.new_id))).*
FROM v71_items m JOIN work_package_item_standard s ON s.tenant_id=m.tenant_id AND s.work_package_item_id=m.old_id;
INSERT INTO work_package_item_responsibility
SELECT (jsonb_populate_record(null::work_package_item_responsibility,to_jsonb(s)||jsonb_build_object('id',gen_random_uuid(),'work_package_item_id',m.new_id))).*
FROM v71_items m JOIN work_package_item_responsibility s ON s.tenant_id=m.tenant_id AND s.work_package_item_id=m.old_id;

UPDATE work_package_version v SET lifecycle_status='PUBLISHED',effective_from=greatest(current_date,original.effective_from),published_at=now(),
  content_hash=encode(digest(v.id::text||':focused-work-entry-v71','sha256'),'hex')
FROM v71_versions m JOIN work_package_version original ON original.tenant_id=m.tenant_id AND original.id=m.old_id
WHERE v.tenant_id=m.tenant_id AND v.id=m.new_id;

-- Only current/future unsent work changes contract. Submitted records retain their original links.
UPDATE work_expectation x SET work_package_item_id=m.new_id,
  row_version=x.row_version+1,updated_at=now(),
  status=CASE WHEN m.item_code IN ('HKA_GUEST_SERVICE_EVENT','HKA_COMPLAINT_EVENT','HKA_LOST_PROPERTY')
    AND x.status='MISSED' THEN 'AVAILABLE' ELSE x.status END
FROM v71_items m WHERE x.tenant_id=m.tenant_id AND x.work_package_item_id=m.old_id
  AND x.business_date>=current_date AND (
    (x.status IN ('PLANNED','AVAILABLE','IN_PROGRESS','MISSED')
      AND NOT EXISTS(SELECT 1 FROM work_record r WHERE r.tenant_id=x.tenant_id AND r.work_expectation_id=x.id AND r.status<>'DRAFT'))
    OR (m.item_code IN ('HKA_GUEST_SERVICE_EVENT','HKA_COMPLAINT_EVENT','HKA_LOST_PROPERTY')
      AND x.status IN ('PLANNED','AVAILABLE','IN_PROGRESS','MISSED','SUBMITTED','SATISFIED','FAILED')));
UPDATE work_record r SET work_package_version_id=m.version_id,work_package_item_id=x.work_package_item_id,
  form_version_id=m.form_version_id,repeatable_entry=CASE WHEN m.item_code IN ('HKA_GUEST_SERVICE_EVENT','HKA_COMPLAINT_EVENT','HKA_LOST_PROPERTY') THEN true ELSE r.repeatable_entry END,
  record_kind=CASE WHEN m.item_code='HKA_LOST_PROPERTY' THEN 'EVENT' ELSE r.record_kind END,
  row_version=r.row_version+1
FROM work_expectation x JOIN v71_items m ON m.tenant_id=x.tenant_id AND m.new_id=x.work_package_item_id
WHERE r.tenant_id=x.tenant_id AND r.work_expectation_id=x.id AND r.status='DRAFT';

-- Split allocations at tomorrow, avoiding regenerated duplicates for today's already submitted work.
INSERT INTO work_package_allocation
SELECT (jsonb_populate_record(null::work_package_allocation,to_jsonb(a)||jsonb_build_object(
  'id',gen_random_uuid(),'work_package_version_id',m.new_id,'valid_from',greatest(current_date+1,a.valid_from),'created_at',now(),'updated_at',now()))).*
FROM work_package_allocation a JOIN v71_versions m ON m.tenant_id=a.tenant_id AND m.old_id=a.work_package_version_id
WHERE a.status='ACTIVE' AND (a.valid_to IS NULL OR a.valid_to>current_date);
UPDATE work_package_allocation a SET status=CASE WHEN valid_from>current_date THEN 'REVOKED' ELSE a.status END,
  valid_to=CASE WHEN valid_from<=current_date THEN current_date ELSE valid_to END,updated_at=now()
FROM v71_versions m WHERE a.tenant_id=m.tenant_id AND a.work_package_version_id=m.old_id AND a.status='ACTIVE'
  AND (a.valid_to IS NULL OR a.valid_to>current_date);

-- Existing report templates continue to resolve by stable work item identity.
-- Effective draft validation applies the new optional-entry contract, without
-- rewriting published templates or immutable submitted report snapshots.
