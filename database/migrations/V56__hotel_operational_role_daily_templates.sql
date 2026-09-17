-- Hotel operational role daily templates V1.
--
-- This is a forward-only expansion on V15/V47/V55. Existing work records,
-- evidence, reports and published versions remain immutable. New allocations
-- and report bindings become effective on the next business date so an
-- in-flight deployment cannot replace today's checklist underneath employees.

SELECT set_config('app.tenant_id', '10000000-0000-0000-0000-000000000001', false);

ALTER TABLE hotel_profile
    ADD COLUMN IF NOT EXISTS assistant_work_template_mode VARCHAR(32)
        NOT NULL DEFAULT 'WITH_FRONT_OFFICE_SUPERVISOR',
    ADD COLUMN IF NOT EXISTS operational_feature_flags JSONB
        NOT NULL DEFAULT '{}'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'hotel_profile'::regclass
          AND conname = 'ck_hotel_profile_assistant_work_template_mode'
    ) THEN
        ALTER TABLE hotel_profile
            ADD CONSTRAINT ck_hotel_profile_assistant_work_template_mode
            CHECK (assistant_work_template_mode IN (
                'WITH_FRONT_OFFICE_SUPERVISOR', 'WITHOUT_FRONT_OFFICE_SUPERVISOR'
            ));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'hotel_profile'::regclass
          AND conname = 'ck_hotel_profile_operational_feature_flags_object'
    ) THEN
        ALTER TABLE hotel_profile
            ADD CONSTRAINT ck_hotel_profile_operational_feature_flags_object
            CHECK (jsonb_typeof(operational_feature_flags) = 'object');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'hotel_profile'::regclass
          AND conname = 'ck_hotel_profile_operational_feature_flag_types'
    ) THEN
        ALTER TABLE hotel_profile
            ADD CONSTRAINT ck_hotel_profile_operational_feature_flag_types
            CHECK (
                (NOT operational_feature_flags ? 'breakfastArea'
                 OR jsonb_typeof(operational_feature_flags -> 'breakfastArea') = 'boolean')
                AND (NOT operational_feature_flags ? 'kitchen'
                 OR jsonb_typeof(operational_feature_flags -> 'kitchen') = 'boolean')
                AND (NOT operational_feature_flags ? 'afternoonTea'
                 OR jsonb_typeof(operational_feature_flags -> 'afternoonTea') = 'boolean')
                AND (NOT operational_feature_flags ? 'bar'
                 OR jsonb_typeof(operational_feature_flags -> 'bar') = 'boolean')
                AND (NOT operational_feature_flags ? 'projection'
                 OR jsonb_typeof(operational_feature_flags -> 'projection') = 'boolean')
                AND (NOT operational_feature_flags ? 'gym'
                 OR jsonb_typeof(operational_feature_flags -> 'gym') = 'boolean')
                AND (NOT operational_feature_flags ? 'terrace'
                 OR jsonb_typeof(operational_feature_flags -> 'terrace') = 'boolean')
                AND (NOT operational_feature_flags ? 'trendyPlayArea'
                 OR jsonb_typeof(operational_feature_flags -> 'trendyPlayArea') = 'boolean')
            );
    END IF;
END $$;

COMMENT ON COLUMN hotel_profile.assistant_work_template_mode IS
    'Explicit store-level selection. Temporary supervisor leave uses delegation and does not switch this mode.';
COMMENT ON COLUMN hotel_profile.operational_feature_flags IS
    'Store facilities and operating capabilities used by conditional checklist items.';

-- Initialize the Pilot store without guessing from a single day of attendance.
-- An active configured position means the store uses the supervisor variant;
-- future changes are explicit Organization Management configuration changes.
UPDATE hotel_profile profile
SET assistant_work_template_mode = CASE WHEN EXISTS (
        SELECT 1
        FROM org_unit_closure scope
        JOIN employee_position_assignment assignment
          ON assignment.tenant_id = scope.tenant_id
         AND assignment.org_unit_id = scope.descendant_id
        JOIN position_definition position
          ON position.tenant_id = assignment.tenant_id
         AND position.id = assignment.position_id
        JOIN employee employee_row
          ON employee_row.tenant_id = assignment.tenant_id
         AND employee_row.id = assignment.employee_id
        JOIN user_account account
          ON account.tenant_id = employee_row.tenant_id
         AND account.id = employee_row.account_id
        WHERE scope.tenant_id = profile.tenant_id
          AND scope.ancestor_id = profile.org_unit_id
          AND position.code = 'FRONT_OFFICE_SUPERVISOR'
          AND position.status = 'ACTIVE'
          AND position.deleted_at IS NULL
          AND position.permanently_deleted_at IS NULL
          AND assignment.status = 'ACTIVE'
          AND assignment.valid_from <= current_date
          AND (assignment.valid_to IS NULL OR assignment.valid_to >= current_date)
          AND employee_row.employment_status = 'ACTIVE'
          AND employee_row.deleted_at IS NULL
          AND account.status = 'ACTIVE'
    ) THEN 'WITH_FRONT_OFFICE_SUPERVISOR'
    ELSE 'WITHOUT_FRONT_OFFICE_SUPERVISOR' END,
    operational_feature_flags = jsonb_build_object(
        'breakfastArea', breakfast_service_enabled,
        'kitchen', false,
        'afternoonTea', false,
        'bar', false,
        'projection', false,
        'gym', false,
        'terrace', false,
        'trendyPlayArea', false
    ) || operational_feature_flags,
    updated_at = now()
WHERE tenant_id = '10000000-0000-0000-0000-000000000001';

-- Service steward, night attendant and breakfast lead are independent
-- positions. They are not aliases of a supervisor or housekeeping attendant.
INSERT INTO position_definition
    (id, tenant_id, code, name, job_family, level_code, status, applies_to_all_hotels)
VALUES
    ((md5('V56:position:SERVICE_STEWARD'))::uuid,
     '10000000-0000-0000-0000-000000000001','SERVICE_STEWARD','服务管家','FRONT_OFFICE','P2','ACTIVE',true),
    ((md5('V56:position:NIGHT_SERVICE_ATTENDANT'))::uuid,
     '10000000-0000-0000-0000-000000000001','NIGHT_SERVICE_ATTENDANT','夜班服务员','HOUSEKEEPING','P2','ACTIVE',true),
    ((md5('V56:position:BREAKFAST_AREA_LEAD'))::uuid,
     '10000000-0000-0000-0000-000000000001','BREAKFAST_AREA_LEAD','早餐区负责人','FOOD_AND_BEVERAGE','P2','ACTIVE',true)
ON CONFLICT (tenant_id, code) DO NOTHING;

-- Reserve the new release-governed role codes before inserting SYSTEM roles.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM app_role
        WHERE role_type = 'CUSTOM'
          AND upper(btrim(code)) IN ('SERVICE_STEWARD','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD')
    ) THEN
        RAISE EXCEPTION 'V56 blocked: a CUSTOM role uses a new reserved operational role code';
    END IF;
END $$;

ALTER TABLE app_role DROP CONSTRAINT ck_app_role_custom_reserved_code;
ALTER TABLE app_role
    ADD CONSTRAINT ck_app_role_custom_reserved_code
    CHECK (
        role_type <> 'CUSTOM'
        OR upper(btrim(code)) NOT IN (
            'PLATFORM_ADMIN','GROUP_ADMIN','CEO','GROUP_VICE_PRESIDENT',
            'GENERAL_MANAGER','ASSISTANT_GENERAL_MANAGER',
            'OTA_OPERATION_MANAGER','OTA_OPERATION_ASSISTANT',
            'FRONT_OFFICE_SUPERVISOR','HOUSEKEEPING_SUPERVISOR',
            'HOUSEKEEPING_ATTENDANT','FRONT_DESK','HR_KPI_ADMIN',
            'SERVICE_STEWARD','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD'
        )
    );

INSERT INTO app_role (id, tenant_id, code, name, role_type)
VALUES
    ((md5('V56:role:SERVICE_STEWARD'))::uuid,
     '10000000-0000-0000-0000-000000000001','SERVICE_STEWARD','服务管家','SYSTEM'),
    ((md5('V56:role:NIGHT_SERVICE_ATTENDANT'))::uuid,
     '10000000-0000-0000-0000-000000000001','NIGHT_SERVICE_ATTENDANT','夜班服务员','SYSTEM'),
    ((md5('V56:role:BREAKFAST_AREA_LEAD'))::uuid,
     '10000000-0000-0000-0000-000000000001','BREAKFAST_AREA_LEAD','早餐区负责人','SYSTEM')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO role_permission (tenant_id, role_id, permission_id)
SELECT role.tenant_id, role.id, permission_item.id
FROM app_role role
JOIN permission permission_item ON permission_item.code = ANY (ARRAY[
    'org.read','standard.read','work-package.read','work-record.read','work-record.submit',
    'work.submit','task.read','task.act','evaluation.read','notification.read',
    'daily-report.read','daily-report.submit','daily-report-template.read',
    'daily-operation.read','ai-recommendation.read','ai-recommendation.feedback',
    'kpi.template.read','kpi.scorecard.read-own','kpi.scorecard.dispute',
    'ui.module.role-workbench','ui.module.my-work','ui.module.daily-reports'
])
WHERE role.tenant_id = '10000000-0000-0000-0000-000000000001'
  AND role.code IN ('SERVICE_STEWARD','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD')
ON CONFLICT DO NOTHING;

-- A position cannot be assigned until its group function profile has a
-- published version.  Seed the three new operational positions with the same
-- explicit position-to-role mapping used by the assignment service.
INSERT INTO position_function_profile
    (id, tenant_id, position_id, scope_type, default_role_id)
SELECT (md5('V56:position-profile:' || position.code))::uuid,
       position.tenant_id, position.id, 'GROUP', role.id
FROM position_definition position
JOIN app_role role
  ON role.tenant_id = position.tenant_id AND role.code = position.code
WHERE position.tenant_id = '10000000-0000-0000-0000-000000000001'
  AND position.code IN ('SERVICE_STEWARD','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD')
  AND NOT EXISTS (
      SELECT 1
      FROM position_function_profile existing
      WHERE existing.tenant_id = position.tenant_id
        AND existing.position_id = position.id
        AND existing.scope_type = 'GROUP'
  );

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM position_definition position
        JOIN app_role role
          ON role.tenant_id = position.tenant_id AND role.code = position.code
        LEFT JOIN position_function_profile profile
          ON profile.tenant_id = position.tenant_id
         AND profile.position_id = position.id
         AND profile.scope_type = 'GROUP'
        WHERE position.tenant_id = '10000000-0000-0000-0000-000000000001'
          AND position.code IN ('SERVICE_STEWARD','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD')
          AND (profile.id IS NULL OR profile.default_role_id IS DISTINCT FROM role.id)
    ) THEN
        RAISE EXCEPTION 'V56 blocked: operational position profile role mapping is missing or inconsistent';
    END IF;
END $$;

INSERT INTO position_function_profile_version
    (id, tenant_id, profile_id, version_no, lifecycle_status,
     authorization_scope_type, wecom_self_selectable,
     created_by, published_by, published_at)
SELECT (md5('V56:position-profile-version:' || position.code))::uuid,
       profile.tenant_id, profile.id,
       coalesce((
           SELECT max(existing.version_no) + 1
           FROM position_function_profile_version existing
           WHERE existing.tenant_id = profile.tenant_id
             AND existing.profile_id = profile.id
       ), 1),
       'PUBLISHED', 'SELF', false,
       '19000000-0000-0000-0000-000000000001',
       '19000000-0000-0000-0000-000000000001', now()
FROM position_definition position
JOIN position_function_profile profile
  ON profile.tenant_id = position.tenant_id
 AND profile.position_id = position.id
 AND profile.scope_type = 'GROUP'
WHERE position.tenant_id = '10000000-0000-0000-0000-000000000001'
  AND position.code IN ('SERVICE_STEWARD','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD')
  AND NOT EXISTS (
      SELECT 1
      FROM position_function_profile_version existing
      WHERE existing.tenant_id = profile.tenant_id
        AND existing.profile_id = profile.id
        AND existing.lifecycle_status = 'PUBLISHED'
  );

INSERT INTO position_function_profile_permission
    (tenant_id, profile_version_id, permission_id)
SELECT profile.tenant_id, published.id, role_grant.permission_id
FROM position_definition position
JOIN position_function_profile profile
  ON profile.tenant_id = position.tenant_id
 AND profile.position_id = position.id
 AND profile.scope_type = 'GROUP'
JOIN position_function_profile_version published
  ON published.tenant_id = profile.tenant_id
 AND published.profile_id = profile.id
 AND published.lifecycle_status = 'PUBLISHED'
JOIN role_permission role_grant
  ON role_grant.tenant_id = profile.tenant_id
 AND role_grant.role_id = profile.default_role_id
JOIN permission permission_item
  ON permission_item.id = role_grant.permission_id
 AND permission_item.delegable_to_position = true
WHERE position.tenant_id = '10000000-0000-0000-0000-000000000001'
  AND position.code IN ('SERVICE_STEWARD','NIGHT_SERVICE_ATTENDANT','BREAKFAST_AREA_LEAD')
ON CONFLICT DO NOTHING;

-- Reusable policy builders live only in this migration session.
CREATE OR REPLACE FUNCTION pg_temp.v56_submission_policy(
    evidence JSONB,
    attachment_required BOOLEAN DEFAULT true
) RETURNS JSONB AS $$
    SELECT jsonb_build_object(
        'completionStatementRequired', true,
        'attachmentRequired', attachment_required,
        'attachmentCountUnlimited', true,
        'maxAttachments', 200,
        'maxFileSizeBytes', 20971520,
        'allowedExtensions', jsonb_build_array('jpg','jpeg','png','pdf','docx','xlsx'),
        'evidenceRequirements', coalesce(evidence, '[]'::jsonb),
        'aiExtraction', jsonb_build_object(
            'enabled', false,
            'providerConfigured', false,
            'mode', 'MANUAL_FALLBACK_UNTIL_PROVIDER_CONFIGURED',
            'manualFallbackEnabled', true,
            'employeeConfirmationRequired', false,
            'outputsAfterProviderEnablement', jsonb_build_array(
                'EVIDENCE_SUMMARY','STRUCTURED_DATA','COMPLETED_FACTS',
                'EXCEPTIONS','FOLLOW_UPS','AI_FACT','AI_ANALYSIS','AI_SUGGESTION'
            )
        )
    )
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION pg_temp.v56_reminder_policy() RETURNS JSONB AS $$
    SELECT '{
      "afterOpenMinutes":1,
      "dueSoonMinutes":15,
      "overdueMinutes":5,
      "escalationMinutes":20,
      "escalationRecipient":"DIRECT_MANAGER",
      "mergeConcurrentMessages":true
    }'::jsonb
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION pg_temp.v56_report_policy(label TEXT) RETURNS JSONB AS $$
    SELECT jsonb_build_object(
        'dailyReport', true,
        'factLabel', label,
        'includeEvidence', true,
        'aiSummary', jsonb_build_object(
            'enabled', true,
            'sections', jsonb_build_array('FACT','ANALYSIS','SUGGESTION'),
            'mayChangeStatus', false,
            'mayApprove', false,
            'mayPenalizeKpi', false,
            'acceptedSuggestionCreates', 'EDITABLE_TASK_DRAFT'
        )
    )
