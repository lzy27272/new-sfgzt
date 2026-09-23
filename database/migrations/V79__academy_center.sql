-- Academy: tenant isolation is established in the same migration as each business table.
ALTER TABLE employee ADD COLUMN employee_type VARCHAR(24)
 CHECK(employee_type IN ('INTERN','REGULAR','SECONDMENT'));
COMMENT ON COLUMN employee.employee_type IS '实习员工 / 正式员工 / 第三方委派（兼职）；NULL仅表示历史资料未补齐';
ALTER TABLE employee ADD COLUMN employee_type_updated_at TIMESTAMPTZ;
ALTER TABLE employee ADD COLUMN employee_type_updated_by UUID;
CREATE INDEX ix_employee_academy_type ON employee(tenant_id,employee_type,id) WHERE deleted_at IS NULL;

INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category) VALUES
 ('ui.module.academy','ui_module','view','学院中心入口',true,'UI_MODULE'),
 ('academy.learn','academy','learn','学习本人课程及参加本人阶段考试',true,'ACADEMY'),
 ('academy.manage','academy','manage','维护课程题库成长地图及追加考试机会',false,'ACADEMY'),
 ('academy.team-read','academy','team-read','查看授权组织的学习进度与成绩',true,'ACADEMY')
ON CONFLICT(code) DO NOTHING;
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$ DECLARE t RECORD; BEGIN
 FOR t IN SELECT id FROM tenant LOOP
  PERFORM set_config('app.tenant_id',t.id::text,true);
  INSERT INTO role_permission(tenant_id,role_id,permission_id)
   SELECT t.id,r.id,p.id FROM app_role r CROSS JOIN permission p
   WHERE r.tenant_id=t.id AND (p.code IN ('ui.module.academy','academy.learn')
    OR (r.code='PLATFORM_ADMIN' AND p.code IN ('academy.manage','academy.team-read')))
   ON CONFLICT DO NOTHING;
  INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
   SELECT t.id,v.id,p.id FROM position_function_profile_version v
   JOIN position_function_profile f ON f.tenant_id=v.tenant_id AND f.id=v.profile_id
   CROSS JOIN permission p WHERE v.tenant_id=t.id AND f.scope_type='GROUP'
    AND p.code IN ('academy.learn','ui.module.academy') ON CONFLICT DO NOTHING;
  INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
   SELECT t.id,v.id,p.id FROM position_function_profile_version v
   JOIN position_function_profile f ON f.tenant_id=v.tenant_id AND f.id=v.profile_id
   CROSS JOIN permission p WHERE v.tenant_id=t.id AND f.scope_type='HOTEL'
    AND p.code IN ('academy.learn','ui.module.academy')
    AND EXISTS(SELECT 1 FROM position_function_profile_permission b
      WHERE b.tenant_id=t.id AND b.profile_version_id=v.based_on_group_version_id AND b.permission_id=p.id)
   ON CONFLICT DO NOTHING;
 END LOOP;
END $$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

CREATE TABLE academy_course (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id),
 family_id UUID NOT NULL, version INTEGER NOT NULL DEFAULT 1 CHECK(version>0),
 title VARCHAR(180) NOT NULL, description VARCHAR(2000) NOT NULL DEFAULT '',
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('VIDEO','PPT')),
 knowledge_tags JSONB NOT NULL DEFAULT '[]',
 state VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','READY','PUBLISHED','RETIRED')),
 object_key VARCHAR(400), original_name VARCHAR(200), sha256 CHAR(64), size_bytes BIGINT,
 duration_seconds NUMERIC(12,3), page_count INTEGER, page_seconds JSONB NOT NULL DEFAULT '[]',
 media_type VARCHAR(120), processing_note VARCHAR(1000),
 revision INTEGER NOT NULL DEFAULT 0, created_by UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), published_at TIMESTAMPTZ,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,family_id,version),
 FOREIGN KEY(tenant_id,created_by) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_academy_course_state ON academy_course(tenant_id,state,created_at DESC);
CREATE TABLE academy_question (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id), course_id UUID NOT NULL,
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('SINGLE','MULTIPLE','BOOLEAN')),
 stem VARCHAR(4000) NOT NULL, options JSONB NOT NULL, answers JSONB NOT NULL,
 explanation VARCHAR(4000) NOT NULL DEFAULT '', knowledge VARCHAR(120) NOT NULL DEFAULT '',
 difficulty VARCHAR(16) NOT NULL DEFAULT 'NORMAL' CHECK(difficulty IN ('EASY','NORMAL','HARD')),
 active BOOLEAN NOT NULL DEFAULT true, version INTEGER NOT NULL DEFAULT 1,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,course_id) REFERENCES academy_course(tenant_id,id)
);
CREATE INDEX ix_academy_question_draw ON academy_question(tenant_id,course_id,kind,difficulty) WHERE active;

