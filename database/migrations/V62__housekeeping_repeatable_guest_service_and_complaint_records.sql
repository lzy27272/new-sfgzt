-- 客房服务员事件型日清：对客服务记录、客诉报备可在同一营业日重复提交，
-- 每条记录独立保留证据，并在岗位日报中按当日全部记录汇总。

-- A production upgrade starts with a fresh NOBYPASSRLS owner connection.
-- Scope this tenant-specific seed to this transaction without disabling RLS
-- or depending on session context left by earlier migrations.
SELECT set_config('app.tenant_id', '10000000-0000-0000-0000-000000000001', true);

ALTER TABLE work_record
    ADD COLUMN series_entry_no INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN repeatable_entry BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_work_record_series_entry CHECK (series_entry_no > 0),
    ADD CONSTRAINT ck_work_record_repeatable_kind CHECK (NOT repeatable_entry OR record_kind = 'EVENT');

DROP INDEX IF EXISTS ux_work_record_expectation_attempt;
CREATE UNIQUE INDEX ux_work_record_expectation_series_attempt
    ON work_record (tenant_id, work_expectation_id, series_entry_no, attempt_no)
    WHERE work_expectation_id IS NOT NULL;

DROP INDEX IF EXISTS uq_work_record_expectation_active_submission;
CREATE UNIQUE INDEX uq_work_record_expectation_active_submission
    ON work_record (tenant_id, work_expectation_id)
    WHERE work_expectation_id IS NOT NULL
      AND NOT repeatable_entry
      AND status IN ('SUBMITTED','APPROVED');
CREATE UNIQUE INDEX uq_work_record_repeatable_series_active_submission
    ON work_record (tenant_id, work_expectation_id, series_entry_no)
    WHERE work_expectation_id IS NOT NULL
      AND repeatable_entry
      AND status IN ('SUBMITTED','APPROVED');

COMMENT ON COLUMN work_record.series_entry_no IS
    '同一工作期望下的独立业务记录序号；repeatable_entry=true 时每次新增记录递增。';
COMMENT ON COLUMN work_record.repeatable_entry IS
    '事件型日清是否允许同一营业日形成多条独立有效记录。';

CREATE TEMP TABLE v62_hka_source ON COMMIT DROP AS
SELECT definition.id AS definition_id, version.id AS source_version_id
FROM work_package_definition definition
JOIN work_package_version version
  ON version.tenant_id=definition.tenant_id
 AND version.work_package_definition_id=definition.id
WHERE definition.tenant_id='10000000-0000-0000-0000-000000000001'
  AND definition.code='WP-PILOT-HK-ATTENDANT-DAILY'
  AND version.lifecycle_status='PUBLISHED'
ORDER BY version.version_no DESC
LIMIT 1;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM v62_hka_source) THEN
        RAISE EXCEPTION 'V62 requires the published housekeeping-attendant daily package';
    END IF;
END $$;

