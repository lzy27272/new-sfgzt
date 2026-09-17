import assert from 'node:assert/strict'
import test from 'node:test'

import { createLatestRequestGuard } from '../src/useResource.ts'

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
