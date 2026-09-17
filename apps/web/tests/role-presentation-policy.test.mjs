import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  applyPublishedModuleVisibility,
  applyRoleDefaultsForFallback,
  desktopModuleForRoute,
  isFullAccountPresentationRole,
  isRouteAllowedByRoleDefaults,
  prefersTeamDailyReports,
  resolveDailyReportsTarget,
  resolveFullAccountPresentationRole,
  resolveRolePresentationPolicy,
  shouldUseRoleDefaultsFallback,
} from '../src/app/rolePresentationPolicy.ts'

const expectedCodes = {
  FRONT_DESK: 'EMPLOYEE',
  HOUSEKEEPING_ATTENDANT: 'EMPLOYEE',
  OTA_OPERATION_ASSISTANT: 'OTA_ASSISTANT',
  FRONT_OFFICE_SUPERVISOR: 'SUPERVISOR',
  HOUSEKEEPING_SUPERVISOR: 'SUPERVISOR',
  ASSISTANT_GENERAL_MANAGER: 'ASSISTANT_MANAGER',
  GENERAL_MANAGER: 'HOTEL_MANAGER',
  OTA_OPERATION_MANAGER: 'REGIONAL_OPERATIONS',
  HR_KPI_ADMIN: 'HR',
  GROUP_VICE_PRESIDENT: 'GROUP_VICE_PRESIDENT',
  CEO: 'CEO',
  PLATFORM_ADMIN: 'PLATFORM_ADMIN',
}

function migrationRoleModuleMatrix() {
  const source = readFileSync(new URL('../../../database/migrations/V37__separate_navigation_modules_from_action_permissions.sql', import.meta.url), 'utf8')
  const storeManagerClosedLoop = readFileSync(new URL('../../../database/migrations/V46__store_manager_daily_work_closed_loop.sql', import.meta.url), 'utf8')
  const result = new Map()
  const values = (fragment) => [...fragment.matchAll(/'([^']+)'/g)].map((match) => match[1])
  const sharedPattern = /FROM unnest\(ARRAY\[(.*?)\]\) AS roles\(role_code\)\s+CROSS JOIN unnest\(ARRAY\[(.*?)\]\) AS modules\(module_id\)/gs
  for (const match of source.matchAll(sharedPattern)) {
    for (const roleCode of values(match[1])) result.set(roleCode, values(match[2]))
  }
  const singlePattern = /SELECT '([^']+)', module_id\s+FROM unnest\(ARRAY\[(.*?)\]\) AS modules\(module_id\)/gs
  for (const match of source.matchAll(singlePattern)) result.set(match[1], values(match[2]))
  if (storeManagerClosedLoop.includes("position.code = 'GENERAL_MANAGER'")
      && storeManagerClosedLoop.includes("module_permission.code = 'ui.module.my-work'")) {
    const generalManagerModules = [...(result.get('GENERAL_MANAGER') ?? [])]
    generalManagerModules.splice(generalManagerModules.indexOf('hotel-dashboard') + 1, 0, 'my-work')
    result.set('GENERAL_MANAGER', [...new Set(generalManagerModules)])
  }
  const adminOnlyMigration = readFileSync(new URL('../../../database/migrations/V54__workbench_pagination_indexes_and_admin_only_configuration.sql', import.meta.url), 'utf8')
  if (adminOnlyMigration.includes("'ui.module.rules', 'ui.module.work-packages'")) {
    for (const [roleCode, moduleIds] of result) {
      if (!['PLATFORM_ADMIN', 'GROUP_ADMIN'].includes(roleCode)) {
        result.set(roleCode, moduleIds.filter((moduleId) => !['rules', 'work-packages'].includes(moduleId)))
      }
    }
  }
  return result
}

test('all persisted Hotel AI OS role codes resolve to the frozen presentation family', () => {
  for (const [roleCode, expected] of Object.entries(expectedCodes)) {
    assert.equal(resolveRolePresentationPolicy(roleCode).key, expected, roleCode)
  }
})