$$ LANGUAGE sql IMMUTABLE;

CREATE TEMP TABLE v56_package_seed (
    variant_code TEXT PRIMARY KEY,
    definition_code TEXT NOT NULL,
    definition_name TEXT NOT NULL,
    definition_description TEXT NOT NULL,
    position_code TEXT NOT NULL,
    version_title TEXT NOT NULL,
    version_description TEXT NOT NULL,
    report_code TEXT NOT NULL,
    report_name TEXT NOT NULL,
    report_due TIME NOT NULL,
    assistant_mode VARCHAR(32)
) ON COMMIT DROP;

INSERT INTO v56_package_seed VALUES
('AGM_WITH_SUPERVISOR','WP-PILOT-AGM-DAILY','店助管理日清工作包（Pilot）','店助两种门店模型的日清闭环。','ASSISTANT_GENERAL_MANAGER','店助日清 V2（有前厅主管门店）','跨部门协同、客房及经营抽查。','DRT-PILOT-AGM-WITH-SUPERVISOR','店助日报（有前厅主管门店）',TIME '21:30','WITH_FRONT_OFFICE_SUPERVISOR'),
('AGM_WITHOUT_SUPERVISOR','WP-PILOT-AGM-DAILY','店助管理日清工作包（Pilot）','店助两种门店模型的日清闭环。','ASSISTANT_GENERAL_MANAGER','店助日清 V2（无前厅主管门店）','承接前厅管理职责的完整日清。','DRT-PILOT-AGM-WITHOUT-SUPERVISOR','店助日报（无前厅主管门店）',TIME '21:30','WITHOUT_FRONT_OFFICE_SUPERVISOR'),
('FO_SUPERVISOR','WP-PILOT-FO-SUPERVISOR-DAILY','前厅主管岗位日清工作包（Pilot）','前厅巡检、OTA、服务及交接。','FRONT_OFFICE_SUPERVISOR','前厅主管日清 V2','七段前厅管理闭环。','DRT-PILOT-FO-SUPERVISOR-DAILY','前厅主管岗位日报',TIME '20:00',NULL),
('SERVICE_STEWARD','WP-PILOT-SERVICE-STEWARD-DAILY','服务管家岗位日清工作包（Pilot）','宾客体验、三跟进、客诉与交接。','SERVICE_STEWARD','服务管家日清 V1','独立服务管家岗位闭环。','DRT-PILOT-SERVICE-STEWARD-DAILY','服务管家岗位日报',TIME '19:00',NULL),
('FRONT_DESK','WP-PILOT-FRONT-DAILY','前台员工岗位日清工作包（Pilot）','按班次执行接待、账务和交接。','FRONT_DESK','前台员工班次日清 V2','按实际班次起止时间执行。','DRT-PILOT-FRONT-DAILY','前台员工岗位日报',TIME '23:59',NULL),
('HK_SUPERVISOR','WP-PILOT-HK-SUPERVISOR-DAILY','客房主管岗位日清工作包（Pilot）','客房进度、质量、库存及遗留物审核。','HOUSEKEEPING_SUPERVISOR','客房主管日清 V2','客房质量及团队闭环。','DRT-PILOT-HK-SUPERVISOR-DAILY','客房主管岗位日报',TIME '18:00',NULL),
('HK_ATTENDANT','WP-PILOT-HK-ATTENDANT-DAILY','客房服务员岗位日清工作包（Pilot）','领用、做房、布草耗品及遗留物闭环。','HOUSEKEEPING_ATTENDANT','客房服务员日清 V1','库存联动的客房服务闭环。','DRT-PILOT-HK-ATTENDANT-DAILY','客房服务员岗位日报',TIME '18:00',NULL),
('NIGHT_ATTENDANT','WP-PILOT-NIGHT-ATTENDANT-DAILY','夜班服务员岗位日清工作包（Pilot）','跨日公区、设备、库存与交接闭环。','NIGHT_SERVICE_ATTENDANT','夜班服务员日清 V1','17:00至次日02:00归属同一营业日。','DRT-PILOT-NIGHT-ATTENDANT-DAILY','夜班服务员岗位日报',TIME '02:00',NULL),
('BREAKFAST_LEAD','WP-PILOT-BREAKFAST-LEAD-DAILY','早餐区负责人岗位日清工作包（Pilot）','开餐、温度、意见、收餐和数据闭环。','BREAKFAST_AREA_LEAD','早餐区负责人日清 V1','仅早餐服务门店启用。','DRT-PILOT-BREAKFAST-LEAD-DAILY','早餐区负责人岗位日报',TIME '14:10',NULL),
('GM_DAILY','WP-PILOT-GM-DAILY','店长经营管理日清工作包（Pilot）','店长与店助共享事项只生成一个门店期望，并保留店长专属检查。','GENERAL_MANAGER','店长日清 V3','统一提醒、共享去重及监控回放检查。','DRT-PILOT-GM-DAILY','店长岗位日报',TIME '22:00',NULL);

INSERT INTO work_package_definition
    (id,tenant_id,code,name,description,position_id,owner_org_unit_id,created_by)
SELECT (md5('V56:wpd:'||seed.definition_code))::uuid,
       '10000000-0000-0000-0000-000000000001',seed.definition_code,
       seed.definition_name,seed.definition_description,position.id,
       '12000000-0000-0000-0000-000000000001',
       '19000000-0000-0000-0000-000000000001'
FROM (SELECT DISTINCT definition_code,definition_name,definition_description,position_code
      FROM v56_package_seed) seed
JOIN position_definition position
  ON position.tenant_id='10000000-0000-0000-0000-000000000001'
 AND position.code=seed.position_code
ON CONFLICT (tenant_id,code) DO NOTHING;

CREATE TEMP TABLE v56_form_seed (
    position_code TEXT PRIMARY KEY,
    form_code TEXT NOT NULL,
    form_name TEXT NOT NULL,
    json_schema JSONB NOT NULL
) ON COMMIT DROP;

INSERT INTO v56_form_seed VALUES
('ASSISTANT_GENERAL_MANAGER','PILOT-V56-AGM-WORK-ITEM','店助日清事项记录',
 '{"type":"object","required":["workSummary","issueFound"],"properties":{"workSummary":{"type":"string","title":"工作结果"},"issueFound":{"type":"boolean","title":"是否发现问题"},"issueSummary":{"type":"string","title":"问题及处理"},"customerCount":{"type":"integer","title":"客户或回访数量","minimum":0},"completedRooms":{"type":"integer","title":"当日已完成客房数","minimum":0},"onDutyHousekeepers":{"type":"integer","title":"当班客房服务员人数","minimum":0},"roomNumbers":{"type":"array","title":"查房房号","items":{"type":"string"},"uniqueItems":true},"dirtyRoomNumbers":{"type":"array","title":"走脏房号","items":{"type":"string"},"uniqueItems":true},"warmingEquipmentIds":{"type":"array","title":"保温设备编号","items":{"type":"string"},"uniqueItems":true},"serviceFacilityIds":{"type":"array","title":"服务设施编号","items":{"type":"string"},"uniqueItems":true},"serviceAwareness":{"type":"string","title":"服务意识","enum":["达标","需整改"]},"receptionProcess":{"type":"string","title":"接待流程","enum":["正确","不正确"]},"aiExtractionConfirmed":{"type":"boolean","title":"已人工确认证据汇总"}}}'::jsonb),
('FRONT_OFFICE_SUPERVISOR','PILOT-V56-FO-SUPERVISOR-WORK-ITEM','前厅主管日清事项记录',
 '{"type":"object","required":["workSummary","issueFound"],"properties":{"workSummary":{"type":"string","title":"工作结果"},"issueFound":{"type":"boolean","title":"是否发现问题"},"issueSummary":{"type":"string","title":"问题及处理"},"reviewCount":{"type":"integer","title":"回访数量","minimum":0},"positiveReviewCount":{"type":"integer","title":"好评数量","minimum":0},"complaintCount":{"type":"integer","title":"客诉数量","minimum":0},"pendingHandover":{"type":"string","title":"前厅待完成事宜"},"serviceFacilityIds":{"type":"array","title":"服务设施编号","items":{"type":"string"},"uniqueItems":true},"aiExtractionConfirmed":{"type":"boolean","title":"已人工确认证据汇总"}}}'::jsonb),
('SERVICE_STEWARD','PILOT-V56-SERVICE-STEWARD-WORK-ITEM','服务管家日清事项记录',
 '{"type":"object","required":["workSummary","issueFound"],"properties":{"workSummary":{"type":"string","title":"服务结果"},"issueFound":{"type":"boolean","title":"是否有异常"},"issueSummary":{"type":"string","title":"异常及处理"},"guestRequestCount":{"type":"integer","title":"宾客需求数量","minimum":0},"closedCount":{"type":"integer","title":"已闭环数量","minimum":0},"specialArrivalRequirement":{"type":"boolean","title":"当日是否有预抵特殊需求"},"publicAreaFacilityIds":{"type":"array","title":"公共区域设施编号","items":{"type":"string"},"uniqueItems":true},"handoverRecipientAssignmentId":{"type":"string","title":"交接接收人"},"aiExtractionConfirmed":{"type":"boolean","title":"已人工确认证据汇总"}}}'::jsonb),
('FRONT_DESK','PILOT-V56-FRONT-DESK-WORK-ITEM','前台员工班次事项记录',
 '{"type":"object","required":["workSummary","issueFound"],"properties":{"workSummary":{"type":"string","title":"班次工作结果"},"issueFound":{"type":"boolean","title":"是否有异常"},"issueSummary":{"type":"string","title":"异常及处理"},"cashDifference":{"type":"number","title":"现金差异"},"handoverRecipientAssignmentId":{"type":"string","title":"交班接收人"},"aiExtractionConfirmed":{"type":"boolean","title":"已人工确认证据汇总"}}}'::jsonb),
('HOUSEKEEPING_SUPERVISOR','PILOT-V56-HK-SUPERVISOR-WORK-ITEM','客房主管日清事项记录',
 '{"type":"object","required":["workSummary","issueFound"],"properties":{"workSummary":{"type":"string","title":"工作结果"},"issueFound":{"type":"boolean","title":"是否发现问题"},"issueSummary":{"type":"string","title":"问题及整改"},"roomsChecked":{"type":"integer","title":"检查房间数","minimum":0},"roomNumbers":{"type":"array","title":"检查房号","items":{"type":"string"},"uniqueItems":true},"reworkCount":{"type":"integer","title":"返工数量","minimum":0},"aiExtractionConfirmed":{"type":"boolean","title":"已人工确认证据汇总"}}}'::jsonb),
('HOUSEKEEPING_ATTENDANT','PILOT-V56-HK-ATTENDANT-WORK-ITEM','客房服务员日清事项记录',
 '{"type":"object","required":["workSummary","issueFound"],"properties":{"workSummary":{"type":"string","title":"工作结果"},"issueFound":{"type":"boolean","title":"是否有异常"},"issueSummary":{"type":"string","title":"异常说明"},"checkoutRooms":{"type":"integer","title":"退房打扫数","minimum":0},"stayoverRooms":{"type":"integer","title":"续住房打扫数","minimum":0},"inventoryQuantities":{"type":"object","title":"门店配置物品数量"},"lostPropertyOccurred":{"type":"boolean","title":"是否发现遗留物"},"handoverRecipientAssignmentId":{"type":"string","title":"遗留物接收人"},"aiExtractionConfirmed":{"type":"boolean","title":"已人工确认证据汇总"}}}'::jsonb),
('NIGHT_SERVICE_ATTENDANT','PILOT-V56-NIGHT-ATTENDANT-WORK-ITEM','夜班服务员日清事项记录',
 '{"type":"object","required":["workSummary","issueFound"],"properties":{"workSummary":{"type":"string","title":"夜班工作结果"},"issueFound":{"type":"boolean","title":"是否有异常"},"issueSummary":{"type":"string","title":"异常说明"},"equipmentReadings":{"type":"object","title":"设备温度及水位"},"equipmentAssetIds":{"type":"array","title":"设备编号","items":{"type":"string"},"uniqueItems":true},"inventoryQuantities":{"type":"object","title":"耗品布草数量"},"lostPropertyOccurred":{"type":"boolean","title":"是否发现遗留物"},"handoverRecipientAssignmentId":{"type":"string","title":"交接接收人"},"aiExtractionConfirmed":{"type":"boolean","title":"已人工确认证据汇总"}}}'::jsonb),
('BREAKFAST_AREA_LEAD','PILOT-V56-BREAKFAST-LEAD-WORK-ITEM','早餐区负责人日清事项记录',
 '{"type":"object","required":["workSummary","issueFound"],"properties":{"workSummary":{"type":"string","title":"工作结果"},"issueFound":{"type":"boolean","title":"是否有异常"},"issueSummary":{"type":"string","title":"异常说明"},"guestBreakfastCount":{"type":"integer","title":"客户早餐人数","minimum":0},"breakfastVoucherCount":{"type":"integer","title":"早餐券数量","minimum":0},"employeeBreakfastCount":{"type":"integer","title":"员工早餐人数","minimum":0},"feedbackPeopleCount":{"type":"integer","title":"意见收集人数","minimum":0},"warmingEquipmentIds":{"type":"array","title":"保温设备编号","items":{"type":"string"},"uniqueItems":true},"aiExtractionConfirmed":{"type":"boolean","title":"已人工确认证据汇总"}}}'::jsonb),
('GENERAL_MANAGER','PILOT-V56-GM-WORK-ITEM','店长日清事项记录',
 '{"type":"object","required":["workSummary","issueFound"],"properties":{"workSummary":{"type":"string","title":"工作结果"},"issueFound":{"type":"boolean","title":"是否发现问题"},"issueSummary":{"type":"string","title":"问题及处理"},"customerCount":{"type":"integer","title":"查看接待客户人数","minimum":0},"roomInspectionRoomNumbers":{"type":"array","title":"查房房号","items":{"type":"string"},"uniqueItems":true},"dirtyRoomNumbers":{"type":"array","title":"走脏房号","items":{"type":"string"},"uniqueItems":true},"warmingEquipmentIds":{"type":"array","title":"保温设备编号","items":{"type":"string"},"uniqueItems":true},"publicAreaFacilityIds":{"type":"array","title":"公共区域设施编号","items":{"type":"string"},"uniqueItems":true},"eveningPublicAreaFacilityIds":{"type":"array","title":"晚间公共区域设施编号","items":{"type":"string"},"uniqueItems":true},"serviceAwareness":{"type":"string","title":"服务意识","enum":["达标","需整改"]},"receptionProcess":{"type":"string","title":"接待流程","enum":["正确","不正确"]},"aiExtractionConfirmed":{"type":"boolean","title":"已人工确认证据汇总"}}}'::jsonb);

