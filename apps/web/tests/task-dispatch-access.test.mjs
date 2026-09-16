import assert from 'node:assert/strict'
import test from 'node:test'

import {
  loadOptionalTaskContext,
  resolveTaskDispatchMode,
} from '../src/features/tasks/taskDispatchAccess.ts'

test('manual dispatch remains available when optional template and standard permissions are absent', async () => {
  let calls = 0
  const loader = async () => { calls += 1; return ['unexpected'] }

  assert.deepEqual(await loadOptionalTaskContext(['task.create', 'task.dispatch'], 'template.read', loader), [])
  assert.deepEqual(await loadOptionalTaskContext(['task.create', 'task.dispatch'], 'standard.read', loader), [])
  assert.equal(calls, 0)
  assert.equal(resolveTaskDispatchMode('GROUP_VICE_PRESIDENT', true, ['task.create', 'task.dispatch']), 'MANAGEMENT')
})

test('optional task context is loaded only with its explicit permission or wildcard', async () => {
  let calls = 0
  const loader = async () => { calls += 1; return ['available'] }

  assert.deepEqual(await loadOptionalTaskContext(['template.read'], 'template.read', loader), ['available'])
  assert.deepEqual(await loadOptionalTaskContext(['*'], 'template.read', loader), ['available'])
  assert.equal(calls, 2)
})

test('chairman dispatch never falls through to the generic task workflow', () => {
  assert.equal(resolveTaskDispatchMode('GROUP_CHAIRMAN', true, ['executive-task.assign']), 'EXECUTIVE')
  assert.equal(resolveTaskDispatchMode('GROUP_CHAIRMAN', false, ['executive-task.assign']), undefined)
  assert.equal(resolveTaskDispatchMode('GROUP_CHAIRMAN', true, ['task.create', 'task.dispatch']), undefined)
  assert.equal(resolveTaskDispatchMode('GROUP_VICE_PRESIDENT', true, ['executive-task.assign']), undefined)
})