test('reviewed aliases resolve without fuzzy role inference', () => {
  assert.equal(resolveRolePresentationPolicy('front-desk').key, 'EMPLOYEE')
  assert.equal(resolveRolePresentationPolicy('客房服务员').key, 'EMPLOYEE')
  assert.equal(resolveRolePresentationPolicy('OTA运营助理').key, 'OTA_ASSISTANT')
  assert.equal(resolveRolePresentationPolicy('前厅主管').key, 'SUPERVISOR')
  assert.equal(resolveRolePresentationPolicy('客房主管').key, 'SUPERVISOR')
  assert.equal(resolveRolePresentationPolicy('店助').key, 'ASSISTANT_MANAGER')
  assert.equal(resolveRolePresentationPolicy('店长').key, 'HOTEL_MANAGER')
  assert.equal(resolveRolePresentationPolicy('OTA运营经理').key, 'REGIONAL_OPERATIONS')
  assert.equal(resolveRolePresentationPolicy('区域/运营经理').key, 'GENERIC')
  assert.equal(resolveRolePresentationPolicy('区域经理').key, 'GENERIC')
  assert.equal(resolveRolePresentationPolicy('人事').key, 'GENERIC')
  assert.equal(resolveRolePresentationPolicy('行政人事').key, 'HR_ADMINISTRATION')
  assert.equal(resolveRolePresentationPolicy('行政人事主管').key, 'HR_ADMINISTRATION_SUPERVISOR')
  assert.equal(resolveRolePresentationPolicy('集团董事长').key, 'GROUP_CHAIRMAN')
  assert.equal(resolveRolePresentationPolicy('集团副总').key, 'GROUP_VICE_PRESIDENT')
  assert.equal(resolveRolePresentationPolicy('集团CEO').key, 'CEO')
  assert.equal(resolveRolePresentationPolicy('集团总经理').key, 'CEO')
  assert.equal(resolveRolePresentationPolicy('GROUP_ADMIN').key, 'PLATFORM_ADMIN')
})

test('both administrative HR roles can reach employee and WeCom onboarding administration', () => {
  for (const roleCode of ['HR_ADMINISTRATION', 'HR_ADMINISTRATION_SUPERVISOR']) {
    const modules = resolveRolePresentationPolicy(roleCode).desktopModuleIds
    assert.ok(modules.includes('organization'), roleCode)
    assert.ok(modules.includes('wecom-bindings'), roleCode)
    assert.ok(modules.includes('wecom-onboarding'), roleCode)
  }
  assert.ok(!resolveRolePresentationPolicy('GROUP_CHAIRMAN').desktopModuleIds.includes('wecom-onboarding'))
})

test('account-wide semantics use the backend exact role allowlist, never UI aliases', () => {
  assert.equal(isFullAccountPresentationRole('PLATFORM_ADMIN'), true)
  assert.equal(isFullAccountPresentationRole('CEO'), true)
  for (const roleCode of ['GROUP_ADMIN', 'SYSTEM_ADMIN', 'GROUP_CEO', 'platform-admin', '平台管理员', '集团CEO']) {
    assert.equal(isFullAccountPresentationRole(roleCode), false, roleCode)
  }
  assert.equal(resolveFullAccountPresentationRole(['CEO', 'PLATFORM_ADMIN']), 'PLATFORM_ADMIN')
  assert.equal(resolveFullAccountPresentationRole(['GROUP_ADMIN', 'GENERAL_MANAGER']), undefined)
})

