-- Independent operations appointments; no employees or hotel portfolios are assigned by this release.
SELECT set_config('app.tenant_id','10000000-0000-0000-0000-000000000001',false);

DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM app_role WHERE role_type='CUSTOM'
    AND upper(btrim(code)) IN ('OPERATIONS_DIRECTOR','OPERATIONS_MANAGER')) THEN
    RAISE EXCEPTION 'V121 blocked: a custom role uses a reserved operations code';
  END IF;
END $$;
ALTER TABLE app_role ADD CONSTRAINT ck_app_role_operations_reserved_code
  CHECK (role_type<>'CUSTOM' OR upper(btrim(code)) NOT IN ('OPERATIONS_DIRECTOR','OPERATIONS_MANAGER'));

INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category)
VALUES('ui.module.operations-work','ui_module','operations-work','显示运营部日清、计划与月度统计',true,'UI_MODULE')
ON CONFLICT(code) DO NOTHING;

INSERT INTO position_definition(id,tenant_id,code,name,job_family,level_code,status,applies_to_all_hotels)
SELECT md5('V121:position:'||code)::uuid,'10000000-0000-0000-0000-000000000001',
       code,name,'OPERATIONS',level_code,'ACTIVE',true
FROM (VALUES('OPERATIONS_DIRECTOR','运营总监','M4'),('OPERATIONS_MANAGER','运营经理','M3')) seed(code,name,level_code)
ON CONFLICT(tenant_id,code) DO NOTHING;

INSERT INTO app_role(id,tenant_id,code,name,role_type)
SELECT md5('V121:role:'||code)::uuid,'10000000-0000-0000-0000-000000000001',code,name,'SYSTEM'
FROM (VALUES('OPERATIONS_DIRECTOR','运营总监'),('OPERATIONS_MANAGER','运营经理')) seed(code,name)
ON CONFLICT(tenant_id,code) DO NOTHING;

INSERT INTO role_permission(tenant_id,role_id,permission_id)
SELECT r.tenant_id,r.id,p.id FROM app_role r CROSS JOIN permission p
WHERE r.tenant_id='10000000-0000-0000-0000-000000000001'
  AND ((r.code IN ('OPERATIONS_DIRECTOR','OPERATIONS_MANAGER') AND p.code=ANY(ARRAY[
    'org.read','standard.read','work-package.read','work-record.read','work-record.submit','work.submit',
    'task.read','task.act','notification.read','work-plan.read','work-plan.submit',
    'ui.module.role-workbench','ui.module.my-work','ui.module.work-plans','ui.module.operations-work']))
    OR (r.code='OPERATIONS_DIRECTOR' AND p.code=ANY(ARRAY[
      'work-record.review','work-record.team-read','work-plan.team-read','work-plan.review',
      'task.create','task.dispatch','task.review']))
    OR (r.code IN ('OTA_OPERATION_MANAGER','OTA_OPERATION_ASSISTANT') AND p.code='ui.module.operations-work'))
ON CONFLICT DO NOTHING;

INSERT INTO position_function_profile(id,tenant_id,position_id,scope_type,default_role_id)
SELECT md5('V121:profile:'||p.code)::uuid,p.tenant_id,p.id,'GROUP',r.id
FROM position_definition p JOIN app_role r ON r.tenant_id=p.tenant_id AND r.code=p.code
WHERE p.tenant_id='10000000-0000-0000-0000-000000000001'
  AND p.code IN ('OPERATIONS_DIRECTOR','OPERATIONS_MANAGER')
ON CONFLICT DO NOTHING;

DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM position_definition p
    JOIN position_function_profile f ON f.tenant_id=p.tenant_id AND f.position_id=p.id AND f.scope_type='GROUP'
    JOIN app_role r ON r.tenant_id=f.tenant_id AND r.id=f.default_role_id
    WHERE p.tenant_id='10000000-0000-0000-0000-000000000001'
      AND p.code IN ('OPERATIONS_DIRECTOR','OPERATIONS_MANAGER') AND (r.code<>p.code OR r.role_type<>'SYSTEM')) THEN
    RAISE EXCEPTION 'V121 blocked: existing operations position maps to a different role';
  END IF;
