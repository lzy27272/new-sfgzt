import test from 'node:test'
import assert from 'node:assert/strict'
import { allowsGuestServiceAlbumUpload, evidenceCaptureSourceMatches, focusedWorkFields, isOccurrenceEntry, isGenericWorkQuestion, matchesTaskAssignee, workPolicyValue } from '../src/app/workEntryPolicy.ts'

test('housekeeping guest service photos can be selected later without changing other work evidence', () => {
  const requirement = { checkpointCode: 'guest_service_photo' }
  assert.equal(allowsGuestServiceAlbumUpload('HKA_GUEST_SERVICE_EVENT', requirement), true)
  assert.equal(allowsGuestServiceAlbumUpload('hka_guest_service_event_fact', requirement), true)
  assert.equal(allowsGuestServiceAlbumUpload('HKA_GUEST_SERVICE_EVENT', { ...requirement, captureSource: 'FILE_PICKER' }), true)
  assert.equal(allowsGuestServiceAlbumUpload('HKA_GUEST_SERVICE_EVENT', { ...requirement, captureSource: 'CAMERA_OR_FILE' }), true)
  for (const code of [undefined, 'HKA_CART_PREPARATION', 'HKA_COMPLAINT_EVENT', 'GM_PUBLIC_AREA_CHECK']) {
    assert.equal(allowsGuestServiceAlbumUpload(code, requirement), false)
  }
  assert.equal(allowsGuestServiceAlbumUpload('HKA_GUEST_SERVICE_EVENT', { checkpointCode: 'other_photo' }), false)
  for (const captureSource of ['CAMERA', 'UNKNOWN']) {
    assert.equal(allowsGuestServiceAlbumUpload('HKA_GUEST_SERVICE_EVENT', { ...requirement, captureSource }), false)
  }
})

test('camera-or-file is a choice of evidence sources, not a stored source or a camera-only requirement', () => {
  for (const actual of ['CAMERA', 'FILE_PICKER', 'SYSTEM', undefined]) {
    assert.equal(evidenceCaptureSourceMatches(actual, 'CAMERA_OR_FILE'), actual === 'CAMERA' || actual === 'FILE_PICKER')
    assert.equal(evidenceCaptureSourceMatches(actual, 'CAMERA'), actual === 'CAMERA')
    assert.equal(evidenceCaptureSourceMatches(actual, 'FILE_PICKER'), actual === 'FILE_PICKER')
    assert.equal(evidenceCaptureSourceMatches(actual, undefined), true)
    assert.equal(evidenceCaptureSourceMatches(actual, 'UNKNOWN'), false)
  }
})

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

test('planned hygiene belongs to the housekeeping daily report, not every work item', () => {
  const fields = { plannedHygieneContent: { title: '计划卫生内容' }, plannedHygieneRoomNumbers: { title: '计划卫生房间号' } }
  assert.deepEqual(focusedWorkFields(fields, 'HKA_DAILY_REPORT'), fields)
  assert.deepEqual(focusedWorkFields(fields, 'HKA_CART_PREPARATION'), {})
  assert.deepEqual(focusedWorkFields(fields, 'HKA_LOST_PROPERTY'), {})
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
