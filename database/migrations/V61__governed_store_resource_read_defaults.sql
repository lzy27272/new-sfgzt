-- Read-only rollout before stock-loss/adjustment approvals are available.
-- Canonical position -> role mappings: GENERAL_MANAGER -> GENERAL_MANAGER,
-- GROUP_GENERAL_MANAGER -> CEO, GROUP_VICE_PRESIDENT -> GROUP_VICE_PRESIDENT.
-- Only demonstrably untouched release defaults are automatically published.
-- Human-managed versions and all hotel overrides require the existing explicit
-- position-profile review/publish workflow; absence of a grant is not consent.
-- Historical permission sets and every assignment/org scope remain unchanged.

-- Established V36/V52 tenant discovery for the NOBYPASSRLS migration owner.
-- All tenant business tables keep FORCE RLS throughout this transaction.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    tenant_record RECORD;
    candidate RECORD;
    published_id UUID;
    draft_id UUID;
    next_version INTEGER;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        FOR candidate IN
            SELECT position_item.id AS position_id, position_item.code AS position_code,
                   profile.id AS profile_id, profile.default_role_id,
                   version.id AS old_version_id, version.authorization_scope_type,
                   version.wecom_self_selectable, version.published_by
            FROM position_definition position_item
            JOIN position_function_profile profile
              ON profile.tenant_id = position_item.tenant_id
             AND profile.position_id = position_item.id AND profile.scope_type = 'GROUP'
            JOIN app_role role_item
              ON role_item.tenant_id = profile.tenant_id AND role_item.id = profile.default_role_id
             AND role_item.role_type = 'SYSTEM'
            JOIN position_function_profile_version version
              ON version.tenant_id = profile.tenant_id AND version.profile_id = profile.id
             AND version.lifecycle_status = 'PUBLISHED' AND version.version_no = 1
            WHERE position_item.tenant_id = tenant_record.id
              AND position_item.status = 'ACTIVE'
              AND position_item.deleted_at IS NULL AND position_item.permanently_deleted_at IS NULL
              AND position_item.applies_to_all_hotels = true
              AND version.authorization_scope_type = 'ORG_TREE'
              AND version.based_on_group_version_id IS NULL
              AND version.copied_from_version_id IS NULL
              -- Account-level CEO identities consume legacy role grants, so
              -- merely leaving hotel override rows unchanged is insufficient.
              -- Any override makes this whole position explicitly governed.
              AND NOT EXISTS (
                  SELECT 1 FROM position_function_profile hotel_profile
                  WHERE hotel_profile.tenant_id = profile.tenant_id
                    AND hotel_profile.position_id = position_item.id
                    AND hotel_profile.scope_type = 'HOTEL'
              )
              AND (
                  (position_item.code IN ('GENERAL_MANAGER', 'GROUP_VICE_PRESIDENT')
                   AND role_item.code = position_item.code
                   AND profile.row_version = 1 AND position_item.row_version = 1
                   AND version.row_version = CASE position_item.code WHEN 'GENERAL_MANAGER' THEN 2 ELSE 1 END
                   AND EXISTS (
                       SELECT 1 FROM audit_log audit
                       WHERE audit.tenant_id = profile.tenant_id
                         AND audit.action = 'SYSTEM_POSITION_PROFILE_DEFAULT_PUBLISHED'
                         AND audit.resource_type = 'POSITION_FUNCTION_PROFILE'
                         AND audit.resource_id = profile.id
                         AND audit.after_data ->> 'source' IN ('V35', 'V36')
                         AND audit.after_data ->> 'positionId' = position_item.id::text
                   ))
                  OR
                  (position_item.code = 'GROUP_GENERAL_MANAGER' AND role_item.code = 'CEO'
                   AND profile.row_version = 0 AND position_item.row_version = 0
                   AND version.row_version = 0
                   AND version.created_at = profile.created_at
                   AND version.created_by = version.published_by)
              )
              AND NOT EXISTS (
                  SELECT 1 FROM position_function_profile_version other
                  WHERE other.tenant_id = profile.tenant_id AND other.profile_id = profile.id
                    AND other.id <> version.id
                    AND NOT (
                        other.lifecycle_status = 'DRAFT' AND other.version_no = 2
                        AND other.copied_from_version_id = version.id
                        AND other.row_version = CASE position_item.code WHEN 'GENERAL_MANAGER' THEN 1 ELSE 0 END
                        AND other.authorization_scope_type = version.authorization_scope_type
                        AND other.wecom_self_selectable = version.wecom_self_selectable
                        AND other.based_on_group_version_id IS NULL
                        AND ARRAY(SELECT permission_id FROM position_function_profile_permission
                                  WHERE tenant_id = profile.tenant_id AND profile_version_id = other.id
                                  ORDER BY permission_id)
                            = ARRAY(SELECT permission_id FROM position_function_profile_permission
                                    WHERE tenant_id = profile.tenant_id AND profile_version_id = version.id
                                    ORDER BY permission_id)
                    )
              )
              AND NOT EXISTS (
                  SELECT 1 FROM audit_log audit
                  WHERE audit.tenant_id = profile.tenant_id
                    AND (audit.action LIKE 'POSITION_PROFILE_%' OR audit.action IN
                         ('POSITION_UPDATED', 'POSITION_SOFT_DELETED', 'POSITION_RESTORED'))
                    AND (audit.resource_id = position_item.id
                         OR audit.after_data ->> 'positionId' = position_item.id::text
                         OR audit.resource_id IN (
                             SELECT id FROM position_function_profile_version
                             WHERE tenant_id = profile.tenant_id AND profile_id = profile.id))
              )
              AND NOT EXISTS (
                  SELECT 1 FROM position_function_profile_permission item
                  JOIN permission permission_item ON permission_item.id = item.permission_id
                  WHERE item.tenant_id = profile.tenant_id AND item.profile_version_id = version.id
                    AND permission_item.code IN ('ui.module.store-resources', 'store-resource.read')
              )
            ORDER BY position_item.id
            FOR UPDATE OF position_item, profile, version
        LOOP
            SELECT max(version_no) + 1 INTO next_version
            FROM position_function_profile_version
            WHERE tenant_id = tenant_record.id AND profile_id = candidate.profile_id;
            published_id := gen_random_uuid();
            draft_id := gen_random_uuid();

            -- Archive only release-owned versions; never rewrite their grants.
            UPDATE position_function_profile_version
            SET lifecycle_status = 'ARCHIVED', row_version = row_version + 1, updated_at = now()
            WHERE tenant_id = tenant_record.id AND profile_id = candidate.profile_id
              AND lifecycle_status IN ('PUBLISHED', 'DRAFT');

            INSERT INTO position_function_profile_version
                (id, tenant_id, profile_id, version_no, lifecycle_status,
                 copied_from_version_id, authorization_scope_type, wecom_self_selectable,
                 created_by, published_by, published_at, row_version)
            VALUES
                (published_id, tenant_record.id, candidate.profile_id, next_version, 'PUBLISHED',
                 candidate.old_version_id, candidate.authorization_scope_type, candidate.wecom_self_selectable,
                 candidate.published_by, candidate.published_by, now(), 1),
                (draft_id, tenant_record.id, candidate.profile_id, next_version + 1, 'DRAFT',
                 published_id, candidate.authorization_scope_type, candidate.wecom_self_selectable,
                 candidate.published_by, NULL, NULL, 0);

            INSERT INTO position_function_profile_permission (tenant_id, profile_version_id, permission_id)
            SELECT tenant_record.id, target.id, item.permission_id
            FROM position_function_profile_permission item
            CROSS JOIN (VALUES (published_id), (draft_id)) target(id)
            WHERE item.tenant_id = tenant_record.id AND item.profile_version_id = candidate.old_version_id;

            INSERT INTO position_function_profile_permission (tenant_id, profile_version_id, permission_id)
            SELECT tenant_record.id, target.id, permission_item.id
            FROM permission permission_item
            CROSS JOIN (VALUES (published_id), (draft_id)) target(id)
            WHERE permission_item.code IN ('ui.module.store-resources', 'store-resource.read')
              AND permission_item.delegable_to_position = true;

            -- Compatibility for account identities without a selected assignment.
            -- Only the very same eligible profile's role receives these two grants.
            INSERT INTO role_permission (tenant_id, role_id, permission_id)
            SELECT tenant_record.id, candidate.default_role_id, id FROM permission
            WHERE code IN ('ui.module.store-resources', 'store-resource.read')
            ON CONFLICT DO NOTHING;

            UPDATE position_function_profile SET row_version = row_version + 1, updated_at = now()
            WHERE tenant_id = tenant_record.id AND id = candidate.profile_id;
            UPDATE position_definition SET row_version = row_version + 1, updated_at = now()
            WHERE tenant_id = tenant_record.id AND id = candidate.position_id;

            INSERT INTO audit_log
                (tenant_id, actor_id, action, resource_type, resource_id, correlation_id, after_data)
            VALUES
                (tenant_record.id, NULL, 'SYSTEM_STORE_RESOURCE_READ_DEFAULT_PUBLISHED',
                 'POSITION_FUNCTION_PROFILE', candidate.profile_id, gen_random_uuid(),
                 jsonb_build_object('category', 'SYSTEM', 'source', 'V61',
                     'positionId', candidate.position_id, 'positionCode', candidate.position_code,
                     'previousVersionId', candidate.old_version_id, 'publishedVersionId', published_id,
                     'authorizationScopeType', candidate.authorization_scope_type,
                     'addedPermissions', jsonb_build_array('ui.module.store-resources', 'store-resource.read')));
        END LOOP;
    END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