END $$;

-- New profiles are explicit portfolios, not organization-tree grants. Existing OTA profiles are not rewritten.
INSERT INTO position_function_profile_version(id,tenant_id,profile_id,version_no,lifecycle_status,
  authorization_scope_type,wecom_self_selectable,created_by,published_by,published_at)
SELECT md5('V121:profile-version:'||p.code)::uuid,p.tenant_id,f.id,1,'PUBLISHED','ASSIGNED_HOTELS',false,
  '19000000-0000-0000-0000-000000000001','19000000-0000-0000-0000-000000000001',now()
FROM position_definition p JOIN position_function_profile f ON f.tenant_id=p.tenant_id AND f.position_id=p.id AND f.scope_type='GROUP'
WHERE p.tenant_id='10000000-0000-0000-0000-000000000001'
  AND p.code IN ('OPERATIONS_DIRECTOR','OPERATIONS_MANAGER')
  AND NOT EXISTS(SELECT 1 FROM position_function_profile_version old WHERE old.tenant_id=f.tenant_id AND old.profile_id=f.id);

INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
SELECT f.tenant_id,v.id,rp.permission_id FROM position_function_profile f
JOIN position_definition p ON p.tenant_id=f.tenant_id AND p.id=f.position_id
JOIN position_function_profile_version v ON v.tenant_id=f.tenant_id AND v.profile_id=f.id AND v.lifecycle_status='PUBLISHED'
JOIN role_permission rp ON rp.tenant_id=f.tenant_id AND rp.role_id=f.default_role_id
JOIN permission permission ON permission.id=rp.permission_id AND permission.delegable_to_position
WHERE f.tenant_id='10000000-0000-0000-0000-000000000001' AND p.code IN ('OPERATIONS_DIRECTOR','OPERATIONS_MANAGER')
ON CONFLICT DO NOTHING;

