import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
const navigation = readFileSync(new URL('../src/app/navigationAccess.ts', import.meta.url), 'utf8')
const workbench = readFileSync(new URL('../src/features/workbench/RoleWorkbench.tsx', import.meta.url), 'utf8')
const resources = readFileSync(new URL('../src/api/resources.ts', import.meta.url), 'utf8')
const styles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
const taskPages = readFileSync(new URL('../src/Pilot6Pages.tsx', import.meta.url), 'utf8')
const mobile = readFileSync(new URL('../src/app/rolePresentationPolicy.ts', import.meta.url), 'utf8')
const workPackageService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workpackage/WorkPackageService.java', import.meta.url), 'utf8')
const workDataService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workdata/WorkDataService.java', import.meta.url), 'utf8')
const attachmentService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workdata/AttachmentService.java', import.meta.url), 'utf8')
const vicePresidentDispatchMigration = readFileSync(new URL('../../../database/migrations/V53__group_vice_president_task_dispatch.sql', import.meta.url), 'utf8')


const boardUi = readFileSync(new URL('../src/features/workbench/WorkBoard.tsx', import.meta.url), 'utf8')
const boardApi = readFileSync(new URL('../src/features/workbench/workBoardApi.ts', import.meta.url), 'utf8')
const boardService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workpackage/WorkBoardService.java', import.meta.url), 'utf8')
const boardModel = readFileSync(new URL('../src/features/workbench/workBoardModel.ts', import.meta.url), 'utf8')
const boardStyles = readFileSync(new URL('../src/features/workbench/workBoard.css', import.meta.url), 'utf8')

test('cockpit, task and notification entries are consolidated into the role workbench', () => {
  assert.match(navigation, /\['hotel-dashboard', 'operations-dashboard'\]\.includes\(item\.id\)/)
  assert.match(app, /buildNavigationCenters\(visibleNavigation, activeIdentity\.label\)/)
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
  assert.match(navigation, /\['rules', 'work-packages'\]\.includes\(item\.id\) && !isPlatformAdmin/)
  assert.match(app, /administratorRouteAllowed/)
})

test('multi-hotel workbench preserves overdue and late-submitted detail routes', () => {
assert.match(workbench, /hotelId: hotel.id, status: 'OVERDUE'/)
  assert.match(boardUi, /已补交/)
  assert.match(boardUi, /status: 'ALL', lateOnly: 'true'/)
  assert.match(workbench, /部门工作统计/)
  assert.match(workbench, /员工工作统计/)
  assert.match(boardModel, /Date.parse\(first\) > Date.parse\(item.dueAt\)/)
})

test('team and personal cards open distinct scoped detail pages and retain dates', () => {
assert.match(workbench, /go\('team-work', \{ ...selectedParams, ...patch/)
  assert.match(workbench, /go\('my-work', \{ ...dateParams, status/)
  assert.match(workbench, /title="团队工作看板"/)
  assert.match(workbench, /title="个人工作看板"/)
  assert.match(boardUi, /aria-label=\{boardLabels\[status\]/)
})

test('executive workbench begins at its authorized portfolio and drills into chosen hotel', () => {
assert.match(workbench, /executive && canReadTeam/)
  assert.match(workbench, /!executive && hotels.length === 1/)
  assert.match(workbench, /进入门店/)
  assert.match(workbench, /返回门店列表/)
  assert.match(workbench, /管理提醒/)
})

test('closure, submission and on-time rates stay distinct from settled KPI snapshots', () => {
assert.match(workbench, /当日闭环完成率/)
  assert.match(workbench, /截至当日月工作完成率/)
  assert.match(boardUi, /提交率/)
  assert.match(boardUi, /按时提交率/)
  assert.match(boardUi, /不受审核等待影响/)
  assert.match(boardService, /completed_rate|field \+ "_rate"/)
  assert.doesNotMatch(boardService, /captureWorkbench\(/)
  assert.match(workPackageService, /onTimeCompleted \* 100\.0 \/ expected/)
})

test('workbench uses server business day and shared date query across both boards', () => {
assert.match(boardUi, /setServerDate\(resource.data.businessDate\)/)
  assert.match(boardModel, /timeZone: 'Asia\/Shanghai'/)
  assert.match(boardApi, /boardQuery\(params, today\)/)
  assert.match(workbench, /useWorkBoard\(identity, 'my', dateParams/)
  assert.match(boardService, /hotel_business_day_config/)
})

test('desktop tables scroll accessibly and mobile work lists become cards', () => {
assert.match(workbench, /role="region" tabIndex=\{0\}/)
  assert.match(boardStyles, /\.wb-table-scroll \{ overflow-x: auto/)
  assert.match(boardStyles, /\.wb-work-table thead \{ display: none;/)
  assert.match(boardStyles, /\.wb-work-table tr \{ display: grid;/)
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
