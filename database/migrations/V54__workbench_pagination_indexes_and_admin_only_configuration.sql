-- Workbench queries resolve organization ancestry and a bounded business-date
-- window for every visible expectation. These indexes keep the query stable as
-- daily work records grow across many hotels.
CREATE INDEX IF NOT EXISTS ix_work_expectation_workbench_scope
    ON work_expectation (tenant_id, business_date, target_org_unit_id, status, due_at);

CREATE INDEX IF NOT EXISTS ix_org_unit_closure_descendant_lookup
    ON org_unit_closure (tenant_id, descendant_id, depth, ancestor_id);

-- V46 publishes the store-manager daily-report template during migration. On a
-- fresh install executed after the hotel's business-day cutoff, its timestamp
-- can be later than the effective instant used to resolve that same business
-- date, leaving the store manager without a report until the following day.
-- Existing installations keep their earlier timestamp; fresh installations
-- are moved back just enough to cover the current business day.
UPDATE daily_report_template_version
SET effective_from = LEAST(effective_from, now() - interval '1 day'),
    updated_at = now(),
    row_version = row_version + 1
WHERE tenant_id = '10000000-0000-0000-0000-000000000001'
  AND id = '46100000-0000-0000-0000-000000000001'
  AND lifecycle_status = 'PUBLISHED';

UPDATE daily_report_section_version
SET effective_from = LEAST(effective_from, now() - interval '1 day'),
    updated_at = now(),
    row_version = row_version + 1
WHERE tenant_id = '10000000-0000-0000-0000-000000000001'
  AND id = '46110000-0000-0000-0000-000000000001'
  AND lifecycle_status = 'PUBLISHED';

-- Product governance: enterprise rules and work-package configuration are
-- platform-administration functions. Runtime work execution continues through
-- work-record permissions and does not require access to either configuration
-- center.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        DELETE FROM position_function_profile_permission profile_grant
        USING position_function_profile_version profile_version,
              position_function_profile profile,
              position_function_profile group_profile,
              app_role mapped_role,
              permission permission_item
        WHERE profile_grant.tenant_id = tenant_record.id
          AND profile_version.tenant_id = profile_grant.tenant_id
          AND profile_version.id = profile_grant.profile_version_id
          AND profile.tenant_id = profile_version.tenant_id
          AND profile.id = profile_version.profile_id
          AND group_profile.tenant_id = profile.tenant_id
          AND group_profile.position_id = profile.position_id
          AND group_profile.scope_type = 'GROUP'
          AND mapped_role.tenant_id = group_profile.tenant_id
          AND mapped_role.id = group_profile.default_role_id
          AND permission_item.id = profile_grant.permission_id
          AND mapped_role.code NOT IN ('PLATFORM_ADMIN', 'GROUP_ADMIN')
          AND permission_item.code IN (
              'ui.module.rules', 'ui.module.work-packages',
              'rule.manage', 'rule.simulate', 'rule.publish',
              'work-package.manage',
              'work-package.publish', 'work-package.allocate'
          );

        DELETE FROM role_permission role_grant
        USING app_role role_item, permission permission_item
        WHERE role_grant.tenant_id = tenant_record.id
          AND role_item.tenant_id = role_grant.tenant_id
          AND role_item.id = role_grant.role_id
          AND permission_item.id = role_grant.permission_id
          AND role_item.code NOT IN ('PLATFORM_ADMIN', 'GROUP_ADMIN')
          AND permission_item.code IN (
              'ui.module.rules', 'ui.module.work-packages',
              'rule.manage', 'rule.simulate', 'rule.publish',
              'work-package.manage',
              'work-package.publish', 'work-package.allocate'
          );
    END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