-- Preserve old published permissions and unpublished administrator drafts. A scope upgrade
-- is safe only when no current OR future ACTIVE appointment can depend on the old scope.
-- BEGIN V121_OTA_PORTFOLIO_UPGRADE
DO $$
DECLARE profile_record RECORD; next_version integer; new_version uuid; safe_scope boolean;
BEGIN
  FOR profile_record IN
    SELECT f.position_id,v.* FROM position_function_profile f
    JOIN position_definition p ON p.tenant_id=f.tenant_id AND p.id=f.position_id
    JOIN app_role r ON r.tenant_id=f.tenant_id AND r.id=f.default_role_id AND r.role_type='SYSTEM' AND r.code=p.code
    JOIN position_function_profile_version v ON v.tenant_id=f.tenant_id AND v.profile_id=f.id AND v.lifecycle_status='PUBLISHED'
    WHERE f.tenant_id='10000000-0000-0000-0000-000000000001' AND f.scope_type='GROUP'
      AND p.code IN ('OTA_OPERATION_MANAGER','OTA_OPERATION_ASSISTANT')
    FOR UPDATE OF f,v
  LOOP
    SELECT NOT EXISTS(SELECT 1 FROM employee_position_assignment a
      WHERE a.tenant_id=profile_record.tenant_id AND a.position_id=profile_record.position_id
        AND a.status='ACTIVE' AND (a.valid_to IS NULL OR a.valid_to>=current_date)) INTO safe_scope;
    IF (safe_scope AND profile_record.authorization_scope_type<>'ASSIGNED_HOTELS') OR NOT EXISTS(
      SELECT 1 FROM position_function_profile_permission pp JOIN permission p ON p.id=pp.permission_id
      WHERE pp.tenant_id=profile_record.tenant_id AND pp.profile_version_id=profile_record.id AND p.code='ui.module.operations-work') THEN
      SELECT max(version_no)+1 INTO next_version FROM position_function_profile_version
      WHERE tenant_id=profile_record.tenant_id AND profile_id=profile_record.profile_id;
      new_version:=gen_random_uuid();
      UPDATE position_function_profile_version SET lifecycle_status='ARCHIVED',row_version=row_version+1,updated_at=now()
      WHERE tenant_id=profile_record.tenant_id AND id=profile_record.id;
      INSERT INTO position_function_profile_version(id,tenant_id,profile_id,version_no,lifecycle_status,
        copied_from_version_id,based_on_group_version_id,authorization_scope_type,wecom_self_selectable,
        created_by,published_by,published_at,row_version)
      VALUES(new_version,profile_record.tenant_id,profile_record.profile_id,next_version,'PUBLISHED',
        profile_record.id,profile_record.based_on_group_version_id,
        CASE WHEN safe_scope THEN 'ASSIGNED_HOTELS' ELSE profile_record.authorization_scope_type END,
        profile_record.wecom_self_selectable,profile_record.published_by,profile_record.published_by,now(),1);
      INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
      SELECT profile_record.tenant_id,new_version,permission_id FROM position_function_profile_permission
      WHERE tenant_id=profile_record.tenant_id AND profile_version_id=profile_record.id;
      INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
      SELECT profile_record.tenant_id,new_version,id FROM permission WHERE code='ui.module.operations-work'
      ON CONFLICT DO NOTHING;
      UPDATE position_function_profile SET row_version=row_version+1,updated_at=now()
      WHERE tenant_id=profile_record.tenant_id AND id=profile_record.profile_id;
      UPDATE position_definition SET row_version=row_version+1,updated_at=now()
      WHERE tenant_id=profile_record.tenant_id AND id=profile_record.position_id;
      INSERT INTO audit_log(tenant_id,action,resource_type,resource_id,correlation_id,after_data)
      VALUES(profile_record.tenant_id,'SYSTEM_OPERATIONS_PROFILE_PUBLISHED','POSITION_FUNCTION_PROFILE',
        profile_record.profile_id,gen_random_uuid(),jsonb_build_object('source','V121','previousVersionId',profile_record.id,
          'publishedVersionId',new_version,'previousScope',profile_record.authorization_scope_type,
          'authorizationScopeType',CASE WHEN safe_scope THEN 'ASSIGNED_HOTELS' ELSE profile_record.authorization_scope_type END,
          'scopeChangedWithoutAppointments',safe_scope,'employeeAssignmentsChanged',false));
    END IF;
  END LOOP;
END $$;
-- END V121_OTA_PORTFOLIO_UPGRADE

CREATE TEMP TABLE v121_roles(code text PRIMARY KEY,name text) ON COMMIT DROP;
INSERT INTO v121_roles VALUES('OPERATIONS_DIRECTOR','运营总监'),('OPERATIONS_MANAGER','运营经理');

INSERT INTO form_definition(id,tenant_id,code,name,form_type,position_id)
SELECT md5('V121:form:'||s.code)::uuid,p.tenant_id,'OPERATIONS-'||s.code||'-DAILY',s.name||'运营日清记录','DAILY_WORK',p.id
FROM v121_roles s JOIN position_definition p ON p.code=s.code AND p.tenant_id='10000000-0000-0000-0000-000000000001';
INSERT INTO form_version(id,tenant_id,form_id,version_no,lifecycle_status,json_schema,ui_schema,published_at)
SELECT md5('V121:form-version:'||s.code)::uuid,f.tenant_id,f.id,1,'PUBLISHED',
  '{"type":"object","required":["workSummary","issueFound","dataScope"],"properties":{
    "workSummary":{"type":"string","title":"检查结果与处理情况","minLength":1},
    "issueFound":{"type":"boolean","title":"是否发现问题或数据缺失"},
    "issueSummary":{"type":"string","title":"异常、缺失数据及后续处理"},
    "dataScope":{"type":"string","title":"平台、竞对或数据日期范围","minLength":1},
    "dataUnavailable":{"type":"boolean","title":"是否存在未接入或未结算数据"}}}'::jsonb,
  '{"description":"按门店和轮次独立留证，数据缺失不得填零或冒充完成。平台截图保留原比例。"}'::jsonb,now()
