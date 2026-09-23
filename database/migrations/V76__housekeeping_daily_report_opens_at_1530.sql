-- Move only the housekeeping-attendant daily report opening to 15:30.
-- Published contracts and submitted report/work evidence remain unchanged.
SELECT set_config('app.tenant_id', '10000000-0000-0000-0000-000000000001', true);

CREATE TEMP TABLE v76_versions ON COMMIT DROP AS
SELECT v.id AS old_id, gen_random_uuid() AS new_id, v.tenant_id,
       (now() AT TIME ZONE tenant.timezone)::date AS starts_on,
       (SELECT max(allv.version_no) FROM work_package_version allv
        WHERE allv.tenant_id=v.tenant_id AND allv.work_package_definition_id=v.work_package_definition_id)
       + row_number() OVER (PARTITION BY v.tenant_id,v.work_package_definition_id ORDER BY v.version_no) AS next_version
FROM work_package_version v
JOIN work_package_definition d ON d.tenant_id=v.tenant_id AND d.id=v.work_package_definition_id
JOIN position_definition p ON p.tenant_id=d.tenant_id AND p.id=d.position_id
JOIN tenant ON tenant.id=v.tenant_id
WHERE v.tenant_id='10000000-0000-0000-0000-000000000001'
  AND p.code='HOUSEKEEPING_ATTENDANT' AND v.lifecycle_status='PUBLISHED'
  AND (v.effective_to IS NULL OR v.effective_to::date >= (now() AT TIME ZONE tenant.timezone)::date)
  AND EXISTS (SELECT 1 FROM work_package_item i WHERE i.tenant_id=v.tenant_id
    AND i.work_package_version_id=v.id AND i.item_code='HKA_DAILY_REPORT'
    AND i.work_window_start IS DISTINCT FROM TIME '15:30');

INSERT INTO work_package_version
SELECT (jsonb_populate_record(null::work_package_version,to_jsonb(v)||jsonb_build_object(
  'id',m.new_id,'version_no',m.next_version,'lifecycle_status','DRAFT','published_at',null,
  'effective_from',null,'created_at',now(),'updated_at',now(),'row_version',0))).*
FROM v76_versions m JOIN work_package_version v ON v.tenant_id=m.tenant_id AND v.id=m.old_id;

CREATE TEMP TABLE v76_items ON COMMIT DROP AS
SELECT i.id AS old_id,gen_random_uuid() AS new_id,i.tenant_id,m.new_id AS version_id
FROM v76_versions m JOIN work_package_item i ON i.tenant_id=m.tenant_id AND i.work_package_version_id=m.old_id;

INSERT INTO work_package_item
SELECT (jsonb_populate_record(null::work_package_item,to_jsonb(i)||jsonb_build_object(
  'id',m.new_id,'work_package_version_id',m.version_id,
  'work_window_start',CASE WHEN i.item_code='HKA_DAILY_REPORT' THEN TIME '15:30' ELSE i.work_window_start END))).*
FROM v76_items m JOIN work_package_item i ON i.tenant_id=m.tenant_id AND i.id=m.old_id;

INSERT INTO work_package_scope
SELECT (jsonb_populate_record(null::work_package_scope,to_jsonb(s)||jsonb_build_object(
  'id',gen_random_uuid(),'work_package_version_id',m.new_id))).*
FROM v76_versions m JOIN work_package_scope s ON s.tenant_id=m.tenant_id AND s.work_package_version_id=m.old_id;
INSERT INTO work_package_item_standard
SELECT (jsonb_populate_record(null::work_package_item_standard,to_jsonb(s)||jsonb_build_object(
  'id',gen_random_uuid(),'work_package_item_id',m.new_id))).*
FROM v76_items m JOIN work_package_item_standard s ON s.tenant_id=m.tenant_id AND s.work_package_item_id=m.old_id;
INSERT INTO work_package_item_responsibility
SELECT (jsonb_populate_record(null::work_package_item_responsibility,to_jsonb(s)||jsonb_build_object(
  'id',gen_random_uuid(),'work_package_item_id',m.new_id))).*
FROM v76_items m JOIN work_package_item_responsibility s ON s.tenant_id=m.tenant_id AND s.work_package_item_id=m.old_id;

