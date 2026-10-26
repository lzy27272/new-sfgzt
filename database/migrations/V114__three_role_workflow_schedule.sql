-- V114: approved three-role proposal, staged as NEW DRAFTS only. No allocation switch,
-- no published-template rewrite and no expectation/record/report/ledger updates.
-- Publish explicitly with a future effective date after the store has confirmed
-- concrete shifts and breakfast hours. Existing administrator drafts are retained.
SELECT set_config('app.tenant_id', '10000000-0000-0000-0000-000000000001', true);

CREATE TEMP TABLE v88_versions ON COMMIT DROP AS
WITH sources AS (
  SELECT v.*, p.code AS position_code,
    coalesce((SELECT i.applicability_policy->>'assistantWorkTemplateMode'
              FROM work_package_item i WHERE i.tenant_id=v.tenant_id
                AND i.work_package_version_id=v.id
                AND i.applicability_policy ? 'assistantWorkTemplateMode' LIMIT 1),'') AS variant
  FROM work_package_version v
  JOIN work_package_definition d ON d.tenant_id=v.tenant_id AND d.id=v.work_package_definition_id
  JOIN position_definition p ON p.tenant_id=d.tenant_id AND p.id=d.position_id
  WHERE v.tenant_id='10000000-0000-0000-0000-000000000001' AND v.lifecycle_status='PUBLISHED'
    AND d.code IN ('WP-PILOT-HK-ATTENDANT-DAILY','WP-PILOT-AGM-DAILY','WP-PILOT-NIGHT-ATTENDANT-DAILY')
    AND (p.code<>'ASSISTANT_GENERAL_MANAGER' OR EXISTS(
      SELECT 1 FROM work_package_item mode_item WHERE mode_item.tenant_id=v.tenant_id
        AND mode_item.work_package_version_id=v.id
        AND mode_item.applicability_policy->>'assistantWorkTemplateMode'
          IN ('WITH_FRONT_OFFICE_SUPERVISOR','WITHOUT_FRONT_OFFICE_SUPERVISOR')))
    AND (v.effective_to IS NULL OR v.effective_to>=now())
), latest AS (
  SELECT DISTINCT ON (tenant_id,work_package_definition_id,variant) *
  FROM sources ORDER BY tenant_id,work_package_definition_id,variant,version_no DESC
)
SELECT v.id AS old_id,gen_random_uuid() AS new_id,v.tenant_id,v.position_code,v.variant,
  (SELECT max(allv.version_no) FROM work_package_version allv
   WHERE allv.tenant_id=v.tenant_id AND allv.work_package_definition_id=v.work_package_definition_id)
   + row_number() OVER (PARTITION BY v.work_package_definition_id ORDER BY v.variant) AS next_version
FROM latest v;

INSERT INTO work_package_version
SELECT (jsonb_populate_record(null::work_package_version,to_jsonb(v)||jsonb_build_object(
  'id',m.new_id,'version_no',m.next_version,'lifecycle_status','DRAFT',
  'title',left(v.title||' · 三时段优化草稿',200),
  'description',coalesce(v.description,'')||' V88：待配置实际班次/早餐营业时间；仅未来明确生效日发布，不追溯调整历史完成率。',
  'effective_from',null,'effective_to',null,'published_by',null,'published_at',null,
  'content_hash',null,'created_at',now(),'updated_at',now()))).*
FROM v88_versions m JOIN work_package_version v ON v.tenant_id=m.tenant_id AND v.id=m.old_id;

CREATE TEMP TABLE v88_items ON COMMIT DROP AS
SELECT i.id AS old_id,gen_random_uuid() AS new_id,i.tenant_id,m.new_id AS version_id,
       i.item_code,m.position_code
FROM v88_versions m JOIN work_package_item i ON i.tenant_id=m.tenant_id AND i.work_package_version_id=m.old_id
WHERE i.item_code NOT IN ('AGM_NS_BREAKFAST_FEEDBACK','AGM_WS_BREAKFAST_FEEDBACK');

-- One breakfast work item, three editable phases, preserving the original schema
-- and evidence constraints. Saving a draft may omit phases; final submission may not.
CREATE TEMP TABLE v88_forms ON COMMIT DROP AS
SELECT m.old_id,gen_random_uuid() AS id,
 (SELECT max(allv.version_no) FROM form_version allv WHERE allv.tenant_id=f.tenant_id AND allv.form_id=f.form_id)
 + row_number() OVER (PARTITION BY f.form_id ORDER BY m.old_id) AS next_version
