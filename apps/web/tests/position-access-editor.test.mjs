import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, model, components, access, policies, routes
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot',
    root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  ;[model, components, access, policies, routes] = await Promise.all([
    vite.ssrLoadModule('/src/features/organization/positionAccessModel.ts'),
    vite.ssrLoadModule('/src/features/organization/PositionAccessEditor.tsx'),
    vite.ssrLoadModule('/src/app/navigationAccess.ts'),
    vite.ssrLoadModule('/src/app/rolePresentationPolicy.ts'),
    vite.ssrLoadModule('/src/app/routeConfig.ts'),
  ])
})
after(async () => { await vite?.close() })
const context = (overrides = {}) => ({ roleCode: 'CUSTOM', authorizationScopeTypes: ['SELF', 'ORG_UNIT', 'ORG_TREE', 'ASSIGNED_HOTELS', 'TENANT'], retainedPermissions: [], workPlansEnabled: true, executiveTasksEnabled: true, ...overrides })
const option = (permissionCode, overrides = {}) => ({ permissionCode, label: permissionCode, category: permissionCode.startsWith('ui.module.') ? 'UI_MODULE' : 'GENERAL', delegable: true, ...overrides })
const ids = (centers) => centers.flatMap((center) => center.items.map((item) => item.id))
const render = (component, props) => renderToStaticMarkup(React.createElement(component, props))

test('configuration groups match all five centers and preserve additional and unknown permissions', () => {
  const options = ['ui.module.workbench', 'task.create', 'ui.module.daily-operations', 'ai-recommendation.read', 'store-resource.read', 'ui.module.organization', 'org.read', 'ui.module.kpi-center', 'kpi.metric.read', 'ui.module.work-packages', 'template.read', 'ui.module.all-functions', 'future.permission'].map((code) => option(code))
  const grouped = model.groupPermissionOptions(options)
  assert.deepEqual(grouped.map(({ id }) => id), ['workbench', 'operations', 'people', 'performance', 'configuration', 'supplemental', 'other'])
  assert.deepEqual(new Set(grouped.flatMap((group) => group.items.map((item) => item.permissionCode))), new Set(options.map((item) => item.permissionCode)))
  assert.equal(model.permissionLabel(option('ui.module.work-packages')), '岗位工作模板')
  assert.equal(model.permissionLabel(option('ui.module.organization')), '组织与人员 / 组织与权限')
  assert.equal(model.groupPermissionOptions(options, 'AI-RECOMMENDATION')[0].items[0].permissionCode, 'ai-recommendation.read')
  assert.deepEqual(model.groupPermissionOptions(options, '无此权限'), [])
})

test('menu checkboxes alone never grant action access or expose a store module', () => {
  const selected = ['ui.module.workbench', 'ui.module.tasks', 'ui.module.store-resources']
  const before = [...selected]
  assert.deepEqual(ids(model.buildPositionPreview(selected, context(), '新岗位')), ['workbench'])
  assert.deepEqual(selected, before)
  assert.ok(ids(model.buildPositionPreview([...selected, 'task.read', 'store-resource.read'], context(), '新岗位')).includes('store-resources'))
  assert.equal(ids(model.buildPositionPreview(['ui.module.workbench', 'store-resource.read'], context(), '新岗位')).includes('store-resources'), false)
})

test('chairman preview respects feature gates without unlocking configuration administration', () => {
  const selected = ['ui.module.workbench', 'ui.module.tasks', 'executive-task.read', 'executive-task.assign', 'ui.module.rules', 'rule.read', 'ui.module.work-packages', 'work-package.read']
  assert.deepEqual(ids(model.buildPositionPreview(selected, context({ roleCode: 'GROUP_CHAIRMAN' }), '董事长')), ['workbench', 'tasks'])
  assert.deepEqual(ids(model.buildPositionPreview(selected, context({ roleCode: 'GROUP_CHAIRMAN', executiveTasksEnabled: false }), '董事长')), ['workbench'])
  assert.equal(model.positionFeatureChecks(selected, context({ roleCode: 'GROUP_CHAIRMAN' }))[0].ready, true)
  assert.equal(model.positionFeatureChecks(selected, context({ roleCode: 'GROUP_CHAIRMAN', executiveTasksEnabled: false }))[0].ready, false)
})

