import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, shortcuts, RoleWorkbench, visibleNavigationForContext
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  shortcuts = await vite.ssrLoadModule('/src/features/workbench/DailyReportShortcuts.tsx')
  ;({ RoleWorkbench } = await vite.ssrLoadModule('/src/features/workbench/RoleWorkbench.tsx'))
  ;({ visibleNavigationForContext } = await vite.ssrLoadModule('/src/app/navigationAccess.ts'))
})
after(async () => { await vite?.close() })

const ownPermissions = ['daily-report.read', 'daily-report.submit', 'ui.module.workbench', 'ui.module.my-work', 'ui.module.daily-reports-my']
const ownProps = (overrides = {}) => ({ permissions: ownPermissions, available: true, hasAssignment: true, go() {}, ...overrides })
const render = (name, props) => renderToStaticMarkup(React.createElement(shortcuts[name], props))
const reportAvailable = (permissions) => visibleNavigationForContext({
  permissions, roleCode: 'HOUSEKEEPING_ATTENDANT', hasAssignment: true,
  executiveTasksEnabled: false, isPlatformAdmin: false, isDataRetentionAdministrator: false,
}).some(item => item.id === 'daily-reports-my')

test('housekeeping report shortcut is actionable with the published module and own report permissions', () => {
  const props = ownProps({ available: reportAvailable(ownPermissions) })
  assert.match(render('OwnDailyReportShortcut', props), /<button type="button" class="primary"[^>]*>日报填报<\/button>/)
  assert.equal(reportAvailable(ownPermissions), true)
})

test('no assignment, hidden module, or missing own-read permission cannot show a filling shortcut', () => {
  for (const overrides of [
    { hasAssignment: false }, { available: false }, { permissions: [] },
    { permissions: ['daily-report.submit'] }, { permissions: ['daily-report.team-read'] },
    { permissions: ['ui.module.daily-reports-my'] },
  ]) assert.equal(render('OwnDailyReportShortcut', ownProps(overrides)), '', JSON.stringify(overrides))
})

test('published module removal hides the shortcut even when API permissions remain', () => {
  const permissions = ownPermissions.filter(code => code !== 'ui.module.daily-reports-my')
  assert.equal(reportAvailable(permissions), false)
  assert.equal(render('OwnDailyReportShortcut', ownProps({ permissions, available: reportAvailable(permissions) })), '')
})

test('read-only employees see My Reports without a promise that they can submit', () => {
  const html = render('OwnDailyReportShortcut', ownProps({ permissions: ['daily-report.read'] }))
  assert.match(html, /class="secondary"/)
  assert.match(html, />我的日报<\/button>/)
  assert.doesNotMatch(html, /日报填报/)
})

test('own shortcut always opens current-assignment reports, including managers with team access', () => {
  for (const permissions of [ownPermissions, [...ownPermissions, 'daily-report.team-read'], ['*']]) {
    const calls = []
    const button = shortcuts.OwnDailyReportShortcut(ownProps({ permissions, go: (...args) => calls.push(args) }))
    button.props.onClick()
    // No hotel, employee, or historical board date is forwarded into the personal report query.
    assert.deepEqual(calls, [['daily-reports-my']])
  }
})

test('assignment or permission changes immediately recompute the shortcut without stale access', () => {
  assert.match(render('OwnDailyReportShortcut', ownProps()), /日报填报/)
  assert.equal(render('OwnDailyReportShortcut', ownProps({ hasAssignment: false })), '')
  assert.equal(render('OwnDailyReportShortcut', ownProps({ permissions: ['daily-report.team-read'] })), '')
  assert.match(render('OwnDailyReportShortcut', ownProps({ permissions: ['daily-report.read'] })), /我的日报/)
})

test('hotel report shortcut requires team permission and published module visibility', () => {
  const props = { permissions: ['daily-report.team-read'], available: true, hotelId: 'hotel-one', go() {} }
  assert.match(render('TeamDailyReportShortcut', props), /团队日报/)
  for (const overrides of [{ permissions: ownPermissions }, { available: false }, { hotelId: '' }]) {
    assert.equal(render('TeamDailyReportShortcut', { ...props, ...overrides }), '')
  }
})

test('team shortcut forwards the selected hotel using the report route orgUnitId contract', () => {
  const calls = []
  const button = shortcuts.TeamDailyReportShortcut({ permissions: ['daily-report.team-read'], available: true, hotelId: 'hotel-two', go: (...args) => calls.push(args) })
  button.props.onClick()
  assert.deepEqual(calls, [['daily-reports-team', { orgUnitId: 'hotel-two' }]])
})

test('housekeeping workbench renders the report entry in its top actions without a selected hotel', () => {
  const props = {
    identity: { key: 'housekeeper-assignment', label: '客房服务员', orgName: '测试门店', businessActorAssignmentId: 'housekeeper-assignment', orgScopes: ['hotel-one'] },
    permissions: [...ownPermissions, 'work-record.read'], presentationKey: 'EMPLOYEE', executiveTasksEnabled: false,
    dailyReportsAvailable: true, routeParams: {}, go() {},
  }
  const html = renderToStaticMarkup(React.createElement(RoleWorkbench, props))
  assert.match(html, /<header class="wb-page-heading">[\s\S]*<div class="wb-actions">[\s\S]*>日报填报<\/button>[\s\S]*<\/header>/)
  assert.doesNotMatch(html, /团队日报|工作下达/)
  assert.doesNotMatch(renderToStaticMarkup(React.createElement(RoleWorkbench, { ...props, dailyReportsAvailable: false })), /日报填报/)
})

test('App connects shortcuts to current published navigation and re-evaluates that prop', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
  assert.match(app, /const dailyReportsAvailable = visibleNavigation\.some\(item => item\.id === 'daily-reports-my'\)/)
  assert.match(app, /<RoleWorkbench[^>]*dailyReportsAvailable=\{dailyReportsAvailable\}/)
  assert.match(app, /\}, \[[^\]]*dailyReportsAvailable[^\]]*\]\)/)
})

test('personal daily report destination continues to use server business day and active assignment', () => {
  const reports = readFileSync(new URL('../src/features/dailyReports/DailyReportRoutes.tsx', import.meta.url), 'utf8')
  assert.match(reports, /loadCurrentBusinessDay\(identity, orgUnitId, signal\)/)
  assert.match(reports, /loadMyDailyReports\(identity, signal, \{ businessDate, status, positionAssignmentId: identity\.businessActorAssignmentId \}\)/)
  assert.match(reports, /report\.status === 'DRAFT' \? '立即填报'/)
})