FROM v88_items m JOIN work_package_item i ON i.tenant_id=m.tenant_id AND i.id=m.old_id
JOIN form_version f ON f.tenant_id=i.tenant_id AND f.id=i.form_version_id
WHERE i.item_code IN ('AGM_NS_BREAKFAST_INSPECTION','AGM_WS_BREAKFAST_INSPECTION');

INSERT INTO form_version
SELECT (jsonb_populate_record(null::form_version,to_jsonb(f)||jsonb_build_object(
 'id',m.id,'version_no',m.next_version,'lifecycle_status','PUBLISHED',
 'json_schema',jsonb_set(jsonb_set(f.json_schema,'{properties}',coalesce(f.json_schema->'properties','{}')||
  '{"breakfastOpeningNotes":{"type":"string","title":"开餐巡查记录","minLength":1,"maxLength":4000},"breakfastPeakNotes":{"type":"string","title":"高峰巡查与客户意见","minLength":1,"maxLength":4000},"breakfastClosingNotes":{"type":"string","title":"收尾汇总与未结事项","minLength":1,"maxLength":4000}}'::jsonb),
  '{required}',coalesce(f.json_schema->'required','[]')||'["breakfastOpeningNotes","breakfastPeakNotes","breakfastClosingNotes"]'::jsonb),
 'created_at',now(),'published_at',now(),'row_version',0))).*
FROM v88_forms m JOIN work_package_item i ON i.id=m.old_id
JOIN form_version f ON f.tenant_id=i.tenant_id AND f.id=i.form_version_id;

CREATE TEMP TABLE v88_schedule (suffix text PRIMARY KEY,opens time,closes time) ON COMMIT DROP;
INSERT INTO v88_schedule VALUES
 ('APPEARANCE_MEETING','08:30','09:00'),('OTA_CHECK','10:30','11:00'),
 ('PUBLIC_AREA','11:00','11:30'),('THREE_FOLLOWUPS','12:30','14:00'),
 ('ROOM_INSPECTION','14:00','16:00'),('CUSTOMER_FORMS','16:00','17:00'),
 ('REVIEW_TRAINING','15:30','18:00'),('OPERATION_FILES','15:30','18:00'),
 ('EVENING_LIGHT_BAR','18:00','19:00');

