-- Guest property is a custody ledger, never hotel stock or a cost journal.
CREATE TABLE lost_property_case (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenant(id),
 hotel_org_unit_id UUID NOT NULL, report_id UUID NOT NULL, entry_id UUID NOT NULL,
 source_revision_id UUID NOT NULL, reporter_assignment_id UUID NOT NULL,
 recipient_assignment_id UUID NOT NULL, item_name VARCHAR(160) NOT NULL,
 quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 10000),
 remaining_quantity INTEGER NOT NULL DEFAULT 0 CHECK(remaining_quantity>=0 AND remaining_quantity<=quantity),
 state VARCHAR(24) NOT NULL CHECK(state IN ('PENDING','RETURNED','IN_CUSTODY','COLLECTED')),
 payload JSONB NOT NULL, storage_location VARCHAR(240), review_note VARCHAR(1000),
 submission_no INTEGER NOT NULL DEFAULT 1, row_version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 received_at TIMESTAMPTZ, received_by UUID,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,report_id,entry_id),
 FOREIGN KEY(tenant_id,hotel_org_unit_id) REFERENCES org_unit(tenant_id,id),
 FOREIGN KEY(tenant_id,report_id) REFERENCES daily_report(tenant_id,id),
 FOREIGN KEY(tenant_id,source_revision_id) REFERENCES daily_report_revision(tenant_id,id),
 FOREIGN KEY(tenant_id,reporter_assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
 FOREIGN KEY(tenant_id,recipient_assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
 FOREIGN KEY(tenant_id,received_by) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_lost_property_hotel ON lost_property_case(tenant_id,hotel_org_unit_id,state,created_at DESC);
CREATE INDEX ix_lost_property_recipient ON lost_property_case(tenant_id,recipient_assignment_id,state);
CREATE INDEX ix_lost_property_reporter ON lost_property_case(tenant_id,reporter_assignment_id);
CREATE INDEX ix_lost_property_revision ON lost_property_case(tenant_id,source_revision_id);
CREATE INDEX ix_lost_property_received_by ON lost_property_case(tenant_id,received_by);
CREATE TABLE lost_property_event (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, case_id UUID NOT NULL,
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('SUBMITTED','RESUBMITTED','RECEIVED','RETURNED','RELEASED')),
 actor_account_id UUID NOT NULL, actor_assignment_id UUID, request_key VARCHAR(180) NOT NULL,
 request_hash CHAR(64) NOT NULL, payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,case_id,request_key),
 FOREIGN KEY(tenant_id,case_id) REFERENCES lost_property_case(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_account_id) REFERENCES user_account(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_assignment_id) REFERENCES employee_position_assignment(tenant_id,id)
);
CREATE INDEX ix_lost_property_event_actor ON lost_property_event(tenant_id,actor_account_id);
CREATE INDEX ix_lost_property_event_assignment ON lost_property_event(tenant_id,actor_assignment_id);
CREATE TABLE daily_activity_photo (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL, report_id UUID, case_id UUID,
 object_key VARCHAR(250) NOT NULL, original_name VARCHAR(180) NOT NULL,
 media_type VARCHAR(32) NOT NULL CHECK(media_type IN ('image/jpeg','image/png')),
 size_bytes BIGINT NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520), sha256 CHAR(64) NOT NULL,
 scan_status VARCHAR(40) NOT NULL, uploaded_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK((report_id IS NULL) <> (case_id IS NULL)), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,report_id) REFERENCES daily_report(tenant_id,id),
 FOREIGN KEY(tenant_id,case_id) REFERENCES lost_property_case(tenant_id,id),
 FOREIGN KEY(tenant_id,uploaded_by) REFERENCES user_account(tenant_id,id)
);
CREATE INDEX ix_daily_activity_photo_report ON daily_activity_photo(tenant_id,report_id);
CREATE INDEX ix_daily_activity_photo_case ON daily_activity_photo(tenant_id,case_id);
CREATE INDEX ix_daily_activity_photo_uploader ON daily_activity_photo(tenant_id,uploaded_by);
CREATE TRIGGER daily_activity_image_lifecycle AFTER INSERT ON daily_activity_photo
 FOR EACH ROW EXECUTE FUNCTION register_image_retention_object();
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['lost_property_case','lost_property_event','daily_activity_photo'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',t);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
   EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO hotel_ai_os_app',t);
  END IF;
 END LOOP;
END $$;
-- Evidence and event history are append-only, including for privileged maintenance callers.
CREATE FUNCTION reject_daily_activity_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Daily activity evidence and custody history are immutable'; END $$;
CREATE TRIGGER lost_property_event_immutable BEFORE UPDATE OR DELETE ON lost_property_event
 FOR EACH ROW EXECUTE FUNCTION reject_daily_activity_evidence_mutation();
CREATE TRIGGER daily_activity_photo_immutable BEFORE UPDATE OR DELETE ON daily_activity_photo
 FOR EACH ROW EXECUTE FUNCTION reject_daily_activity_evidence_mutation();
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
  REVOKE UPDATE ON lost_property_event,daily_activity_photo FROM hotel_ai_os_app;
 END IF;
END $$;
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category) VALUES
 ('ui.module.lost-properties','ui_module','view','遗留物品保管入口',true,'UI_MODULE'),
 ('lost-property.read','lost-property','read','查看授权门店遗留物品保管清单',true,'STORE_RESOURCE'),
 ('lost-property.receive','lost-property','receive','审核本人被指定接收的遗留物并入库',true,'STORE_RESOURCE'),
 ('lost-property.release','lost-property','release','核验客人领取并登记遗留物出库',true,'STORE_RESOURCE')