CREATE TABLE academy_map (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id), family_id UUID NOT NULL,
 version INTEGER NOT NULL DEFAULT 1, title VARCHAR(180) NOT NULL, position_id UUID NOT NULL,
 scope_org_id UUID, state VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED','RETIRED')),
 revision INTEGER NOT NULL DEFAULT 0, created_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 published_at TIMESTAMPTZ, UNIQUE(tenant_id,id), UNIQUE(tenant_id,family_id,version),
 FOREIGN KEY(tenant_id,position_id) REFERENCES position_definition(tenant_id,id),
 FOREIGN KEY(tenant_id,scope_org_id) REFERENCES org_unit(tenant_id,id)
);
CREATE INDEX ix_academy_map_match ON academy_map(tenant_id,position_id,scope_org_id) WHERE state='PUBLISHED';
CREATE TABLE academy_stage (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, map_id UUID NOT NULL,
 stage_no INTEGER NOT NULL CHECK(stage_no>0), title VARCHAR(180) NOT NULL,
 objective VARCHAR(2000) NOT NULL DEFAULT '', due_days INTEGER CHECK(due_days BETWEEN 1 AND 365),
 exam_rules JSONB NOT NULL, pass_score INTEGER NOT NULL CHECK(pass_score>0),
 exam_minutes INTEGER NOT NULL CHECK(exam_minutes BETWEEN 1 AND 180),
 attempt_limit INTEGER NOT NULL CHECK(attempt_limit BETWEEN 1 AND 20),
 retry_minutes INTEGER NOT NULL CHECK(retry_minutes BETWEEN 0 AND 10080),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,map_id,stage_no), UNIQUE(tenant_id,map_id,id),
 FOREIGN KEY(tenant_id,map_id) REFERENCES academy_map(tenant_id,id)
);
CREATE TABLE academy_stage_course (
 tenant_id UUID NOT NULL, stage_id UUID NOT NULL, course_id UUID NOT NULL, sort_order INTEGER NOT NULL,
 PRIMARY KEY(tenant_id,stage_id,course_id),
 FOREIGN KEY(tenant_id,stage_id) REFERENCES academy_stage(tenant_id,id),
 FOREIGN KEY(tenant_id,course_id) REFERENCES academy_course(tenant_id,id)
);
CREATE INDEX ix_academy_stage_course_course ON academy_stage_course(tenant_id,course_id);