FROM v121_roles s JOIN form_definition f ON f.tenant_id='10000000-0000-0000-0000-000000000001' AND f.code='OPERATIONS-'||s.code||'-DAILY';

INSERT INTO work_package_definition(id,tenant_id,code,name,description,position_id,owner_org_unit_id,created_by)
SELECT md5('V121:definition:'||s.code)::uuid,p.tenant_id,'WP-OPERATIONS-'||s.code||'-DAILY',s.name||'多门店日清',
  '按显式负责门店分别执行；不替换旧OTA岗位清单或KPI。',p.id,
  '12000000-0000-0000-0000-000000000001','19000000-0000-0000-0000-000000000001'
FROM v121_roles s JOIN position_definition p ON p.code=s.code AND p.tenant_id='10000000-0000-0000-0000-000000000001';
INSERT INTO work_package_version(id,tenant_id,work_package_definition_id,version_no,lifecycle_status,title,description,content_hash,created_by)
SELECT md5('V121:version:'||s.code)::uuid,d.tenant_id,d.id,1,'DRAFT',s.name||'日清 V1',
  '每家负责门店每日7项，正常与异常均人工审核，截图按类别留证。',encode(digest('V121:'||s.code,'sha256'),'hex'),
  '19000000-0000-0000-0000-000000000001'
FROM v121_roles s JOIN work_package_definition d ON d.tenant_id='10000000-0000-0000-0000-000000000001' AND d.code='WP-OPERATIONS-'||s.code||'-DAILY';
INSERT INTO work_package_scope(id,tenant_id,work_package_version_id,scope_type)
SELECT md5('V121:scope:'||code)::uuid,'10000000-0000-0000-0000-000000000001',md5('V121:version:'||code)::uuid,'TENANT' FROM v121_roles;

CREATE TEMP TABLE v121_items(code text,name text,opens time,target text,due time,remind integer,sort integer,evidence jsonb) ON COMMIT DROP;
INSERT INTO v121_items VALUES
('OPS_ROUND_1','预订、流量、竞价检查 · 第1轮','09:30','09:40','09:50',20,10,'[]'),
('OPS_YESTERDAY','昨日经营数据巡查','10:00','10:00–10:30','10:40',50,20,
 '[{"checkpointCode":"yesterday_pms","label":"昨日PMS报表","minimum":1},{"checkpointCode":"yesterday_traffic","label":"昨日平台流量","minimum":1},{"checkpointCode":"yesterday_sales","label":"昨日平台销售","minimum":1},{"checkpointCode":"yesterday_live_traffic","label":"昨日抖音直播流量","minimum":1},{"checkpointCode":"yesterday_live_sales","label":"昨日抖音直播销售","minimum":1},{"checkpointCode":"yesterday_qr_stay","label":"昨日扫码住数据","minimum":1}]'),
('OPS_PLATFORM','平台指标、违规、点评及获客币巡查','10:50','10:50–11:20','11:30',50,30,
 '[{"checkpointCode":"platform_scores","label":"各平台指标分","minimum":1},{"checkpointCode":"platform_violations","label":"平台违规记录","minimum":1},{"checkpointCode":"platform_reviews","label":"平台后台点评分","minimum":1},{"checkpointCode":"platform_acquisition_credit","label":"获客币领取明细","minimum":1}]'),
('OPS_ROUND_2','预订、流量、竞价检查 · 第2轮','11:10','11:20','11:30',20,40,'[]'),
('OPS_INVENTORY','未来7天及节假日可售房型库存检查','11:40','11:40–12:00','12:10',40,50,'[]'),
('OPS_ROUND_3','预订、流量、竞价检查 · 第3轮','15:10','15:20','15:40',30,60,'[]'),
('OPS_ROUND_4','预订、流量、竞价检查 · 第4轮','17:40','17:50','18:00',20,70,'[]');
UPDATE v121_items SET evidence='[
 {"checkpointCode":"round_bookings","label":"两主力平台预订数据","minimum":1},
 {"checkpointCode":"round_traffic","label":"两主力平台流量数据","minimum":1},
 {"checkpointCode":"round_own_prices","label":"本店各平台外网价格","minimum":1},
 {"checkpointCode":"round_competitor_prices","label":"两家竞对外网价格","minimum":1}]'::jsonb WHERE code LIKE 'OPS_ROUND_%';