test('mobile five-tab labels and targets match the consolidated workbench matrix', () => {
  const cases = {
    FRONT_DESK: [['工作台', 'workbench'], ['日报', 'daily-reports-my'], ['工作', 'my-work'], ['KPI', 'kpi-center'], ['我的', 'all-functions']],
    FRONT_OFFICE_SUPERVISOR: [['工作台', 'workbench'], ['日报', 'daily-reports-my'], ['团队', 'team-work'], ['KPI', 'kpi-center'], ['我的', 'all-functions']],
    ASSISTANT_GENERAL_MANAGER: [['工作台', 'workbench'], ['日报', 'daily-reports-my'], ['日运营', 'daily-operations'], ['KPI', 'kpi-center'], ['我的', 'all-functions']],
    GENERAL_MANAGER: [['工作台', 'workbench'], ['日报', 'daily-reports-my'], ['日运营', 'daily-operations'], ['KPI', 'kpi-center'], ['我的', 'all-functions']],
    OTA_OPERATION_MANAGER: [['工作台', 'workbench'], ['日报', 'daily-reports-my'], ['日运营', 'daily-operations'], ['KPI', 'kpi-center'], ['我的', 'all-functions']],
    HR_KPI_ADMIN: [['工作台', 'workbench'], ['人员', 'organization'], ['入职', 'wecom-onboarding'], ['KPI', 'kpi-center'], ['我的', 'all-functions']],
    HR_ADMINISTRATION_SUPERVISOR: [['工作台', 'workbench'], ['人员', 'organization'], ['团队', 'team-work'], ['消息', 'notifications'], ['我的', 'all-functions']],
    HR_ADMINISTRATION: [['工作台', 'workbench'], ['人员', 'organization'], ['企微绑定', 'wecom-bindings'], ['消息', 'notifications'], ['我的', 'all-functions']],
    GROUP_VICE_PRESIDENT: [['工作台', 'workbench'], ['日报', 'daily-reports-my'], ['日运营', 'daily-operations'], ['KPI', 'kpi-center'], ['我的', 'all-functions']],
    GROUP_CHAIRMAN: [['工作台', 'workbench'], ['日报', 'daily-reports-my'], ['日运营', 'daily-operations'], ['消息', 'notifications'], ['我的', 'account-self-service']],
    CEO: [['工作台', 'workbench'], ['日报', 'daily-reports-my'], ['日运营', 'daily-operations'], ['KPI', 'kpi-center'], ['我的', 'all-functions']],
    PLATFORM_ADMIN: [['工作台', 'workbench'], ['工作包', 'work-packages'], ['系统配置', 'organization'], ['规则', 'rules'], ['我的', 'all-functions']],
  }
  for (const [roleCode, expected] of Object.entries(cases)) {
    const actual = resolveRolePresentationPolicy(roleCode).mobileTabs.map(({ label, target }) => [label, target])
    assert.deepEqual(actual, expected, roleCode)
  }

  const ceoTabs = resolveRolePresentationPolicy('CEO').mobileTabs
  assert.equal(ceoTabs[1].slot, 'secondary')
  assert.equal(ceoTabs[1].target, 'daily-reports-my')
  assert.deepEqual(ceoTabs.map((tab) => tab.slot), ['primary', 'secondary', 'domain', 'notifications', 'profile'])
})

test('every frozen mobile tab resolves to a module available to that role', () => {
  const specialTargets = new Set(['all-functions', 'account-self-service'])
  for (const roleCode of Object.keys(expectedCodes)) {
    const policy = resolveRolePresentationPolicy(roleCode)
    const modules = new Set(policy.desktopModuleIds ?? [])
    for (const tab of policy.mobileTabs) {
      assert.ok(
        specialTargets.has(tab.target) || modules.has(desktopModuleForRoute(tab.target)),
        `${roleCode}: ${tab.label} -> ${tab.target}`,
      )
    }
  }
})

