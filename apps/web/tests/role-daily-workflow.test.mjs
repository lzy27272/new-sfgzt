import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { breakfastWorkflowSchedule, dailyWorkflowRole, dailyWorkflowGroup, dailyWorkflowGroups, isImmediateWorkflowItem, loadDailyWorkflowItems, workflowTime, workflowTimeOrder } from '../src/features/workbench/roleDailyWorkflow.ts'

const role = 'HOUSEKEEPING_ATTENDANT'
const work = (itemCode, overrides = {}) => ({ id: itemCode, itemCode, ...overrides })
const page = (items, total = items.length, number = 0) => ({ data: { items, filtered: { item_count: total }, page: number, size: 50 }, source: 'api' })

test('three-role grouping uses the actual current position, never the shared employee presentation', () => {
  for (const key of ['HOUSEKEEPING_ATTENDANT', 'ASSISTANT_GENERAL_MANAGER', 'NIGHT_SERVICE_ATTENDANT']) assert.equal(dailyWorkflowRole(key), key)
  for (const key of ['EMPLOYEE', 'FRONT_DESK', 'HOUSEKEEPING_SUPERVISOR', 'GENERAL_MANAGER', 'PLATFORM_ADMIN', 'GENERIC']) assert.equal(dailyWorkflowRole(key), undefined)
  assert.equal(dailyWorkflowRole(' housekeeping_attendant '), role)
  assert.equal(dailyWorkflowRole(undefined), undefined)
})

test('every optimized role has exactly three presentation groups without changing work identities', () => {
  for (const groups of Object.values(dailyWorkflowGroups)) assert.deepEqual(groups.map(group => group.id), ['start', 'during', 'close'])
  const item = work('HKA_CART_PREPARATION', { assignmentId: 'own', status: 'AVAILABLE' })
  const before = structuredClone(item)
  assert.equal(dailyWorkflowGroup(role, item), 'start')
  assert.deepEqual(item, before)
})

test('housekeeping keeps immediate records apart from closing usage and report', () => {
  for (const code of ['HKA_CART_PREPARATION', 'HKA_ITEM_ISSUE']) assert.equal(dailyWorkflowGroup(role, work(code)), 'start')
  for (const code of ['HKA_GUEST_SERVICE_EVENT', 'HKA_COMPLAINT_EVENT', 'HKA_LOST_PROPERTY']) assert.equal(dailyWorkflowGroup(role, work(code)), 'during')
  for (const code of ['HKA_USAGE_RETURN', 'HKA_DAILY_REPORT']) assert.equal(dailyWorkflowGroup(role, work(code)), 'close')
})

test('assistant variants classify by item code, not localized or user-edited titles', () => {
  for (const prefix of ['AGM_NS_', 'AGM_WS_']) {
    for (const suffix of ['BREAKFAST_INSPECTION', 'BREAKFAST_FEEDBACK', 'BREAKFAST_WORKFLOW', 'APPEARANCE_MEETING', 'OTA_CHECK', 'PUBLIC_AREA', 'KITCHEN_INSPECTION']) {
      assert.equal(dailyWorkflowGroup('ASSISTANT_GENERAL_MANAGER', work(prefix + suffix, { title: '任意标题' })), 'start')
    }
    for (const suffix of ['THREE_FOLLOWUPS', 'ROOM_INSPECTION', 'CUSTOMER_FORMS', 'OPERATION_FILES']) assert.equal(dailyWorkflowGroup('ASSISTANT_GENERAL_MANAGER', work(prefix + suffix)), 'during')
    for (const suffix of ['EVENING_LIGHT_BAR', 'EVENING_PUBLIC_AREA', 'DAILY_REPORT']) assert.equal(dailyWorkflowGroup('ASSISTANT_GENERAL_MANAGER', work(prefix + suffix)), 'close')
  }
})

