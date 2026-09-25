-- Versioned repair: retain scope, menus, existing grants and all draft edits.
-- No changes to assigned hotels, reporting lines or inventory reviewer configuration.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$
DECLARE t record; c record; next_version integer; new_id uuid;
BEGIN
 FOR t IN SELECT id FROM tenant LOOP
  PERFORM set_config('app.tenant_id',t.id::text,true);
  INSERT INTO role_permission(tenant_id,role_id,permission_id)
   SELECT t.id,r.id,p.id FROM app_role r CROSS JOIN permission p
   WHERE r.tenant_id=t.id AND r.role_type='SYSTEM' AND r.code IN ('GROUP_VICE_PRESIDENT','GENERAL_MANAGER')
     AND p.code IN ('work-record.read','work-record.team-read','work-record.review',
                    'daily-report.read','daily-report.team-read','daily-report.review','daily-report.revision-review')
   ON CONFLICT DO NOTHING;
  FOR c IN SELECT f.position_id,v.* FROM position_function_profile f
   JOIN position_function_profile group_profile ON group_profile.tenant_id=f.tenant_id
     AND group_profile.position_id=f.position_id AND group_profile.scope_type='GROUP'
   JOIN app_role r ON r.tenant_id=group_profile.tenant_id AND r.id=group_profile.default_role_id
   JOIN position_function_profile_version v ON v.tenant_id=f.tenant_id AND v.profile_id=f.id
   WHERE f.tenant_id=t.id AND r.role_type='SYSTEM' AND r.code IN ('GROUP_VICE_PRESIDENT','GENERAL_MANAGER')
     AND v.lifecycle_status='PUBLISHED' AND EXISTS (
       SELECT 1 FROM permission p WHERE p.code IN ('work-record.read','work-record.team-read','work-record.review',
         'daily-report.read','daily-report.team-read','daily-report.review','daily-report.revision-review')
       AND NOT EXISTS (SELECT 1 FROM position_function_profile_permission granted
         WHERE granted.tenant_id=t.id AND granted.profile_version_id=v.id AND granted.permission_id=p.id))
  LOOP
   SELECT max(version_no)+1 INTO next_version FROM position_function_profile_version WHERE tenant_id=t.id AND profile_id=c.profile_id;
   new_id:=gen_random_uuid();
   UPDATE position_function_profile_version SET lifecycle_status='ARCHIVED',row_version=row_version+1,updated_at=now() WHERE tenant_id=t.id AND id=c.id;
   INSERT INTO position_function_profile_version(id,tenant_id,profile_id,version_no,lifecycle_status,copied_from_version_id,
     based_on_group_version_id,authorization_scope_type,wecom_self_selectable,created_by,published_by,published_at,row_version)
    VALUES(new_id,t.id,c.profile_id,next_version,'PUBLISHED',c.id,c.based_on_group_version_id,c.authorization_scope_type,
      c.wecom_self_selectable,c.published_by,c.published_by,now(),1);
   INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
    SELECT t.id,new_id,permission_id FROM position_function_profile_permission WHERE tenant_id=t.id AND profile_version_id=c.id;
   INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
    SELECT t.id,new_id,id FROM permission WHERE code IN ('work-record.read','work-record.team-read','work-record.review',
      'daily-report.read','daily-report.team-read','daily-report.review','daily-report.revision-review') ON CONFLICT DO NOTHING;
   UPDATE position_function_profile SET row_version=row_version+1,updated_at=now() WHERE tenant_id=t.id AND id=c.profile_id;
   UPDATE position_definition SET row_version=row_version+1,updated_at=now() WHERE tenant_id=t.id AND id=c.position_id;
   INSERT INTO audit_log(tenant_id,action,resource_type,resource_id,correlation_id,after_data)
    VALUES(t.id,'SYSTEM_MANAGEMENT_REVIEW_PROFILE_PUBLISHED','POSITION_FUNCTION_PROFILE',c.profile_id,gen_random_uuid(),
     jsonb_build_object('source','V85','previousVersionId',c.id,'publishedVersionId',new_id,'authorizationScopeType',c.authorization_scope_type));
  END LOOP;
 END LOOP;
END $$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