test('desktop module allowlists match every frozen role template', () => {
  const cases = {
    FRONT_DESK: ['workbench', 'my-work', 'tasks', 'daily-reports-my', 'evaluations', 'kpi-center', 'notifications', 'all-functions'],
    HOUSEKEEPING_ATTENDANT: ['workbench', 'my-work', 'tasks', 'daily-reports-my', 'evaluations', 'kpi-center', 'notifications', 'all-functions'],
    OTA_OPERATION_ASSISTANT: ['workbench', 'my-work', 'tasks', 'daily-reports-my', 'kpi-center', 'notifications', 'all-functions'],
    FRONT_OFFICE_SUPERVISOR: ['workbench', 'my-work', 'team-work', 'tasks', 'daily-reports-my', 'daily-operations', 'evaluations', 'kpi-center', 'notifications', 'all-functions'],
    HOUSEKEEPING_SUPERVISOR: ['workbench', 'my-work', 'team-work', 'tasks', 'daily-reports-my', 'daily-operations', 'evaluations', 'kpi-center', 'notifications', 'all-functions'],
    ASSISTANT_GENERAL_MANAGER: ['workbench', 'hotel-dashboard', 'team-work', 'tasks', 'daily-reports-my', 'daily-report-templates', 'daily-operations', 'evaluations', 'kpi-center', 'notifications', 'all-functions'],
    GENERAL_MANAGER: ['workbench', 'hotel-dashboard', 'my-work', 'team-work', 'tasks', 'daily-reports-my', 'daily-operations', 'kpi-center', 'evaluations', 'notifications', 'all-functions'],
    OTA_OPERATION_MANAGER: ['workbench', 'operations-dashboard', 'tasks', 'daily-reports-my', 'daily-operations', 'kpi-center', 'notifications', 'all-functions'],
    HR_KPI_ADMIN: ['workbench', 'tasks', 'organization', 'wecom-bindings', 'wecom-onboarding', 'kpi-center', 'notifications', 'all-functions'],
    GROUP_VICE_PRESIDENT: ['workbench', 'operations-dashboard', 'tasks', 'daily-reports-my', 'daily-operations', 'kpi-center', 'notifications', 'all-functions'],
    GROUP_CHAIRMAN: ['workbench', 'hotel-dashboard', 'operations-dashboard', 'team-work', 'tasks', 'daily-reports-my', 'daily-operations', 'evaluations', 'notifications'],
    CEO: ['workbench', 'hotel-dashboard', 'operations-dashboard', 'investments', 'team-work', 'tasks', 'daily-reports-my', 'daily-report-templates', 'daily-operations', 'kpi-center', 'evaluations', 'templates', 'organization', 'wecom-bindings', 'wecom-onboarding', 'notifications', 'all-functions'],
    PLATFORM_ADMIN: ['workbench', 'hotel-dashboard', 'operations-dashboard', 'investments', 'work-packages', 'my-work', 'team-work', 'tasks', 'daily-reports-my', 'daily-report-templates', 'daily-operations', 'kpi-center', 'rules', 'evaluations', 'templates', 'organization', 'wecom-webhooks', 'wecom-bindings', 'wecom-onboarding', 'notifications', 'all-functions'],
  }
  for (const [roleCode, expected] of Object.entries(cases)) {
    assert.deepEqual(resolveRolePresentationPolicy(roleCode).desktopModuleIds, expected, roleCode)
  }
})

test('database module grants match every standard frontend role policy', () => {
  const migrationMatrix = migrationRoleModuleMatrix()
  for (const roleCode of Object.keys(expectedCodes)) {
    assert.deepEqual(
      migrationMatrix.get(roleCode),
      resolveRolePresentationPolicy(roleCode).desktopModuleIds,
      roleCode,
    )
  }
})