INSERT INTO work_package_item
SELECT (jsonb_populate_record(null::work_package_item,to_jsonb(i)||jsonb_build_object(
 'id',m.new_id,'work_package_version_id',m.version_id,'form_version_id',coalesce(f.id,i.form_version_id),
 'description',CASE WHEN i.item_code='HKA_CART_PREPARATION'
   THEN '开工后15分钟内检查布草车和工作车准备情况，异常及时上报；不要求每天重复拍照打卡。'
   ELSE i.description END,
 'review_mode',CASE WHEN i.item_code='HKA_CART_PREPARATION' THEN 'NONE' ELSE i.review_mode END,
 'work_window_start',CASE WHEN i.item_code='HKA_DAILY_REPORT' THEN time '15:30'
   WHEN i.item_code IN ('HKA_CART_PREPARATION','NSA_FINAL_PUBLIC_RECHECK','NSA_DAILY_HANDOVER') THEN null
   ELSE coalesce(s.opens,i.work_window_start) END,
 'work_window_end',CASE WHEN i.item_code='HKA_DAILY_REPORT' THEN time '18:00'
   WHEN i.item_code IN ('HKA_CART_PREPARATION','NSA_FINAL_PUBLIC_RECHECK','NSA_DAILY_HANDOVER') THEN null
   ELSE coalesce(s.closes,i.work_window_end) END,
 'due_local_time',CASE WHEN i.item_code='HKA_DAILY_REPORT' THEN time '18:00'
   WHEN i.item_code IN ('HKA_CART_PREPARATION','NSA_FINAL_PUBLIC_RECHECK','NSA_DAILY_HANDOVER') THEN null
   ELSE coalesce(s.closes,i.due_local_time) END,
 'submission_policy',CASE WHEN i.item_code='HKA_CART_PREPARATION'
   THEN i.submission_policy||'{"attachmentRequired":false,"evidenceRequirements":[]}'::jsonb
   WHEN f.id IS NOT NULL THEN jsonb_set(i.submission_policy,'{evidenceRequirements}',
     coalesce(i.submission_policy->'evidenceRequirements','[]')||coalesce(feedback.submission_policy->'evidenceRequirements','[]'))
   ELSE i.submission_policy END,
 'execution_policy',i.execution_policy||jsonb_build_object('workflowRevision','THREE_ROLE_V88',
   'workflowGroup',CASE
     WHEN i.item_code IN ('HKA_CART_PREPARATION','HKA_ITEM_ISSUE','NSA_CART_PREPARATION','NSA_ITEM_ISSUE') THEN 'START'
     WHEN i.item_code IN ('HKA_DAILY_REPORT','HKA_USAGE_RETURN','NSA_FINAL_PUBLIC_RECHECK','NSA_DAILY_HANDOVER')
       OR i.item_code LIKE 'AGM_%_EVENING_%' THEN 'CLOSE'
     WHEN i.item_code LIKE 'AGM_%' AND (s.opens<time '12:00' OR f.id IS NOT NULL) THEN 'START'
     ELSE 'DURING' END)
   ||CASE WHEN i.item_code='HKA_CART_PREPARATION' THEN
     '{"shiftTiming":{"availableAnchor":"SHIFT_START","availableOffsetMinutes":0,"dueAnchor":"SHIFT_START","dueOffsetMinutes":15}}'::jsonb
     WHEN i.item_code IN ('NSA_FINAL_PUBLIC_RECHECK','NSA_DAILY_HANDOVER') THEN
     '{"shiftTiming":{"availableAnchor":"SHIFT_END","availableOffsetMinutes":-30,"dueAnchor":"SHIFT_END","dueOffsetMinutes":15}}'::jsonb
     WHEN i.item_code='NSA_EQUIPMENT_SECOND' THEN '{"calendarDayOffset":1}'::jsonb
     ELSE '{}'::jsonb END
   ||CASE WHEN f.id IS NOT NULL THEN
     '{"minimumInterviewCount":5,"breakfastStages":{"scheduleConfirmed":false,"opening":{"field":"breakfastOpeningNotes","start":"07:00","end":"07:20"},"peak":{"field":"breakfastPeakNotes","start":"08:00","end":"08:20"},"closing":{"field":"breakfastClosingNotes","start":"10:00","end":"10:30"},"unresolvedRecheck":"09:30","recheckOnlyWhenUnresolved":true}}'::jsonb
     ELSE '{}'::jsonb END,
 'created_at',now(),'updated_at',now()))).*
FROM v88_items m JOIN work_package_item i ON i.tenant_id=m.tenant_id AND i.id=m.old_id
LEFT JOIN v88_forms f ON f.old_id=i.id
LEFT JOIN v88_schedule s ON s.suffix=regexp_replace(i.item_code,'^AGM_(NS|WS)_','')
LEFT JOIN work_package_item feedback ON feedback.tenant_id=i.tenant_id
 AND feedback.work_package_version_id=i.work_package_version_id
 AND feedback.item_code=replace(i.item_code,'BREAKFAST_INSPECTION','BREAKFAST_FEEDBACK') AND f.id IS NOT NULL;

INSERT INTO work_package_scope
SELECT (jsonb_populate_record(null::work_package_scope,to_jsonb(s)||jsonb_build_object('id',gen_random_uuid(),'work_package_version_id',m.new_id))).*
FROM v88_versions m JOIN work_package_scope s ON s.tenant_id=m.tenant_id AND s.work_package_version_id=m.old_id;
INSERT INTO work_package_item_standard
SELECT (jsonb_populate_record(null::work_package_item_standard,to_jsonb(s)||jsonb_build_object('id',gen_random_uuid(),'work_package_item_id',m.new_id))).*
FROM v88_items m JOIN work_package_item_standard s ON s.tenant_id=m.tenant_id AND s.work_package_item_id=m.old_id;
INSERT INTO work_package_item_responsibility
SELECT (jsonb_populate_record(null::work_package_item_responsibility,to_jsonb(s)||jsonb_build_object('id',gen_random_uuid(),'work_package_item_id',m.new_id))).*
FROM v88_items m JOIN work_package_item_responsibility s ON s.tenant_id=m.tenant_id AND s.work_package_item_id=m.old_id;

