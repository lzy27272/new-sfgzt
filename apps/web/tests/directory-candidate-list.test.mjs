import assert from 'node:assert/strict'
import test from 'node:test'
import { directoryCandidateStoreKey, directoryCandidateStores, isPendingDirectoryCandidate, selectDirectoryCandidates } from '../src/features/wecom/directoryCandidateList.ts'

const candidate = (id, status, patch = {}) => ({
  id, status, displayName: `模拟员工${id}`, requestedLoginName: `local.${id}`, maskedFingerprint: 'TEST••••0001',
  requestedOrgUnitId: 'department-a', requestedHotelId: 'hotel-a', requestedHotelName: '验收甲店',
  requestedDepartmentName: '客房部', requestedPositionName: '客房服务员',
  expiringSoon: false, retryable: false, canRegenerate: false, updatedAt: '2026-10-25T10:00:00+08:00', rowVersion: 0,
  ...patch,
})
const items = [
  candidate('recent-approved', 'APPROVED', { updatedAt: '2026-10-25T11:00:00+08:00' }),
  candidate('old-pending', 'PENDING_APPROVAL', { updatedAt: '2026-09-18T10:00:00+08:00' }),
  candidate('conflict', 'CONFLICT', { requestedHotelId: 'hotel-b', requestedHotelName: '验收乙店' }),
  candidate('waiting', 'WAITING_PROFILE', { requestedOrgUnitId: undefined, requestedHotelId: undefined, requestedHotelName: undefined }),
  candidate('headquarters', 'REJECTED', { requestedOrgUnitId: 'group', requestedHotelId: 'group', requestedHotelName: '集团总部' }),
]

test('pending view includes review and binding conflicts, not completed or waiting-for-employee records', () => {
  assert.deepEqual(selectDirectoryCandidates(items, 'PENDING', '', '').map(item => item.id), ['conflict', 'old-pending'])
  assert.equal(isPendingDirectoryCandidate(items[0]), false)
  assert.equal(isPendingDirectoryCandidate(items[3]), false)
})

test('all states keep older pending records ahead of newer completed records without mutating the source', () => {
  const before = structuredClone(items)
  const visible = selectDirectoryCandidates(items, '', '', '')
  assert.deepEqual(visible.slice(0, 2).map(item => item.id), ['conflict', 'old-pending'])
  assert.equal(visible[2].id, 'recent-approved')
  assert.deepEqual(items, before)
})

test('store options come from all states, merge departments and preserve headquarters and unselected records', () => {
  const rows = [...items, candidate('front', 'APPROVED', { requestedOrgUnitId: 'department-front', requestedDepartmentName: '前厅部' })]
  const stores = directoryCandidateStores(rows)
  assert.equal(stores.length, 4)
  assert.equal(stores.filter(store => store.name === '验收甲店').length, 1)
  assert.ok(stores.some(store => store.key === 'id:group' && store.name === '集团总部'))
  assert.equal(stores.at(-1).key, 'unassigned')
  assert.equal(selectDirectoryCandidates(rows, 'APPROVED', 'id:hotel-a', '').length, 2)
})

test('status, store and case-insensitive employee/account/department search intersect', () => {
  assert.deepEqual(selectDirectoryCandidates(items, 'APPROVED', 'id:hotel-a', ' LOCAL.RECENT-APPROVED ').map(item => item.id), ['recent-approved'])
  assert.equal(selectDirectoryCandidates(items, 'APPROVED', 'id:hotel-b', '').length, 0)
  assert.equal(selectDirectoryCandidates(items, 'PENDING', 'id:hotel-a', '客房部')[0].id, 'old-pending')
  assert.equal(selectDirectoryCandidates(items, '', '', '不存在的关键词').length, 0)
  assert.equal(selectDirectoryCandidates(items, '', 'unassigned', '')[0].id, 'waiting')
})

test('same-name stores with different IDs remain separate; legacy name-only responses remain filterable', () => {
  const sameName = [candidate('one', 'APPROVED'), candidate('two', 'APPROVED', { requestedHotelId: 'hotel-other' })]
  assert.equal(directoryCandidateStores(sameName).length, 2)
  assert.equal(selectDirectoryCandidates(sameName, '', 'id:hotel-other', '')[0].id, 'two')
  const legacy = candidate('legacy', 'APPROVED', { requestedHotelId: undefined })
  assert.equal(directoryCandidateStoreKey(legacy), 'name:验收甲店')
  assert.equal(selectDirectoryCandidates([legacy], '', 'name:验收甲店', '').length, 1)
})

test('equal timestamps have deterministic ID order and all closed statuses remain accessible', () => {
  const closed = ['APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'WAITING_PROFILE'].map((status, index) => candidate(String(index), status))
  for (const row of closed) assert.deepEqual(selectDirectoryCandidates(closed, row.status, '', ''), [row])
  assert.deepEqual(selectDirectoryCandidates([candidate('b', 'PENDING_APPROVAL'), candidate('a', 'PENDING_APPROVAL')], '', '', '').map(item => item.id), ['a', 'b'])
  assert.deepEqual(selectDirectoryCandidates([], 'PENDING', '', ''), [])
})