test('legacy/demo fallback removes modules outside the confirmed employee defaults', () => {
  const permissionVisible = [
    { id: 'workbench', label: '角色工作台' },
    { id: 'my-work', label: '我的工作' },
    { id: 'work-packages', label: '工作包中心' },
    { id: 'templates', label: '集团模板配置' },
    { id: 'daily-report-templates', label: '日报模板中心' },
    { id: 'organization', label: '组织与权限' },
    { id: 'wecom-webhooks', label: '企业微信 Webhook' },
    { id: 'notifications', label: '通知中心' },
  ]
  assert.deepEqual(
    applyRoleDefaultsForFallback(permissionVisible, 'FRONT_DESK').map((item) => item.id),
    ['workbench', 'my-work', 'notifications'],
  )
  assert.deepEqual(
    applyRoleDefaultsForFallback(permissionVisible, 'HOUSEKEEPING_ATTENDANT').map((item) => item.id),
    ['workbench', 'my-work', 'notifications'],
  )
})

test('role default fallback only removes from the backend-visible set and never grants a module', () => {
  const permissionVisible = [{ id: 'workbench' }, { id: 'notifications' }]
  for (const roleCode of Object.keys(expectedCodes)) {
    const narrowed = applyRoleDefaultsForFallback(permissionVisible, roleCode)
    assert.ok(narrowed.every((item) => permissionVisible.includes(item)), roleCode)
    assert.ok(narrowed.length <= permissionVisible.length, roleCode)
  }
})

test('unknown and custom positions keep the backend-filtered generic view unchanged', () => {
  const permissionVisible = [
    { id: 'workbench' },
    { id: 'tasks' },
    { id: 'daily-operations' },
    { id: 'notifications' },
  ]
  const policy = resolveRolePresentationPolicy('CUSTOM_NIGHT_AUDITOR')
  assert.equal(policy.key, 'GENERIC')
  assert.equal(policy.knownRole, false)
  assert.equal(policy.desktopModuleIds, null)
  assert.deepEqual(applyRoleDefaultsForFallback(permissionVisible, 'CUSTOM_NIGHT_AUDITOR'), permissionVisible)
})

test('platform administrator fallback keeps all currently known modules', () => {
  const permissionVisible = [
    { id: 'workbench' },
    { id: 'organization' },
    { id: 'wecom-webhooks' },
    { id: 'investments' },
  ]
  assert.deepEqual(applyRoleDefaultsForFallback(permissionVisible, 'PLATFORM_ADMIN'), permissionVisible)
  assert.deepEqual(
    applyRoleDefaultsForFallback(permissionVisible, 'CEO').map((item) => item.id),
    ['workbench', 'organization', 'investments'],
  )
})

test('direct-link guard normalizes child pages to their owning desktop module', () => {
  assert.equal(desktopModuleForRoute('daily-report-detail'), 'daily-reports-my')
  assert.equal(desktopModuleForRoute('daily-reports-team'), 'daily-reports-my')
  assert.equal(desktopModuleForRoute('daily-report-template-version'), 'daily-report-templates')
  assert.equal(desktopModuleForRoute('daily-operation-snapshot-detail'), 'daily-operations')
  assert.equal(desktopModuleForRoute('kpi-scorecard-detail'), 'kpi-center')
  assert.equal(desktopModuleForRoute('investment-professional'), 'investments')
  assert.equal(desktopModuleForRoute('notifications'), 'notifications')
})

test('direct-link default guard can only narrow the backend decision during fallback', () => {
  assert.equal(isRouteAllowedByRoleDefaults('FRONT_DESK', 'daily-report-detail', true), true)
  assert.equal(isRouteAllowedByRoleDefaults('FRONT_DESK', 'daily-report-detail', false), false)
  assert.equal(isRouteAllowedByRoleDefaults('FRONT_DESK', 'daily-report-template-version', true), false)
  assert.equal(isRouteAllowedByRoleDefaults('FRONT_DESK', 'organization', true), false)
  assert.equal(isRouteAllowedByRoleDefaults('CEO', 'investment-professional', true), true)
  assert.equal(isRouteAllowedByRoleDefaults('CEO', 'wecom-webhooks', true), false)
  assert.equal(isRouteAllowedByRoleDefaults('PLATFORM_ADMIN', 'wecom-webhooks', true), true)
})