test('quick dispatch needs create and dispatch, not template management', () => {
  const selected = ['ui.module.workbench', 'task.create', 'task.dispatch']
  assert.equal(model.positionFeatureChecks(selected, context({ roleCode: 'GROUP_VICE_PRESIDENT' }))[0].ready, true)
  assert.equal(model.positionFeatureChecks(selected.filter((code) => code !== 'task.dispatch'), context())[0].ready, false)
  assert.equal(selected.some((code) => code.startsWith('template.')), false)
})

test('retained HR permissions are explained but are not automatically granted to a position preview', () => {
  const ctx = context({ roleCode: 'HR_ADMINISTRATION_SUPERVISOR', retainedPermissions: [option('wecom-binding.read', { delegable: false }), option('org.manage', { delegable: false })] })
  const selected = ['ui.module.workbench', 'ui.module.wecom-onboarding', 'ui.module.organization']
  assert.deepEqual(model.previewPermissions(selected, ctx), selected)
  assert.deepEqual(ids(model.buildPositionPreview(selected, ctx, '行政人事主管')), ['workbench'])
  const markup = render(components.PositionAccessPreview, { label: '行政人事主管', permissions: selected, context: ctx })
  assert.match(markup, /系统保留权限（2 项，只读）/)
  assert.match(markup, /不会自动下放/)
  assert.doesNotMatch(markup, /type="checkbox"/)
})

test('full-account roles include retained grants while preview remains independent of the operator', () => {
  const selected = ['ui.module.organization']
  const retainedPermissions = [option('org.manage', { delegable: false })]
  assert.ok(ids(model.buildPositionPreview(selected, context({ roleCode: 'CEO', retainedPermissions }), '总经理')).includes('organization'))
  assert.equal(ids(model.buildPositionPreview(selected, context({ roleCode: 'FRONT_DESK', retainedPermissions }), '员工')).includes('organization'), false)
})

test('unselectable permissions cannot be newly checked, but an existing stale grant can be removed', () => {
  const restricted = option('executive-task.assign', { delegable: false, restrictionReason: '仅集团董事长岗位可配置' })
  const props = { options: [restricted], selected: [], disabled: false, onToggle() {} }
  assert.match(render(components.PermissionChecklist, props), /type="checkbox" disabled=""/)
  const existing = render(components.PermissionChecklist, { ...props, selected: [restricted.permissionCode] })
  assert.doesNotMatch(existing, /disabled=""/)
  assert.match(existing, /checked=""/)
  assert.match(render(components.PermissionChecklist, { ...props, selected: [restricted.permissionCode], disabled: true }), /disabled=""/)
})

test('protected self-selection is disabled without losing a previously checked value silently', () => {
  const props = { context: context({ selfSelectionBlockedReason: '该岗位不能开放员工自选' }), scope: 'ASSIGNED_HOTELS', selfSelectable: false, disabled: false, onScopeChange() {}, onSelfSelectableChange() {} }
  const markup = render(components.PositionAccessSettings, props)
  assert.match(markup, /type="checkbox" disabled=""/)
  assert.match(markup, /人员列表 → 员工 → 编辑/)
  assert.match(markup, /岗位待分配|该岗位不能开放员工自选/)
  assert.doesNotMatch(render(components.PositionAccessSettings, { ...props, selfSelectable: true }), /type="checkbox" disabled=""/)
  assert.match(render(components.PositionAccessSettings, { ...props, context: undefined }), /<select disabled=""/)
})

