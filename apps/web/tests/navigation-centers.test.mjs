import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  buildNavigationCenters,
  canOpenStoreResources,
  isCenterNavigationItemActive,
  navigationCenterForSection,
} from '../src/app/navigationModel.ts'
import { applyPublishedModuleVisibility, resolveDailyReportsTarget } from '../src/app/rolePresentationPolicy.ts'

const feature = (id, sectionId) => ({ id, sectionId, label: id, icon: '' })
const allFeatures = [
  feature('workbench'), feature('my-work'), feature('team-work'), feature('tasks'), feature('notifications'),
  feature('daily-operations'), feature('daily-reports-my', 'daily-reports'), feature('investments'), feature('store-resources'),
  feature('organization'), feature('wecom-onboarding'), feature('wecom-bindings'),
  feature('kpi-center', 'kpi'), feature('evaluations'), feature('templates'), feature('work-packages'),
  feature('daily-report-templates'), feature('rules'), feature('wecom-webhooks'), feature('data-retention'),
]

test('full navigation has exactly five ordered centers and preserves every authorized feature', () => {
  const centers = buildNavigationCenters(allFeatures, '平台管理员')
  assert.deepEqual(centers.map(({ id, label }) => [id, label]), [
    ['workbench', '平台管理员工作台'], ['operations', '经营中心'], ['people', '人事中心'],
    ['performance', '绩效中心'], ['configuration', '配置中心'],
  ])
  assert.deepEqual(centers[0].items.map((item) => item.id), ['workbench', 'my-work', 'daily-reports-my', 'team-work', 'tasks', 'notifications'])
  assert.deepEqual(centers[1].items.map((item) => item.id), ['daily-operations', 'investments', 'store-resources'])
  assert.deepEqual(new Set(centers.flatMap((center) => center.items.map((item) => item.id))), new Set(allFeatures.map((item) => item.id)))
  assert.equal(centers[4].items.find((item) => item.id === 'work-packages').label, '岗位工作模板')
})

test('published modules determine nonempty centers without granting new feature access', () => {
  const visible = applyPublishedModuleVisibility(allFeatures, [
    'ui.module.workbench', 'ui.module.my-work', 'ui.module.daily-reports-my', 'ui.module.kpi-center',
  ], 'FRONT_DESK')
  const centers = buildNavigationCenters(visible, '前台员工')
  assert.deepEqual(centers.map((center) => center.id), ['workbench', 'performance'])
  assert.deepEqual(centers[0].items.map((item) => item.id), ['workbench', 'my-work', 'daily-reports-my'])
  assert.deepEqual(buildNavigationCenters([], '未授权岗位'), [])
  assert.deepEqual(buildNavigationCenters([feature('tasks')], '主管').map((center) => center.id), ['workbench'])
})

test('organization and permissions share one existing route and keep the selected parent in the hash query', () => {
  const centers = buildNavigationCenters([feature('organization')], '人事')
  const people = centers.find((center) => center.id === 'people').items[0]
  const configuration = centers.find((center) => center.id === 'configuration').items[0]
  assert.equal(people.id, 'organization')
  assert.equal(people.label, '组织与人员')
  assert.equal(people.params, undefined)
  assert.equal(configuration.id, people.id)
  assert.deepEqual(configuration.params, { center: 'configuration' })
  assert.equal(navigationCenterForSection('organization'), 'people')
  assert.equal(navigationCenterForSection('organization', configuration.params), 'configuration')
  assert.equal(navigationCenterForSection('organization', { center: 'anything' }), 'people')
})

test('old hash sections and nested feature sections select the owning main center', () => {
  const cases = {
    workbench: 'workbench', 'hotel-dashboard': 'workbench', 'operations-dashboard': 'workbench',
    'my-work': 'workbench', 'team-work': 'workbench', tasks: 'workbench', notifications: 'workbench',
    'all-functions': 'workbench', 'account-self-service': 'workbench',
    'daily-operations': 'operations', 'daily-reports': 'workbench', investments: 'operations', 'store-resources': 'operations',
    organization: 'people', 'wecom-onboarding': 'people', 'wecom-bindings': 'people',
    kpi: 'performance', evaluations: 'performance',
    templates: 'configuration', 'work-packages': 'configuration', 'daily-report-templates': 'configuration',
    rules: 'configuration', 'wecom-webhooks': 'configuration', 'data-retention': 'configuration',
  }
  for (const [section, expected] of Object.entries(cases)) assert.equal(navigationCenterForSection(section), expected, section)
  assert.equal(isCenterNavigationItemActive(feature('daily-reports-my', 'daily-reports'), 'daily-reports'), true)
  assert.equal(isCenterNavigationItemActive(feature('kpi-center', 'kpi'), 'kpi'), true)
  assert.equal(isCenterNavigationItemActive(feature('workbench'), 'hotel-dashboard'), true)
  assert.equal(isCenterNavigationItemActive(feature('tasks'), 'notifications'), false)
})

test('manager and personal report destinations keep their existing permission-aware routes', () => {
  assert.equal(resolveDailyReportsTarget({ roleCode: 'GENERAL_MANAGER', hasAssignment: true, canUseOwnReport: true, canUseTeamReport: true }), 'daily-reports-team')
  assert.equal(resolveDailyReportsTarget({ roleCode: 'FRONT_DESK', hasAssignment: true, canUseOwnReport: true, canUseTeamReport: false }), 'daily-reports-my')
})

test('resource shortcuts need read permission and module visibility together', () => {
  const resource = [feature('store-resources')]
  assert.equal(canOpenStoreResources(['store-resource.read'], resource), true)
  assert.equal(canOpenStoreResources(['*'], resource), true)
  assert.equal(canOpenStoreResources(['store-resource.read'], []), false)
  assert.equal(canOpenStoreResources(['*'], []), false)
  assert.equal(canOpenStoreResources(['store-resource.asset-manage'], resource), false)
  assert.equal(canOpenStoreResources(['ui.module.store-resources'], resource), false)
})

test('workbench resource links preserve hotel scope and open the fixed asset tab', () => {
  const workbench = readFileSync(new URL('../src/features/workbench/RoleWorkbench.tsx', import.meta.url), 'utf8')
  assert.match(workbench, /storeResourcesAvailable && allows\(permissions, permissionCodes\.storeResource\.read\)/)
  assert.match(workbench, /go\('store-resources', \{ hotelId: hotel\.id, tab: 'assets' \}\)/)
  assert.match(workbench, /go\('store-resources', \{ hotelId: selectedHotel\.id, tab: 'assets' \}\)/)
  assert.equal((workbench.match(/\{canReadStoreResources && <button/g) ?? []).length, 2)
})
