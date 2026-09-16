import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
const workbench = readFileSync(new URL('../src/features/workbench/RoleWorkbench.tsx', import.meta.url), 'utf8')
const resources = readFileSync(new URL('../src/api/resources.ts', import.meta.url), 'utf8')
const styles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
const taskPages = readFileSync(new URL('../src/Pilot6Pages.tsx', import.meta.url), 'utf8')
const mobile = readFileSync(new URL('../src/app/rolePresentationPolicy.ts', import.meta.url), 'utf8')
const workPackageService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workpackage/WorkPackageService.java', import.meta.url), 'utf8')
const workDataService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workdata/WorkDataService.java', import.meta.url), 'utf8')
const attachmentService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workdata/AttachmentService.java', import.meta.url), 'utf8')
const vicePresidentDispatchMigration = readFileSync(new URL('../../../database/migrations/V53__group_vice_president_task_dispatch.sql', import.meta.url), 'utf8')

test('cockpit, task and notification entries are consolidated into the role workbench', () => {
  assert.match(app, /\['hotel-dashboard', 'operations-dashboard', 'tasks', 'notifications'\]\.includes\(item\.id\)/)
  assert.match(app, /item\.id === 'workbench' \? `\$\{activeIdentity\.label\}工作台`/)
  assert.match(workbench, /一键下达任务/)
  assert.match(workbench, /消息提醒/)
})

test('quick dispatch opens the real task dialog in the workbench and keeps target policy authoritative', () => {
  assert.match(workbench, /<TaskCreateDialog/)
  assert.match(workbench, /permissions=\{permissions\}/)
  assert.match(workbench, /creationSource="WORKBENCH_QUICK_DISPATCH"/)
  assert.match(workbench, /initialHotelId=\{selectedHotel\?\.id\}/)
  assert.match(taskPages, /initialHotelId/)
  assert.match(vicePresidentDispatchMigration, /'task\.create', 'task\.dispatch'/)
  assert.match(vicePresidentDispatchMigration, /position_function_profile_permission/)
})

test('quick dispatch does not require optional template or standard read permissions', () => {
  assert.match(taskPages, /loadOptionalTaskContext\(permissions, 'template\.read'/)
  assert.match(taskPages, /loadOptionalTaskContext\(permissions, 'standard\.read'/)
  assert.match(taskPages, /\{canReadTemplates && <label>任务模板/)
  assert.match(taskPages, /\{canReadStandards && <label>关联标准/)
  assert.match(app, /<TaskCreateDialog identity=\{identity\} permissions=\{permissions\}/)
})

test('rules and work package routes are platform-administrator only', () => {
  assert.match(app, /\['rules', 'work-packages'\]\.includes\(item\.id\) && !isPlatformAdmin/)
  assert.match(app, /administratorRouteAllowed/)
})

test('multi-hotel workbench supports overdue to late-submitted drill-down', () => {
  assert.match(workbench, /已逾期/)
  assert.match(workbench, /仍未提交/)
  assert.match(workbench, /已补交/)
  assert.match(workbench, /submitted > due/)
  assert.match(workbench, /TeamWorkDrawer/)
  assert.match(workbench, /部门员工统计/)
  assert.match(workbench, /本月完成率/)
  assert.match(workbench, /summaryHotel\.departments/)
  assert.match(workPackageService, /"departments"/)
  assert.match(workPackageService, /"employees"/)
})

test('workbench headline metrics open status details with matching data scope', () => {
  assert.match(workbench, /const openMetricStatus = \(status:/)
  assert.match(workbench, /!executive && selectedHotel/)
  assert.match(workbench, /scope: 'all', status, focus: 'work-status'/)
  assert.match(workbench, /allScopeDrilldown \? undefined/)
  assert.match(workbench, /id="workbench-status-detail" tabIndex=\{-1\}/)
  assert.match(workbench, /scrollIntoView\(\{ behavior: 'smooth', block: 'start' \}\)/)
  assert.match(workbench, /aria-label=\{`查看待提交工作明细/)
  assert.match(workbench, /点击查看 ›/)
})

test('executive workbench keeps the hotel portfolio and opens the first hotel detail by default', () => {
  assert.match(workbench, /\{executive && <section className="workbench-portfolio panel">/)
  assert.match(workbench, /\(executive \|\| hotelManagementKeys\.has\(presentationKey\)\) \? hotels\[0\]/)
  assert.match(workbench, /部门工作统计/)
  assert.match(workbench, /部门员工工作统计/)
  assert.match(workbench, /<h3>工作任务<\/h3>/)
  assert.match(workbench, /今日个人工作/)
  assert.match(workbench, /管理提醒/)
})

test('daily and month-to-date completion rates use the KPI on-time formula', () => {
  assert.match(workbench, /当日完成率/)
  assert.match(workbench, /截至当日月工作完成率/)
  assert.match(workbench, /当日按时完成项 ÷ 当日应完成项/)
  assert.match(workbench, /本月截至今日按时完成项 ÷ 同期应完成项/)
  assert.match(workbench, /loadWorkbenchSummary/)
  assert.match(workPackageService, /teamWorkbenchSummary/)
  assert.match(workPackageService, /onTimeCompleted \* 100\.0 \/ expected/)
  assert.match(workPackageService, /first_record\.submitted_at/)
})

test('workbench pins requests to the reactive Shanghai business date', () => {
  const currentScope = workbench.slice(workbench.indexOf('function currentScopeWork'), workbench.indexOf('function hotelIdFor'))
  assert.match(workbench, /timeZone: 'Asia\/Shanghai'/)
  assert.match(workbench, /role-workbench-work:\$\{canReadTeam\}:\$\{canReadOwn\}:\$\{currentBusinessDate\}/)
  assert.match(workbench, /loadTeamWork\(identity, \{ businessDate: currentBusinessDate \}\)/)
  assert.match(workbench, /loadWorkbenchSummary\(identity, currentBusinessDate\)/)
  assert.match(workbench, /visibilitychange/)
  assert.match(resources, /query\.set\('businessDate', options\.businessDate\)/)
  assert.match(resources, /if \(businessDate\) query\.set\('businessDate', businessDate\)/)
  assert.doesNotMatch(currentScope, /isLateSubmitted/)
})

test('wide hotel details expose a keyboard-accessible horizontal scroll region', () => {
  assert.match(workbench, /className="workbench-detail-scroll" role="region" tabIndex=\{0\}/)
  assert.match(styles, /\.workbench-hotel-table,\.workbench-detail-scroll \{ overflow-x: auto;/)
  assert.match(styles, /\.workbench-detail-grid \{ min-width: 1080px;/)
})

test('mobile main tabs no longer expose task and notification centers', () => {
  const factory = mobile.slice(mobile.indexOf('const tabs ='), mobile.indexOf('const modules ='))
  assert.doesNotMatch(factory, /target: 'tasks'/)
  assert.doesNotMatch(factory, /target: 'notifications'/)
  assert.match(factory, /target: 'kpi-center'/)
})

test('team readers can open submitted work records and evidence within org scope', () => {
  assert.match(workPackageService, /requireAnyPermission\("work-record\.review", "work-record\.team-read"\)/)
  assert.match(workPackageService, /"work-package\.read", "work-record\.read", "work-record\.review", "work-record\.team-read"/)
  assert.match(workDataService, /requireAnyPermission\("work-record\.read", "work-record\.review", "work-record\.team-read"\)/)
  assert.match(attachmentService, /requireAnyPermission\("work-record\.read", "work-record\.review", "work-record\.team-read"\)/)
})
