import test from 'node:test'
import assert from 'node:assert/strict'
import { groomingWorkCandidates, aiWorkDetailParams } from '../src/features/aiEvidence/startModel.ts'

test('original submitted grooming work remains discoverable without AI checks, including the legacy manager template', () => {
  const codes = ['GM_APPEARANCE_MEETING_V56', 'GM_APPEARANCE_MORNING_MEETING_V2', 'GM_APPEARANCE_MORNING_MEETING', 'AGM_NS_APPEARANCE_MEETING', 'AGM_WS_APPEARANCE_MEETING']
  const rows = codes.map((itemCode, i) => ({ id: String(i), itemCode, recordId: 'record-' + i, latestRecordStatus: 'SUBMITTED' }))
  assert.deepEqual(groomingWorkCandidates([...rows, { id: 'unsubmitted', itemCode: codes[0] },
    { id: 'meeting-only', title: '仪容晨会', itemCode: 'OTHER', recordId: 'r' }]), rows)
  assert.equal(groomingWorkCandidates([{ id: 'repeatable', itemCode: codes[0], records: [{ id: 'r', status: 'APPROVED' }] }]).length, 1)
})

test('opening original work anchors its actual date and clears unrelated queue, status, paging and record selection', () => {
  const item = { id: 'expectation', businessDate: '2026-10-27' }
  const params = aiWorkDetailParams(item, 'authorized-hotel')
  assert.deepEqual(params, { hotelId: 'authorized-hotel', dateMode: 'day', from: '2026-10-27', to: '2026-10-27', queue: 'PERIOD', status: 'ALL', expectationId: 'expectation', recordId: undefined })
  for (const key of ['lateOnly', 'page', 'recordId', 'departmentId', 'assignmentId']) assert.equal(params[key], undefined)
  assert.equal(aiWorkDetailParams(item).hotelId, undefined)
})

test('draft records do not appear as submitted work; a selected historical submission keeps its own ID', () => {
  const base = { id: 'expectation', itemCode: 'AGM_NS_APPEARANCE_MEETING', businessDate: '2026-10-27', recordId: 'draft' }
  assert.deepEqual(groomingWorkCandidates([{ ...base, latestRecordStatus: 'DRAFT' },
    { ...base, latestRecordStatus: 'DRAFT', latestSubmittedAt: '2026-10-27T01:00:00Z' }, base]), [])
  const historical = { ...base, latestRecordStatus: 'DRAFT', records: [{ id: 'draft', status: 'DRAFT' }, { id: 'submitted', status: 'SUBMITTED' }] }
  assert.equal(groomingWorkCandidates([historical]).length, 1)
  assert.equal(aiWorkDetailParams(historical).recordId, 'submitted')
  assert.equal(groomingWorkCandidates([{ ...base, latestSubmittedAt: '2026-10-27T01:00:00Z' }]).length, 1)
})
