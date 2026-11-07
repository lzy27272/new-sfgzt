-- Operations plans reuse the governed V39 revisions, reviews and task provenance.
-- No employee, hotel portfolio or old OTA inspection assignment is created.
ALTER TABLE management_work_plan ADD COLUMN operations_plan boolean NOT NULL DEFAULT false;
ALTER TABLE management_work_plan ADD COLUMN due_at timestamptz;
ALTER TABLE management_work_plan ADD CONSTRAINT ck_operations_plan_due CHECK (NOT operations_plan OR due_at IS NOT NULL);
ALTER TABLE management_work_plan_revision ADD COLUMN operations_payload jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE management_work_plan_revision ADD COLUMN operations_draft_version bigint NOT NULL DEFAULT 0;
ALTER TABLE management_work_plan_item ADD COLUMN action_id uuid;
ALTER TABLE management_work_plan_item ADD COLUMN hotel_id uuid;
ALTER TABLE management_work_plan_item ADD COLUMN acceptance_criteria text;
ALTER TABLE management_work_plan_item ADD COLUMN linked_task_id uuid;
ALTER TABLE management_work_plan_item ADD COLUMN linked_expectation_id uuid;
ALTER TABLE management_work_plan_item ADD FOREIGN KEY (tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id);
ALTER TABLE management_work_plan_item ADD FOREIGN KEY (tenant_id,linked_task_id) REFERENCES management_task(tenant_id,id);
ALTER TABLE management_work_plan_item ADD FOREIGN KEY (tenant_id,linked_expectation_id) REFERENCES work_expectation(tenant_id,id);
ALTER TABLE management_work_plan_item ADD CONSTRAINT ck_operations_single_link CHECK (linked_task_id IS NULL OR linked_expectation_id IS NULL);
CREATE UNIQUE INDEX ux_operations_revision_action ON management_work_plan_item(tenant_id,revision_id,action_id) WHERE action_id IS NOT NULL;

CREATE TABLE operations_plan_action (
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  owner_assignment_id uuid NOT NULL,
  action_id uuid NOT NULL,
  hotel_id uuid NOT NULL,
  task_id uuid NOT NULL,
  first_plan_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id,owner_assignment_id,action_id),
  UNIQUE(tenant_id,task_id),
  FOREIGN KEY(tenant_id,owner_assignment_id) REFERENCES employee_position_assignment(tenant_id,id),
  FOREIGN KEY(tenant_id,hotel_id) REFERENCES org_unit(tenant_id,id),
  FOREIGN KEY(tenant_id,task_id) REFERENCES management_task(tenant_id,id),
  FOREIGN KEY(tenant_id,first_plan_id) REFERENCES management_work_plan(tenant_id,id)
);
ALTER TABLE operations_plan_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE operations_plan_action FORCE ROW LEVEL SECURITY;
CREATE POLICY operations_plan_action_tenant ON operations_plan_action
  USING (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE INDEX ix_operations_plan_due ON management_work_plan(tenant_id,due_at,status) WHERE operations_plan;

CREATE OR REPLACE FUNCTION enforce_work_plan_revision_immutability()
RETURNS trigger AS $$
BEGIN
  IF OLD.status <> 'DRAFT' AND ROW(NEW.tenant_id,NEW.work_plan_id,NEW.revision_no,NEW.summary,
      NEW.operations_payload,NEW.created_by_assignment_id,NEW.created_at)
      IS DISTINCT FROM ROW(OLD.tenant_id,OLD.work_plan_id,OLD.revision_no,OLD.summary,
      OLD.operations_payload,OLD.created_by_assignment_id,OLD.created_at) THEN
    RAISE EXCEPTION 'Submitted work-plan revision is immutable';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Existing leaders receive only the operations review navigation and operation permissions.
-- Review still requires an effective reporting relation and the target hotel's authorization.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$
DECLARE tenant_key uuid; current_profile record; published_id uuid;
BEGIN
FOR tenant_key IN SELECT id FROM tenant LOOP
  PERFORM set_config('app.tenant_id',tenant_key::text,true);
  FOR current_profile IN
    SELECT f.id as profile_id,f.position_id,v.id as version_id,v.authorization_scope_type,
           v.based_on_group_version_id,v.wecom_self_selectable,v.published_by
    FROM position_function_profile f
    JOIN app_role r ON r.tenant_id=f.tenant_id AND r.id=f.default_role_id
    JOIN LATERAL(SELECT version.* FROM position_function_profile_version version
      WHERE version.tenant_id=f.tenant_id AND version.profile_id=f.id AND version.lifecycle_status='PUBLISHED'
      ORDER BY version.version_no DESC LIMIT 1) v ON true
    WHERE f.tenant_id=tenant_key AND f.scope_type='GROUP'
      AND r.code IN ('CEO','GROUP_VICE_PRESIDENT','GROUP_CHAIRMAN','PLATFORM_ADMIN','GROUP_ADMIN')
      AND EXISTS(SELECT 1 FROM permission p WHERE p.code IN('ui.module.operations-work','work-plan.read','work-plan.team-read','work-plan.review')
        AND NOT EXISTS(SELECT 1 FROM position_function_profile_permission have WHERE have.tenant_id=f.tenant_id
          AND have.profile_version_id=v.id AND have.permission_id=p.id))
  LOOP
    published_id:=gen_random_uuid();
    UPDATE position_function_profile_version SET lifecycle_status='ARCHIVED',row_version=row_version+1,updated_at=now()
      WHERE tenant_id=tenant_key AND id=current_profile.version_id;
    INSERT INTO position_function_profile_version(id,tenant_id,profile_id,version_no,lifecycle_status,
        copied_from_version_id,based_on_group_version_id,authorization_scope_type,wecom_self_selectable,
        created_by,published_by,published_at,row_version)
      SELECT published_id,tenant_key,current_profile.profile_id,coalesce(max(version_no),0)+1,'PUBLISHED',
             current_profile.version_id,current_profile.based_on_group_version_id,current_profile.authorization_scope_type,
             current_profile.wecom_self_selectable,current_profile.published_by,current_profile.published_by,now(),1
      FROM position_function_profile_version WHERE tenant_id=tenant_key AND profile_id=current_profile.profile_id;
    INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
      SELECT tenant_key,published_id,permission_id FROM position_function_profile_permission
      WHERE tenant_id=tenant_key AND profile_version_id=current_profile.version_id;
    INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
      SELECT tenant_key,published_id,id FROM permission
      WHERE code IN('ui.module.operations-work','work-plan.read','work-plan.team-read','work-plan.review') ON CONFLICT DO NOTHING;
    UPDATE position_function_profile SET row_version=row_version+1,updated_at=now() WHERE tenant_id=tenant_key AND id=current_profile.profile_id;
    UPDATE position_definition SET row_version=row_version+1,updated_at=now() WHERE tenant_id=tenant_key AND id=current_profile.position_id;
    INSERT INTO audit_log(tenant_id,action,resource_type,resource_id,correlation_id,after_data)
      VALUES(tenant_key,'OPERATIONS_PLAN_REVIEW_PROFILE_PUBLISHED','POSITION_FUNCTION_PROFILE',current_profile.profile_id,
        gen_random_uuid(),jsonb_build_object('source','V122','previousVersionId',current_profile.version_id,'publishedVersionId',published_id));
  END LOOP;
END LOOP;
IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='hotel_ai_os_app') THEN
  GRANT SELECT,INSERT,UPDATE,DELETE ON operations_plan_action TO hotel_ai_os_app;
END IF;
END $$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
