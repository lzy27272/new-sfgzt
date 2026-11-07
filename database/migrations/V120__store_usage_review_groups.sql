-- Explicit store review groups share one approval: the first successful review posts usage.
-- Keep the original reviewer column and immutable submission snapshots for compatibility.
CREATE TABLE store_usage_review_config_member (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    hotel_org_unit_id UUID NOT NULL,
    reviewer_assignment_id UUID NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, hotel_org_unit_id, reviewer_assignment_id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id)
        REFERENCES store_usage_review_config(tenant_id, hotel_org_unit_id),
    FOREIGN KEY (tenant_id, reviewer_assignment_id)
        REFERENCES employee_position_assignment(tenant_id, id),
    FOREIGN KEY (tenant_id, created_by)
        REFERENCES user_account(tenant_id, id)
);

CREATE INDEX ix_store_usage_review_config_member_assignment
    ON store_usage_review_config_member(tenant_id, reviewer_assignment_id);

ALTER TABLE store_usage_review_config_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE store_usage_review_config_member FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON store_usage_review_config_member
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Capability to be explicitly assigned to a review group; this grants no stock access or group membership.
INSERT INTO permission(code,resource,action,description,delegable_to_position,function_category)
VALUES('store-resource.usage-review','store_resource','usage-review',
       '按门店显式指定审核耗用；不授予库存查看、管理或自动审批权限',true,'STORE_RESOURCE')
ON CONFLICT(code) DO NOTHING;

ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
DO $$
DECLARE tenant_record RECORD; profile_record RECORD; next_version INTEGER; published_id UUID;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        INSERT INTO store_usage_review_config_member
            (tenant_id, hotel_org_unit_id, reviewer_assignment_id, created_by, created_at)
        SELECT tenant_id, hotel_org_unit_id, reviewer_assignment_id, updated_by, updated_at
        FROM store_usage_review_config
        WHERE tenant_id = tenant_record.id;

        INSERT INTO role_permission(tenant_id,role_id,permission_id)
        SELECT tenant_record.id, role_item.id, permission_item.id
        FROM app_role role_item CROSS JOIN permission permission_item
        WHERE role_item.tenant_id=tenant_record.id AND role_item.role_type='SYSTEM'
          AND role_item.code IN ('GENERAL_MANAGER','ASSISTANT_GENERAL_MANAGER')
          AND permission_item.code='store-resource.usage-review'
        ON CONFLICT DO NOTHING;

        -- Preserve published history, all existing permissions and unpublished administrator edits.
        FOR profile_record IN
            SELECT profile.position_id, version.*
            FROM position_function_profile profile
            JOIN position_definition position_item
              ON position_item.tenant_id=profile.tenant_id AND position_item.id=profile.position_id
            JOIN app_role role_item
              ON role_item.tenant_id=profile.tenant_id AND role_item.id=profile.default_role_id
             AND role_item.role_type='SYSTEM' AND role_item.code=position_item.code
            JOIN position_function_profile_version version
              ON version.tenant_id=profile.tenant_id AND version.profile_id=profile.id
            WHERE profile.tenant_id=tenant_record.id AND profile.scope_type='GROUP'
              AND position_item.code IN ('GENERAL_MANAGER','ASSISTANT_GENERAL_MANAGER')
              AND version.lifecycle_status='PUBLISHED'
              AND NOT EXISTS (
                  SELECT 1 FROM position_function_profile_permission granted
                  JOIN permission permission_item ON permission_item.id=granted.permission_id
                  WHERE granted.tenant_id=profile.tenant_id AND granted.profile_version_id=version.id
                    AND permission_item.code='store-resource.usage-review')
            FOR UPDATE OF profile, version
        LOOP
            SELECT max(version_no)+1 INTO next_version FROM position_function_profile_version
            WHERE tenant_id=tenant_record.id AND profile_id=profile_record.profile_id;
            published_id:=gen_random_uuid();
            UPDATE position_function_profile_version
            SET lifecycle_status='ARCHIVED',row_version=row_version+1,updated_at=now()
            WHERE tenant_id=tenant_record.id AND id=profile_record.id;
            INSERT INTO position_function_profile_version
                (id,tenant_id,profile_id,version_no,lifecycle_status,copied_from_version_id,
                 based_on_group_version_id,authorization_scope_type,wecom_self_selectable,
                 created_by,published_by,published_at,row_version)
            VALUES(published_id,tenant_record.id,profile_record.profile_id,next_version,'PUBLISHED',profile_record.id,
                   profile_record.based_on_group_version_id,profile_record.authorization_scope_type,
                   profile_record.wecom_self_selectable,profile_record.published_by,profile_record.published_by,now(),1);
            INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
            SELECT tenant_record.id,published_id,permission_id FROM position_function_profile_permission
            WHERE tenant_id=tenant_record.id AND profile_version_id=profile_record.id;
            INSERT INTO position_function_profile_permission(tenant_id,profile_version_id,permission_id)
            SELECT tenant_record.id,published_id,id FROM permission WHERE code='store-resource.usage-review';
            UPDATE position_function_profile SET row_version=row_version+1,updated_at=now()
            WHERE tenant_id=tenant_record.id AND id=profile_record.profile_id;
            UPDATE position_definition SET row_version=row_version+1,updated_at=now()
            WHERE tenant_id=tenant_record.id AND id=profile_record.position_id;
            INSERT INTO audit_log(tenant_id,action,resource_type,resource_id,correlation_id,after_data)
            VALUES(tenant_record.id,'SYSTEM_USAGE_REVIEW_PROFILE_PUBLISHED','POSITION_FUNCTION_PROFILE',
                   profile_record.profile_id,gen_random_uuid(),jsonb_build_object('source','V120',
                     'previousVersionId',profile_record.id,'publishedVersionId',published_id,
                     'authorizationScopeType',profile_record.authorization_scope_type,
                     'addedPermissions',jsonb_build_array('store-resource.usage-review'),
                     'reviewGroupMembershipChanged',false,'stockAccessGranted',false));
        END LOOP;
    END LOOP;
END $$;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hotel_ai_os_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON store_usage_review_config_member TO hotel_ai_os_app;
    END IF;
END $$;

COMMENT ON TABLE store_usage_review_config_member IS
    'Explicit eligible reviewer assignments; one review completes pending usage without changing historical snapshots.';