INSERT INTO form_definition (id,tenant_id,code,name,form_type,position_id)
SELECT (md5('V56:form:'||seed.form_code))::uuid,
       '10000000-0000-0000-0000-000000000001',seed.form_code,seed.form_name,
       'DAILY_WORK',position.id
FROM v56_form_seed seed
JOIN position_definition position
  ON position.tenant_id='10000000-0000-0000-0000-000000000001'
 AND position.code=seed.position_code
ON CONFLICT (tenant_id,code) DO NOTHING;

INSERT INTO form_version
    (id,tenant_id,form_id,version_no,lifecycle_status,json_schema,ui_schema,published_at)
SELECT (md5('V56:form-version:'||seed.form_code))::uuid,
       '10000000-0000-0000-0000-000000000001',definition.id,
       coalesce((SELECT max(version_no)+1 FROM form_version existing
                 WHERE existing.tenant_id=definition.tenant_id AND existing.form_id=definition.id),1),
       'PUBLISHED',seed.json_schema,
       '{"aiExtractionPreview":true,"employeeConfirmationRequired":true}'::jsonb,now()
FROM v56_form_seed seed
JOIN form_definition definition
  ON definition.tenant_id='10000000-0000-0000-0000-000000000001'
 AND definition.code=seed.form_code
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO work_package_version
    (id,tenant_id,work_package_definition_id,version_no,lifecycle_status,title,
     description,content_hash,created_by)
SELECT (md5('V56:wpv:'||seed.variant_code))::uuid,
       '10000000-0000-0000-0000-000000000001',definition.id,
       coalesce(existing.max_version,0)
         + row_number() OVER (PARTITION BY definition.id ORDER BY seed.variant_code),
       'DRAFT',seed.version_title,seed.version_description,
       encode(digest('V56:'||seed.variant_code,'sha256'),'hex'),
       '19000000-0000-0000-0000-000000000001'
FROM v56_package_seed seed
JOIN work_package_definition definition
  ON definition.tenant_id='10000000-0000-0000-0000-000000000001'
 AND definition.code=seed.definition_code
LEFT JOIN LATERAL (
    SELECT max(version_no) AS max_version
    FROM work_package_version version
    WHERE version.tenant_id=definition.tenant_id
      AND version.work_package_definition_id=definition.id
) existing ON true
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO work_package_scope (id,tenant_id,work_package_version_id,scope_type)
SELECT (md5('V56:wps:'||seed.variant_code))::uuid,
       '10000000-0000-0000-0000-000000000001',
       (md5('V56:wpv:'||seed.variant_code))::uuid,'TENANT'
FROM v56_package_seed seed
ON CONFLICT (tenant_id,id) DO NOTHING;

CREATE TEMP TABLE v56_item_seed (
    variant_code TEXT NOT NULL,
    item_code TEXT NOT NULL,
    item_name TEXT NOT NULL,
    item_description TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    period_type VARCHAR(16) NOT NULL DEFAULT 'DAY',
    window_start TIME,
    window_end TIME,
    due_time TIME,
    item_type VARCHAR(32) NOT NULL DEFAULT 'INSPECTION',
    attachment_required BOOLEAN NOT NULL DEFAULT true,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    applicability JSONB NOT NULL DEFAULT '{}'::jsonb,
    execution JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (variant_code,item_code)
) ON COMMIT DROP;

-- Store assistant: store configuration selects exactly one of these variants.
INSERT INTO v56_item_seed VALUES
('AGM_WITH_SUPERVISOR','AGM_WS_BREAKFAST_INSPECTION','早餐厅巡查与设备温度','检查出餐、摆盘、用餐区及保温设备温度。',1,'DAY',TIME '07:00',TIME '10:30',TIME '10:30','INSPECTION',true,
 '[{"checkpointCode":"serving_plating","label":"出餐及摆盘","minimum":3,"recommendedMaximum":6},{"checkpointCode":"dining_hall","label":"大厅整体","minimum":3,"recommendedMaximum":4},{"checkpointCode":"warming_temperature","label":"保温设备当前温度","minimumPerConfiguredAsset":1,"configuredAssetInstanceField":"warmingEquipmentIds","instanceLabel":"设备编号"}]',
 '{"requiresBreakfastService":true}',
 '{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITH_SUPERVISOR','AGM_WS_BREAKFAST_FEEDBACK','早餐客户用餐体验调研','向不少于5位客户了解口味、体验和意见。',2,'DAY',TIME '07:00',TIME '10:30',TIME '10:30','SCHEDULED_RECORD',true,
 '[{"checkpointCode":"feedback_record","label":"意见收集记录或表格","minimum":1,"allowedKinds":["IMAGE","DOCUMENT"]}]',
 '{"requiresBreakfastService":true}',
 '{"minimumInterviewCount":5,"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITH_SUPERVISOR','AGM_WS_APPEARANCE_MEETING','员工仪容仪表与晨会','全体员工、本人及班前会记录。',3,'DAY',TIME '08:00',TIME '10:30',TIME '10:30','INSPECTION',true,
 '[{"checkpointCode":"full_body","label":"全体全身照","minimum":1},{"checkpointCode":"recorder_radio","label":"工作记录仪和对讲机佩戴","minimum":1,"recommendedMaximum":2},{"checkpointCode":"meeting_minutes","label":"晨会纪要","minimum":1,"recommendedMaximum":2}]',
 '{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITH_SUPERVISOR','AGM_WS_KITCHEN_INSPECTION','后厨巡检','检查菜品标签、消毒记录和食品存放日期。',4,'DAY',TIME '12:30',TIME '13:30',TIME '13:30','INSPECTION',true,
 '[{"checkpointCode":"food_labels","label":"菜品标签","minimum":1,"recommendedMaximum":5},{"checkpointCode":"disinfection_log","label":"消毒记录","minimum":1,"recommendedMaximum":2},{"checkpointCode":"food_storage_date","label":"食品存放及日期","minimum":3,"recommendedMaximum":5}]',
 '{"requiresFacility":"KITCHEN"}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"sharedGenerationMode":"ONE_EXPECTATION_PER_ORG_BUSINESS_DATE","delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITH_SUPERVISOR','AGM_WS_ROOM_INSPECTION','客房查房抽检','抽检不少于完成客房20%，且每名做房员工至少5间。',5,'DAY',TIME '13:30',TIME '16:00',TIME '16:00','INSPECTION',true,
 '[{"checkpointCode":"room_overview","label":"逐房全景照片","minimumPerInstance":1,"instanceField":"roomNumbers","instanceLabel":"房号"},{"checkpointCode":"room_issue","label":"问题照片","minimum":0,"unlimited":true},{"checkpointCode":"dirty_room","label":"脏房全景","minimumPerInstance":1,"instanceField":"dirtyRoomNumbers"}]',
 '{}','{"requiredRoomCountFormula":"max(ceil(completedRooms*0.20),onDutyHousekeepers*5)","sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITH_SUPERVISOR','AGM_WS_CUSTOMER_FORMS','续住沟通与投诉意见表','上传续住客户沟通及投诉意见资料。',6,'DAY',TIME '14:00',TIME '17:00',TIME '17:00','SCHEDULED_RECORD',true,
 '[{"checkpointCode":"stayover_communication","label":"续住客户沟通表","minimum":0,"allowedKinds":["IMAGE","DOCUMENT"]},{"checkpointCode":"complaint_form","label":"投诉意见表","minimum":0,"allowedKinds":["IMAGE","DOCUMENT"]}]',
 '{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITH_SUPERVISOR','AGM_WS_OPERATION_FILES','工程、培训、沟通及库存汇总','上传并核实维修、培训、沟通考核和库存出入库记录。',7,'DAY',TIME '17:00',TIME '18:00',TIME '18:00','METRIC_REVIEW',true,
 '[{"checkpointCode":"operation_files","label":"工程维修、培训、沟通考核、库存资料","minimum":1,"allowedKinds":["IMAGE","PDF","WORD","EXCEL"]}]',
 '{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITH_SUPERVISOR','AGM_WS_EVENING_LIGHT_BAR','晚间灯光、投影与吧台巡检','检查店内与门头亮化、音乐投影及吧台餐具。',8,'DAY',TIME '18:00',TIME '19:00',TIME '19:00','INSPECTION',true,
 '[{"checkpointCode":"indoor_lighting","label":"室内灯光","minimum":1,"recommendedMaximum":5},{"checkpointCode":"bar_inside","label":"吧台内","minimum":1,"recommendedMaximum":4},{"checkpointCode":"popcorn_machine","label":"爆米花机","minimum":1},{"checkpointCode":"counter","label":"台面","minimum":1},{"checkpointCode":"facade_lighting","label":"室外外墙亮化","minimum":0,"recommendedMaximum":3},{"checkpointCode":"storefront_outdoor_lighting","label":"门头及室外灯光","minimum":0,"recommendedMaximum":3}]',
 '{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}');


INSERT INTO v56_item_seed VALUES
('AGM_WITHOUT_SUPERVISOR','AGM_NS_BREAKFAST_INSPECTION','早餐厅巡查与设备温度','检查出餐、摆盘、用餐区及保温设备温度。',1,'DAY',TIME '07:00',TIME '10:30',TIME '10:30','INSPECTION',true,
 '[{"checkpointCode":"serving_plating","label":"出餐及摆盘","minimum":3,"recommendedMaximum":6},{"checkpointCode":"dining_hall","label":"大厅整体","minimum":3,"recommendedMaximum":4},{"checkpointCode":"warming_temperature","label":"保温设备当前温度","minimumPerConfiguredAsset":1,"configuredAssetInstanceField":"warmingEquipmentIds","instanceLabel":"设备编号"}]','{"requiresBreakfastService":true}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_BREAKFAST_FEEDBACK','早餐客户用餐体验调研','向不少于5位客户了解口味、体验和意见。',2,'DAY',TIME '07:00',TIME '10:30',TIME '10:30','SCHEDULED_RECORD',true,'[{"checkpointCode":"feedback_record","label":"意见记录或表格","minimum":1}]','{"requiresBreakfastService":true}','{"minimumInterviewCount":5,"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_APPEARANCE_MEETING','员工仪容仪表与晨会','全体员工、本人及班前会记录。',3,'DAY',TIME '08:00',TIME '10:30',TIME '10:30','INSPECTION',true,'[{"checkpointCode":"full_body","label":"全体全身照","minimum":1},{"checkpointCode":"recorder_radio","label":"记录仪和对讲机佩戴","minimum":1,"recommendedMaximum":2},{"checkpointCode":"meeting_minutes","label":"晨会纪要","minimum":1,"recommendedMaximum":2}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_OTA_CHECK','OTA检查','检查房价房态、在线咨询问答、违规看板和评价回复。',4,'DAY',TIME '10:30',TIME '11:00',TIME '11:00','INSPECTION',true,'[{"checkpointCode":"ota_calendar_screenshot","label":"包含电脑日历的OTA截图","minimum":1}]','{}','{"structuredChecks":["RATE_INVENTORY","ONLINE_QA","VIOLATION_DASHBOARD","REVIEW_REPLY"],"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_PUBLIC_AREA','公共区域巡查','检查大堂、前台、水吧、行李房、健身房及洗衣房。',5,'DAY',TIME '11:00',TIME '11:30',TIME '11:30','INSPECTION',true,'[{"checkpointCode":"lobby","label":"大堂","minimum":2,"recommendedMaximum":5},{"checkpointCode":"front_desk_inside","label":"前台内侧","minimum":1},{"checkpointCode":"front_desk_outside","label":"前台外侧","minimum":1},{"checkpointCode":"service_facilities","label":"百宝箱、水吧、行李房、健身房","minimumPerConfiguredFacility":1,"configuredFacilityInstanceField":"serviceFacilityIds","instanceLabel":"设施编号"},{"checkpointCode":"laundry","label":"洗衣房内外及滤网","minimum":2}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_KITCHEN_INSPECTION','后厨巡检','检查菜品标签、消毒记录和食品存放日期。',6,'DAY',TIME '11:30',TIME '12:00',TIME '12:00','INSPECTION',true,'[{"checkpointCode":"food_labels","label":"菜品标签","minimum":1,"recommendedMaximum":5},{"checkpointCode":"disinfection_log","label":"消毒记录","minimum":1,"recommendedMaximum":2},{"checkpointCode":"food_storage_date","label":"食品存放及日期","minimum":3,"recommendedMaximum":5}]','{"requiresFacility":"KITCHEN"}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"sharedGenerationMode":"ONE_EXPECTATION_PER_ORG_BUSINESS_DATE","delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_THREE_FOLLOWUPS','三跟进订单分配与检查','登记订单、负责人、截止时间、状态及异常。',7,'DAY',TIME '12:30',TIME '14:00',TIME '14:00','SCHEDULED_RECORD',true,'[{"checkpointCode":"followup_orders","label":"脱敏订单跟进证据","minimum":1}]','{}','{"structuredFields":["MASKED_ORDER_ID","OWNER","DEADLINE","STATUS"],"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_ROOM_INSPECTION','客房查房抽检','抽检不少于完成客房20%，且每名做房员工至少5间。',8,'DAY',TIME '14:00',TIME '16:00',TIME '16:00','INSPECTION',true,'[{"checkpointCode":"room_overview","label":"逐房全景照片","minimumPerInstance":1,"instanceField":"roomNumbers","instanceLabel":"房号"},{"checkpointCode":"room_issue","label":"问题照片","minimum":0,"unlimited":true},{"checkpointCode":"dirty_room","label":"脏房全景","minimumPerInstance":1,"instanceField":"dirtyRoomNumbers"}]','{}','{"requiredRoomCountFormula":"max(ceil(completedRooms*0.20),onDutyHousekeepers*5)","sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_AFTERNOON_TEA','下午茶巡检','检查陈列、餐饮、区域和设备。',9,'DAY',TIME '14:30',TIME '15:00',TIME '15:00','INSPECTION',true,'[{"checkpointCode":"afternoon_tea","label":"下午茶现场","minimum":1}]','{"requiresFacility":"AFTERNOON_TEA"}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"sharedGenerationMode":"ONE_EXPECTATION_PER_ORG_BUSINESS_DATE","delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_REVIEW_TRAINING','好评跟进与员工培训','核查入住前中记录，填写离店回访达成数并上传培训记录。',10,'DAY',TIME '15:30',TIME '18:00',TIME '18:00','SCHEDULED_RECORD',true,'[{"checkpointCode":"review_followup","label":"好评跟进记录","minimum":1},{"checkpointCode":"training_record","label":"培训记录","minimum":1}]','{}','{"noInducedReviews":true,"noGuestPhoneOperation":true,"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_CUSTOMER_FORMS','续住沟通与投诉意见表','上传续住客户沟通及投诉意见资料。',11,'DAY',TIME '16:00',TIME '17:00',TIME '17:00','SCHEDULED_RECORD',true,'[{"checkpointCode":"customer_forms","label":"续住沟通及投诉意见资料","minimum":1}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_OPERATION_FILES','工程、培训、沟通及库存汇总','上传并核实维修、培训、沟通考核和库存出入库记录。',12,'DAY',TIME '17:00',TIME '18:00',TIME '18:00','METRIC_REVIEW',true,'[{"checkpointCode":"operation_files","label":"工程维修、沟通考核、库存资料","minimum":1},{"checkpointCode":"training_record_reference","label":"复用前项培训记录","minimum":0,"allowReference":true}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}'),
