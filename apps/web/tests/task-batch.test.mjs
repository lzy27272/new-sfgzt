import assert from 'node:assert/strict'
import test from 'node:test'
import { deliverTask, newTaskDelivery, newTaskRow, taskBatchErrors, taskTitle, localDateTimeInput } from '../src/features/tasks/taskBatch.ts'

const person = { id: 'a1', hotelId: 'h1', orgUnitId: 'd1', employeeName: '模拟甲', positionName: '前厅主管', orgName: '前厅部' }
const row = (patch = {}) => ({ ...newTaskRow(), assigneeAssignmentId: 'a1', content: '检查大厅灯具\n逐项登记处理结果', ...patch })

test('batch validation rejects blank content and cross-store assignees before any requests', () => {
  const first = row(), samePerson = row(), wrongStore = row({ assigneeAssignmentId: 'a2' }), empty = row({ content: '  \n ' })
  const people = [person, { ...person, id: 'a2', hotelId: 'h2' }]
  assert.deepEqual(taskBatchErrors([first, samePerson], 'h1', people), {})
  assert.match(taskBatchErrors([wrongStore], 'h1', people)[wrongStore.id], /当前门店/)
  assert.match(taskBatchErrors([empty], 'h1', people)[empty.id], /任务内容/)
  assert.deepEqual(taskBatchErrors([first], 'h1', [], new Set([first.id])), {})
  assert.match(taskBatchErrors([first], 'h1', people, new Set(), 'a1')[first.id], /验收负责人不能相同/)
  assert.deepEqual(taskBatchErrors([first], 'h1', people, new Set([first.id]), 'a1'), {})
})

test('title derives from the first content line, keeps full content and does not split emoji', () => {
  assert.equal(taskTitle('  检查大厅灯具\n逐项登记  ', '巡查：'), '巡查：检查大厅灯具')
  const content = '🧹'.repeat(260) + '\n完整执行要求'
  const title = taskTitle(content)
  assert.equal(Array.from(title).length, 240)
  assert.equal(title, '🧹'.repeat(240))
  assert.ok(content.endsWith('完整执行要求'))
})

test('datetime-local defaults preserve local wall clock rather than display UTC as local time', () => {
  const date = new Date(2026, 9, 24, 15, 30)
  assert.equal(localDateTimeInput(date), '2026-10-24T15:30')
  assert.equal(new Date(localDateTimeInput(date)).getTime(), date.getTime())
})

test('each task has its own request snapshot and stable retry keys even for the same assignee', () => {
  const first = row(), second = row(), input = { assigneeAssignmentId: 'a1', description: first.content, sourceSnapshot: { taskPolicy: { attachmentRequired: true } } }
  const a = newTaskDelivery(first, input), b = newTaskDelivery(second, input)
  input.description = '后续编辑'
  input.sourceSnapshot.taskPolicy.attachmentRequired = false
  assert.equal(a.input.description, first.content)
  assert.equal(a.input.sourceSnapshot.taskPolicy.attachmentRequired, true)
  assert.notEqual(a.createIdempotencyKey, b.createIdempotencyKey)
  assert.notEqual(a.dispatchIdempotencyKey, b.dispatchIdempotencyKey)
})

test('partial batch retry skips delivered tasks and resumes the failed task only', async () => {
  const a = newTaskDelivery(row(), { title: '任务甲' }), b = newTaskDelivery(row(), { title: '任务乙' })
  const requests = [], stages = []
  let fail = true
  const ports = {
    create: async (input, key) => { requests.push(['create', key, input.title]); return { id: input.title, row_version: 0 } },
    upload: async () => assert.fail('No references to upload'),
    dispatch: async (id, version, key) => { requests.push(['dispatch', id, key]); if (id === '任务乙' && fail) { fail = false; throw new Error('短暂断网') } },
  }
  await deliverTask(a, ports, stage => stages.push(stage))
  await assert.rejects(deliverTask(b, ports, () => {}), /短暂断网/)
  assert.equal(a.dispatched, true); assert.equal(b.dispatched, false)
  await deliverTask(a, ports, () => assert.fail('Delivered row must be skipped'))
  await deliverTask(b, ports, () => {})
  assert.equal(b.dispatched, true)
  assert.equal(requests.filter(r => r[0] === 'create').length, 2)
  assert.equal(requests.filter(r => r[0] === 'dispatch' && r[1] === '任务甲').length, 1)
  assert.deepEqual(requests.filter(r => r[0] === 'dispatch' && r[1] === '任务乙').map(r => r[2]), [b.dispatchIdempotencyKey, b.dispatchIdempotencyKey])
  assert.deepEqual(stages, ['creating', 'dispatching', 'dispatched'])
})

test('reference upload failure resumes only pending files before dispatch', async () => {
  const firstFile = { name: '现场一.jpg' }, secondFile = { name: '现场二.jpg' }
  const draft = newTaskDelivery(row({ referenceFiles: [{ id: 'f1', file: firstFile }, { id: 'f2', file: secondFile }] }), { title: '检查现场' })
  let createCount = 0, dispatchCount = 0, fail = true
  const uploads = []
  const ports = {
    create: async () => { createCount++; return { id: 'task-1', row_version: 3 } },
    upload: async (id, file) => { uploads.push(file.name); if (file === secondFile && fail) { fail = false; throw new Error('扫描暂不可用') } },
    dispatch: async (id, version) => { assert.equal(version, 3); dispatchCount++ },
  }
  await assert.rejects(deliverTask(draft, ports, () => {}), /扫描暂不可用/)
  assert.equal(dispatchCount, 0); assert.deepEqual(draft.pendingFiles.map(f => f.id), ['f2'])
  await deliverTask(draft, ports, () => {})
  assert.equal(createCount, 1); assert.equal(dispatchCount, 1)
  assert.deepEqual(uploads, ['现场一.jpg', '现场二.jpg', '现场二.jpg'])
  assert.deepEqual(draft.pendingFiles, [])
})

test('lost dispatch response reuses the same server command without creating another task', async () => {
  const draft = newTaskDelivery(row(), { title: '检查大厅' }), committedKeys = new Set()
  let first = true, createCount = 0
  const ports = {
    create: async () => { createCount++; return { id: 'task-1', row_version: 0 } }, upload: async () => {},
    dispatch: async (id, version, key) => { committedKeys.add(key); if (first) { first = false; throw new Error('响应丢失') } },
  }
  await assert.rejects(deliverTask(draft, ports, () => {}), /响应丢失/)
  await deliverTask(draft, ports, () => {})
  assert.equal(createCount, 1); assert.equal(committedKeys.size, 1); assert.equal(draft.dispatched, true)
})

test('definite validation rejection permits editing, but an uncertain create stays locked on later rejection', async () => {
  const rejected = () => Promise.reject(Object.assign(new Error('任职已停用'), { status: 400 }))
  const ports = { create: rejected, upload: async () => {}, dispatch: async () => {} }
  const definite = newTaskDelivery(row(), { title: '任务甲' })
  await assert.rejects(deliverTask(definite, ports, () => {}))
  assert.equal(definite.editable, true)
  const uncertain = newTaskDelivery(row(), { title: '任务乙' })
  await assert.rejects(deliverTask(uncertain, { ...ports, create: async () => { throw new Error('响应丢失') } }, () => {}))
  await assert.rejects(deliverTask(uncertain, ports, () => {}))
  assert.equal(uncertain.editable, false)
})
