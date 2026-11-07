import assert from 'node:assert/strict'
import test from 'node:test'
import { addDays, chinaToday, emptyPlanDraft, localDateTime, percentage, planDeadline, planDueAt, planPeriodStart, serializePlanDraft, validatePlanDraft } from '../src/features/operations/model.ts'
import { evidenceRequirementLabel } from '../src/app/workEntryPolicy.ts'
import { resolveNotificationNavigation } from '../src/app/notificationNavigation.ts'
import { applyPublishedModuleVisibility, resolveRolePresentationPolicy } from '../src/app/rolePresentationPolicy.ts'
import { buildNavigationCenters, navigationCenterForSection } from '../src/app/navigationModel.ts'
import { safeTaskDeepLink } from '../src/features/wecom/entryRoute.ts'

test('统计百分比使用真实返回值，无应完成不伪造100%', () => {
  assert.equal(percentage(null), '—')
  assert.equal(percentage(undefined), '—')
  assert.equal(percentage(0), '0%')
  assert.equal(percentage(93.333), '93.3%')
})
test('中国日期和周周期跨年不依赖设备时区', () => {
  assert.equal(chinaToday(new Date('2026-12-31T17:00:00Z')), '2027-01-01')
  assert.equal(planPeriodStart('WEEKLY', '2027-01-01'), '2026-12-28')
  assert.equal(planPeriodStart('MONTHLY', '2027-01-16'), '2027-01-01')
  assert.equal(addDays('2026-12-28', 13), '2027-01-10')
  assert.equal(planDeadline(planDueAt('WEEKLY', '2026-12-28')), '2027-01-03 24:00')
  assert.equal(planDeadline(planDueAt('MONTHLY', '2027-01-01')), '2027-01-05 24:00')
})
test('北京时间截止提交带显式偏移且稳定行动身份不变', () => {
  const draft = emptyPlanDraft(['hotel-a'])
  draft.items = [{ actionId: 'stable-action', hotelId: 'hotel-a', assigneeAssignmentId: 'assignment-a', title: '房型核对', description: '', dueAt: '2026-11-11T18:00', acceptanceCriteria: '核对记录与截图' }]
  const serialized = serializePlanDraft(draft)
  assert.equal(serialized.items[0].dueAt, '2026-11-11T18:00:00+08:00')
  assert.equal(serialized.items[0].actionId, 'stable-action')
  assert.equal(localDateTime('2026-11-11T10:00:00Z'), '2026-11-11T18:00')
  assert.equal(localDateTime('2026-11-11T18:00'), '2026-11-11T18:00')
  assert.equal(draft.items[0].dueAt, '2026-11-11T18:00')
})
test('行动校验拒绝重复身份、范围外门店和缺失验收标准', () => {
  const item = { actionId: 'a', hotelId: 'hotel-a', assigneeAssignmentId: 'owner', title: '房型核对', description: '', dueAt: '2026-11-11T18:00', acceptanceCriteria: '保留核对证据' }
  const draft = { summary: '已核实工作总结', objective: '下一周期重点', risk: '', hotelSummaries: {}, items: [item] }
  assert.equal(validatePlanDraft(draft, ['hotel-a']), undefined)
  assert.match(validatePlanDraft(draft, ['hotel-b']), /不在当前负责范围/)
  assert.match(validatePlanDraft({ ...draft, items: [item, item] }, ['hotel-a']), /重复/)
  assert.match(validatePlanDraft({ ...draft, items: [{ ...item, acceptanceCriteria: '' }] }, ['hotel-a']), /验收标准/)
})
test('库存证据实际日期按工作归属日跨月计算，旧证据名称不变', () => {
  assert.equal(evidenceRequirementLabel({ label: '可售房型库存', dateOffsetDays: 6 }, '2026-12-28'), '2027-01-03 · 可售房型库存')
  assert.equal(evidenceRequirementLabel({ label: '前台现场照' }, '2026-12-28'), '前台现场照')
})
test('计划通知保留具体计划ID，缺少计划权限不生成误导链接', () => {
  const notification = { sourceType: 'WORK_PLAN', sourceId: 'plan-a', type: 'WORK_PLAN_SUBMITTED' }
  assert.deepEqual(resolveNotificationNavigation(notification, ['work-plan.review']), { view: 'operations-work', params: { tab: 'plans', planId: 'plan-a' }, actionLabel: '查看待审批计划' })
  assert.equal(resolveNotificationNavigation(notification, ['notification.read']), undefined)
})
test('企微工作计划回跳严格限制单一plans标签与UUID，拒绝重复参数', () => {
  globalThis.window = { location: { origin: 'https://www.sfgzt.cn' } }
  const id = '123e4567-e89b-42d3-a456-426614174000'
  assert.equal(safeTaskDeepLink(`#/operations-work?tab=plans&planId=${id}`), `#/operations-work?tab=plans&planId=${id}`)
  for (const suffix of [`tab=daily&planId=${id}`, `tab=plans&planId=${id}&planId=${id}`, `tab=plans&planId=invalid`, `tab=plans&planId=${id}&actorId=other`]) assert.throws(() => safeTaskDeepLink(`#/operations-work?${suffix}`), /参数无效/)
})
test('新运营岗位有独立导航，菜单发布不扩大其他岗位权限', () => {
  assert.equal(resolveRolePresentationPolicy('OPERATIONS_DIRECTOR').key, 'OPERATIONS')
  assert.equal(resolveRolePresentationPolicy('OPERATIONS_MANAGER').key, 'OPERATIONS')
  assert.equal(resolveRolePresentationPolicy('OTA_OPERATION_MANAGER').key, 'REGIONAL_OPERATIONS')
  const items = [{ id: 'operations-work', label: '运营部工作', icon: '' }, { id: 'daily-operations', label: '日运营', icon: '' }]
  assert.deepEqual(applyPublishedModuleVisibility(items, ['ui.module.operations-work'], 'OPERATIONS_MANAGER').map(item => item.id), ['operations-work'])
  assert.deepEqual(applyPublishedModuleVisibility(items, ['ui.module.my-work'], 'HOUSEKEEPING_ATTENDANT'), [])
  assert.equal(buildNavigationCenters([items[0]], '运营经理')[0].id, 'operations')
  assert.equal(navigationCenterForSection('operations-work'), 'operations')
})