CREATE TABLE academy_plan (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, employee_id UUID NOT NULL, account_id UUID NOT NULL,
 assignment_id UUID NOT NULL, org_id UUID NOT NULL, map_id UUID NOT NULL,
 current_stage INTEGER NOT NULL DEFAULT 1, state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
 CHECK(state IN ('ACTIVE','COMPLETED','SUSPENDED','CANCELLED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,assignment_id),
 FOREIGN KEY(tenant_id,employee_id) REFERENCES employee(tenant_id,id),
 FOREIGN KEY(tenant_id,account_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
 FOREIGN KEY(tenant_id,org_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,map_id) REFERENCES academy_map(tenant_id,id)
);
CREATE INDEX ix_academy_plan_owner ON academy_plan(tenant_id,account_id,state);
CREATE INDEX ix_academy_plan_team ON academy_plan(tenant_id,org_id,state);
CREATE TABLE academy_plan_stage (
 tenant_id UUID NOT NULL, plan_id UUID NOT NULL, stage_id UUID NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'LEARNING'
 CHECK(state IN ('LEARNING','READY','EXAM','RETRY','PASSED','NEEDS_ATTENTION','CONFIG_ERROR')),
 extra_attempts INTEGER NOT NULL DEFAULT 0 CHECK(extra_attempts>=0),
 available_at TIMESTAMPTZ NOT NULL DEFAULT now(), due_at TIMESTAMPTZ,
 passed_at TIMESTAMPTZ, last_error VARCHAR(1000), PRIMARY KEY(tenant_id,plan_id,stage_id),
 FOREIGN KEY(tenant_id,plan_id) REFERENCES academy_plan(tenant_id,id),
 FOREIGN KEY(tenant_id,stage_id) REFERENCES academy_stage(tenant_id,id)
);
CREATE TABLE academy_progress (
 tenant_id UUID NOT NULL, employee_id UUID NOT NULL, course_id UUID NOT NULL,
 watched_seconds NUMERIC(12,3) NOT NULL DEFAULT 0 CHECK(watched_seconds>=0),
 completed_pages INTEGER NOT NULL DEFAULT 0, page_elapsed JSONB NOT NULL DEFAULT '[]',
 completed_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,employee_id,course_id),
 FOREIGN KEY(tenant_id,employee_id) REFERENCES employee(tenant_id,id),
 FOREIGN KEY(tenant_id,course_id) REFERENCES academy_course(tenant_id,id)
);
CREATE TABLE academy_learning_session (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, employee_id UUID NOT NULL, account_id UUID NOT NULL,
 plan_id UUID NOT NULL, course_id UUID NOT NULL, sequence_no INTEGER NOT NULL DEFAULT 0,
 last_position NUMERIC(12,3) NOT NULL DEFAULT 0, page_no INTEGER NOT NULL DEFAULT 1,
 last_seen TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL,
 was_playing BOOLEAN NOT NULL DEFAULT false, was_visible BOOLEAN NOT NULL DEFAULT false,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,employee_id),
 FOREIGN KEY(tenant_id,plan_id) REFERENCES academy_plan(tenant_id,id),
 FOREIGN KEY(tenant_id,course_id) REFERENCES academy_course(tenant_id,id)
);
CREATE TABLE academy_exam (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, plan_id UUID NOT NULL, stage_id UUID NOT NULL,
 attempt_no INTEGER NOT NULL CHECK(attempt_no>0),
 state VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','RUNNING','PASSED','FAILED')),
 paper JSONB NOT NULL, responses JSONB NOT NULL DEFAULT '{}',
 total_score INTEGER NOT NULL, pass_score INTEGER NOT NULL, minutes INTEGER NOT NULL,
 score INTEGER, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), started_at TIMESTAMPTZ,
 deadline TIMESTAMPTZ, submitted_at TIMESTAMPTZ, version INTEGER NOT NULL DEFAULT 0,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,plan_id,stage_id,attempt_no),
 FOREIGN KEY(tenant_id,plan_id) REFERENCES academy_plan(tenant_id,id),
 FOREIGN KEY(tenant_id,stage_id) REFERENCES academy_stage(tenant_id,id)
);
CREATE INDEX ix_academy_exam_due ON academy_exam(tenant_id,deadline) WHERE state='RUNNING';
CREATE TABLE academy_dispatch_queue (
 tenant_id UUID NOT NULL, employee_id UUID NOT NULL, requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,employee_id), FOREIGN KEY(tenant_id,employee_id) REFERENCES employee(tenant_id,id)
);
CREATE TABLE academy_dispatch_issue (
 tenant_id UUID NOT NULL, assignment_id UUID NOT NULL, employee_id UUID NOT NULL,
 org_id UUID NOT NULL, reason VARCHAR(1000) NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,assignment_id),
 FOREIGN KEY(tenant_id,assignment_id) REFERENCES employee_position_assignment(tenant_id,id)
);
CREATE FUNCTION academy_enqueue_employee() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE emp UUID;
BEGIN
 IF TG_TABLE_NAME='employee' THEN emp:=NEW.id;
 ELSIF TG_TABLE_NAME='employee_position_assignment' THEN emp:=NEW.employee_id;
 ELSE SELECT id INTO emp FROM employee WHERE tenant_id=NEW.tenant_id AND account_id=NEW.account_id;
 END IF;
 IF emp IS NOT NULL THEN
  INSERT INTO academy_dispatch_queue(tenant_id,employee_id) VALUES(NEW.tenant_id,emp)
   ON CONFLICT(tenant_id,employee_id) DO UPDATE SET requested_at=now();
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER academy_employee_changed AFTER INSERT OR UPDATE OF employee_type,employment_status,account_id
 ON employee FOR EACH ROW EXECUTE FUNCTION academy_enqueue_employee();
CREATE TRIGGER academy_assignment_changed AFTER INSERT OR UPDATE OF status,position_id,org_unit_id,valid_from,valid_to
 ON employee_position_assignment FOR EACH ROW EXECUTE FUNCTION academy_enqueue_employee();
CREATE TRIGGER academy_binding_changed AFTER INSERT OR UPDATE OF status,account_id
 ON wecom_user_binding FOR EACH ROW EXECUTE FUNCTION academy_enqueue_employee();

DO $$ DECLARE tab TEXT; BEGIN
 FOREACH tab IN ARRAY ARRAY['academy_course','academy_question','academy_map','academy_stage','academy_stage_course',
 'academy_plan','academy_plan_stage','academy_progress','academy_learning_session','academy_exam',
 'academy_dispatch_queue','academy_dispatch_issue'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',tab);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
   EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO hotel_ai_os_app',tab);
  END IF;
 END LOOP;
END $$;