('AGM_WITHOUT_SUPERVISOR','AGM_NS_EVENING_LIGHT_BAR','晚间灯光、投影与吧台巡检','检查店内与门头亮化、音乐投影及吧台餐具。',13,'DAY',TIME '18:00',TIME '19:00',TIME '19:00','INSPECTION',true,'[{"checkpointCode":"indoor_lighting","label":"室内灯光","minimum":1,"recommendedMaximum":5},{"checkpointCode":"bar_inside","label":"吧台内","minimum":1,"recommendedMaximum":4},{"checkpointCode":"popcorn_machine","label":"爆米花机","minimum":1},{"checkpointCode":"counter","label":"台面","minimum":1},{"checkpointCode":"facade_lighting","label":"室外外墙亮化","minimum":0,"recommendedMaximum":3},{"checkpointCode":"storefront_outdoor_lighting","label":"门头及室外灯光","minimum":0,"recommendedMaximum":3}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true,"delegationAllowed":true,"oneClickRestTransfer":true}');


-- Front-office supervisor.
INSERT INTO v56_item_seed VALUES
('FO_SUPERVISOR','FO_AREA_HANDOVER','前厅区域巡检与交班跟进','巡检前厅、核对交接班和账务签字。',1,'DAY',TIME '10:00',TIME '10:30',TIME '10:30','INSPECTION',true,'[{"checkpointCode":"front_inside_panorama","label":"前台内部全景","minimum":1},{"checkpointCode":"counter","label":"台面","minimum":1},{"checkpointCode":"front_outside_panorama","label":"前台外部全景","minimum":1},{"checkpointCode":"available_counter","label":"可用前台台面","minimum":1},{"checkpointCode":"handover_signature","label":"交接班签字","minimum":1},{"checkpointCode":"account_signature","label":"账务核对签字","minimum":1}]','{}','{}'),
('FO_SUPERVISOR','FO_OTA_CHECK','OTA检查','检查房价房态、咨询问答、违规看板和评价回复。',2,'DAY',TIME '10:30',TIME '11:00',TIME '11:00','INSPECTION',true,'[{"checkpointCode":"ota_calendar_screenshot","label":"包含电脑日历的截图","minimum":1}]','{}','{"structuredChecks":["RATE_INVENTORY","ONLINE_QA","VIOLATION_DASHBOARD","REVIEW_REPLY"]}'),
('FO_SUPERVISOR','FO_PUBLIC_AREA','公共区域巡查','检查大堂、服务设施、洗衣房及滤网。',3,'DAY',TIME '11:00',TIME '11:30',TIME '11:30','INSPECTION',true,'[{"checkpointCode":"lobby","label":"大堂","minimum":2,"recommendedMaximum":5},{"checkpointCode":"service_facilities","label":"百宝箱、水吧、行李房、健身房","minimumPerConfiguredFacility":1,"recommendedMaximumPerConfiguredFacility":2,"configuredFacilityInstanceField":"serviceFacilityIds","instanceLabel":"设施编号"},{"checkpointCode":"laundry","label":"洗衣房内外及滤网","minimum":1,"recommendedMaximum":3}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["FRONT_OFFICE_SUPERVISOR","SERVICE_STEWARD"],"firstValidSubmissionWins":true}'),
('FO_SUPERVISOR','FO_THREE_FOLLOWUPS','三跟进订单分配与检查','分配并检查三跟进订单。',4,'DAY',TIME '12:00',TIME '14:00',TIME '14:00','SCHEDULED_RECORD',true,'[{"checkpointCode":"followup_orders","label":"脱敏订单证据","minimum":1}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["ASSISTANT_GENERAL_MANAGER","FRONT_OFFICE_SUPERVISOR"],"firstValidSubmissionWins":true,"structuredFields":["MASKED_ORDER_ID","OWNER","DEADLINE","STATUS"]}'),
('FO_SUPERVISOR','FO_AFTERNOON_TEA','下午茶巡检','巡检下午茶准备和现场。',5,'DAY',TIME '14:30',TIME '15:00',TIME '15:00','INSPECTION',true,'[{"checkpointCode":"afternoon_tea","label":"下午茶现场","minimum":1}]','{"requiresFacility":"AFTERNOON_TEA"}','{"sharedPerOrg":true,"allowedPositionCodes":["ASSISTANT_GENERAL_MANAGER","FRONT_OFFICE_SUPERVISOR"],"firstValidSubmissionWins":true,"sharedGenerationMode":"ONE_EXPECTATION_PER_ORG_BUSINESS_DATE"}'),
('FO_SUPERVISOR','FO_CTRIP_TRAINING','携程回访与员工培训','登记回访数量和结果并上传培训记录。',6,'DAY',TIME '15:30',TIME '18:00',TIME '18:00','SCHEDULED_RECORD',true,'[{"checkpointCode":"ctrip_followup","label":"携程回访记录","minimum":1},{"checkpointCode":"training_record","label":"员工培训记录","minimum":1}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["ASSISTANT_GENERAL_MANAGER","FRONT_OFFICE_SUPERVISOR"],"firstValidSubmissionWins":true,"noInducedReviews":true,"noGuestPhoneOperation":true}'),
('FO_SUPERVISOR','FO_DAILY_SUMMARY','前厅工作汇总与交接','汇总好评率、客诉、意见表及待办交接。',7,'DAY',TIME '18:00',TIME '19:00',TIME '19:00','METRIC_REVIEW',true,'[{"checkpointCode":"summary_files","label":"汇总、意见及交接资料","minimum":1}]','{}','{"structuredFields":["TODAY_REVIEW_COUNT","POSITIVE_REVIEW_COUNT","POSITIVE_RATE","COMPLAINT_COUNT","CLOSED_COUNT","PENDING_HANDOVER"]}');

-- Service steward remains an independent position.
INSERT INTO v56_item_seed VALUES
('SERVICE_STEWARD','SS_HANDOVER','接班与服务待办确认','逐项承接或退回上一班服务待办。',1,'DAY',TIME '09:30',TIME '10:00',TIME '10:00','REVIEW_APPROVAL',false,'[]','{}','{"handoverConfirmationRequired":true}'),
('SERVICE_STEWARD','SS_PREARRIVAL','预抵及重点服务准备','有特殊需求时准备脱敏服务清单。',2,'DAY',TIME '10:00',TIME '11:00',TIME '11:00','SCHEDULED_RECORD',true,'[{"checkpointCode":"service_list","label":"脱敏重点服务清单","minimum":1}]','{"condition":"HAS_SPECIAL_ARRIVAL_REQUIREMENT","excludeFromCompletionWhenNotApplicable":true}','{"privacyMaskRequired":true}'),
('SERVICE_STEWARD','SS_PUBLIC_AREA','公共区域巡查','与前厅主管共享公共区域巡查。',3,'DAY',TIME '11:00',TIME '11:30',TIME '11:30','INSPECTION',true,'[{"checkpointCode":"configured_public_areas","label":"门店已配置公共区域","minimumPerConfiguredFacility":1,"configuredFacilityInstanceField":"publicAreaFacilityIds","instanceLabel":"区域编号"}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["FRONT_OFFICE_SUPERVISOR","SERVICE_STEWARD"],"firstValidSubmissionWins":true}'),
('SERVICE_STEWARD','SS_RECEPTION_FOLLOWUP','到店接待与三跟进执行','关联脱敏订单、服务单及处理结果。',4,'DAY',TIME '11:30',TIME '13:30',TIME '13:30','SCHEDULED_RECORD',true,'[{"checkpointCode":"service_followup","label":"脱敏服务跟进证据","minimum":1}]','{}','{"privacyMaskRequired":true,"structuredFields":["MASKED_ORDER_ID","OWNER","DEADLINE","RESULT"]}'),
('SERVICE_STEWARD','SS_AFTERNOON_TEA','下午茶与宾客体验服务','记录现场服务和宾客意见。',5,'DAY',TIME '14:30',TIME '15:30',TIME '15:30','SCHEDULED_RECORD',true,'[{"checkpointCode":"afternoon_tea_service","label":"下午茶现场及意见","minimum":1,"recommendedMaximum":3}]','{"requiresFacility":"AFTERNOON_TEA"}','{}'),
('SERVICE_STEWARD','SS_INHOUSE_FOLLOWUP','在住及续住需求跟进','记录需求、处理人、时限和结果。',6,'DAY',TIME '15:30',TIME '17:30',TIME '17:30','SCHEDULED_RECORD',false,'[]','{}','{"structuredFields":["REQUEST","OWNER","DEADLINE","RESULT"]}'),
('SERVICE_STEWARD','SS_COMPLAINT_HANDOVER','客诉意见与未完成事项交接','未闭环事项必须指定接收人确认。',7,'DAY',TIME '17:30',TIME '18:30',TIME '18:30','REVIEW_APPROVAL',true,'[{"checkpointCode":"complaint_handover","label":"客诉及交接记录","minimum":0}]','{}','{"handoverConfirmationRequired":true,"returnReasonRequired":true,"privacyMaskRequired":true}');

-- Front desk is shift-relative. shiftTiming is authoritative; fixed TIME columns
-- stay null so the actual work_duty_period controls availability and due time.
INSERT INTO v56_item_seed VALUES
('FRONT_DESK','FD_WORKSTATION_READY','到岗与工作台准备','检查仪容、工作台及前台设备。',1,'SHIFT',NULL,NULL,NULL,'INSPECTION',true,'[{"checkpointCode":"appearance","label":"仪容","minimum":1},{"checkpointCode":"workstation","label":"工作台及设备","minimum":1}]','{}','{"shiftTiming":{"availableAnchor":"SHIFT_START","availableOffsetMinutes":0,"dueAnchor":"SHIFT_START","dueOffsetMinutes":15}}'),
('FRONT_DESK','FD_SHIFT_HANDOVER_IN','接班确认','核对备用金、票据、钥匙房卡、客诉维修和待办并双方签名。',2,'SHIFT',NULL,NULL,NULL,'REVIEW_APPROVAL',true,'[{"checkpointCode":"handover_in","label":"接班签字记录","minimum":1}]','{}','{"shiftTiming":{"availableAnchor":"SHIFT_START","availableOffsetMinutes":0,"dueAnchor":"SHIFT_START","dueOffsetMinutes":30},"handoverConfirmationRequired":true}'),
('FRONT_DESK','FD_PMS_OTA_PRECHECK','PMS与OTA班前检查','检查房态、房价库存和异常。',3,'SHIFT',NULL,NULL,NULL,'INSPECTION',true,'[{"checkpointCode":"pms_ota_masked","label":"脱敏PMS或OTA证据","minimum":1}]','{}','{"shiftTiming":{"availableAnchor":"SHIFT_START","availableOffsetMinutes":15,"dueAnchor":"SHIFT_START","dueOffsetMinutes":45},"privacyMaskRequired":true}'),
('FRONT_DESK','FD_RECEPTION_SERVICE','接待与客户诉求处理','正常交易自动汇总，仅补充异常客诉及待跟进。',4,'SHIFT',NULL,NULL,NULL,'EVENT_RECORD',false,'[]','{}','{"shiftTiming":{"availableAnchor":"SHIFT_START","availableOffsetMinutes":30,"dueAnchor":"SHIFT_END","dueOffsetMinutes":-60},"privacyMaskRequired":true}'),
('FRONT_DESK','FD_MIDSHIFT_REVIEW','房态与待办复核','复核房态差异、三跟进、维修停用房及未闭环诉求。',5,'SHIFT',NULL,NULL,NULL,'METRIC_REVIEW',false,'[]','{}','{"shiftTiming":{"availableAnchor":"SHIFT_START","availableOffsetMinutes":30,"dueAnchor":"SHIFT_END","dueOffsetMinutes":-60}}'),
('FRONT_DESK','FD_OPEN_ITEMS_CLOSEOUT','未闭环事项收口','指定接收人、完成期限和交接说明。',6,'SHIFT',NULL,NULL,NULL,'REVIEW_APPROVAL',false,'[]','{}','{"shiftTiming":{"availableAnchor":"SHIFT_END","availableOffsetMinutes":-90,"dueAnchor":"SHIFT_END","dueOffsetMinutes":-45},"handoverConfirmationRequired":true}'),
('FRONT_DESK','FD_CASH_ITEM_RECONCILIATION','账务与物品核对','核对收款、押金、退款、发票、备用金、房卡及钥匙。',7,'SHIFT',NULL,NULL,NULL,'METRIC_REVIEW',true,'[{"checkpointCode":"reconciliation","label":"脱敏账务核对记录","minimum":1}]','{}','{"shiftTiming":{"availableAnchor":"SHIFT_END","availableOffsetMinutes":-60,"dueAnchor":"SHIFT_END","dueOffsetMinutes":-20},"differenceReasonRequired":true,"highRiskImmediateEscalation":true}'),
('FRONT_DESK','FD_SHIFT_HANDOVER_OUT','交班与班次日报','逐项交接并由下一班签收。',8,'SHIFT',NULL,NULL,NULL,'REVIEW_APPROVAL',true,'[{"checkpointCode":"handover_out","label":"交班签收记录","minimum":1}]','{}','{"shiftTiming":{"availableAnchor":"SHIFT_END","availableOffsetMinutes":-30,"dueAnchor":"SHIFT_END","dueOffsetMinutes":15},"handoverConfirmationRequired":true,"returnReasonRequired":true}');