test('unknown custom-position direct links preserve backend permission decisions', () => {
  assert.equal(isRouteAllowedByRoleDefaults('CUSTOM_NIGHT_AUDITOR', 'organization', true), true)
  assert.equal(isRouteAllowedByRoleDefaults('CUSTOM_NIGHT_AUDITOR', 'organization', false), false)
})

test('published assignment permissions stay authoritative after an administrator changes modules', () => {
  assert.equal(shouldUseRoleDefaultsFallback({
    identitySource: 'api',
    fullAccountLevel: false,
    assignmentPermissionsPresent: true,
  }), false)
  assert.equal(shouldUseRoleDefaultsFallback({
    identitySource: 'demo',
    fullAccountLevel: false,
    assignmentPermissionsPresent: true,
  }), true)
  assert.equal(shouldUseRoleDefaultsFallback({
    identitySource: 'api',
    fullAccountLevel: false,
    assignmentPermissionsPresent: false,
  }), true)
})

test('published module grants control navigation independently from action permissions', () => {
  const actionVisible = [
    { id: 'workbench' },
    { id: 'hotel-dashboard' },
    { id: 'operations-dashboard' },
    { id: 'notifications' },
  ]
  assert.deepEqual(
    applyPublishedModuleVisibility(actionVisible, [
      'dashboard.hotel',
      'dashboard.operations',
      'notification.read',
      'ui.module.workbench',
      'ui.module.hotel-dashboard',
      'ui.module.notifications',
    ], 'GENERAL_MANAGER').map((item) => item.id),
    ['workbench', 'hotel-dashboard', 'notifications'],
  )

  const actionDeniedBeforeModuleFilter = actionVisible.filter((item) => item.id !== 'operations-dashboard')
  assert.deepEqual(
    applyPublishedModuleVisibility(actionDeniedBeforeModuleFilter, [
      'ui.module.operations-dashboard',
    ], 'OTA_OPERATION_MANAGER'),
    [],
  )
})

test('profiles without explicit module grants use the reviewed compatibility matrix', () => {
  const actionVisible = [
    { id: 'workbench' },
    { id: 'hotel-dashboard' },
    { id: 'operations-dashboard' },
    { id: 'notifications' },
  ]
  assert.deepEqual(
    applyPublishedModuleVisibility(actionVisible, ['dashboard.hotel', 'notification.read'], 'GENERAL_MANAGER')
      .map((item) => item.id),
    ['workbench', 'hotel-dashboard', 'notifications'],
  )
})

test('management roles open team reports even when they also have own-report permission', () => {
  for (const roleCode of [
    'FRONT_OFFICE_SUPERVISOR', 'HOUSEKEEPING_SUPERVISOR', 'ASSISTANT_GENERAL_MANAGER',
    'GENERAL_MANAGER', 'OTA_OPERATION_MANAGER', 'GROUP_VICE_PRESIDENT', 'CEO',
    'PLATFORM_ADMIN', 'GROUP_ADMIN',
  ]) assert.equal(prefersTeamDailyReports(roleCode), true, roleCode)
  assert.equal(prefersTeamDailyReports('FRONT_DESK'), false)
  assert.equal(prefersTeamDailyReports('OTA_OPERATION_ASSISTANT'), false)
  assert.equal(resolveDailyReportsTarget({ roleCode: 'GENERAL_MANAGER', hasAssignment: true, canUseOwnReport: true, canUseTeamReport: true }), 'daily-reports-team')
  assert.equal(resolveDailyReportsTarget({ roleCode: 'GENERAL_MANAGER', hasAssignment: true, canUseOwnReport: true, canUseTeamReport: false }), 'daily-reports-my')
  assert.equal(resolveDailyReportsTarget({ roleCode: 'FRONT_DESK', hasAssignment: true, canUseOwnReport: true, canUseTeamReport: true }), 'daily-reports-my')
})