-- Dedicated forms keep the two event records concise and avoid adding mandatory
-- fields to the attendant's existing scheduled checklist items.
INSERT INTO form_definition (id,tenant_id,code,name,form_type,position_id)
SELECT (md5('V62:form:'||seed.code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,
       seed.code,seed.name,'DAILY_WORK',position.id
FROM (VALUES
    ('PILOT-V62-HKA-GUEST-SERVICE-EVENT','对客服务记录'),
    ('PILOT-V62-HKA-COMPLAINT-EVENT','客诉报备')
) seed(code,name)
JOIN position_definition position
  ON position.tenant_id='10000000-0000-0000-0000-000000000001'
 AND position.code='HOUSEKEEPING_ATTENDANT'
ON CONFLICT (tenant_id,code) DO NOTHING;

INSERT INTO form_version
    (id,tenant_id,form_id,version_no,lifecycle_status,json_schema,ui_schema,published_at)
SELECT (md5('V62:form-version:'||seed.code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,definition.id,
       coalesce((SELECT max(existing.version_no)+1 FROM form_version existing
                 WHERE existing.tenant_id=definition.tenant_id
                   AND existing.form_id=definition.id),1),
       'PUBLISHED',seed.schema,
       '{"layout":"COMPACT_EVENT","repeatable":true,"employeeConfirmationRequired":true}'::jsonb,
       now()
FROM (VALUES
    ('PILOT-V62-HKA-GUEST-SERVICE-EVENT',
     '{"type":"object","required":["roomNumber","serviceContent"],"properties":{"roomNumber":{"type":"string","title":"房号","minLength":1,"maxLength":32},"serviceContent":{"type":"string","title":"服务内容描述","minLength":1,"maxLength":2000}}}'::jsonb),
    ('PILOT-V62-HKA-COMPLAINT-EVENT',
     '{"type":"object","required":["roomNumber","complaintContent"],"properties":{"roomNumber":{"type":"string","title":"房号","minLength":1,"maxLength":32},"complaintContent":{"type":"string","title":"客诉内容","minLength":1,"maxLength":2000},"initialHandling":{"type":"string","title":"初步处理情况","maxLength":2000}}}'::jsonb)
) seed(code,schema)
JOIN form_definition definition
  ON definition.tenant_id='10000000-0000-0000-0000-000000000001'
 AND definition.code=seed.code
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO work_package_version
    (id,tenant_id,work_package_definition_id,version_no,lifecycle_status,title,
     description,content_hash,effective_from,published_by,published_at,created_by)
SELECT (md5('V62:wpv:HK_ATTENDANT'))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,source.definition_id,
       (SELECT max(existing.version_no)+1 FROM work_package_version existing
        WHERE existing.tenant_id='10000000-0000-0000-0000-000000000001'
          AND existing.work_package_definition_id=source.definition_id),
       'DRAFT','客房服务员日清 V2（服务与客诉事件记录）',
       '保留既有客房日清，新增可重复提交的对客服务记录和客诉报备。',
       encode(digest('V62:HK_ATTENDANT:REPEATABLE_EVENTS','sha256'),'hex'),
       NULL,
       NULL,NULL,
       '19000000-0000-0000-0000-000000000001'::uuid
FROM v62_hka_source source
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO work_package_scope
    (id,tenant_id,work_package_version_id,scope_type,brand_id,org_unit_id,position_id)
SELECT (md5('V62:scope:'||scope.id::text))::uuid,scope.tenant_id,
       (md5('V62:wpv:HK_ATTENDANT'))::uuid,scope.scope_type,
       scope.brand_id,scope.org_unit_id,scope.position_id
FROM v62_hka_source source
JOIN work_package_scope scope
  ON scope.tenant_id='10000000-0000-0000-0000-000000000001'
 AND scope.work_package_version_id=source.source_version_id
ON CONFLICT (tenant_id,id) DO NOTHING;

CREATE TEMP TABLE v62_hka_item_map ON COMMIT DROP AS
SELECT item.id AS source_item_id,(md5('V62:item-copy:'||item.id::text))::uuid AS target_item_id,
       item.item_code
FROM v62_hka_source source
JOIN work_package_item item
  ON item.tenant_id='10000000-0000-0000-0000-000000000001'
 AND item.work_package_version_id=source.source_version_id;

INSERT INTO work_package_item
    (id,tenant_id,work_package_version_id,item_code,name,description,item_type,
     form_version_id,sort_order,required,period_type,timezone_mode,fixed_timezone,
     work_window_start,work_window_end,due_local_time,grace_minutes,weekdays,
     day_of_month,holiday_policy,waiver_allowed,target_granularity,review_mode,
     submission_policy,reminder_policy,report_policy,applicability_policy,execution_policy)
SELECT mapping.target_item_id,item.tenant_id,(md5('V62:wpv:HK_ATTENDANT'))::uuid,
       item.item_code,item.name,item.description,item.item_type,item.form_version_id,
       CASE WHEN item.item_code='HKA_DAILY_REPORT' THEN 7 ELSE item.sort_order END,
       item.required,item.period_type,item.timezone_mode,item.fixed_timezone,
       item.work_window_start,item.work_window_end,item.due_local_time,item.grace_minutes,
       item.weekdays,item.day_of_month,item.holiday_policy,item.waiver_allowed,
       item.target_granularity,item.review_mode,item.submission_policy,item.reminder_policy,
       item.report_policy,item.applicability_policy,item.execution_policy
FROM v62_hka_item_map mapping
JOIN work_package_item item
  ON item.tenant_id='10000000-0000-0000-0000-000000000001'
 AND item.id=mapping.source_item_id
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO work_package_item
    (id,tenant_id,work_package_version_id,item_code,name,description,item_type,
     form_version_id,sort_order,required,period_type,timezone_mode,
     work_window_start,work_window_end,due_local_time,grace_minutes,weekdays,
     holiday_policy,waiver_allowed,target_granularity,review_mode,
     submission_policy,reminder_policy,report_policy,applicability_policy,execution_policy)
VALUES
((md5('V62:item:HKA_GUEST_SERVICE_EVENT'))::uuid,
 '10000000-0000-0000-0000-000000000001',(md5('V62:wpv:HK_ATTENDANT'))::uuid,
 'HKA_GUEST_SERVICE_EVENT','对客服务记录上传',
 '按每次对客服务独立填写房号、拍照并描述服务内容；同一营业日可重复提交。',
 'EVENT_RECORD',(md5('V62:form-version:PILOT-V62-HKA-GUEST-SERVICE-EVENT'))::uuid,
 5,true,'DAY','HOTEL',TIME '00:00',TIME '18:00',TIME '18:00',0,
 ARRAY[1,2,3,4,5,6,7]::smallint[],'INCLUDE',false,'ASSIGNMENT_ORG','MANUAL',
 '{"completionStatementRequired":false,"attachmentRequired":true,"attachmentCountUnlimited":true,"maxAttachments":200,"maxFileSizeBytes":20971520,"allowedExtensions":["jpg","jpeg","png"],"evidenceRequirements":[{"checkpointCode":"guest_service_photo","label":"对客服务照片","minimum":1,"unlimited":true,"allowedKinds":["IMAGE"]}]}'::jsonb,
 '{"afterOpenMinutes":1,"dueSoonMinutes":15,"overdueMinutes":5,"escalationMinutes":20,"escalationRecipient":"DIRECT_MANAGER","mergeConcurrentMessages":true}'::jsonb,
 '{"dailyReport":true,"factLabel":"对客服务记录","includeEvidence":true,"includeExceptions":true,"aggregateAllRecords":true}'::jsonb,
 '{}'::jsonb,
 '{"enabled":true,"repeatableDailyRecords":true,"evidenceOriginalRetentionDays":5,"structuredSummaryRetentionMonths":36}'::jsonb),
((md5('V62:item:HKA_COMPLAINT_EVENT'))::uuid,
 '10000000-0000-0000-0000-000000000001',(md5('V62:wpv:HK_ATTENDANT'))::uuid,
 'HKA_COMPLAINT_EVENT','客诉报备',
 '每次客诉独立填写房号或发生区域、客诉内容和初步处理情况；同一营业日可重复提交。',
 'EVENT_RECORD',(md5('V62:form-version:PILOT-V62-HKA-COMPLAINT-EVENT'))::uuid,
 6,true,'DAY','HOTEL',TIME '00:00',TIME '18:00',TIME '18:00',0,
 ARRAY[1,2,3,4,5,6,7]::smallint[],'INCLUDE',false,'ASSIGNMENT_ORG','MANUAL',
 '{"completionStatementRequired":false,"attachmentRequired":false,"attachmentCountUnlimited":true,"maxAttachments":200,"maxFileSizeBytes":20971520,"allowedExtensions":["jpg","jpeg","png","pdf","docx","xlsx"],"evidenceRequirements":[{"checkpointCode":"complaint_evidence","label":"客诉现场或处理凭证（可选）","minimum":0,"unlimited":true,"allowedKinds":["IMAGE","DOCUMENT"]}]}'::jsonb,
 '{"afterOpenMinutes":1,"dueSoonMinutes":15,"overdueMinutes":5,"escalationMinutes":20,"escalationRecipient":"DIRECT_MANAGER","mergeConcurrentMessages":true}'::jsonb,
 '{"dailyReport":true,"factLabel":"客诉报备","includeEvidence":true,"includeExceptions":true,"aggregateAllRecords":true}'::jsonb,
 '{}'::jsonb,
 '{"enabled":true,"repeatableDailyRecords":true,"evidenceOriginalRetentionDays":5,"structuredSummaryRetentionMonths":36}'::jsonb)
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO work_package_item_standard
    (id,tenant_id,work_package_item_id,standard_version_id,usage_type,weight)
SELECT (md5('V62:item-standard-copy:'||standard.id::text))::uuid,standard.tenant_id,
       mapping.target_item_id,standard.standard_version_id,standard.usage_type,standard.weight
FROM v62_hka_item_map mapping
JOIN work_package_item_standard standard
  ON standard.tenant_id='10000000-0000-0000-0000-000000000001'
 AND standard.work_package_item_id=mapping.source_item_id
ON CONFLICT DO NOTHING;

INSERT INTO work_package_item_standard
    (id,tenant_id,work_package_item_id,standard_version_id,usage_type,weight)
SELECT (md5('V62:event-standard:'||item.item_code))::uuid,item.tenant_id,item.id,
       '41300000-0000-0000-0000-000000000001'::uuid,'EXECUTION',1
FROM work_package_item item
WHERE item.tenant_id='10000000-0000-0000-0000-000000000001'
  AND item.id IN ((md5('V62:item:HKA_GUEST_SERVICE_EVENT'))::uuid,
                  (md5('V62:item:HKA_COMPLAINT_EVENT'))::uuid)
ON CONFLICT DO NOTHING;

INSERT INTO work_package_item_responsibility
    (id,tenant_id,work_package_item_id,participant_type,resolver_type,position_id,
     scope_strategy,escalation_level)
SELECT (md5('V62:item-responsibility-copy:'||responsibility.id::text))::uuid,
       responsibility.tenant_id,mapping.target_item_id,responsibility.participant_type,
       responsibility.resolver_type,responsibility.position_id,
       responsibility.scope_strategy,responsibility.escalation_level
FROM v62_hka_item_map mapping
JOIN work_package_item_responsibility responsibility
  ON responsibility.tenant_id='10000000-0000-0000-0000-000000000001'
 AND responsibility.work_package_item_id=mapping.source_item_id
ON CONFLICT DO NOTHING;

INSERT INTO work_package_item_responsibility
    (id,tenant_id,work_package_item_id,participant_type,resolver_type,scope_strategy,escalation_level)
SELECT (md5('V62:event-responsibility:'||item.item_code||':'||participant.participant_type))::uuid,
       item.tenant_id,item.id,participant.participant_type,participant.resolver_type,
       participant.scope_strategy,0
FROM work_package_item item
CROSS JOIN (VALUES
    ('EXECUTOR','CURRENT_ASSIGNMENT','ASSIGNMENT_ORG'),
    ('REVIEWER','DIRECT_MANAGER_ASSIGNMENT','ANCESTOR_ORG')
) participant(participant_type,resolver_type,scope_strategy)
WHERE item.tenant_id='10000000-0000-0000-0000-000000000001'
  AND item.id IN ((md5('V62:item:HKA_GUEST_SERVICE_EVENT'))::uuid,
                  (md5('V62:item:HKA_COMPLAINT_EVENT'))::uuid)
ON CONFLICT DO NOTHING;

UPDATE work_package_version
SET lifecycle_status='PUBLISHED',effective_from=date_trunc('day',now())+interval '1 day',
    published_by='19000000-0000-0000-0000-000000000001'::uuid,published_at=now(),
    updated_at=now()
WHERE tenant_id='10000000-0000-0000-0000-000000000001'
  AND id=(md5('V62:wpv:HK_ATTENDANT'))::uuid
  AND lifecycle_status='DRAFT';

-- Switch release-owned allocations at the next business day boundary. Existing
-- expectations and historical evidence remain bound to their immutable version.
UPDATE work_package_allocation allocation
SET status=CASE WHEN allocation.valid_from>current_date THEN 'REVOKED' ELSE allocation.status END,
    valid_to=CASE WHEN allocation.valid_from<=current_date THEN current_date ELSE allocation.valid_to END,
    updated_at=now()
FROM work_package_version version
JOIN work_package_definition definition
  ON definition.tenant_id=version.tenant_id
 AND definition.id=version.work_package_definition_id
WHERE allocation.tenant_id='10000000-0000-0000-0000-000000000001'
  AND allocation.tenant_id=version.tenant_id
  AND allocation.work_package_version_id=version.id
  AND definition.code='WP-PILOT-HK-ATTENDANT-DAILY'
  AND version.id<>(md5('V62:wpv:HK_ATTENDANT'))::uuid
  AND allocation.status='ACTIVE'
  AND (allocation.valid_to IS NULL OR allocation.valid_to>current_date);

INSERT INTO work_package_allocation
    (id,tenant_id,work_package_version_id,position_assignment_id,target_org_unit_id,
     allocation_source,valid_from,status,allocated_by)
SELECT (md5('V62:allocation:'||assignment.id::text))::uuid,assignment.tenant_id,
       (md5('V62:wpv:HK_ATTENDANT'))::uuid,assignment.id,hotel.org_unit_id,
       'SYSTEM',current_date+1,'ACTIVE','19000000-0000-0000-0000-000000000001'::uuid
FROM employee_position_assignment assignment
JOIN position_definition position
  ON position.tenant_id=assignment.tenant_id AND position.id=assignment.position_id
JOIN employee employee_row
  ON employee_row.tenant_id=assignment.tenant_id AND employee_row.id=assignment.employee_id
JOIN user_account account
  ON account.tenant_id=employee_row.tenant_id AND account.id=employee_row.account_id
JOIN LATERAL (
    SELECT profile.org_unit_id
    FROM org_unit_closure scope
    JOIN hotel_profile profile
      ON profile.tenant_id=scope.tenant_id AND profile.org_unit_id=scope.ancestor_id
    WHERE scope.tenant_id=assignment.tenant_id AND scope.descendant_id=assignment.org_unit_id
    ORDER BY scope.depth LIMIT 1
) hotel ON true
WHERE assignment.tenant_id='10000000-0000-0000-0000-000000000001'
  AND position.code='HOUSEKEEPING_ATTENDANT'
  AND assignment.status='ACTIVE'
  AND assignment.valid_from<=current_date+1
  AND (assignment.valid_to IS NULL OR assignment.valid_to>=current_date+1)
  AND employee_row.employment_status='ACTIVE' AND account.status='ACTIVE'
ON CONFLICT (tenant_id,id) DO NOTHING;

-- A new report definition avoids mutating any tenant-owned draft under the
-- prior definition while keeping all historical reports readable.
INSERT INTO daily_report_template_definition
    (id,tenant_id,code,name,description,template_origin,owner_org_unit_id,
     position_id,status,created_by)
SELECT (md5('V62:drt:HKA'))::uuid,'10000000-0000-0000-0000-000000000001'::uuid,
       'DRT-PILOT-HK-ATTENDANT-DAILY-V2','客房服务员岗位日报 V2',
       '既有岗位日清与当日全部对客服务、客诉事件自动汇总。','HQ',
       '12000000-0000-0000-0000-000000000001'::uuid,position.id,'ACTIVE',
       '19000000-0000-0000-0000-000000000001'::uuid
FROM position_definition position
WHERE position.tenant_id='10000000-0000-0000-0000-000000000001'
  AND position.code='HOUSEKEEPING_ATTENDANT'
ON CONFLICT (tenant_id,code) DO NOTHING;

INSERT INTO daily_report_section_definition
    (id,tenant_id,code,name,description,section_origin,owner_org_unit_id,
     position_id,status,created_by)
SELECT (md5('V62:drs:HKA'))::uuid,'10000000-0000-0000-0000-000000000001'::uuid,
       'DRS-PILOT-HK-ATTENDANT-DAILY-V2','客房服务员岗位日报 V2 工作事实',
       '岗位日清、重复业务事件、证据摘要和其他工作。','HQ',
       '12000000-0000-0000-0000-000000000001'::uuid,position.id,'ACTIVE',
       '19000000-0000-0000-0000-000000000001'::uuid
FROM position_definition position
WHERE position.tenant_id='10000000-0000-0000-0000-000000000001'
  AND position.code='HOUSEKEEPING_ATTENDANT'
ON CONFLICT (tenant_id,code) DO NOTHING;

INSERT INTO daily_report_template_version
    (id,tenant_id,template_id,version_no,lifecycle_status,work_package_version_id,
     configuration,created_by)
VALUES ((md5('V62:drtv:HKA'))::uuid,
        '10000000-0000-0000-0000-000000000001',(md5('V62:drt:HKA'))::uuid,
        1,'DRAFT',(md5('V62:wpv:HK_ATTENDANT'))::uuid,
        '{"title":"客房服务员岗位日报 V2","automaticWorkSummary":true,"employeeManualSupplement":true,"repeatableEventAggregation":true,"aiSummary":{"enabled":true,"sections":["FACT","ANALYSIS","SUGGESTION"],"employeeConfirmationRequired":true,"deterministicMetricsOnly":true,"mayApprove":false,"mayPenalizeKpi":false,"mayDispatchOfficialTask":false},"retention":{"originalEvidenceDays":5,"structuredDataMonths":36}}'::jsonb,
        '19000000-0000-0000-0000-000000000001'::uuid)
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_section_version
    (id,tenant_id,section_definition_id,version_no,lifecycle_status,
     condition_expression,configuration,created_by)
VALUES ((md5('V62:drsv:HKA'))::uuid,
        '10000000-0000-0000-0000-000000000001',(md5('V62:drs:HKA'))::uuid,
        1,'DRAFT','{}'::jsonb,
        '{"sectionCode":"hk_attendant_v2","title":"客房服务员岗位日报 V2","sectionOrigin":"HQ","aiAttachmentExtraction":true,"employeeConfirmsAiResult":true}'::jsonb,
        '19000000-0000-0000-0000-000000000001'::uuid)
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_template_section
    (id,tenant_id,template_version_id,section_version_id,section_role,required,sort_order)
VALUES ((md5('V62:drts:HKA'))::uuid,
        '10000000-0000-0000-0000-000000000001',(md5('V62:drtv:HKA'))::uuid,
        (md5('V62:drsv:HKA'))::uuid,'BASE',true,1)
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_template_item
    (id,tenant_id,section_version_id,item_code,label,input_type,required,
     work_package_item_id,evidence_policy,source_policy,validation_rules,option_values,sort_order)
SELECT (md5('V62:drti:HKA:'||item.item_code))::uuid,item.tenant_id,
       (md5('V62:drsv:HKA'))::uuid,lower(item.item_code)||'_fact',item.name,
       'WORK_RECORD_REFERENCE',false,item.id,
       '{"inheritFromWorkRecord":true,"includeEvidenceLinks":true}'::jsonb,
       jsonb_build_object('sourceType','WORK_RECORD','mode',
           CASE WHEN coalesce((item.execution_policy->>'repeatableDailyRecords')::boolean,false)
                THEN 'AUTO_ALL_RECORDS' ELSE 'AUTO_APPROVED' END),
       '{}'::jsonb,'[]'::jsonb,item.sort_order
FROM work_package_item item
WHERE item.tenant_id='10000000-0000-0000-0000-000000000001'
  AND item.work_package_version_id=(md5('V62:wpv:HK_ATTENDANT'))::uuid
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_template_item
    (id,tenant_id,section_version_id,item_code,label,input_type,required,
     evidence_policy,source_policy,validation_rules,option_values,sort_order)
SELECT (md5('V62:drti:HKA:'||suffix.code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,(md5('V62:drsv:HKA'))::uuid,
       suffix.code,suffix.label,'LONG_TEXT',false,'{"required":false}'::jsonb,
       suffix.source_policy,'{"maxLength":8000}'::jsonb,'[]'::jsonb,900+suffix.sort_order
FROM (VALUES
    ('aiFact','AI事实汇总','{"sourceType":"AI_SUMMARY","section":"FACT","employeeConfirmationRequired":true}'::jsonb,1),
    ('aiAnalysis','AI分析','{"sourceType":"AI_SUMMARY","section":"ANALYSIS","employeeConfirmationRequired":true}'::jsonb,2),
    ('aiSuggestion','AI建议','{"sourceType":"AI_SUMMARY","section":"SUGGESTION","employeeConfirmationRequired":true}'::jsonb,3),
    ('manualOtherWork','其他工作与手工汇报','{"sourceType":"MANUAL"}'::jsonb,4)
) suffix(code,label,source_policy,sort_order)
ON CONFLICT (tenant_id,id) DO NOTHING;

UPDATE daily_report_section_version
SET lifecycle_status='PUBLISHED',content_hash=encode(digest(configuration::text,'sha256'),'hex'),
    effective_from=date_trunc('day',now())+interval '1 day',
    published_by='19000000-0000-0000-0000-000000000001',published_at=now(),
    updated_at=now(),row_version=row_version+1
WHERE tenant_id='10000000-0000-0000-0000-000000000001'
  AND id=(md5('V62:drsv:HKA'))::uuid AND lifecycle_status='DRAFT';

UPDATE daily_report_template_version
SET lifecycle_status='PUBLISHED',content_hash=encode(digest(configuration::text,'sha256'),'hex'),
    effective_from=date_trunc('day',now())+interval '1 day',
    published_by='19000000-0000-0000-0000-000000000001',published_at=now(),
    updated_at=now(),row_version=row_version+1
WHERE tenant_id='10000000-0000-0000-0000-000000000001'
  AND id=(md5('V62:drtv:HKA'))::uuid AND lifecycle_status='DRAFT';

UPDATE daily_report_template_assignment assignment
SET status=CASE WHEN assignment.valid_from>current_date THEN 'REVOKED' ELSE assignment.status END,
    valid_to=CASE WHEN assignment.valid_from<=current_date THEN current_date ELSE assignment.valid_to END,
    row_version=assignment.row_version+1,updated_at=now()
FROM daily_report_template_version version
JOIN daily_report_template_definition definition
  ON definition.tenant_id=version.tenant_id AND definition.id=version.template_id
WHERE assignment.tenant_id='10000000-0000-0000-0000-000000000001'
  AND assignment.tenant_id=version.tenant_id AND assignment.template_version_id=version.id
  AND assignment.status='ACTIVE'
  AND (assignment.valid_to IS NULL OR assignment.valid_to>current_date)
  AND definition.code IN ('DRT-PILOT-HK-ATTENDANT-DAILY','DRT-PILOT-HK-ATTENDANT-DAILY-V2')
  AND version.id<>(md5('V62:drtv:HKA'))::uuid;

INSERT INTO daily_report_template_assignment
    (id,tenant_id,template_version_id,assignment_kind,scope_type,position_id,
     priority,valid_from,status,assigned_by)
SELECT (md5('V62:drta:HKA:POSITION'))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,(md5('V62:drtv:HKA'))::uuid,
       'BASE','POSITION',position.id,910,current_date+1,'ACTIVE',
       '19000000-0000-0000-0000-000000000001'::uuid
FROM position_definition position
WHERE position.tenant_id='10000000-0000-0000-0000-000000000001'
  AND position.code='HOUSEKEEPING_ATTENDANT'
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_delivery_policy
    (id,tenant_id,template_assignment_id,enabled,open_local_time,due_local_time,
     grace_minutes,pre_due_reminder_minutes,overdue_reminder_minutes,backfill_days,
     created_by,updated_by)
VALUES ((md5('V62:drdp:HKA:POSITION'))::uuid,
        '10000000-0000-0000-0000-000000000001',(md5('V62:drta:HKA:POSITION'))::uuid,
        true,TIME '16:30',TIME '18:00',0,ARRAY[15]::integer[],ARRAY[5,20]::integer[],1,
        '19000000-0000-0000-0000-000000000001'::uuid,
        '19000000-0000-0000-0000-000000000001'::uuid)
ON CONFLICT (tenant_id,template_assignment_id) DO NOTHING;

COMMENT ON COLUMN work_package_item.execution_policy IS
    'repeatableDailyRecords=true 表示一个日清期望可承载多条独立事件记录；每条可单独审核并聚合到日报。';