-- Housekeeping supervisor and room attendant.
INSERT INTO v56_item_seed VALUES
('HK_SUPERVISOR','HKS_MORNING_ASSIGNMENT','晨会、出勤与做房分配','登记出勤、房号、优先房和时限。',1,'DAY',TIME '08:00',TIME '09:00',TIME '09:00','SCHEDULED_RECORD',true,'[{"checkpointCode":"meeting_assignment","label":"晨会和任务分配记录","minimum":1,"allowReference":true}]','{}','{}'),
('HK_SUPERVISOR','HKS_READINESS_CHECK','房态、布草、耗品与设备准备','核对房态表、领用台账和差异。',2,'DAY',TIME '09:00',TIME '10:00',TIME '10:00','INSPECTION',true,'[{"checkpointCode":"readiness","label":"脱敏房态及物资准备记录","minimum":1}]','{}','{"privacyMaskRequired":true}'),
('HK_SUPERVISOR','HKS_PROGRESS','做房进度与急房跟进','汇总完成、进行中、延误和受阻房间。',3,'DAY',TIME '10:00',TIME '12:30',TIME '12:30','METRIC_REVIEW',false,'[]','{}','{}'),
('HK_SUPERVISOR','HKS_QUALITY_REWORK','客房质量检查与返工验收','按配置比例逐房检查，问题返工后再次验收。',4,'DAY',TIME '12:30',TIME '16:30',TIME '16:30','INSPECTION',true,'[{"checkpointCode":"room_quality","label":"逐房质量证据","minimumPerInstance":1,"instanceField":"roomNumbers"},{"checkpointCode":"rework","label":"返工复验","minimum":0,"unlimited":true}]','{}','{"inspectionRatioFromStoreConfig":true,"reworkReinspectionRequired":true}'),
('HK_SUPERVISOR','HKS_RESOURCE_REVIEW','耗品、布草、遗留物及维修审核','审核员工台账和差异。',5,'DAY',TIME '16:30',TIME '17:30',TIME '17:30','REVIEW_APPROVAL',false,'[]','{}','{"reviewerPositionConfig":["HOUSEKEEPING_SUPERVISOR","ASSISTANT_GENERAL_MANAGER","GENERAL_MANAGER"],"differenceReasonRequired":true}'),
('HK_SUPERVISOR','HKS_DAILY_HANDOVER','客房日报与交接','汇总做房、合格率、返工、库存差异及工程问题。',6,'DAY',TIME '17:30',TIME '18:00',TIME '18:00','METRIC_REVIEW',false,'[]','{}','{}'),

('HK_ATTENDANT','HKA_ITEM_ISSUE','物品领用登记','按门店耗品、清洁用品和布草配置填写数量。',1,'DAY',TIME '08:00',TIME '09:10',TIME '09:10','SCHEDULED_RECORD',false,'[]','{}','{"inventoryMovement":"STORE_TO_EMPLOYEE_ON_HAND","quantityOnly":true,"reviewerPositionConfig":["HOUSEKEEPING_SUPERVISOR","ASSISTANT_GENERAL_MANAGER","GENERAL_MANAGER"]}'),
('HK_ATTENDANT','HKA_CART_PREPARATION','布草车与工作车整理','拍摄布草车和工作车并登记是否发现遗留物。',2,'DAY',TIME '11:30',TIME '12:30',TIME '12:30','INSPECTION',true,'[{"checkpointCode":"linen_cart","label":"布草车","minimum":1},{"checkpointCode":"work_cart","label":"工作车","minimum":1}]','{}','{}'),
('HK_ATTENDANT','HKA_USAGE_RETURN','耗品布草使用与车辆归位','登记使用、退回、期末在手及损坏差异。',3,'DAY',TIME '16:30',TIME '18:00',TIME '18:00','METRIC_REVIEW',true,'[{"checkpointCode":"cart_parked","label":"布草车停放","minimum":1},{"checkpointCode":"cart_inside","label":"车内整理","minimum":1}]','{}','{"balanceFormula":"ISSUED=USED+RETURNED+ENDING_ON_HAND+DAMAGED_OR_VARIANCE","differenceReasonRequired":true}'),
('HK_ATTENDANT','HKA_LOST_PROPERTY','遗留物品移交','填写物品、数量、房号、时间、位置并由接收人签收。',4,'DAY',TIME '16:30',TIME '18:00',TIME '18:00','REVIEW_APPROVAL',true,'[{"checkpointCode":"lost_property","label":"遗留物照片","minimum":1}]','{"conditionField":"lostPropertyOccurred","equals":true,"excludeFromCompletionWhenNotApplicable":true}','{"handoverConfirmationRequired":true,"returnReasonRequired":true}'),
('HK_ATTENDANT','HKA_DAILY_REPORT','客房服务员日报','填写退房、续住数量并自动带入领用使用台账。',5,'DAY',TIME '16:30',TIME '18:00',TIME '18:00','METRIC_REVIEW',false,'[]','{}','{"autoSources":["ISSUE_LEDGER","USAGE_LEDGER","LOST_PROPERTY","ROOM_COUNTS"]}');

-- Night shift belongs to the business date on which the 17:00 shift starts.
-- Due times below 17:00 are intentionally interpreted as next-day by the V5
-- work expectation materializer.
INSERT INTO v56_item_seed VALUES
('NIGHT_ATTENDANT','NSA_CART_PREPARATION','班前布草车与工作车准备','整理布草车和工作车并检查待移交遗留物。',1,'DAY',TIME '17:00',TIME '18:30',TIME '18:30','INSPECTION',true,'[{"checkpointCode":"linen_cart","label":"布草车","minimum":1},{"checkpointCode":"work_cart","label":"工作车","minimum":1}]','{}','{"businessDayAnchor":"SHIFT_START_DATE"}'),
('NIGHT_ATTENDANT','NSA_ITEM_ISSUE','班前耗品布草领用','按门店配置填写期初领用数量。',2,'DAY',TIME '17:00',TIME '18:30',TIME '18:30','SCHEDULED_RECORD',false,'[]','{}','{"businessDayAnchor":"SHIFT_START_DATE","inventoryMovement":"STORE_TO_EMPLOYEE_ON_HAND","quantityOnly":true}'),
('NIGHT_ATTENDANT','NSA_EQUIPMENT_FIRST','第一次设备巡查','逐台拍摄空气能机组温度及水位。',3,'DAY',TIME '18:00',TIME '19:00',TIME '19:00','INSPECTION',true,'[{"checkpointCode":"equipment_temperature","label":"每台设备温度","minimumPerConfiguredAsset":1,"configuredAssetInstanceField":"equipmentAssetIds","instanceLabel":"设备编号"},{"checkpointCode":"water_level","label":"水位","minimum":1}]','{}','{"businessDayAnchor":"SHIFT_START_DATE","employeeReadingConfirmationRequired":true,"highRiskImmediateEscalation":true}'),
('NIGHT_ATTENDANT','NSA_PUBLIC_CLEANING','公共区域打扫自检与设备间巡检','覆盖门头、大堂、洗衣房、潮玩区、楼层过道和电梯间。',4,'DAY',TIME '18:00',TIME '20:00',TIME '20:00','INSPECTION',true,'[{"checkpointCode":"storefront","label":"门头灯光","minimum":1},{"checkpointCode":"lobby","label":"前厅大堂","minimum":2,"recommendedMaximum":5},{"checkpointCode":"laundry","label":"洗衣房","minimum":1},{"checkpointCode":"trendy_play_area","label":"潮玩区","minimum":2,"conditionalFacility":"TRENDY_PLAY_AREA"},{"checkpointCode":"floor_corridor_elevator","label":"楼层过道电梯间","minimum":5,"recommendedMaximum":8}]','{}','{"businessDayAnchor":"SHIFT_START_DATE"}'),
('NIGHT_ATTENDANT','NSA_NIGHT_PUBLIC_PATROL','夜间公共区域巡查','逐层巡查过道、电梯间、电梯、大厅露台和公共卫生间。',5,'DAY',TIME '21:00',TIME '01:00',TIME '01:00','INSPECTION',true,'[{"checkpointCode":"floor_corridor","label":"各层过道","minimumPerHotelFloor":2},{"checkpointCode":"floor_elevator_lobby","label":"各层电梯间","minimumPerHotelFloor":1,"recommendedMaximumPerHotelFloor":2},{"checkpointCode":"elevator_inside","label":"电梯内部","minimum":1,"recommendedMaximum":2},{"checkpointCode":"lobby_terrace","label":"大厅和露台","minimum":3,"recommendedMaximum":5},{"checkpointCode":"public_restroom_sink","label":"公共卫生间洗手台","minimum":2}]','{}','{"businessDayAnchor":"SHIFT_START_DATE","crossMidnight":true}'),
('NIGHT_ATTENDANT','NSA_EQUIPMENT_SECOND','第二次设备巡查','再次拍摄设备温度和水位并与第一次对比。',6,'DAY',TIME '00:00',TIME '02:00',TIME '02:00','INSPECTION',true,'[{"checkpointCode":"equipment_temperature","label":"每台设备温度","minimumPerConfiguredAsset":1,"configuredAssetInstanceField":"equipmentAssetIds","instanceLabel":"设备编号"},{"checkpointCode":"water_level","label":"水位","minimum":1}]','{}','{"businessDayAnchor":"SHIFT_START_DATE","crossMidnight":true,"compareWithItemCode":"NSA_EQUIPMENT_FIRST","employeeReadingConfirmationRequired":true,"highRiskImmediateEscalation":true}'),
('NIGHT_ATTENDANT','NSA_FINAL_PUBLIC_RECHECK','后半夜公共区域收尾复查','复查门头、大堂、洗衣房、潮玩区及楼层。',7,'DAY',TIME '01:30',TIME '02:00',TIME '02:00','INSPECTION',true,'[{"checkpointCode":"storefront","label":"门头灯光","minimum":1},{"checkpointCode":"lobby","label":"前厅大堂","minimum":2,"recommendedMaximum":5},{"checkpointCode":"laundry","label":"洗衣房","minimum":1},{"checkpointCode":"trendy_play_area","label":"潮玩区","minimum":2,"conditionalFacility":"TRENDY_PLAY_AREA"},{"checkpointCode":"floor_corridor_elevator","label":"楼层过道电梯间","minimum":5,"recommendedMaximum":8}]','{}','{"businessDayAnchor":"SHIFT_START_DATE","crossMidnight":true}'),
('NIGHT_ATTENDANT','NSA_LOST_PROPERTY','遗留物品移交','仅发现遗留物时登记并由接收人签收。',8,'DAY',TIME '01:00',TIME '02:00',TIME '02:00','REVIEW_APPROVAL',true,'[{"checkpointCode":"lost_property","label":"遗留物照片","minimum":1}]','{"conditionField":"lostPropertyOccurred","equals":true,"excludeFromCompletionWhenNotApplicable":true}','{"businessDayAnchor":"SHIFT_START_DATE","crossMidnight":true,"handoverConfirmationRequired":true}'),
('NIGHT_ATTENDANT','NSA_DAILY_HANDOVER','夜班日报与特殊事项交接','汇总做房、耗品布草和特殊事项并选择接收人。',9,'DAY',TIME '01:00',TIME '02:00',TIME '02:00','REVIEW_APPROVAL',false,'[]','{}','{"businessDayAnchor":"SHIFT_START_DATE","crossMidnight":true,"handoverConfirmationRequired":true,"copyPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER","HOUSEKEEPING_SUPERVISOR"],"balanceFormula":"ISSUED=USED+RETURNED+ENDING_ON_HAND+DAMAGED_OR_VARIANCE"}');

-- Breakfast lead exists only in stores that provide breakfast service.
INSERT INTO v56_item_seed VALUES
('BREAKFAST_LEAD','BL_DISH_PREPARATION','开餐准备：菜品出品','检查明档、摆餐、菜品和客户就餐区。',1,'DAY',TIME '06:00',TIME '07:00',TIME '07:00','INSPECTION',true,'[{"checkpointCode":"display_area","label":"明档陈列区","minimum":1},{"checkpointCode":"buffet_area","label":"摆餐区","minimum":1,"recommendedMaximum":3},{"checkpointCode":"dishes","label":"菜品","minimum":3,"recommendedMaximum":6},{"checkpointCode":"dining_area","label":"客户就餐区","minimum":2,"recommendedMaximum":4}]','{"requiresBreakfastService":true}','{}'),
('BREAKFAST_LEAD','BL_DINING_READY','开餐准备：用餐区','确认桌椅整齐和桌面整洁。',2,'DAY',TIME '06:00',TIME '07:00',TIME '07:00','INSPECTION',true,'[{"checkpointCode":"dining_ready","label":"早餐用餐区","minimum":3,"recommendedMaximum":5}]','{"requiresBreakfastService":true}','{}'),
('BREAKFAST_LEAD','BL_WARMING_READY','开餐准备：保温设备','每台设备上传当前温度照片。',3,'DAY',TIME '06:00',TIME '07:00',TIME '07:00','INSPECTION',true,'[{"checkpointCode":"warming_temperature","label":"保温设备温度","minimumPerConfiguredAsset":1,"configuredAssetInstanceField":"warmingEquipmentIds","instanceLabel":"设备编号"}]','{"requiresBreakfastService":true}','{"employeeReadingConfirmationRequired":true,"sopThresholdFromStoreConfig":true,"highRiskImmediateEscalation":true}'),
('BREAKFAST_LEAD','BL_GUEST_FEEDBACK','客户意见收集','上传意见记录并填写收集人数。',4,'DAY',TIME '07:00',TIME '11:30',TIME '11:30','SCHEDULED_RECORD',true,'[{"checkpointCode":"feedback_record","label":"意见记录表或文件","minimum":1}]','{"requiresBreakfastService":true}','{"allowNoFeedbackWithReason":true,"feedbackCategories":["TASTE","TEMPERATURE","VARIETY","HYGIENE","SERVICE"]}'),
('BREAKFAST_LEAD','BL_CLOSE_PREPARATION','收餐与下班整理','检查厨房、灶台、工作台、消毒柜、洗碗池和冰箱。',5,'DAY',TIME '12:30',TIME '14:00',TIME '14:00','INSPECTION',true,'[{"checkpointCode":"kitchen_panorama","label":"厨房全景","minimum":3,"recommendedMaximum":5},{"checkpointCode":"stove","label":"灶台","minimum":1,"recommendedMaximum":3},{"checkpointCode":"worktop","label":"工作台面","minimum":1,"recommendedMaximum":4},{"checkpointCode":"disinfection_cabinet","label":"消毒柜内部","minimum":1},{"checkpointCode":"sink","label":"洗碗池","minimum":1},{"checkpointCode":"refrigerator","label":"冰箱内部","minimum":1,"recommendedMaximum":4}]','{"requiresBreakfastService":true}','{}'),
('BREAKFAST_LEAD','BL_DAILY_REPORT','早餐日报','填写客户人数、早餐券数量和员工早餐人数。',6,'DAY',TIME '12:50',TIME '14:10',TIME '14:10','METRIC_REVIEW',false,'[]','{"requiresBreakfastService":true}','{"structuredFields":["GUEST_BREAKFAST_COUNT","BREAKFAST_VOUCHER_COUNT","EMPLOYEE_BREAKFAST_COUNT"],"differenceReasonRequired":true}');