UPDATE work_package_version v SET lifecycle_status='PUBLISHED',
  effective_from=greatest(m.starts_on,original.effective_from),published_at=now(),
  content_hash=encode(digest(v.id::text||':hka-daily-open-1530-v76','sha256'),'hex')
FROM v76_versions m JOIN work_package_version original ON original.tenant_id=m.tenant_id AND original.id=m.old_id
WHERE v.tenant_id=m.tenant_id AND v.id=m.new_id;

-- Switch allocations today so work not yet generated also opens at 15:30.
-- Keep all original item/record identifiers. The generator deduplicates by stable
-- work-item code, including today's existing and pre-generated future work.
INSERT INTO work_package_allocation
SELECT (jsonb_populate_record(null::work_package_allocation,to_jsonb(a)||jsonb_build_object(
  'id',gen_random_uuid(),'work_package_version_id',m.new_id,
  'valid_from',greatest(m.starts_on,a.valid_from),'created_at',now(),'updated_at',now()))).*
FROM work_package_allocation a JOIN v76_versions m ON m.tenant_id=a.tenant_id AND m.old_id=a.work_package_version_id
WHERE a.status='ACTIVE' AND (a.valid_to IS NULL OR a.valid_to>=m.starts_on);
UPDATE work_package_allocation a SET
  status=CASE WHEN a.valid_from>=m.starts_on THEN 'REVOKED' ELSE a.status END,
  valid_to=CASE WHEN a.valid_from<m.starts_on THEN m.starts_on-1 ELSE a.valid_to END,updated_at=now()
FROM v76_versions m WHERE a.tenant_id=m.tenant_id AND a.work_package_version_id=m.old_id
  AND a.status='ACTIVE' AND (a.valid_to IS NULL OR a.valid_to>=m.starts_on);

-- Existing unfinished work adopts the new opening. Due time, evidence and drafts
-- are not rewritten. Match the generator's FIXED/tenant timezone rules.
WITH openings AS (
  SELECT x.id,x.tenant_id,
    (x.business_date+TIME '15:30') AT TIME ZONE
      (CASE WHEN i.timezone_mode='FIXED' THEN i.fixed_timezone ELSE tenant.timezone END) AS available_at
  FROM work_expectation x
  JOIN work_package_item i ON i.tenant_id=x.tenant_id AND i.id=x.work_package_item_id
  JOIN v76_versions m ON m.tenant_id=i.tenant_id AND m.old_id=i.work_package_version_id
  JOIN tenant ON tenant.id=x.tenant_id
  WHERE i.item_code='HKA_DAILY_REPORT' AND x.business_date>=m.starts_on
    AND x.status IN ('PLANNED','AVAILABLE','IN_PROGRESS','MISSED')
    AND NOT EXISTS (SELECT 1 FROM work_record r WHERE r.tenant_id=x.tenant_id
      AND r.work_expectation_id=x.id AND r.status<>'DRAFT')
)
UPDATE work_expectation x SET available_at=o.available_at,row_version=x.row_version+1,updated_at=now()
FROM openings o WHERE x.tenant_id=o.tenant_id AND x.id=o.id
  AND x.available_at IS DISTINCT FROM o.available_at AND x.due_at>=o.available_at;

-- Delivery policy is editable configuration; immutable daily-report snapshots stay as-is.
UPDATE daily_report_delivery_policy policy SET open_local_time=TIME '15:30',
  row_version=policy.row_version+1,updated_at=now()
FROM daily_report_template_assignment a
JOIN daily_report_template_version v ON v.tenant_id=a.tenant_id AND v.id=a.template_version_id
JOIN daily_report_template_definition d ON d.tenant_id=v.tenant_id AND d.id=v.template_id
JOIN position_definition p ON p.tenant_id=d.tenant_id AND p.id=d.position_id
JOIN tenant ON tenant.id=a.tenant_id
WHERE policy.tenant_id='10000000-0000-0000-0000-000000000001'
  AND policy.tenant_id=a.tenant_id AND policy.template_assignment_id=a.id
  AND p.code='HOUSEKEEPING_ATTENDANT' AND a.assignment_kind='BASE' AND a.status='ACTIVE'
  AND (a.valid_to IS NULL OR a.valid_to >= (now() AT TIME ZONE tenant.timezone)::date)
  AND policy.open_local_time IS DISTINCT FROM TIME '15:30';