-- Stage matching report drafts only when no human-owned mutable report/section exists.
CREATE TEMP TABLE v88_reports ON COMMIT DROP AS
SELECT r.id AS old_id,gen_random_uuid() AS new_id,r.tenant_id,w.new_id AS work_version_id,w.position_code,
  (SELECT max(version_no)+1 FROM daily_report_template_version allv
   WHERE allv.tenant_id=r.tenant_id AND allv.template_id=r.template_id) AS next_version
FROM daily_report_template_version r
JOIN daily_report_template_definition d ON d.tenant_id=r.tenant_id AND d.id=r.template_id
JOIN v88_versions w ON w.tenant_id=r.tenant_id
JOIN work_package_version source ON source.tenant_id=w.tenant_id AND source.id=w.old_id
JOIN work_package_version bound ON bound.tenant_id=r.tenant_id AND bound.id=r.work_package_version_id
WHERE r.tenant_id='10000000-0000-0000-0000-000000000001' AND r.lifecycle_status='PUBLISHED'
 AND d.template_origin='HQ' AND d.position_id=(SELECT position_id FROM work_package_definition WHERE id=source.work_package_definition_id)
 AND bound.work_package_definition_id=source.work_package_definition_id
 AND (w.position_code<>'ASSISTANT_GENERAL_MANAGER' OR r.configuration->>'templateVariant'=
   CASE w.variant WHEN 'WITH_FRONT_OFFICE_SUPERVISOR' THEN 'AGM_WITH_SUPERVISOR' ELSE 'AGM_WITHOUT_SUPERVISOR' END)
 AND r.version_no=(SELECT max(version_no) FROM daily_report_template_version newest
   WHERE newest.tenant_id=r.tenant_id AND newest.template_id=r.template_id AND newest.lifecycle_status='PUBLISHED')
 AND NOT EXISTS(SELECT 1 FROM daily_report_template_version draft WHERE draft.tenant_id=r.tenant_id
   AND draft.template_id=r.template_id AND draft.lifecycle_status IN ('DRAFT','IN_REVIEW'))
 AND NOT EXISTS(SELECT 1 FROM daily_report_template_section link
   JOIN daily_report_section_version existing ON existing.tenant_id=link.tenant_id AND existing.id=link.section_version_id
   JOIN daily_report_section_version draft ON draft.tenant_id=existing.tenant_id
     AND draft.section_definition_id=existing.section_definition_id AND draft.lifecycle_status IN ('DRAFT','IN_REVIEW')
   WHERE link.tenant_id=r.tenant_id AND link.template_version_id=r.id);

INSERT INTO daily_report_template_version
SELECT (jsonb_populate_record(null::daily_report_template_version,to_jsonb(r)||jsonb_build_object(
 'id',m.new_id,'version_no',m.next_version,'lifecycle_status','DRAFT','work_package_version_id',m.work_version_id,
 'configuration',r.configuration||jsonb_build_object('sections','[]'::jsonb,'workflowRevision','THREE_ROLE_V88',
   'deliveryTiming',CASE WHEN m.position_code='NIGHT_SERVICE_ATTENDANT' THEN
    '{"mode":"SHIFT_END","openOffsetMinutes":-30,"dueOffsetMinutes":15}'::jsonb
    WHEN m.position_code='HOUSEKEEPING_ATTENDANT' THEN
    '{"mode":"FIXED_LOCAL","openLocalTime":"15:30","dueLocalTime":"18:00"}'::jsonb
    ELSE '{"mode":"FIXED_LOCAL","openLocalTime":"19:00","dueLocalTime":"19:30"}'::jsonb END),
 'effective_from',null,'effective_to',null,'published_by',null,'published_at',null,
 'review_requested_by',null,'review_requested_at',null,'reviewed_by',null,'reviewed_at',null,
 'content_hash',null,'row_version',0,'created_at',now(),'updated_at',now()))).*
FROM v88_reports m JOIN daily_report_template_version r ON r.tenant_id=m.tenant_id AND r.id=m.old_id;

CREATE TEMP TABLE v88_sections ON COMMIT DROP AS
SELECT s.id AS old_id,gen_random_uuid() AS new_id,s.tenant_id,m.old_id AS report_old_id,m.new_id AS report_new_id,
 (SELECT max(version_no) FROM daily_report_section_version allv WHERE allv.tenant_id=s.tenant_id
  AND allv.section_definition_id=s.section_definition_id)
 + row_number() OVER (PARTITION BY s.section_definition_id ORDER BY m.old_id) AS next_version