UPDATE v121_items SET evidence=(SELECT jsonb_agg(jsonb_build_object('checkpointCode','inventory_day_'||n,
  'label',case when n=0 then '营业日可售房型库存' else '营业日后第'||n||'天可售房型库存' end,
  'dateOffsetDays',n,'minimum',1) ORDER BY n) FROM generate_series(0,6)n) WHERE code='OPS_INVENTORY';

INSERT INTO work_package_item(id,tenant_id,work_package_version_id,item_code,name,description,item_type,form_version_id,
 sort_order,required,period_type,timezone_mode,work_window_start,work_window_end,due_local_time,grace_minutes,weekdays,
 holiday_policy,waiver_allowed,target_granularity,review_mode,submission_policy,reminder_policy,report_policy,execution_policy)
SELECT md5('V121:item:'||role.code||':'||i.code)::uuid,'10000000-0000-0000-0000-000000000001',
 md5('V121:version:'||role.code)::uuid,i.code,i.name,
 '每家负责门店独立检查；计划时段：'||i.target||'。库存指可售房型，不是物资库存。','INSPECTION',
 md5('V121:form-version:'||role.code)::uuid,i.sort,true,'DAY','HOTEL',i.opens,i.due,i.due,0,
 ARRAY[1,2,3,4,5,6,7]::smallint[],'INCLUDE',false,'ASSIGNMENT_ORG','MANUAL',
 jsonb_build_object('completionStatementRequired',true,'attachmentRequired',true,'attachmentCountUnlimited',true,
   'maxAttachments',200,'allowedExtensions',jsonb_build_array('jpg','jpeg','png','pdf','xlsx'),
   'evidenceRequirements',(SELECT jsonb_agg(e||'{"captureSource":"FILE_PICKER"}'::jsonb) FROM jsonb_array_elements(i.evidence)e)),
 jsonb_build_object('dueSoonMinutes',i.remind,'overdueMinutes',0,'escalationMinutes',20,
   'escalationRecipient','DIRECT_MANAGER','mergeConcurrentMessages',true),
 '{"dailyReport":false}'::jsonb,
 jsonb_build_object('enabled',true,'operationsDaily',true,'plannedTarget',i.target,
   'requiredCaptureSource','FILE_PICKER','separateRoleExecution',true)
FROM v121_roles role CROSS JOIN v121_items i;

INSERT INTO work_package_item_standard(id,tenant_id,work_package_item_id,standard_version_id,usage_type,weight)
SELECT md5('V121:standard:'||role.code||':'||i.code)::uuid,'10000000-0000-0000-0000-000000000001',
 md5('V121:item:'||role.code||':'||i.code)::uuid,'41300000-0000-0000-0000-000000000001','EXECUTION',1
FROM v121_roles role CROSS JOIN v121_items i;
INSERT INTO work_package_item_responsibility(id,tenant_id,work_package_item_id,participant_type,resolver_type,scope_strategy,escalation_level)
SELECT md5('V121:'||resolver.type||':'||role.code||':'||i.code)::uuid,'10000000-0000-0000-0000-000000000001',
 md5('V121:item:'||role.code||':'||i.code)::uuid,resolver.type,resolver.resolver,resolver.scope,0
FROM v121_roles role CROSS JOIN v121_items i CROSS JOIN
 (VALUES('EXECUTOR','CURRENT_ASSIGNMENT','TARGET_ORG'),('REVIEWER','DIRECT_MANAGER_ASSIGNMENT','ANCESTOR_ORG'))resolver(type,resolver,scope);

-- Publishing is future-effective and allocates nobody. The runtime materializer honors explicit appointment portfolios.
UPDATE work_package_version SET lifecycle_status='PUBLISHED',published_at=now(),published_by=created_by,
  effective_from=(current_date+1)::timestamptz
WHERE id IN (SELECT md5('V121:version:'||code)::uuid FROM v121_roles);