test('night equipment rounds remain separate real work, while handover is a closing item', () => {
  const night = 'NIGHT_SERVICE_ATTENDANT'
  assert.equal(dailyWorkflowGroup(night, work('NSA_CART_PREPARATION')), 'start')
  assert.equal(dailyWorkflowGroup(night, work('NSA_EQUIPMENT_FIRST')), 'during')
  assert.equal(dailyWorkflowGroup(night, work('NSA_EQUIPMENT_SECOND')), 'during')
  assert.equal(dailyWorkflowGroup(night, work('NSA_LOST_PROPERTY')), 'during')
  assert.equal(dailyWorkflowGroup(night, work('NSA_FINAL_PUBLIC_RECHECK')), 'close')
  assert.equal(dailyWorkflowGroup(night, work('NSA_DAILY_HANDOVER')), 'close')
})

test('unmapped current-assignment work is not hidden or guessed from titles', () => {
  for (const key of Object.keys(dailyWorkflowGroups)) {
    assert.equal(dailyWorkflowGroup(key, work('CUSTOM_ITEM', { title: '早餐巡查' })), 'during')
    assert.equal(dailyWorkflowGroup(key, {}), 'during')
  }
})

test('published workflow group metadata takes priority while unrecognized metadata falls back safely', () => {
  assert.equal(dailyWorkflowGroup(role, work('CUSTOM_ITEM', { executionPolicy: { workflowGroup: 'CLOSE' } })), 'close')
  assert.equal(dailyWorkflowGroup(role, work('HKA_DAILY_REPORT', { executionPolicy: { workflowGroup: 'START' } })), 'start')
  assert.equal(dailyWorkflowGroup(role, work('HKA_DAILY_REPORT', { executionPolicy: { workflowGroup: 'UNKNOWN' } })), 'close')
})

test('breakfast shows three configured stages without inventing stage completion or using global fixed times', () => {
  const item = work('AGM_NS_BREAKFAST_INSPECTION', { executionPolicy: { breakfastStages: {
    scheduleConfirmed: true, opening: { field: 'breakfastOpeningNotes', start: '06:30', end: '06:50' },
    peak: { field: 'breakfastPeakNotes', start: '08:10', end: '08:30' }, closing: { field: 'breakfastClosingNotes', start: '10:10', end: '10:40' },
    unresolvedRecheck: '09:30', recheckOnlyWhenUnresolved: true,
  } } })
  const result = breakfastWorkflowSchedule(item)
  assert.equal(result.confirmed, true)
  assert.equal(result.stages.length, 3)
  assert.equal(result.stages[0].start, '06:30')
  assert.equal(result.stages[2].end, '10:40')
  assert.equal(result.recheck, '09:30')
  assert.ok(result.stages.every(stage => !('completed' in stage)))
})

test('missing or malformed breakfast stage configuration is explicit, never replaced with demo times', () => {
  assert.equal(breakfastWorkflowSchedule(work('AGM_NS_BREAKFAST_INSPECTION')), undefined)
  assert.equal(breakfastWorkflowSchedule(work('x', { executionPolicy: { breakfastStages: [] } })), undefined)
  const result = breakfastWorkflowSchedule(work('x', { executionPolicy: { breakfastStages: { opening: { start: '25:00', end: null } } } }))
  assert.equal(result.confirmed, false)
  assert.equal(result.stages[0].start, '待配置')
  assert.equal(result.stages[1].end, '待配置')
  assert.equal(result.recheck, undefined)
})

test('immediate entries are selected only from real API work and retain occurrence policy', () => {
  assert.equal(isImmediateWorkflowItem(work('HKA_COMPLAINT_EVENT')), true)
  assert.equal(isImmediateWorkflowItem(work('NSA_LOST_PROPERTY')), true)
  assert.equal(isImmediateWorkflowItem(work('CUSTOM_EVENT', { executionPolicy: { registrationMode: 'WHEN_OCCURRED' } })), true)
  assert.equal(isImmediateWorkflowItem(work('HKA_DAILY_REPORT')), false)
})

test('times show actual materialized timestamps, including next-day shift end, or missing config', () => {
  assert.equal(workflowTime('2026-10-25T07:30:00+08:00'), '10/25 07:30')
  assert.equal(workflowTime('2026-10-26T08:15:00+08:00'), '10/26 08:15')
  assert.equal(workflowTime(undefined), '待配置')
  assert.equal(workflowTime('invalid'), '待配置')
})