FROM v88_reports m JOIN daily_report_template_section link ON link.tenant_id=m.tenant_id AND link.template_version_id=m.old_id
JOIN daily_report_section_version s ON s.tenant_id=link.tenant_id AND s.id=link.section_version_id;
INSERT INTO daily_report_section_version
SELECT (jsonb_populate_record(null::daily_report_section_version,to_jsonb(s)||jsonb_build_object(
 'id',m.new_id,'version_no',m.next_version,'lifecycle_status','DRAFT',
 'effective_from',null,'effective_to',null,'published_by',null,'published_at',null,
 'review_requested_by',null,'review_requested_at',null,'reviewed_by',null,'reviewed_at',null,
 'content_hash',null,'row_version',0,'created_at',now(),'updated_at',now()))).*
FROM v88_sections m JOIN daily_report_section_version s ON s.tenant_id=m.tenant_id AND s.id=m.old_id;
INSERT INTO daily_report_template_section
SELECT (jsonb_populate_record(null::daily_report_template_section,to_jsonb(link)||jsonb_build_object(
 'id',gen_random_uuid(),'template_version_id',m.report_new_id,'section_version_id',m.new_id,'created_at',now()))).*
FROM v88_sections m JOIN daily_report_template_section link ON link.tenant_id=m.tenant_id
 AND link.template_version_id=m.report_old_id AND link.section_version_id=m.old_id;
INSERT INTO daily_report_template_item
SELECT (jsonb_populate_record(null::daily_report_template_item,to_jsonb(i)||jsonb_build_object(
 'id',gen_random_uuid(),'section_version_id',s.new_id,'work_package_item_id',coalesce(target.id,i.work_package_item_id),
 'row_version',0,'created_at',now(),'updated_at',now()))).*
FROM v88_sections s JOIN v88_reports r ON r.tenant_id=s.tenant_id AND r.new_id=s.report_new_id
JOIN daily_report_template_item i ON i.tenant_id=s.tenant_id AND i.section_version_id=s.old_id
LEFT JOIN work_package_item old ON old.tenant_id=i.tenant_id AND old.id=i.work_package_item_id
LEFT JOIN work_package_item target ON target.tenant_id=i.tenant_id AND target.work_package_version_id=r.work_version_id
 AND target.item_code=old.item_code
WHERE old.item_code IS NULL OR old.item_code NOT IN ('AGM_NS_BREAKFAST_FEEDBACK','AGM_WS_BREAKFAST_FEEDBACK');

-- Intentionally no auto-publication, allocation, expectation regeneration, policy
-- overwrite or completion-denominator migration. Administrators publish prospectively.