ON CONFLICT(code) DO NOTHING;
-- Front desk defaults only; other positions are explicitly configured by administrators.
-- Copy published group profiles, retaining old versions and un-published administrator drafts.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$ DECLARE t RECORD; c RECORD; next_version INTEGER; new_id UUID; BEGIN
 FOR t IN SELECT id FROM tenant LOOP
  PERFORM set_config('app.tenant_id',t.id::text,true);
  -- Only untouched current/future placeholders are cancelled; uploaded drafts and historical evidence remain.
  WITH changed AS (
   UPDATE work_expectation x SET status='CANCELLED',cancellation_reason='服务案例与遗留物合并到日报统一填报',
    cancelled_by_account_id=e.account_id,cancelled_at=now(),row_version=x.row_version+1
   FROM work_package_item i,employee_position_assignment a,employee e,tenant tn
   WHERE x.tenant_id=t.id AND i.tenant_id=x.tenant_id AND i.id=x.work_package_item_id
    AND i.item_code IN ('HKA_GUEST_SERVICE_EVENT','HKA_LOST_PROPERTY')
    AND a.tenant_id=x.tenant_id AND a.id=x.position_assignment_id AND e.tenant_id=a.tenant_id AND e.id=a.employee_id
    AND e.account_id IS NOT NULL AND tn.id=x.tenant_id
    AND x.business_date>=(now() AT TIME ZONE tn.timezone)::date
    AND x.status IN ('PLANNED','AVAILABLE','MISSED')
    AND NOT EXISTS(SELECT 1 FROM work_record w WHERE w.tenant_id=x.tenant_id AND w.work_expectation_id=x.id)
   RETURNING x.id
  )
  INSERT INTO audit_log(tenant_id,action,resource_type,resource_id,correlation_id,after_data)
   SELECT t.id,'SYSTEM_DAILY_ACTIVITY_ENTRY_MERGED','WORK_EXPECTATION',id,gen_random_uuid(),
     '{"source":"V81","reason":"merged into daily report; history retained"}'::jsonb FROM changed;
  INSERT INTO role_permission(tenant_id,role_id,permission_id)
   SELECT t.id,r.id,p.id FROM app_role r CROSS JOIN permission p
   WHERE r.tenant_id=t.id AND r.code IN ('PLATFORM_ADMIN','FRONT_DESK') AND r.role_type='SYSTEM'
    AND p.code IN ('ui.module.lost-properties','lost-property.read','lost-property.receive','lost-property.release')
   ON CONFLICT DO NOTHING;
  FOR c IN SELECT f.id profile_id,f.position_id,v.* FROM position_function_profile f
   JOIN app_role r ON r.tenant_id=f.tenant_id AND r.id=f.default_role_id
   JOIN position_function_profile_version v ON v.tenant_id=f.tenant_id AND v.profile_id=f.id
   WHERE f.tenant_id=t.id AND f.scope_type='GROUP' AND r.code='FRONT_DESK' AND r.role_type='SYSTEM'
     AND v.lifecycle_status='PUBLISHED'
  LOOP
   SELECT max(version_no)+1 INTO next_version FROM position_function_profile_version WHERE tenant_id=t.id AND profile_id=c.profile_id;
   new_id:=gen_random_uuid();
   UPDATE position_function_profile_version SET lifecycle_status='ARCHIVED',row_version=row_version+1,updated_at=now() WHERE tenant_id=t.id AND id=c.id;
   INSERT INTO position_function_profile_version(id,tenant_id,profile_id,version_no,lifecycle_status,copied_from_version_id,
     authorization_scope_type,wecom_self_selectable,created_by,published_by,published_at,row_version)
    VALUES(new_id,t.id,c.profile_id,next_version,'PUBLISHED',c.id,c.authorization_scope_type,c.wecom_self_selectable,
      c.published_by,c.published_by,now(),1);
   INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
    SELECT t.id,new_id,permission_id FROM position_function_profile_permission WHERE tenant_id=t.id AND profile_version_id=c.id;
   INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
    SELECT t.id,new_id,id FROM permission WHERE code IN ('ui.module.lost-properties','lost-property.read','lost-property.receive','lost-property.release') ON CONFLICT DO NOTHING;
   UPDATE position_function_profile SET row_version=row_version+1,updated_at=now() WHERE tenant_id=t.id AND id=c.profile_id;
   UPDATE position_definition SET row_version=row_version+1,updated_at=now() WHERE tenant_id=t.id AND id=c.position_id;
   INSERT INTO audit_log(tenant_id,action,resource_type,resource_id,correlation_id,after_data)
    VALUES(t.id,'SYSTEM_LOST_PROPERTY_PROFILE_PUBLISHED','POSITION_FUNCTION_PROFILE',c.profile_id,gen_random_uuid(),
     jsonb_build_object('source','V81','previousVersionId',c.id,'publishedVersionId',new_id,'authorizationScopeType',c.authorization_scope_type));
  END LOOP;
 END LOOP;
END $$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
