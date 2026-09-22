-- Additive release: existing published work packages, records and settled KPI remain unchanged.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category)
VALUES('manager-cycle.review','manager-cycle','review','复核负责门店的店长周期工作；不授予其他工作复核权限',true,'WORK')
ON CONFLICT(code) DO NOTHING;
ALTER TABLE work_package_item DROP CONSTRAINT work_package_item_period_type_check;
ALTER TABLE work_package_item ADD CONSTRAINT work_package_item_period_type_check
  CHECK (period_type IN ('SHIFT','DAY','WEEK','MONTH','QUARTER','EVENT'));
ALTER TABLE work_expectation ADD COLUMN period_start date;
ALTER TABLE work_expectation ADD COLUMN period_end date;
ALTER TABLE work_expectation ADD CONSTRAINT work_expectation_period_dates
  CHECK ((period_start IS NULL AND period_end IS NULL) OR
         (period_start IS NOT NULL AND period_end IS NOT NULL AND period_end >= period_start));
CREATE INDEX ix_work_expectation_cycle ON work_expectation (tenant_id, position_assignment_id, period_start, period_end)
  WHERE period_start IS NOT NULL;

CREATE TABLE employee_weekly_assessment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id),
  hotel_id uuid NOT NULL, employee_id uuid NOT NULL, week_start date NOT NULL,
  work_expectation_id uuid NOT NULL, score numeric(5,2) NOT NULL CHECK (score BETWEEN 0 AND 100),
  focus text NOT NULL DEFAULT '' CHECK (length(focus)<=2000),
  employee_name_snapshot text NOT NULL, position_name_snapshot text NOT NULL,
  assessed_by_account_id uuid NOT NULL, row_version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,id), UNIQUE (tenant_id,hotel_id,employee_id,week_start),
  CHECK (extract(isodow FROM week_start)=1),
  FOREIGN KEY (tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
  FOREIGN KEY (tenant_id,employee_id) REFERENCES employee(tenant_id,id),
  FOREIGN KEY (tenant_id,work_expectation_id) REFERENCES work_expectation(tenant_id,id),
  FOREIGN KEY (tenant_id,assessed_by_account_id) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_employee_weekly_assessment_history ON employee_weekly_assessment(tenant_id,hotel_id,employee_id,week_start DESC);
CREATE INDEX ix_employee_weekly_assessment_work ON employee_weekly_assessment(tenant_id,work_expectation_id);
ALTER TABLE employee_weekly_assessment ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_weekly_assessment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_employee_weekly_assessment ON employee_weekly_assessment
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
COMMENT ON TABLE employee_weekly_assessment IS '人工周评，至少保留三年；不直接修改月度KPI、薪酬或工作完成率';
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
  GRANT SELECT,INSERT,UPDATE ON employee_weekly_assessment TO hotel_ai_os_app;
END IF; END $$;

-- Business-specific fields; no generic work-result/exception questions.
CREATE TEMP TABLE manager_cycle_seed (
 code text, name text, period text, ordering int, schedule jsonb, due_time time,
 fields jsonb, description text
) ON COMMIT DROP;
INSERT INTO manager_cycle_seed VALUES
('GM_W_OPERATION','周经营与渠道复盘','WEEK',1,'{"dueDayOffset":7}','12:00','{"operationReview":"经营指标与目标、上周对比","channelReview":"渠道与客源分析","improvementPlan":"改进措施与责任安排"}','复盘收入、入住率、平均房价及渠道变化；本周归属、下周一12点前提交。'),
('GM_W_SERVICE','客诉、差评与整改跟进','WEEK',2,'{"dueDayOffset":4}','18:00','{"serviceFollowup":"客诉与差评跟进（未发生可填写无）","rectificationPlan":"未完成整改的责任人与时限（选填）"}','核对实际发生的客诉、回访及整改记录；没有客诉不要求编造案例。'),
('GM_W_HYGIENE','计划卫生推进与抽查','WEEK',3,'{"dueDayOffset":3}','18:00','{"hygieneProgress":"计划卫生推进情况","checkedRooms":"抽查房号与卫生内容","followupPlan":"后续安排（选填）"}','结合员工计划卫生内容与房号记录，检查覆盖、质量及后续安排。'),
('GM_W_MAINTENANCE','设备维修与安全隐患复查','WEEK',4,'{"dueDayOffset":2}','18:00','{"repairReview":"维修与停用设备复查","riskFollowup":"未关闭隐患的跟进安排（选填）"}','核对维修、停用设备、反复故障及未关闭隐患。'),
('GM_W_STOCK','重点物资、布草抽盘与备货','WEEK',5,'{"dueDayOffset":4}','12:00','{"stockCheck":"重点物资与布草抽盘记录","stockDifference":"盘点差异说明（选填）","stockPlan":"下周备货安排"}','抽盘重点耗品及布草，确认差异和下周备货需求。'),
('GM_W_TRAINING','员工培训与下周排班检查','WEEK',6,'{"dueDayOffset":4}','18:00','{"trainingRecord":"培训主题、参加人员及效果","staffingPlan":"下周排班与人员保障"}','检查培训落实、人员覆盖及下周班次衔接。'),
('GM_W_MEETING','周例会与下周工作部署','WEEK',7,'{"dueDayOffset":4}','18:00','{"meetingMinutes":"周例会记录与上周事项跟进","nextWeekPlan":"下周重点、责任人及完成时限","supportNeeded":"需协调资源（选填）"}','明确责任人、完成时限与资源；可由工作下达建立后续任务。'),
('GM_W_EMPLOYEE','员工绩效评估','WEEK',8,'{"dueDayOffset":7}','12:00','{}','选择本店员工，填写0至100分和关注重点；一人一周一份，未评价不是零分。'),
('GM_M_OPERATION','月度经营与成本复盘','MONTH',1,'{"dueMonthOffset":1,"dueDay":5}','18:00','{"operationReview":"月度经营指标与目标对比","costReview":"人工、耗品、洗涤、能耗及应收分析","improvementPlan":"经营改进安排"}','次月5日前提交，归属原月份；财务口径须核实。'),
('GM_M_CHANNEL','渠道与客源检查','MONTH',2,'{"dueMonthOffset":0,"dueDay":25}','18:00','{"channelReview":"渠道、价格与客源结构检查","demandPlan":"下月需求与营销安排"}','当月25日前检查渠道、房价及下月客源安排。'),
('GM_M_SERVICE','客诉、差评与服务质量复盘','MONTH',3,'{"dueMonthOffset":1,"dueDay":3}','18:00','{"serviceReview":"客诉、差评与服务质量分析","serviceImprovement":"重复问题及改进措施"}','次月3日前提交，按原月份汇总。'),
('GM_M_STOCK','库存、布草与耗品盘点确认','MONTH',4,'{"dueMonthOffset":1,"dueDay":2}','18:00','{"inventorySummary":"月末盘点汇总","differenceConfirmation":"差异、损耗与积压确认","purchasePlan":"采购与处理安排（选填）"}','月末盘点、次月2日前确认，不直接改写库存台账。'),
('GM_M_HYGIENE','计划卫生与设备保养验收','MONTH',5,'{"dueMonthOffset":0,"dueDay":0}','18:00','{"hygieneAcceptance":"计划卫生覆盖与验收","maintenanceAcceptance":"设备保养验收","followupPlan":"未完成事项跟进（选填）"}','核验月度房间和卫生项目覆盖及设备保养。'),
('GM_M_EMPLOYEE','员工绩效、培训与面谈','MONTH',6,'{"dueMonthOffset":1,"dueDay":5}','18:00','{"performanceReview":"员工周评与工作表现复盘","trainingInterview":"培训及面谈记录","growthPlan":"员工改进与成长计划"}','次月5日前提交；周评不直接折算月度KPI。'),
('GM_M_SAFETY','安全与经营风险专项检查','MONTH',7,'{"dueMonthOffset":0,"dueDay":20}','18:00','{"safetyCheck":"安全与经营风险检查记录","expiryReview":"证照、合同到期检查","riskPlan":"未关闭风险处理安排（选填）"}','当月20日前；专业设备维护须由合格人员完成。'),
('GM_M_PLAN','下月工作与资源计划','MONTH',8,'{"dueMonthOffset":0,"dueDay":28}','18:00','{"nextMonthPlan":"下月重点工作与时限","resourcePlan":"人员、采购、维修及资源需求"}','当月28日前确定下月工作及资源计划。'),
('GM_M_WATER','空气能净水设备检查','MONTH',9,'{"dueMonthOffset":0,"dueDay":28}','18:00','{}','检查净水设备工作与药剂储备；不正常或不足时填写处理安排，照片选填。'),
('GM_Q_OPERATION','季度经营复盘与策略调整','QUARTER',1,'{"dueMonthOffset":3,"dueDay":10}','18:00','{"quarterReview":"季度经营趋势与执行成效","strategyPlan":"下一季度策略与目标"}','下一季度首月10日前提交，归属原季度。'),
('GM_Q_ASSETS','固定资产与设备状态核查','QUARTER',2,'{"dueMonthOffset":2,"dueDay":0}','18:00','{"assetReview":"固定资产与设备核查","assetPlan":"闲置、损坏、维修及更新安排"}','季度末前核查，不直接变更资产台账。'),
('GM_Q_QUALITY','全店服务品质深度检查','QUARTER',3,'{"dueMonthOffset":1,"dueDay":0}','18:00','{"qualityReview":"客房、前厅、公共区域品质检查","qualityPlan":"质量改进责任与时限"}','本季度第二个月月底前完成深度检查。'),
('GM_Q_SAFETY','安全风险复盘与应急能力检查','QUARTER',4,'{"dueMonthOffset":2,"dueDay":0}','18:00','{"riskReview":"季度风险复盘","emergencyReadiness":"应急预案、培训及演练检查","readinessPlan":"应急能力改进安排（选填）"}','季度计划内完成，默认季度末截止；不替代日常检查和专业维保。'),
('GM_Q_PEOPLE','人员能力与梯队评估','QUARTER',5,'{"dueMonthOffset":2,"dueDay":25}','18:00','{"capabilityReview":"骨干能力与员工成长评估","successionPlan":"后备人员与下季度人员计划"}','季度末月25日前完成。'),
('GM_Q_VENDOR','供应商与外包服务评估','QUARTER',6,'{"dueMonthOffset":2,"dueDay":20}','18:00','{"vendorReview":"洗涤、维修、耗品等供应商评价","vendorPlan":"价格、质量、交付问题及改进安排"}','季度末月20日前完成供应商履约评估。');

ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$ DECLARE tenant_row record; item record; position_uuid uuid; definition_uuid uuid; version_uuid uuid;
 form_uuid uuid; form_version_uuid uuid; item_uuid uuid; props jsonb; required_fields jsonb;
BEGIN
 FOR tenant_row IN SELECT id FROM tenant LOOP
  PERFORM set_config('app.tenant_id',tenant_row.id::text,true);
  SELECT id INTO position_uuid FROM position_definition WHERE tenant_id=tenant_row.id AND code='GENERAL_MANAGER';
  IF position_uuid IS NULL THEN CONTINUE; END IF;
  INSERT INTO work_package_definition(tenant_id,code,name,description,position_id)
    VALUES(tenant_row.id,'WP-GM-CYCLES','店长周清、月清与季清工作包','按已确认的8项周清、9项月清、6项季清执行',position_uuid)
    RETURNING id INTO definition_uuid;
  INSERT INTO work_package_version(tenant_id,work_package_definition_id,version_no,title)
    VALUES(tenant_row.id,definition_uuid,1,'店长周期工作清单 V1') RETURNING id INTO version_uuid;
  INSERT INTO work_package_scope(tenant_id,work_package_version_id,scope_type,position_id)
    VALUES(tenant_row.id,version_uuid,'POSITION',position_uuid);
  FOR item IN SELECT * FROM manager_cycle_seed ORDER BY period,ordering LOOP
    SELECT coalesce(jsonb_object_agg(key,jsonb_build_object('type','string','title',value #>> '{}','maxLength',4000)), '{}')
      INTO props FROM jsonb_each(item.fields);
    SELECT coalesce(jsonb_agg(key),'[]') INTO required_fields FROM jsonb_each(item.fields)
      WHERE (value #>> '{}') NOT LIKE '%选填%';
    IF item.code='GM_M_WATER' THEN
      props := '{"equipmentNormal":{"type":"string","title":"净水设备是否正常工作","enum":["正常","不正常"]},"chemicalSufficient":{"type":"string","title":"净水药剂是否充足","enum":["充足","不足"]},"handlingPlan":{"type":"string","title":"处理安排（不正常或不足时必填）","maxLength":4000}}';
      required_fields := '["equipmentNormal","chemicalSufficient"]';
    END IF;
    INSERT INTO form_definition(tenant_id,code,name,form_type,position_id)
      VALUES(tenant_row.id,item.code,item.name,'DAILY_WORK',position_uuid) RETURNING id INTO form_uuid;
    INSERT INTO form_version(tenant_id,form_id,version_no,lifecycle_status,json_schema,published_at)
      VALUES(tenant_row.id,form_uuid,1,'PUBLISHED',jsonb_build_object('type','object','properties',props,'required',required_fields),now())
      RETURNING id INTO form_version_uuid;
    INSERT INTO work_package_item(tenant_id,work_package_version_id,item_code,name,description,item_type,form_version_id,
      sort_order,required,period_type,timezone_mode,due_local_time,review_mode,submission_policy,execution_policy,reminder_policy,report_policy)
    VALUES(tenant_row.id,version_uuid,item.code,item.name,item.description,'SCHEDULED_RECORD',form_version_uuid,item.ordering,
      true,item.period,'TENANT',item.due_time,'MANUAL',
      '{"completionStatementRequired":false,"exceptionStatementRequired":false,"nextActionRequired":false,"attachmentRequired":false,"maxAttachments":200,"maxFileSizeBytes":20971520,"allowedExtensions":["jpg","jpeg","png","webp","pdf","doc","docx","xls","xlsx"]}'::jsonb
        || CASE WHEN item.code='GM_M_WATER' THEN '{"evidenceRequirements":[{"checkpointCode":"waterEquipment","label":"现场照片（选填）","captureSource":"CAMERA_OR_FILE","minimum":0,"allowedKinds":["IMAGE"]}]}'::jsonb ELSE '{}'::jsonb END,
      jsonb_build_object('enabled',true,'cycleSchedule',item.schedule,'managerCycle',true),
      '{"moments":[{"code":"BEFORE_3D","kind":"REMINDER","dueOffsetMinutes":-4320,"recipient":"EXECUTORS"},{"code":"BEFORE_1D","kind":"REMINDER","dueOffsetMinutes":-1440,"recipient":"EXECUTORS"},{"code":"OVERDUE","kind":"OVERDUE","dueOffsetMinutes":0,"recipient":"EXECUTORS"},{"code":"ESCALATION","kind":"ESCALATION","dueOffsetMinutes":0,"recipient":"DIRECT_MANAGER"}]}',
      '{"autoInclude":true,"showEvidence":true,"showExceptions":false,"cycleReport":true}') RETURNING id INTO item_uuid;
    INSERT INTO work_package_item_responsibility(tenant_id,work_package_item_id,participant_type,resolver_type)
      VALUES(tenant_row.id,item_uuid,'EXECUTOR','CURRENT_ASSIGNMENT'),(tenant_row.id,item_uuid,'REVIEWER','DIRECT_MANAGER_ASSIGNMENT'),
        (tenant_row.id,item_uuid,'ACCEPTOR','DIRECT_MANAGER_ASSIGNMENT');
  END LOOP;
  UPDATE work_package_version SET lifecycle_status='PUBLISHED',effective_from=current_date,published_at=now(),
    content_hash=encode(digest(version_uuid::text||':manager-cycles-v73','sha256'),'hex') WHERE id=version_uuid AND tenant_id=tenant_row.id;
 END LOOP;
END $$;

-- The approved new workflow has its own narrow action permission. Keep all old
-- grants, scopes, historical versions and administrator drafts intact. Publish
-- a copied group profile only when the vice president already has team-read.
DO $$ DECLARE t record; c record; published_id uuid; next_version integer; BEGIN
 FOR t IN SELECT id FROM tenant LOOP
  PERFORM set_config('app.tenant_id',t.id::text,true);
  FOR c IN
   SELECT p.id profile_id,p.position_id,p.default_role_id,v.* FROM position_function_profile p
   JOIN position_definition d ON d.tenant_id=p.tenant_id AND d.id=p.position_id
   JOIN app_role role_item ON role_item.tenant_id=p.tenant_id AND role_item.id=p.default_role_id
   JOIN position_function_profile_version v ON v.tenant_id=p.tenant_id AND v.profile_id=p.id
   WHERE p.tenant_id=t.id AND p.scope_type='GROUP' AND d.code='GROUP_VICE_PRESIDENT'
     AND role_item.code='GROUP_VICE_PRESIDENT' AND role_item.role_type='SYSTEM'
     AND v.lifecycle_status='PUBLISHED'
     AND EXISTS(SELECT 1 FROM position_function_profile_permission g JOIN permission q ON q.id=g.permission_id
       WHERE g.tenant_id=t.id AND g.profile_version_id=v.id AND q.code='work-record.team-read')
  LOOP
   SELECT max(version_no)+1 INTO next_version FROM position_function_profile_version WHERE tenant_id=t.id AND profile_id=c.profile_id;
   published_id:=gen_random_uuid();
   UPDATE position_function_profile_version SET lifecycle_status='ARCHIVED',row_version=row_version+1,updated_at=now()
     WHERE tenant_id=t.id AND id=c.id;
   INSERT INTO position_function_profile_version(id,tenant_id,profile_id,version_no,lifecycle_status,copied_from_version_id,
      authorization_scope_type,wecom_self_selectable,created_by,published_by,published_at,row_version)
     VALUES(published_id,t.id,c.profile_id,next_version,'PUBLISHED',c.id,c.authorization_scope_type,c.wecom_self_selectable,
       c.published_by,c.published_by,now(),1);
   INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
     SELECT t.id,published_id,permission_id FROM position_function_profile_permission WHERE tenant_id=t.id AND profile_version_id=c.id;
   INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
     SELECT t.id,published_id,id FROM permission WHERE code='manager-cycle.review';
   INSERT INTO role_permission(tenant_id,role_id,permission_id)
     SELECT t.id,c.default_role_id,id FROM permission WHERE code='manager-cycle.review' ON CONFLICT DO NOTHING;
   UPDATE position_function_profile SET row_version=row_version+1,updated_at=now() WHERE tenant_id=t.id AND id=c.profile_id;
   UPDATE position_definition SET row_version=row_version+1,updated_at=now() WHERE tenant_id=t.id AND id=c.position_id;
   INSERT INTO audit_log(tenant_id,actor_id,action,resource_type,resource_id,correlation_id,after_data)
     VALUES(t.id,NULL,'SYSTEM_MANAGER_CYCLE_REVIEW_PUBLISHED','POSITION_FUNCTION_PROFILE',c.profile_id,gen_random_uuid(),
       jsonb_build_object('source','V73','positionId',c.position_id,'previousVersionId',c.id,'publishedVersionId',published_id,
       'authorizationScopeType',c.authorization_scope_type,'addedPermissions',jsonb_build_array('manager-cycle.review')));
  END LOOP;
 END LOOP;
END $$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