-- The existing guards allow retirement but not a prospective end-date closure
-- while retaining PUBLISHED for old-day resolution. Allow only that metadata
-- operation while the corresponding V88 candidate is staged; content stays frozen.
CREATE OR REPLACE FUNCTION protect_work_package_version() RETURNS trigger AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.lifecycle_status IN ('PUBLISHED','RETIRED') THEN
   RAISE EXCEPTION 'published or retired work package versions are immutable';
 END IF;
 IF TG_OP='UPDATE' AND OLD.lifecycle_status IN ('PUBLISHED','RETIRED') THEN
   IF OLD.lifecycle_status='PUBLISHED' AND NEW.lifecycle_status='PUBLISHED'
      AND NEW.effective_to>now() AND NEW.effective_to>=OLD.effective_from
      AND (OLD.effective_to IS NULL OR NEW.effective_to<OLD.effective_to)
      AND (to_jsonb(NEW)-ARRAY['effective_to','updated_at'])=(to_jsonb(OLD)-ARRAY['effective_to','updated_at'])
      AND EXISTS(SELECT 1 FROM work_package_version candidate
        JOIN work_package_item item ON item.tenant_id=candidate.tenant_id AND item.work_package_version_id=candidate.id
        WHERE candidate.tenant_id=OLD.tenant_id AND candidate.work_package_definition_id=OLD.work_package_definition_id
          AND candidate.lifecycle_status='DRAFT' AND item.execution_policy->>'workflowRevision'='THREE_ROLE_V88'
          AND coalesce(item.applicability_policy->>'assistantWorkTemplateMode','')=
            coalesce((SELECT max(old_item.applicability_policy->>'assistantWorkTemplateMode') FROM work_package_item old_item
              WHERE old_item.tenant_id=OLD.tenant_id AND old_item.work_package_version_id=OLD.id),'')) THEN RETURN NEW;
   END IF;
   IF OLD.lifecycle_status='PUBLISHED' AND NEW.lifecycle_status='RETIRED'
      AND (to_jsonb(NEW)-ARRAY['lifecycle_status','effective_to','updated_at'])
       =(to_jsonb(OLD)-ARRAY['lifecycle_status','effective_to','updated_at']) THEN RETURN NEW;
   END IF;
   RAISE EXCEPTION 'published or retired work package versions are immutable';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION protect_daily_report_template_version() RETURNS trigger AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.lifecycle_status IN ('PUBLISHED','RETIRED') THEN
   RAISE EXCEPTION 'published or retired daily report template versions are immutable';
 END IF;
 IF TG_OP='UPDATE' AND OLD.lifecycle_status IN ('PUBLISHED','RETIRED') THEN
   IF OLD.lifecycle_status='PUBLISHED' AND NEW.lifecycle_status='PUBLISHED'
      AND NEW.effective_to>now() AND NEW.effective_to>OLD.effective_from
      AND (OLD.effective_to IS NULL OR NEW.effective_to<OLD.effective_to)
      AND (to_jsonb(NEW)-ARRAY['effective_to','row_version','updated_at'])
       =(to_jsonb(OLD)-ARRAY['effective_to','row_version','updated_at'])
      AND EXISTS(SELECT 1 FROM daily_report_template_version candidate
        WHERE candidate.tenant_id=OLD.tenant_id AND candidate.template_id=OLD.template_id
          AND candidate.lifecycle_status IN ('DRAFT','IN_REVIEW')
          AND candidate.configuration->>'workflowRevision'='THREE_ROLE_V88') THEN RETURN NEW;
   END IF;
   IF OLD.lifecycle_status='PUBLISHED' AND NEW.lifecycle_status='RETIRED'
      AND (to_jsonb(NEW)-ARRAY['lifecycle_status','effective_to','row_version','updated_at'])
       =(to_jsonb(OLD)-ARRAY['lifecycle_status','effective_to','row_version','updated_at']) THEN RETURN NEW;
   END IF;
   RAISE EXCEPTION 'published or retired daily report template versions are immutable';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION protect_daily_report_section_version() RETURNS trigger AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.lifecycle_status IN ('PUBLISHED','RETIRED') THEN
   RAISE EXCEPTION 'published or retired daily report section versions are immutable';
 END IF;
 IF TG_OP='UPDATE' AND OLD.lifecycle_status IN ('PUBLISHED','RETIRED') THEN
   IF OLD.lifecycle_status='PUBLISHED' AND NEW.lifecycle_status='PUBLISHED'
      AND NEW.effective_to>now() AND NEW.effective_to>OLD.effective_from
      AND (OLD.effective_to IS NULL OR NEW.effective_to<OLD.effective_to)
      AND (to_jsonb(NEW)-ARRAY['effective_to','row_version','updated_at'])
       =(to_jsonb(OLD)-ARRAY['effective_to','row_version','updated_at'])
      AND EXISTS(SELECT 1 FROM daily_report_section_version candidate
        JOIN daily_report_template_section link ON link.tenant_id=candidate.tenant_id AND link.section_version_id=candidate.id
        JOIN daily_report_template_version template ON template.tenant_id=link.tenant_id AND template.id=link.template_version_id
        WHERE candidate.tenant_id=OLD.tenant_id AND candidate.section_definition_id=OLD.section_definition_id
          AND candidate.lifecycle_status IN ('DRAFT','IN_REVIEW') AND template.lifecycle_status IN ('DRAFT','IN_REVIEW')
          AND template.configuration->>'workflowRevision'='THREE_ROLE_V88') THEN RETURN NEW;
   END IF;
   IF OLD.lifecycle_status='PUBLISHED' AND NEW.lifecycle_status='RETIRED'
      AND (to_jsonb(NEW)-ARRAY['lifecycle_status','effective_to','row_version','updated_at'])
       =(to_jsonb(OLD)-ARRAY['lifecycle_status','effective_to','row_version','updated_at']) THEN RETURN NEW;
   END IF;
   RAISE EXCEPTION 'published or retired daily report section versions are immutable';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
