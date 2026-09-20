import assert from 'node:assert/strict'
import test from 'node:test'
import { dateRange, boardQuery, validDate, rate, isLateSubmitted, withBoardParams, selectTeamRecordId, personalWorkLabel } from '../src/features/workbench/workBoardModel.ts'
import { currentAssignment, groupPersonnel, personnelScope } from '../src/features/organization/personnelDirectoryModel.ts'

test('personal board distinguishes own shared and delegated items without expanding visibility', () => {
  assert.equal(personalWorkLabel({ assignmentId: 'own' }, 'own'), '我的岗位工作')
  assert.equal(personalWorkLabel({ assignmentId: 'manager' }, 'own'), '本岗位可执行的共享工作')
  assert.equal(personalWorkLabel({ assignmentId: 'manager', delegateAssignmentId: 'own' }, 'own'), '转交给我的工作')
  assert.equal(personalWorkLabel({ assignmentId: 'manager' }), '')
})

test('week begins Monday and crosses month/year correctly', () => {
  assert.deepEqual(dateRange('week', '2026-01-01'), { from: '2025-12-29', to: '2026-01-04' })
  assert.deepEqual(dateRange('week', '2026-09-20'), { from: '2026-09-14', to: '2026-09-20' })
  assert.deepEqual(dateRange('month', '2026-09-20'), { from: '2026-09-01', to: '2026-09-20' })
  const month = boardQuery({ dateMode: 'month', from: '2026-09-01', to: '2026-09-20' })
  assert.equal(month.from, '2026-09-01')
  assert.equal(month.to, '2026-09-20', 'month-to-date must not collapse to the first day after navigation')
})
test('date defaults can be resolved from server business day and invalid calendar days rejected', () => {
  assert.equal(boardQuery({}, '2026-09-19').from, '2026-09-19')
  assert.equal(validDate('2026-02-30'), false)
  assert.equal(validDate('2024-02-29'), true)
  assert.deepEqual(dateRange('custom', '2026-08-29', '2026-09-20'), { from: '2026-08-29', to: '2026-09-20' })
})
test('drilldown preserves dates and organization but clears stale page/detail', () => {
  const params = { dateMode: 'week', from: '2026-09-14', to: '2026-09-20', hotelId: 'hotel', page: '4', expectationId: 'old' }
  assert.deepEqual(withBoardParams(params, { status: 'REVIEW', departmentId: 'department' }), {
    ...params, page: undefined, expectationId: undefined, status: 'REVIEW', departmentId: 'department',
  })
  assert.equal(boardQuery({ status: 'LATE_SUBMITTED' }).lateOnly, true)
})
test('late submission is based on first successful submission and zero denominator is not 100%', () => {
  assert.equal(rate(null), '—')
  assert.equal(rate(0), '0%')
  const work = { dueAt: '2026-09-20T10:00:00+08:00', firstSubmittedAt: '2026-09-20T09:59:00+08:00', latestSubmittedAt: '2026-09-20T11:00:00+08:00' }
  assert.equal(isLateSubmitted(work), false)
  assert.equal(isLateSubmitted({ ...work, firstSubmittedAt: '2026-09-20T10:01:00+08:00' }), true)
})

test('repeatable reviews remain reachable when a newer record is already approved', () => {
  const work = { recordId: 'latest', records: [
    { id: 'latest', status: 'APPROVED' }, { id: 'pending', status: 'SUBMITTED' },
  ], executionPolicy: { repeatableDailyRecords: true } }
  assert.equal(selectTeamRecordId(work), 'pending')
  assert.equal(selectTeamRecordId(work, 'latest'), 'latest')
  assert.equal(selectTeamRecordId({ ...work, executionPolicy: {} }), 'latest')
  assert.throws(() => selectTeamRecordId(work, 'other-work-record'), /不属于当前工作/)
})
const orgs = [
  { id: 'hq', name: '总部', unitType: 'GROUP', status: 'ACTIVE' },
  { id: 'a', name: 'A店', parentId: 'hq', unitType: 'HOTEL', status: 'ACTIVE' },
  { id: 'b', name: 'B店', parentId: 'hq', unitType: 'HOTEL', status: 'ACTIVE' },
  { id: 'dept', name: '客房部', parentId: 'a', unitType: 'DEPARTMENT', status: 'ACTIVE' },
]
const staff = [
  { id: 'one', name: '员工甲', assignmentId: '1', employmentStatus: 'ACTIVE', orgUnitId: 'a', primary: true },
  { id: 'one', name: '员工甲', assignmentId: '2', employmentStatus: 'ACTIVE', orgUnitId: 'dept', primary: false },
  { id: 'one', name: '员工甲', assignmentId: '3', employmentStatus: 'ACTIVE', orgUnitId: 'b', primary: false },
  { id: 'boss', name: '高管', assignmentId: '4', employmentStatus: 'ACTIVE', orgUnitId: 'hq', responsibleHotelNames: 'A店、B店' },
  { id: 'past', name: '到期员工', assignmentId: '5', employmentStatus: 'ACTIVE', orgUnitId: 'a', validTo: '2026-09-19' },
  { id: 'future', name: '未来员工', assignmentId: '6', employmentStatus: 'ACTIVE', orgUnitId: 'a', validFrom: '2026-09-21' },
]
test('one employee per hotel with multiple assignments expandable; management scope is not employment', () => {
  const rows = groupPersonnel(staff, orgs, 'a', '2026-09-20')
  assert.equal(rows.length, 1)
  assert.equal(rows[0].length, 2)
  assert.equal(groupPersonnel(staff, orgs, 'b', '2026-09-20').length, 1)
  assert.equal(personnelScope(staff[3], orgs), 'hq')
  assert.equal(groupPersonnel(staff, orgs, 'hq', '2026-09-20').length, 1)
})
test('ended, future, expired and disabled assignments do not inflate current staffing', () => {
  assert.equal(groupPersonnel(staff, orgs, 'a', '2026-09-20', true).length, 3)
  assert.equal(groupPersonnel(staff, orgs, 'unassigned', '2026-09-20').length, 2)
  assert.equal(currentAssignment({ ...staff[0], assignmentStatus: 'ENDED' }, '2026-09-20'), false)
  assert.equal(currentAssignment({ ...staff[0], accountStatus: 'INACTIVE' }, '2026-09-20'), false)
  assert.equal(currentAssignment({ ...staff[0], validTo: '2026-09-20' }, '2026-09-20'), true)
})
