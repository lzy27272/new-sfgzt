import assert from 'node:assert/strict'
import test from 'node:test'

import { createLatestRequestGuard, createResourceRequestGuard } from '../src/useResource.ts'

test('a response from a previous role cannot overwrite the newest role resource', () => {
  const guard = createLatestRequestGuard()
  const oldRoleRequest = guard.next()
  const newRoleRequest = guard.next()

  assert.equal(guard.isCurrent(oldRoleRequest), false)
  assert.equal(guard.isCurrent(newRoleRequest), true)
})

test('unmount invalidates an in-flight resource request', () => {
  const guard = createLatestRequestGuard()
  const inFlightRequest = guard.next()

  guard.invalidate()

  assert.equal(guard.isCurrent(inFlightRequest), false)
})

test('refresh waits for the current work request instead of invalidating a slow response', () => {
  const guard = createResourceRequestGuard(true)
  const slow = guard.start()
  assert.equal(guard.start(true), undefined)
  assert.equal(guard.isCurrent(slow.generation), true)
  assert.equal(slow.signal.aborted, false)
  guard.finish(slow.generation)
  assert.ok(guard.start(true), 'a completed request allows the next refresh')
})

test('a new filter cancels the old fetch and its completion cannot clear the new request', () => {
  const guard = createResourceRequestGuard(true)
  const oldFilter = guard.start()
  const newFilter = guard.start()
  assert.equal(oldFilter.signal.aborted, true)
  assert.equal(guard.isCurrent(oldFilter.generation), false)
  guard.finish(oldFilter.generation)
  assert.equal(guard.start(true), undefined)
  assert.equal(newFilter.signal.aborted, false)
  guard.invalidate()
  assert.equal(newFilter.signal.aborted, true)
  assert.equal(guard.isCurrent(newFilter.generation), false)
})

test('existing resources retain their non-cancelling request behavior by default', () => {
  const guard = createResourceRequestGuard()
  const first = guard.start()
  const refresh = guard.start(true)
  assert.ok(refresh)
  assert.equal(first.signal.aborted, false)
  assert.equal(guard.isCurrent(first.generation), false)
  assert.equal(guard.isCurrent(refresh.generation), true)
})