test('legacy cockpit flags are not falsely rendered as standalone menus', () => {
  const selected = ['ui.module.workbench', 'ui.module.hotel-dashboard', 'ui.module.operations-dashboard', 'dashboard.hotel', 'dashboard.operations']
  const preview = model.buildPositionPreview(selected, context(), '运营经理')
  assert.deepEqual(ids(preview), ['workbench'])
  assert.match(model.permissionDescription(option('ui.module.operations-dashboard')), /不启用被冻结的区域经理/)
  assert.match(render(components.PositionAccessPreview, { label: '运营经理', permissions: selected, context: context() }), /当前草稿/)
})

test('feature checks cover scoped completions, AI, inventory and work plans without mutation', () => {
  const selected = ['ui.module.workbench', 'ui.module.daily-operations', 'ui.module.store-resources', 'ui.module.my-work', 'ui.module.work-plans', 'work-record.read', 'work-record.team-read', 'daily-operation.read', 'ai-recommendation.read', 'store-resource.read', 'work-plan.read']
  const checks = model.positionFeatureChecks(selected, context())
  for (const label of ['门店 / 下属工作完成率', '日报汇总与 AI 建议查看', '库存与固定资产查看']) assert.equal(checks.find((item) => item.label === label).ready, true, label)
  assert.equal(checks.find((item) => item.label === '周 / 月工作计划').ready, false)
  assert.equal(checks.find((item) => item.label === '周 / 月工作计划').statusLabel, '入口待接入')
  assert.equal(model.positionFeatureChecks(selected, context({ workPlansEnabled: false })).find((item) => item.label === '周 / 月工作计划').ready, false)
})

test('live and preview navigation reuse exactly the same permission and visibility filtering', () => {
  // Reference behavior before extraction, including demo fallback and administrator exceptions.
  function original(ctx) {
    const filtered = access.navigation.filter((item) => {
      if (item.administratorOnly && !ctx.isDataRetentionAdministrator) return false
      if (['hotel-dashboard', 'operations-dashboard'].includes(item.id)) return false
      if (['rules', 'work-packages'].includes(item.id) && !ctx.isPlatformAdmin) return false
      if (item.id === 'my-work' && !ctx.hasAssignment) return false
      if (item.id === 'tasks' && ctx.roleCode === 'GROUP_CHAIRMAN') return ctx.executiveTasksEnabled && ctx.hasAssignment && ['*', 'executive-task.read', 'executive-task.assign'].some((code) => ctx.permissions.includes(code))
      return !item.permissions?.length || (ctx.demo && !routes.isDailyFeatureRoute(item.id) && !routes.isInvestmentFeatureRoute(item.id)) || ctx.permissions.includes('*') || item.permissions.some((code) => ctx.permissions.includes(code))
    })
    const published = policies.applyPublishedModuleVisibility(filtered, ctx.permissions, ctx.roleCode)
    if (!ctx.isDataRetentionAdministrator || published.some((item) => item.id === 'data-retention')) return published
    return access.navigation.filter((item) => published.some((entry) => entry.id === item.id) || item.id === 'data-retention')
  }
  const samples = [[], ['*'], ['task.read'], ['ui.module.workbench', 'ui.module.tasks', 'task.read'], ['ui.module.tasks', 'executive-task.assign'], ['ui.module.store-resources', 'store-resource.read']]
  for (const roleCode of ['CUSTOM', 'FRONT_DESK', 'CEO', 'GROUP_CHAIRMAN', 'PLATFORM_ADMIN', 'HR_ADMINISTRATION_SUPERVISOR']) {
    for (const permissions of samples) for (const flag of [false, true]) {
      const ctx = { permissions, roleCode, hasAssignment: flag, executiveTasksEnabled: flag, isPlatformAdmin: roleCode === 'PLATFORM_ADMIN', isDataRetentionAdministrator: roleCode === 'PLATFORM_ADMIN', demo: flag }
      assert.deepEqual(access.visibleNavigationForContext(ctx), original(ctx))
    }
  }
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
  assert.match(app, /visibleNavigationForContext\(\{/)
})