test('group rows follow real available time while missing timing is placed last', () => {
  const late = work('late', { title: '后检查', availableAt: '2026-10-25T11:00:00+08:00' })
  const early = work('early', { title: '先检查', availableAt: '2026-10-25T07:00:00+08:00' })
  const missing = work('missing', { title: '未配置' })
  assert.deepEqual([missing, late, early].sort(workflowTimeOrder).map(item => item.id), ['early', 'late', 'missing'])
})

test('a personal list larger than 50 fetches every page without truncating or changing status', async () => {
  const requests = []
  const source = Array.from({ length: 123 }, (_, index) => work('item-' + index, { boardStatus: index === 121 ? 'REVIEW' : 'PENDING' }))
  const result = await loadDailyWorkflowItems(async number => { requests.push(number); return page(source.slice(number * 50, (number + 1) * 50), 123, number) })
  assert.deepEqual(requests, [0, 1, 2])
  assert.equal(result.source, 'api')
  assert.equal(result.data.length, 123)
  assert.equal(result.data[121].boardStatus, 'REVIEW')
})

test('empty API result stays empty and a demo fallback cannot look like real work', async () => {
  assert.deepEqual(await loadDailyWorkflowItems(async () => page([])), { data: [], source: 'api' })
  await assert.rejects(loadDailyWorkflowItems(async () => ({ ...page([work('demo')]), source: 'fallback' })), /实际工作数据/)
})

test('incomplete or changing pages show an error instead of a falsely complete list', async () => {
  await assert.rejects(loadDailyWorkflowItems(async number => number === 0 ? page([work('one')], 2) : page([], 2)), /完整读取/)
  await assert.rejects(loadDailyWorkflowItems(async () => page([work('one')], 2)), /发生变化/)
})

test('group component stays on personal scope and reuses the existing form and report route', () => {
  const ui = readFileSync(new URL('../src/features/workbench/RoleDailyWorkflowPanel.tsx', import.meta.url), 'utf8')
  assert.match(ui, /loadWorkBoard\(identity, 'my'/)
  assert.match(ui, /status: 'ALL', queue: 'PERIOD'/)
  assert.match(ui, /module\.WorkRecordDialog/)
  assert.match(ui, /loadExpectation\(identity, item.id\)/)
  assert.match(ui, /go\('daily-reports-my', \{ businessDate: result.data.businessDate/)
  assert.match(ui, /日报提交不代替接收人交班确认|交班仍须各自确认/)
  assert.doesNotMatch(ui, /fetch\(|demoExpectations|firstValidSubmissionWins|createWorkRecord/)
})

test('workbench preserves all other roles and resets the grouped component on assignment/date changes', () => {
  const ui = readFileSync(new URL('../src/features/workbench/RoleWorkbench.tsx', import.meta.url), 'utf8')
  assert.match(ui, /dailyWorkflowRole\(identity.roleCode\)/)
  assert.match(ui, /workflowRole && own.data.businessDate && !own.error && <RoleDailyWorkflow/)
  assert.match(ui, /key=\{identity.key \+ ':' \+ own.query.from \+ ':' \+ own.query.to\}/)
  assert.match(ui, /!workflowRole && own.data.upcomingItems/)
})

test('manual dispatch reviews load the complete queue and explicitly preview six with a total and full-list route', () => {
  const ui = readFileSync(new URL('../src/features/workbench/RoleWorkbench.tsx', import.meta.url), 'utf8')
  assert.match(ui, /canReviewTasks = Boolean\(identity.businessActorAssignmentId\) && allows\(permissions, 'task.review'\)/)
  assert.match(ui, /if \(!canReviewTasks\) return \{ data: \[\], source: 'api'/)
  assert.match(ui, /await loadTasks\(identity, \{ view: 'review' \}\)/)
  assert.match(ui, /if \(result.source !== 'api'\) throw new Error\('未读取到实际待审核任务/)
  assert.match(ui, /visibleTaskReviews = pendingTaskReviews.slice\(0, 6\)/)
  assert.match(ui, /visibleTaskReviews.map\(task/)
  assert.match(ui, /共 \{pendingTaskReviews.length\} 项待审核/)
  assert.match(ui, /查看全部待审核任务/)
  assert.match(ui, /go\('tasks', \{ view: 'review', taskId: task.id \}\)/)
  assert.match(ui, /与日清完成率分开/)
})