-- Store manager V3 replaces V47 without dropping the manager's independent
-- evening patrol/CCTV duties.  Overlapping manager/assistant/front-office work
-- shares semantic keys and is materialized once per hotel business date.
INSERT INTO v56_item_seed VALUES
('GM_DAILY','GM_BREAKFAST_INSPECTION_V56','早餐厅巡查','检查明档、摆餐、菜品、就餐区和保温温度。',1,'DAY',TIME '07:00',TIME '10:30',TIME '10:30','INSPECTION',true,
 '[{"checkpointCode":"breakfast_overview","label":"出餐、摆盘和用餐区","minimum":3,"recommendedMaximum":10},{"checkpointCode":"warming_temperature","label":"保温设备当前温度","minimumPerConfiguredAsset":1,"configuredAssetInstanceField":"warmingEquipmentIds","instanceLabel":"设备编号"}]','{"requiresBreakfastService":true}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true}'),
('GM_DAILY','GM_APPEARANCE_MEETING_V56','仪容仪表与晨会','拍摄全体员工仪容仪表并上传晨会纪要。',2,'DAY',TIME '08:00',TIME '10:30',TIME '10:30','INSPECTION',true,
 '[{"checkpointCode":"full_body","label":"全员全身照","minimum":1},{"checkpointCode":"recorder_radio","label":"记录仪和对讲机佩戴","minimum":1,"recommendedMaximum":2},{"checkpointCode":"meeting_minutes","label":"晨会纪要","minimum":1,"recommendedMaximum":2}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true}'),
('GM_DAILY','GM_PUBLIC_AREA_AM_V56','上午公共区域巡查','检查大堂、前台、楼层公区、洗衣房和门店配置功能区。',3,'DAY',TIME '09:30',TIME '11:30',TIME '11:30','INSPECTION',true,
 '[{"checkpointCode":"configured_public_areas","label":"门店配置公共区域","minimumPerConfiguredFacility":1,"configuredFacilityInstanceField":"publicAreaFacilityIds","instanceLabel":"区域编号"}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER","FRONT_OFFICE_SUPERVISOR","SERVICE_STEWARD"],"firstValidSubmissionWins":true}'),
('GM_DAILY','GM_FRONT_DESK_COACHING_V56','前台带教与三跟进','完成前台带教、客诉处理和三跟进订单检查。',4,'DAY',TIME '11:30',TIME '14:30',TIME '14:30','SCHEDULED_RECORD',false,
 '[]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER","FRONT_OFFICE_SUPERVISOR"],"firstValidSubmissionWins":true,"structuredFields":["MASKED_ORDER_ID","OWNER","DEADLINE","STATUS"]}'),
('GM_DAILY','GM_ROOM_INSPECTION_V56','客房查房','查房5间、走脏房2间；逐房填写房号并分别上传不限张数的对应照片。',5,'DAY',TIME '14:30',TIME '17:30',TIME '17:30','INSPECTION',true,
 '[{"checkpointCode":"room_inspection","label":"查房","requiredInstances":5,"instanceField":"roomInspectionRoomNumbers","minimumPerInstance":1,"unlimited":true},{"checkpointCode":"dirty_room","label":"走脏房","requiredInstances":2,"instanceField":"dirtyRoomNumbers","minimumPerInstance":1,"unlimited":true}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true}'),
('GM_DAILY','GM_PEOPLE_CUSTOMER_FORMS_V56','人员与客户沟通记录','按实际发生上传员工沟通、培训、续住沟通及投诉意见表。',6,'DAY',TIME '17:30',TIME '18:00',TIME '18:00','SCHEDULED_RECORD',false,
 '[{"checkpointCode":"communication_forms","label":"当日实际发生的沟通及培训记录","minimum":0,"unlimited":true}]','{}','{"sharedPerOrg":true,"allowedPositionCodes":["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER"],"firstValidSubmissionWins":true}'),
('GM_DAILY','GM_PUBLIC_AREA_PM_V56','晚间公共区域巡查','对晚间营业状态和公区进行独立复查留证。',7,'DAY',TIME '18:00',TIME '19:00',TIME '19:00','INSPECTION',true,
 '[{"checkpointCode":"evening_public_areas","label":"晚间公共区域","minimumPerConfiguredFacility":1,"configuredFacilityInstanceField":"eveningPublicAreaFacilityIds","instanceLabel":"区域编号"}]','{}','{"allowedPositionCodes":["GENERAL_MANAGER"]}'),
('GM_DAILY','GM_CCTV_REVIEW_V56','监控回放接待标准抽查','查看昨日凌晨及白天管理层不在期间的客户接待标准。',8,'DAY',TIME '18:00',TIME '19:00',TIME '19:00','INSPECTION',true,
 '[{"checkpointCode":"cctv_review","label":"脱敏后的监控回放查看截图","minimum":1,"privacyMaskRequired":true}]','{}','{"allowedPositionCodes":["GENERAL_MANAGER"],"privacyMaskRequired":true,"structuredFields":["CUSTOMER_RECEPTION_COUNT","SERVICE_AWARENESS","RECEPTION_PROCESS"],"serviceAwarenessOptions":["达标","需整改"],"receptionProcessOptions":["正确","不正确"]}');

-- Build item policies from the frozen seed. The assistant mode in
-- applicability_policy is mutually exclusive at the hotel profile level.
INSERT INTO work_package_item
    (id,tenant_id,work_package_version_id,item_code,name,description,item_type,
     form_version_id,sort_order,required,period_type,timezone_mode,
     work_window_start,work_window_end,due_local_time,grace_minutes,weekdays,
     holiday_policy,waiver_allowed,target_granularity,review_mode,
     submission_policy,reminder_policy,report_policy,applicability_policy,execution_policy)
SELECT (md5('V56:item:'||item.variant_code||':'||item.item_code))::uuid,
       '10000000-0000-0000-0000-000000000001',
       (md5('V56:wpv:'||item.variant_code))::uuid,
       item.item_code,item.item_name,item.item_description,item.item_type,
       (md5('V56:form-version:'||form_seed.form_code))::uuid,
       item.sort_order,true,item.period_type,'HOTEL',item.window_start,item.window_end,
       item.due_time,0,ARRAY[1,2,3,4,5,6,7]::smallint[],'INCLUDE',false,
       'ASSIGNMENT_ORG','MANUAL',
       pg_temp.v56_submission_policy(item.evidence,item.attachment_required),
       pg_temp.v56_reminder_policy(),pg_temp.v56_report_policy(item.item_name),
       item.applicability || CASE WHEN package.assistant_mode IS NULL THEN '{}'::jsonb
                                  ELSE jsonb_build_object('assistantWorkTemplateMode',package.assistant_mode) END,
       item.execution || jsonb_build_object(
           'enabled',true,
           'aiExtractionRequiredForAttachments',false,
           'employeeConfirmsAiResult',false,
           'evidenceOriginalRetentionDays',5,
           'structuredSummaryRetentionMonths',36
       )
FROM v56_item_seed item
JOIN v56_package_seed package ON package.variant_code=item.variant_code
JOIN v56_form_seed form_seed ON form_seed.position_code=package.position_code
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO work_package_item_standard
    (id,tenant_id,work_package_item_id,standard_version_id,usage_type,weight)
