import test from 'node:test'
import assert from 'node:assert/strict'
import { focusedWorkFields, isOccurrenceEntry, isGenericWorkQuestion, matchesTaskAssignee, workPolicyValue } from '../src/app/workEntryPolicy.ts'

test('role work forms remove generic questions but keep actual business facts', () => {
  const fields = { workSummary: { title: '工作结果' }, issueFound: { title: '是否有异常' },
    checkoutRooms: { title: '退房打扫数' }, aiExtractionConfirmed: { title: '已人工确认证据汇总' } }
  assert.deepEqual(Object.keys(focusedWorkFields(fields, 'OTHER')), ['checkoutRooms', 'aiExtractionConfirmed'])
  assert.equal(Object.keys(fields).length, 4)
  assert.deepEqual(Object.keys(focusedWorkFields(fields, 'HKA_DAILY_REPORT')), ['checkoutRooms'])
  assert.deepEqual(Object.keys(focusedWorkFields(fields, 'HKA_CART_PREPARATION')), [])
})

test('only events become occurrence entries; report references use the same contract', () => {
  for (const code of ['HKA_GUEST_SERVICE_EVENT', 'HKA_COMPLAINT_EVENT', 'HKA_LOST_PROPERTY']) {
    assert.equal(isOccurrenceEntry(code), true)
    assert.equal(isOccurrenceEntry(code.toLowerCase() + '_fact'), true)
  }
  assert.equal(isOccurrenceEntry('HKA_DAILY_REPORT'), false)
  assert.equal(isGenericWorkQuestion('custom_key', '是否有异常'), true)
  assert.equal(isGenericWorkQuestion('complaintContent', '客诉内容'), false)
})

test('task assignee search supports role and name, not just employee names', () => {
  const candidate = { employeeName: '测试员工', positionName: '前厅主管', orgName: '解放路店' }
  assert.equal(matchesTaskAssignee(candidate, '前厅主管'), true)
  assert.equal(matchesTaskAssignee(candidate, '测试 前厅'), true)
  assert.equal(matchesTaskAssignee(candidate, '客房'), false)
})

test('conditional photos use actual issue details, not a hidden stale yes/no value', () => {
  assert.equal(workPolicyValue({ issueSummary: '门锁损坏' }, 'issueFound'), true)
  assert.equal(workPolicyValue({ issueSummary: '无异常', issueFound: true }, 'issueFound'), false)
  assert.equal(workPolicyValue({}, 'issueFound'), false)
  assert.equal(workPolicyValue({ specialArrivalRequirement: true }, 'specialArrivalRequirement'), true)
})
