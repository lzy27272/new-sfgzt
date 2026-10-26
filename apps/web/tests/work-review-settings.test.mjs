import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createWorkPackageItemDraft, mapWorkPackageItem, serializeWorkPackageItems, validateWorkPackageItems } from '../src/features/workPackages/configuration.ts'
import { reviewSettingLocked, reviewSettingValue } from '../src/features/workPackages/configuration.ts'

const draft = () => ({ ...createWorkPackageItemDraft(1, 'form'), itemCode: 'ROUTINE_CHECK', name: '例行检查', reviewMode: 'NONE' })
test('ordinary exempt and exception-only settings retain the frozen execution policy', () => {
  const item = draft()
  assert.equal(reviewSettingValue(item), 'NONE')
  item.executionExtras = { requiresExceptionReview: true, spotCheckRate: 25, source: 'kept' }
  assert.equal(reviewSettingValue(item), 'EXCEPTION_ONLY')
  const [serialized] = serializeWorkPackageItems([item])
  assert.equal(serialized.reviewMode, 'NONE')
  assert.deepEqual(serialized.executionPolicy, { ...item.executionExtras, enabled: true })
  assert.ok(serialized.responsibilities.some(row => row.participantType === 'ACCEPTOR'))
  assert.equal(reviewSettingValue(mapWorkPackageItem(serialized)), 'EXCEPTION_ONLY')
})
test('critical confirmations cannot be serialized as exempt even from stale drafts', () => {
  for (const code of ['HKA_ITEM_ISSUE','HKA_USAGE_RETURN','HKA_LOST_PROPERTY','HKA_COMPLAINT_EVENT','NSA_DAILY_HANDOVER']) {
    const item = { ...draft(), itemCode: code }
    assert.equal(reviewSettingLocked(item), true)
    assert.equal(reviewSettingValue(item), 'MANUAL')
    assert.equal(serializeWorkPackageItems([item])[0].reviewMode, 'MANUAL')
  }
  for (const flag of ['inventoryUsage', 'handoverConfirmationRequired', 'highRiskImmediateEscalation'])
    assert.equal(reviewSettingLocked({ ...draft(), executionExtras: { [flag]: true } }), true)
  assert.equal(reviewSettingLocked({ ...draft(), submissionExtras: { inventoryUsage: true } }), true)
})
test('sampling percentage is optional but must be an integer from one to one hundred', () => {
  assert.equal(validateWorkPackageItems([draft()]).some(message => message.includes('抽查比例')), false)
  for (const rate of [0, -1, 101, 1.5, '20'])
    assert.equal(validateWorkPackageItems([{ ...draft(), executionExtras: { spotCheckRate: rate } }]).some(message => message.includes('抽查比例')), true)
  for (const rate of [1, 20, 100])
    assert.equal(validateWorkPackageItems([{ ...draft(), executionExtras: { spotCheckRate: rate } }]).some(message => message.includes('抽查比例')), false)
})
test('dispatch is shown as locked manual policy instead of an exempt task control', () => {
  const editor = readFileSync(new URL('../src/features/workPackages/WorkPackageItemEditor.tsx', import.meta.url), 'utf8')
  assert.match(editor, /工作下达任务始终必须人工审核/)
  assert.match(editor, /requiresExceptionReview: event.target.value === 'EXCEPTION_ONLY'/)
  assert.match(editor, /disabled=\{reviewSettingLocked\(selected\)\}/)
})

test('editing a daily business-date item preserves and updates actual-shift timing', () => {
  const timing = { availableAnchor: 'SHIFT_END', availableOffsetMinutes: -30, dueAnchor: 'SHIFT_END', dueOffsetMinutes: 15 }
  const item = mapWorkPackageItem({ ...serializeWorkPackageItems([draft()])[0], periodType: 'DAY', executionPolicy: { workflowRevision: 'THREE_ROLE_V88', shiftTiming: timing } })
  assert.deepEqual(serializeWorkPackageItems([item])[0].executionPolicy.shiftTiming, timing)
  item.shiftDueOffsetMinutes = '20'
  assert.equal(serializeWorkPackageItems([item])[0].executionPolicy.shiftTiming.dueOffsetMinutes, 20)
  item.shiftDueOffsetMinutes = '-40'
  assert.ok(validateWorkPackageItems([item]).some(message => message.includes('截止')))
})

test('editing breakfast work keeps all three required-stage fields and store confirmation', () => {
  const stages = { scheduleConfirmed: false, opening: { field: 'breakfastOpeningNotes', start: '07:00', end: '07:20' }, peak: { field: 'breakfastPeakNotes', start: '08:00', end: '08:20' }, closing: { field: 'breakfastClosingNotes', start: '10:00', end: '10:30' }, unresolvedRecheck: '09:30', recheckOnlyWhenUnresolved: true }
  const item = mapWorkPackageItem({ ...serializeWorkPackageItems([draft()])[0], executionPolicy: { breakfastStages: stages, calendarDayOffset: 0 } })
  assert.deepEqual(serializeWorkPackageItems([item])[0].executionPolicy.breakfastStages, stages)
})