SELECT (md5('V56:item-standard:'||item.variant_code||':'||item.item_code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,
       (md5('V56:item:'||item.variant_code||':'||item.item_code))::uuid,
       '41300000-0000-0000-0000-000000000001'::uuid,'EXECUTION',1
FROM v56_item_seed item
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO work_package_item_responsibility
    (id,tenant_id,work_package_item_id,participant_type,resolver_type,scope_strategy,escalation_level)
SELECT (md5('V56:executor:'||item.variant_code||':'||item.item_code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,
       (md5('V56:item:'||item.variant_code||':'||item.item_code))::uuid,
       'EXECUTOR','CURRENT_ASSIGNMENT','ASSIGNMENT_ORG',0
FROM v56_item_seed item
UNION ALL
SELECT (md5('V56:reviewer:'||item.variant_code||':'||item.item_code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,
       (md5('V56:item:'||item.variant_code||':'||item.item_code))::uuid,
       'REVIEWER','DIRECT_MANAGER_ASSIGNMENT','ANCESTOR_ORG',0
FROM v56_item_seed item
ON CONFLICT (tenant_id,id) DO NOTHING;

-- Every shared item has an explicit semantic key.  The expectation materializer
-- uses this key (not the version-specific item code) to reuse one canonical
-- hotel/business-date expectation across the allowed positions.
UPDATE work_package_item item
SET execution_policy = item.execution_policy || jsonb_build_object(
        'sharedGenerationMode','ONE_EXPECTATION_PER_ORG_BUSINESS_DATE',
        'sharedReportProjectionMode','ACTUAL_EXECUTOR_ONLY',
        'sharedKey', CASE item.item_code
            WHEN 'AGM_WS_BREAKFAST_INSPECTION' THEN 'MANAGEMENT_BREAKFAST_INSPECTION'
            WHEN 'AGM_NS_BREAKFAST_INSPECTION' THEN 'MANAGEMENT_BREAKFAST_INSPECTION'
            WHEN 'GM_BREAKFAST_INSPECTION_V56' THEN 'MANAGEMENT_BREAKFAST_INSPECTION'
            WHEN 'AGM_WS_BREAKFAST_FEEDBACK' THEN 'MANAGEMENT_BREAKFAST_FEEDBACK'
            WHEN 'AGM_NS_BREAKFAST_FEEDBACK' THEN 'MANAGEMENT_BREAKFAST_FEEDBACK'
            WHEN 'AGM_WS_APPEARANCE_MEETING' THEN 'MANAGEMENT_APPEARANCE_MEETING'
            WHEN 'AGM_NS_APPEARANCE_MEETING' THEN 'MANAGEMENT_APPEARANCE_MEETING'
            WHEN 'GM_APPEARANCE_MEETING_V56' THEN 'MANAGEMENT_APPEARANCE_MEETING'
            WHEN 'AGM_WS_KITCHEN_INSPECTION' THEN 'MANAGEMENT_KITCHEN_INSPECTION'
            WHEN 'AGM_NS_KITCHEN_INSPECTION' THEN 'MANAGEMENT_KITCHEN_INSPECTION'
            WHEN 'AGM_WS_ROOM_INSPECTION' THEN 'MANAGEMENT_ROOM_INSPECTION'
            WHEN 'AGM_NS_ROOM_INSPECTION' THEN 'MANAGEMENT_ROOM_INSPECTION'
            WHEN 'GM_ROOM_INSPECTION_V56' THEN 'MANAGEMENT_ROOM_INSPECTION'
            WHEN 'AGM_WS_CUSTOMER_FORMS' THEN 'MANAGEMENT_CUSTOMER_FORMS'
            WHEN 'AGM_NS_CUSTOMER_FORMS' THEN 'MANAGEMENT_CUSTOMER_FORMS'
            WHEN 'GM_PEOPLE_CUSTOMER_FORMS_V56' THEN 'MANAGEMENT_CUSTOMER_FORMS'
            WHEN 'AGM_WS_OPERATION_FILES' THEN 'MANAGEMENT_OPERATION_FILES'
            WHEN 'AGM_NS_OPERATION_FILES' THEN 'MANAGEMENT_OPERATION_FILES'
            WHEN 'AGM_WS_EVENING_LIGHT_BAR' THEN 'MANAGEMENT_EVENING_LIGHT_BAR'
            WHEN 'AGM_NS_EVENING_LIGHT_BAR' THEN 'MANAGEMENT_EVENING_LIGHT_BAR'
            WHEN 'AGM_NS_PUBLIC_AREA' THEN 'PUBLIC_AREA_INSPECTION'
            WHEN 'GM_PUBLIC_AREA_AM_V56' THEN 'PUBLIC_AREA_INSPECTION'
            WHEN 'FO_PUBLIC_AREA' THEN 'PUBLIC_AREA_INSPECTION'
            WHEN 'SS_PUBLIC_AREA' THEN 'PUBLIC_AREA_INSPECTION'
            WHEN 'AGM_NS_THREE_FOLLOWUPS' THEN 'THREE_FOLLOWUPS'
            WHEN 'GM_FRONT_DESK_COACHING_V56' THEN 'THREE_FOLLOWUPS'
            WHEN 'FO_THREE_FOLLOWUPS' THEN 'THREE_FOLLOWUPS'
            WHEN 'AGM_NS_AFTERNOON_TEA' THEN 'AFTERNOON_TEA_INSPECTION'
            WHEN 'FO_AFTERNOON_TEA' THEN 'AFTERNOON_TEA_INSPECTION'
            WHEN 'AGM_NS_REVIEW_TRAINING' THEN 'REVIEW_FOLLOWUP_AND_TRAINING'
            WHEN 'FO_CTRIP_TRAINING' THEN 'REVIEW_FOLLOWUP_AND_TRAINING'
            ELSE item.item_code
        END
    )
WHERE item.tenant_id='10000000-0000-0000-0000-000000000001'
  AND item.work_package_version_id IN (
      SELECT (md5('V56:wpv:'||variant_code))::uuid FROM v56_package_seed
  )
  AND coalesce((item.execution_policy->>'sharedPerOrg')::boolean,false);

-- Public-area work can be completed by any of the configured management/front
-- office roles, regardless of which template first creates the shared row.
UPDATE work_package_item item
SET execution_policy = jsonb_set(
        item.execution_policy,
        '{allowedPositionCodes}',
        '["GENERAL_MANAGER","ASSISTANT_GENERAL_MANAGER","FRONT_OFFICE_SUPERVISOR","SERVICE_STEWARD"]'::jsonb,
        true
    )
WHERE item.tenant_id='10000000-0000-0000-0000-000000000001'
  AND item.execution_policy->>'sharedKey'='PUBLIC_AREA_INSPECTION';

-- A shared row can be created first from the manager or front-office package.
-- Keep the assistant's day-off transfer capability on the canonical row instead
-- of depending on which position happened to materialize it first.
UPDATE work_package_item item
SET execution_policy = item.execution_policy || '{
      "delegationAllowed":true,
      "ownerRestOption":true,
      "oneClickRestTransfer":true
    }'::jsonb
WHERE item.tenant_id='10000000-0000-0000-0000-000000000001'
  AND item.work_package_version_id IN (
      SELECT (md5('V56:wpv:'||variant_code))::uuid FROM v56_package_seed
  )
  AND coalesce((item.execution_policy->>'sharedPerOrg')::boolean,false)
  AND jsonb_exists(
      coalesce(item.execution_policy->'allowedPositionCodes','[]'::jsonb),
      'ASSISTANT_GENERAL_MANAGER'
  );

UPDATE work_package_version
SET lifecycle_status='PUBLISHED',effective_from=date_trunc('day',now())+interval '1 day',
    published_by='19000000-0000-0000-0000-000000000001',published_at=now(),updated_at=now()
WHERE tenant_id='10000000-0000-0000-0000-000000000001'
  AND id IN (SELECT (md5('V56:wpv:'||variant_code))::uuid FROM v56_package_seed)
  AND lifecycle_status='DRAFT';

-- End only the release-owned predecessor allocations after today. Do not touch
-- tenant-created packages and do not fabricate employee assignments.
UPDATE work_package_allocation allocation
SET status = CASE WHEN allocation.valid_from > current_date THEN 'REVOKED'
                  ELSE allocation.status END,
    valid_to = CASE WHEN allocation.valid_from <= current_date THEN current_date
                    ELSE allocation.valid_to END,
    updated_at = now()
FROM work_package_version version
JOIN work_package_definition definition
  ON definition.tenant_id=version.tenant_id
 AND definition.id=version.work_package_definition_id
WHERE allocation.tenant_id='10000000-0000-0000-0000-000000000001'
  AND allocation.tenant_id=version.tenant_id
  AND allocation.work_package_version_id=version.id
  AND definition.code IN (
      'WP-PILOT-GM-DAILY','WP-PILOT-AGM-DAILY','WP-PILOT-FO-SUPERVISOR-DAILY',
      'WP-PILOT-FRONT-DAILY','WP-PILOT-HK-SUPERVISOR-DAILY'
  )
  AND version.id NOT IN (SELECT (md5('V56:wpv:'||variant_code))::uuid FROM v56_package_seed)
  AND allocation.status='ACTIVE'
  AND (allocation.valid_to IS NULL OR allocation.valid_to>current_date);

-- New allocations are derived only from real, active, effective assignments.
-- The assistant LATERAL lookup resolves the closest HOTEL ancestor and selects
-- exactly one mutually-exclusive template mode.
INSERT INTO work_package_allocation
    (id,tenant_id,work_package_version_id,position_assignment_id,target_org_unit_id,
     allocation_source,valid_from,status,allocated_by)
SELECT (md5('V56:allocation:'||assignment.id::text||':'||package.variant_code))::uuid,
       assignment.tenant_id,(md5('V56:wpv:'||package.variant_code))::uuid,
       assignment.id,hotel_config.hotel_org_unit_id,'SYSTEM',current_date+1,'ACTIVE',
       '19000000-0000-0000-0000-000000000001'
FROM employee_position_assignment assignment
JOIN position_definition position
  ON position.tenant_id=assignment.tenant_id AND position.id=assignment.position_id
JOIN employee employee_row
  ON employee_row.tenant_id=assignment.tenant_id AND employee_row.id=assignment.employee_id
JOIN user_account account
  ON account.tenant_id=employee_row.tenant_id AND account.id=employee_row.account_id
JOIN v56_package_seed package ON package.position_code=position.code
LEFT JOIN LATERAL (
    SELECT profile.org_unit_id AS hotel_org_unit_id,
           profile.assistant_work_template_mode
    FROM org_unit_closure scope
    JOIN hotel_profile profile
      ON profile.tenant_id=scope.tenant_id AND profile.org_unit_id=scope.ancestor_id
    WHERE scope.tenant_id=assignment.tenant_id
      AND scope.descendant_id=assignment.org_unit_id
    ORDER BY scope.depth ASC
    LIMIT 1
) hotel_config ON true
WHERE assignment.tenant_id='10000000-0000-0000-0000-000000000001'
  AND assignment.status='ACTIVE'
  AND assignment.valid_from<=current_date+1
  AND (assignment.valid_to IS NULL OR assignment.valid_to>=current_date+1)
  AND employee_row.employment_status='ACTIVE' AND account.status='ACTIVE'
  AND hotel_config.hotel_org_unit_id IS NOT NULL
  AND (
      package.assistant_mode IS NULL
      OR package.assistant_mode=hotel_config.assistant_work_template_mode
  )
  AND (
      package.variant_code<>'FO_SUPERVISOR'
      OR hotel_config.assistant_work_template_mode='WITH_FRONT_OFFICE_SUPERVISOR'
  )
  AND (
      package.variant_code<>'BREAKFAST_LEAD'
      OR EXISTS (
          SELECT 1
          FROM org_unit_closure breakfast_scope
          JOIN hotel_profile breakfast_hotel
            ON breakfast_hotel.tenant_id=breakfast_scope.tenant_id
           AND breakfast_hotel.org_unit_id=breakfast_scope.ancestor_id
          WHERE breakfast_scope.tenant_id=assignment.tenant_id
            AND breakfast_scope.descendant_id=assignment.org_unit_id
            AND breakfast_hotel.breakfast_service_enabled=true
      )
  )
ON CONFLICT (tenant_id,id) DO NOTHING;

-- Daily report templates: automatic work facts + AI fact/analysis/suggestion +
-- employee manual supplement. Each assistant variant owns a distinct template
-- definition so both can be published while remaining mutually exclusive by
-- store assignment.
CREATE TEMP TABLE v56_report_seed ON COMMIT DROP AS
SELECT package.*,
       replace(package.report_code,'DRT-','DRS-') AS section_code,
       CASE package.variant_code
           WHEN 'NIGHT_ATTENDANT' THEN TIME '01:00'
           WHEN 'BREAKFAST_LEAD' THEN TIME '12:50'
           WHEN 'HK_ATTENDANT' THEN TIME '16:30'
           WHEN 'HK_SUPERVISOR' THEN TIME '17:30'
           WHEN 'FO_SUPERVISOR' THEN TIME '18:00'
           WHEN 'SERVICE_STEWARD' THEN TIME '18:30'
           WHEN 'FRONT_DESK' THEN TIME '00:00'
           ELSE TIME '19:00'
       END AS report_open
FROM v56_package_seed package;

INSERT INTO daily_report_template_definition
    (id,tenant_id,code,name,description,template_origin,owner_org_unit_id,
     position_id,status,created_by)
SELECT (md5('V56:drt:'||seed.report_code))::uuid,
       '10000000-0000-0000-0000-000000000001',seed.report_code,seed.report_name,
       '每日工作自动汇总、AI事实/分析/建议及员工手工补充。','HQ',
       '12000000-0000-0000-0000-000000000001',position.id,'ACTIVE',
       '19000000-0000-0000-0000-000000000001'
FROM v56_report_seed seed
JOIN position_definition position
  ON position.tenant_id='10000000-0000-0000-0000-000000000001'
 AND position.code=seed.position_code
ON CONFLICT (tenant_id,code) DO NOTHING;

INSERT INTO daily_report_section_definition
    (id,tenant_id,code,name,description,section_origin,owner_org_unit_id,
     position_id,status,created_by)
SELECT (md5('V56:drs:'||seed.section_code))::uuid,
       '10000000-0000-0000-0000-000000000001',seed.section_code,
       seed.report_name||'工作事实','自动事实、证据摘要、异常和其他工作。','HQ',
       '12000000-0000-0000-0000-000000000001',position.id,'ACTIVE',
       '19000000-0000-0000-0000-000000000001'
FROM v56_report_seed seed
JOIN position_definition position
  ON position.tenant_id='10000000-0000-0000-0000-000000000001'
 AND position.code=seed.position_code
ON CONFLICT (tenant_id,code) DO NOTHING;

INSERT INTO daily_report_template_version
    (id,tenant_id,template_id,version_no,lifecycle_status,work_package_version_id,
     configuration,created_by)
SELECT (md5('V56:drtv:'||seed.variant_code))::uuid,
       '10000000-0000-0000-0000-000000000001',definition.id,
       coalesce((SELECT max(version_no)+1 FROM daily_report_template_version existing
                 WHERE existing.tenant_id=definition.tenant_id AND existing.template_id=definition.id),1),
       'DRAFT',(md5('V56:wpv:'||seed.variant_code))::uuid,
       jsonb_build_object(
           'title',seed.report_name,
           'templateVariant',seed.variant_code,
           'automaticWorkSummary',true,
           'employeeManualSupplement',true,
           'aiSummary',jsonb_build_object(
               'enabled',true,'sections',jsonb_build_array('FACT','ANALYSIS','SUGGESTION'),
               'employeeConfirmationRequired',true,'deterministicMetricsOnly',true,
               'mayApprove',false,'mayPenalizeKpi',false,'mayDispatchOfficialTask',false
           ),
           'retention',jsonb_build_object('originalEvidenceDays',5,'structuredDataMonths',36),
           'shiftRelative',seed.variant_code='FRONT_DESK',
           'crossMidnightBusinessDate',seed.variant_code='NIGHT_ATTENDANT'
       ),'19000000-0000-0000-0000-000000000001'
FROM v56_report_seed seed
JOIN daily_report_template_definition definition
  ON definition.tenant_id='10000000-0000-0000-0000-000000000001'
 AND definition.code=seed.report_code
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_section_version
    (id,tenant_id,section_definition_id,version_no,lifecycle_status,
     condition_expression,configuration,created_by)
SELECT (md5('V56:drsv:'||seed.variant_code))::uuid,
       '10000000-0000-0000-0000-000000000001',definition.id,
       coalesce((SELECT max(version_no)+1 FROM daily_report_section_version existing
                 WHERE existing.tenant_id=definition.tenant_id
                   AND existing.section_definition_id=definition.id),1),
       'DRAFT',
       CASE WHEN seed.assistant_mode IS NULL THEN '{}'::jsonb
            ELSE jsonb_build_object('assistantWorkTemplateMode',seed.assistant_mode) END,
       jsonb_build_object(
           'sectionCode',lower(seed.variant_code),'title',seed.report_name,
           'sectionOrigin','HQ','aiAttachmentExtraction',true,
           'employeeConfirmsAiResult',true
       ),'19000000-0000-0000-0000-000000000001'
FROM v56_report_seed seed
JOIN daily_report_section_definition definition
  ON definition.tenant_id='10000000-0000-0000-0000-000000000001'
 AND definition.code=seed.section_code
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_template_section
    (id,tenant_id,template_version_id,section_version_id,section_role,required,sort_order)
SELECT (md5('V56:drts:'||variant_code))::uuid,
       '10000000-0000-0000-0000-000000000001',
       (md5('V56:drtv:'||variant_code))::uuid,
       (md5('V56:drsv:'||variant_code))::uuid,'BASE',true,1
FROM v56_report_seed
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_template_item
    (id,tenant_id,section_version_id,item_code,label,input_type,required,
     work_package_item_id,evidence_policy,source_policy,validation_rules,option_values,sort_order)
SELECT (md5('V56:drti:'||item.variant_code||':'||item.item_code))::uuid,
       '10000000-0000-0000-0000-000000000001',
       (md5('V56:drsv:'||item.variant_code))::uuid,
       lower(item.item_code)||'_fact',item.item_name,'WORK_RECORD_REFERENCE',false,
       (md5('V56:item:'||item.variant_code||':'||item.item_code))::uuid,
       '{"inheritFromWorkRecord":true,"includeEvidenceLinks":true}'::jsonb,
       '{"sourceType":"WORK_RECORD","mode":"AUTO_APPROVED"}'::jsonb,
       '{}'::jsonb,'[]'::jsonb,item.sort_order
FROM v56_item_seed item
WHERE item.item_code NOT IN ('AGM_WS_CCTV_REVIEW','AGM_NS_CCTV_REVIEW')
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_template_item
    (id,tenant_id,section_version_id,item_code,label,input_type,required,
     evidence_policy,source_policy,validation_rules,option_values,sort_order)
SELECT (md5('V56:drti:'||seed.variant_code||':'||suffix.code))::uuid,
       '10000000-0000-0000-0000-000000000001',
       (md5('V56:drsv:'||seed.variant_code))::uuid,
       suffix.code,suffix.label,'LONG_TEXT',suffix.required,
       '{"required":false}'::jsonb,suffix.source_policy,
       '{"maxLength":8000}'::jsonb,'[]'::jsonb,900+suffix.sort_order
FROM v56_report_seed seed
CROSS JOIN (VALUES
    ('aiFact','AI事实汇总',false,'{"sourceType":"AI_SUMMARY","section":"FACT","employeeConfirmationRequired":true}'::jsonb,1),
    ('aiAnalysis','AI分析',false,'{"sourceType":"AI_SUMMARY","section":"ANALYSIS","employeeConfirmationRequired":true}'::jsonb,2),
    ('aiSuggestion','AI建议',false,'{"sourceType":"AI_SUMMARY","section":"SUGGESTION","employeeConfirmationRequired":true}'::jsonb,3),
    ('manualOtherWork','其他工作与手工汇报',false,'{"sourceType":"MANUAL"}'::jsonb,4)
) suffix(code,label,required,source_policy,sort_order)
ON CONFLICT (tenant_id,id) DO NOTHING;

UPDATE daily_report_section_version
SET lifecycle_status='PUBLISHED',content_hash=encode(digest(configuration::text,'sha256'),'hex'),
    effective_from=date_trunc('day',now())+interval '1 day',
    published_by='19000000-0000-0000-0000-000000000001',published_at=now(),
    updated_at=now(),row_version=row_version+1
WHERE tenant_id='10000000-0000-0000-0000-000000000001'
  AND id IN (SELECT (md5('V56:drsv:'||variant_code))::uuid FROM v56_report_seed)
  AND lifecycle_status='DRAFT';

UPDATE daily_report_template_version
SET lifecycle_status='PUBLISHED',content_hash=encode(digest(configuration::text,'sha256'),'hex'),
    effective_from=date_trunc('day',now())+interval '1 day',
    published_by='19000000-0000-0000-0000-000000000001',published_at=now(),
    updated_at=now(),row_version=row_version+1
WHERE tenant_id='10000000-0000-0000-0000-000000000001'
  AND id IN (SELECT (md5('V56:drtv:'||variant_code))::uuid FROM v56_report_seed)
  AND lifecycle_status='DRAFT';

-- The manager/assistant checklist has one canonical expectation per hotel.  It
-- is anchored by the store-manager allocation, but both positions may execute
-- the shared items.  Managers and assistants therefore receive separate daily
-- report views over the same work-package facts without generating a second
-- checklist.  The manager-only CCTV review is deliberately absent from the
-- assistant report above and present in the manager report below.
CREATE TEMP TABLE v56_gm_report_seed (
    report_variant_code TEXT PRIMARY KEY,
    package_variant_code TEXT NOT NULL,
    report_code TEXT NOT NULL,
    report_name TEXT NOT NULL,
    section_code TEXT NOT NULL,
    required_hotel_mode VARCHAR(32) NOT NULL,
    report_open TIME NOT NULL,
    report_due TIME NOT NULL
) ON COMMIT DROP;

-- Reserved for a future store-mode-specific manager report split.  V56 keeps a
-- single GM_DAILY report; mode-specific work is already represented by shared
-- assistant expectations and is therefore not duplicated here.

INSERT INTO daily_report_template_definition
    (id,tenant_id,code,name,description,template_origin,owner_org_unit_id,
     position_id,status,created_by)
SELECT (md5('V56:drt:'||seed.report_code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,seed.report_code,
       '店长岗位日报','店长与店助共享日清事实；店长保留管理责任及专属监控抽查。','HQ',
       '12000000-0000-0000-0000-000000000001'::uuid,position.id,'ACTIVE',
       '19000000-0000-0000-0000-000000000001'::uuid
FROM (SELECT DISTINCT report_code FROM v56_gm_report_seed) seed
JOIN position_definition position
  ON position.tenant_id='10000000-0000-0000-0000-000000000001'
 AND position.code='GENERAL_MANAGER'
ON CONFLICT (tenant_id,code) DO NOTHING;

INSERT INTO daily_report_section_definition
    (id,tenant_id,code,name,description,section_origin,owner_org_unit_id,
     position_id,status,created_by)
SELECT (md5('V56:drs:'||seed.section_code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,seed.section_code,
       seed.report_name||'工作事实','自动事实、证据摘要、异常、AI建议和其他工作。','HQ',
       '12000000-0000-0000-0000-000000000001'::uuid,position.id,'ACTIVE',
       '19000000-0000-0000-0000-000000000001'::uuid
FROM v56_gm_report_seed seed
JOIN position_definition position
  ON position.tenant_id='10000000-0000-0000-0000-000000000001'
 AND position.code='GENERAL_MANAGER'
ON CONFLICT (tenant_id,code) DO NOTHING;

INSERT INTO daily_report_template_version
    (id,tenant_id,template_id,version_no,lifecycle_status,work_package_version_id,
     configuration,created_by)
SELECT (md5('V56:drtv:'||seed.report_variant_code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,definition.id,
       coalesce(existing.max_version,0)
         + row_number() OVER (PARTITION BY definition.id ORDER BY seed.report_variant_code),
       'DRAFT',(md5('V56:wpv:'||seed.package_variant_code))::uuid,
       jsonb_build_object(
           'title',seed.report_name,
           'templateVariant',seed.report_variant_code,
           'packageVariant',seed.package_variant_code,
           'assistantWorkTemplateMode',seed.required_hotel_mode,
           'automaticWorkSummary',true,
           'employeeManualSupplement',true,
           'aiSummary',jsonb_build_object(
               'enabled',true,'sections',jsonb_build_array('FACT','ANALYSIS','SUGGESTION'),
               'employeeConfirmationRequired',true,'deterministicMetricsOnly',true,
               'mayApprove',false,'mayPenalizeKpi',false,'mayDispatchOfficialTask',false
           ),
           'retention',jsonb_build_object('originalEvidenceDays',5,'structuredDataMonths',36)
       ),'19000000-0000-0000-0000-000000000001'::uuid
FROM v56_gm_report_seed seed
JOIN daily_report_template_definition definition
  ON definition.tenant_id='10000000-0000-0000-0000-000000000001'
 AND definition.code=seed.report_code
LEFT JOIN LATERAL (
    SELECT max(version_no) AS max_version
    FROM daily_report_template_version version
    WHERE version.tenant_id=definition.tenant_id AND version.template_id=definition.id
) existing ON true
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_section_version
    (id,tenant_id,section_definition_id,version_no,lifecycle_status,
     condition_expression,configuration,created_by)
SELECT (md5('V56:drsv:'||seed.report_variant_code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,definition.id,
       coalesce((SELECT max(version_no)+1 FROM daily_report_section_version existing
                 WHERE existing.tenant_id=definition.tenant_id
                   AND existing.section_definition_id=definition.id),1),
       'DRAFT',jsonb_build_object('assistantWorkTemplateMode',seed.required_hotel_mode),
       jsonb_build_object(
           'sectionCode',lower(seed.report_variant_code),'title',seed.report_name,
           'sectionOrigin','HQ','aiAttachmentExtraction',true,
           'employeeConfirmsAiResult',true
       ),'19000000-0000-0000-0000-000000000001'::uuid
FROM v56_gm_report_seed seed
JOIN daily_report_section_definition definition
  ON definition.tenant_id='10000000-0000-0000-0000-000000000001'
 AND definition.code=seed.section_code
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_template_section
    (id,tenant_id,template_version_id,section_version_id,section_role,required,sort_order)
SELECT (md5('V56:drts:'||report_variant_code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,
       (md5('V56:drtv:'||report_variant_code))::uuid,
       (md5('V56:drsv:'||report_variant_code))::uuid,'BASE',true,1
FROM v56_gm_report_seed
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_template_item
    (id,tenant_id,section_version_id,item_code,label,input_type,required,
     work_package_item_id,evidence_policy,source_policy,validation_rules,option_values,sort_order)
SELECT (md5('V56:drti:'||report.report_variant_code||':'||item.item_code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,
       (md5('V56:drsv:'||report.report_variant_code))::uuid,
       lower(item.item_code)||'_fact',item.item_name,'WORK_RECORD_REFERENCE',false,
       (md5('V56:item:'||item.variant_code||':'||item.item_code))::uuid,
       '{"inheritFromWorkRecord":true,"includeEvidenceLinks":true}'::jsonb,
       '{"sourceType":"WORK_RECORD","mode":"AUTO_APPROVED"}'::jsonb,
       '{}'::jsonb,'[]'::jsonb,item.sort_order
FROM v56_gm_report_seed report
JOIN v56_item_seed item ON item.variant_code=report.package_variant_code
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_template_item
    (id,tenant_id,section_version_id,item_code,label,input_type,required,
     evidence_policy,source_policy,validation_rules,option_values,sort_order)
SELECT (md5('V56:drti:'||seed.report_variant_code||':'||suffix.code))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,
       (md5('V56:drsv:'||seed.report_variant_code))::uuid,
       suffix.code,suffix.label,'LONG_TEXT',suffix.required,
       '{"required":false}'::jsonb,suffix.source_policy,
       '{"maxLength":8000}'::jsonb,'[]'::jsonb,900+suffix.sort_order
FROM v56_gm_report_seed seed
CROSS JOIN (VALUES
    ('aiFact','AI事实汇总',false,'{"sourceType":"AI_SUMMARY","section":"FACT","employeeConfirmationRequired":true}'::jsonb,1),
    ('aiAnalysis','AI分析',false,'{"sourceType":"AI_SUMMARY","section":"ANALYSIS","employeeConfirmationRequired":true}'::jsonb,2),
    ('aiSuggestion','AI建议',false,'{"sourceType":"AI_SUMMARY","section":"SUGGESTION","employeeConfirmationRequired":true}'::jsonb,3),
    ('manualOtherWork','其他工作与手工汇报',false,'{"sourceType":"MANUAL"}'::jsonb,4)
) suffix(code,label,required,source_policy,sort_order)
ON CONFLICT (tenant_id,id) DO NOTHING;

UPDATE daily_report_section_version
SET lifecycle_status='PUBLISHED',content_hash=encode(digest(configuration::text,'sha256'),'hex'),
    effective_from=date_trunc('day',now())+interval '1 day',
    published_by='19000000-0000-0000-0000-000000000001',published_at=now(),
    updated_at=now(),row_version=row_version+1
WHERE tenant_id='10000000-0000-0000-0000-000000000001'
  AND id IN (SELECT (md5('V56:drsv:'||report_variant_code))::uuid FROM v56_gm_report_seed)
  AND lifecycle_status='DRAFT';

UPDATE daily_report_template_version
SET lifecycle_status='PUBLISHED',content_hash=encode(digest(configuration::text,'sha256'),'hex'),
    effective_from=date_trunc('day',now())+interval '1 day',
    published_by='19000000-0000-0000-0000-000000000001',published_at=now(),
    updated_at=now(),row_version=row_version+1
WHERE tenant_id='10000000-0000-0000-0000-000000000001'
  AND id IN (SELECT (md5('V56:drtv:'||report_variant_code))::uuid FROM v56_gm_report_seed)
  AND lifecycle_status='DRAFT';

-- Stop only release-owned predecessor report bindings after the current day.
-- Historical reports remain linked to their immutable published versions.
UPDATE daily_report_template_assignment assignment
SET valid_to=greatest(assignment.valid_from,current_date),
    row_version=assignment.row_version+1,updated_at=now()
FROM daily_report_template_version version
JOIN daily_report_template_definition definition
  ON definition.tenant_id=version.tenant_id AND definition.id=version.template_id
WHERE assignment.tenant_id='10000000-0000-0000-0000-000000000001'
  AND assignment.tenant_id=version.tenant_id
  AND assignment.template_version_id=version.id
  AND assignment.assigned_by='19000000-0000-0000-0000-000000000001'
  AND assignment.status='ACTIVE'
  AND (assignment.valid_to IS NULL OR assignment.valid_to>current_date)
  AND definition.code IN (
      'DRT-PILOT-GM-DAILY','DRT-PILOT-AGM-DAILY',
      'DRT-PILOT-FO-SUPERVISOR-DAILY','DRT-PILOT-FRONT-DAILY',
      'DRT-PILOT-HK-SUPERVISOR-DAILY'
  )
  AND version.id NOT IN (
      SELECT (md5('V56:drtv:'||variant_code))::uuid FROM v56_report_seed
      UNION ALL
      SELECT (md5('V56:drtv:'||report_variant_code))::uuid FROM v56_gm_report_seed
  );

CREATE TEMP TABLE v56_report_assignment_seed (
    report_variant_code TEXT NOT NULL,
    template_version_id UUID NOT NULL,
    report_position_code TEXT NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    org_unit_id UUID,
    position_id UUID,
    report_open TIME NOT NULL,
    report_due TIME NOT NULL,
    assignment_id UUID PRIMARY KEY
) ON COMMIT DROP;

-- Roles without a store-mode condition use the ordinary position fallback.
INSERT INTO v56_report_assignment_seed
SELECT seed.variant_code,(md5('V56:drtv:'||seed.variant_code))::uuid,
       seed.position_code,'POSITION',NULL::uuid,position.id,
       seed.report_open,seed.report_due,
       (md5('V56:drta:'||seed.variant_code||':POSITION'))::uuid
FROM v56_report_seed seed
JOIN position_definition position
  ON position.tenant_id='10000000-0000-0000-0000-000000000001'
 AND position.code=seed.position_code
WHERE seed.variant_code NOT IN (
    'AGM_WITH_SUPERVISOR','AGM_WITHOUT_SUPERVISOR','FO_SUPERVISOR','BREAKFAST_LEAD'
);

-- Assistant variants and the front-office-supervisor template are bound per
-- hotel so the two assistant modes cannot both resolve for one assignment.
INSERT INTO v56_report_assignment_seed
SELECT seed.variant_code,(md5('V56:drtv:'||seed.variant_code))::uuid,
       seed.position_code,'ORG_TREE',profile.org_unit_id,NULL::uuid,
       seed.report_open,seed.report_due,
       (md5('V56:drta:'||seed.variant_code||':'||profile.org_unit_id::text))::uuid
FROM v56_report_seed seed
JOIN hotel_profile profile
  ON profile.tenant_id='10000000-0000-0000-0000-000000000001'
JOIN org_unit hotel
  ON hotel.tenant_id=profile.tenant_id AND hotel.id=profile.org_unit_id
 AND hotel.unit_type='HOTEL' AND hotel.status='ACTIVE'
WHERE (
    seed.variant_code IN ('AGM_WITH_SUPERVISOR','AGM_WITHOUT_SUPERVISOR')
    AND seed.assistant_mode=profile.assistant_work_template_mode
) OR (
    seed.variant_code='FO_SUPERVISOR'
    AND profile.assistant_work_template_mode='WITH_FRONT_OFFICE_SUPERVISOR'
);

-- Breakfast templates are created only for hotels that actually provide it.
INSERT INTO v56_report_assignment_seed
SELECT seed.variant_code,(md5('V56:drtv:'||seed.variant_code))::uuid,
       seed.position_code,'ORG_TREE',profile.org_unit_id,NULL::uuid,
       seed.report_open,seed.report_due,
       (md5('V56:drta:'||seed.variant_code||':'||profile.org_unit_id::text))::uuid
FROM v56_report_seed seed
JOIN hotel_profile profile
  ON profile.tenant_id='10000000-0000-0000-0000-000000000001'
 AND profile.breakfast_service_enabled=true
JOIN org_unit hotel
  ON hotel.tenant_id=profile.tenant_id AND hotel.id=profile.org_unit_id
 AND hotel.unit_type='HOTEL' AND hotel.status='ACTIVE'
WHERE seed.variant_code='BREAKFAST_LEAD';

-- Store-manager reports use the same shared package facts as the matching
-- assistant mode; no second work-package allocation is introduced here.
INSERT INTO v56_report_assignment_seed
SELECT seed.report_variant_code,(md5('V56:drtv:'||seed.report_variant_code))::uuid,
       'GENERAL_MANAGER','ORG_TREE',profile.org_unit_id,NULL::uuid,
       seed.report_open,seed.report_due,
       (md5('V56:drta:'||seed.report_variant_code||':'||profile.org_unit_id::text))::uuid
FROM v56_gm_report_seed seed
JOIN hotel_profile profile
  ON profile.tenant_id='10000000-0000-0000-0000-000000000001'
 AND profile.assistant_work_template_mode=seed.required_hotel_mode
JOIN org_unit hotel
  ON hotel.tenant_id=profile.tenant_id AND hotel.id=profile.org_unit_id
 AND hotel.unit_type='HOTEL' AND hotel.status='ACTIVE';

INSERT INTO daily_report_template_assignment
    (id,tenant_id,template_version_id,assignment_kind,scope_type,org_unit_id,
     position_id,priority,valid_from,status,assigned_by)
SELECT seed.assignment_id,'10000000-0000-0000-0000-000000000001'::uuid,
       seed.template_version_id,'BASE',seed.scope_type,seed.org_unit_id,
       seed.position_id,900,current_date+1,'ACTIVE',
       '19000000-0000-0000-0000-000000000001'::uuid
FROM v56_report_assignment_seed seed
ON CONFLICT (tenant_id,id) DO NOTHING;

INSERT INTO daily_report_delivery_policy
    (id,tenant_id,template_assignment_id,enabled,open_local_time,due_local_time,
     grace_minutes,pre_due_reminder_minutes,overdue_reminder_minutes,backfill_days,
     created_by,updated_by)
SELECT (md5('V56:drdp:'||seed.assignment_id::text))::uuid,
       '10000000-0000-0000-0000-000000000001'::uuid,seed.assignment_id,true,
       seed.report_open,seed.report_due,0,ARRAY[15]::integer[],ARRAY[5,20]::integer[],1,
       '19000000-0000-0000-0000-000000000001'::uuid,
       '19000000-0000-0000-0000-000000000001'::uuid
FROM v56_report_assignment_seed seed
ON CONFLICT (tenant_id,template_assignment_id) DO NOTHING;

COMMENT ON COLUMN hotel_profile.assistant_work_template_mode IS
    '店助模板二选一：有前厅主管/无前厅主管。临时休息使用当日一键转交，不修改本配置。';
COMMENT ON COLUMN work_package_item.execution_policy IS
    'V56 sharedKey 用于门店+营业日+时段共享单例；shiftTiming 使用 available/due anchor 与 offset 规范键。';
