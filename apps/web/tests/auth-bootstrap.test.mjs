import assert from 'node:assert/strict'
import test from 'node:test'

import {
  bootstrapAssignmentId,
  bootstrapAssignments,
  canLoadSecondaryResources,
  canRenderAuthenticatedShell,
  canUseStoredAcceptanceRole,
  shouldExpireAuthenticatedSession,
} from '../src/app/authBootstrap.ts'

test('server-resolved sessions never bootstrap with acceptance-role assignments', () => {
  const acceptanceAssignment = { id: 'acceptance-only' }

  assert.equal(bootstrapAssignmentId('bearer', acceptanceAssignment.id), '')
  assert.deepEqual(bootstrapAssignments('bearer', [acceptanceAssignment]), [])
  assert.equal(bootstrapAssignmentId('server', acceptanceAssignment.id), '')
  assert.deepEqual(bootstrapAssignments('server', [acceptanceAssignment]), [])
})

test('development header mode preserves acceptance-role bootstrap context', () => {
  const acceptanceAssignment = { id: 'acceptance-only' }

  assert.equal(bootstrapAssignmentId('dev-header', acceptanceAssignment.id), acceptanceAssignment.id)
  assert.deepEqual(bootstrapAssignments('dev-header', [acceptanceAssignment]), [acceptanceAssignment])
})

test('server-resolved sessions defer secondary requests until identity succeeds', () => {
  assert.equal(canLoadSecondaryResources('bearer', true), false)
  assert.equal(canLoadSecondaryResources('bearer', false, '身份读取失败'), false)
  assert.equal(canLoadSecondaryResources('bearer', false), true)
  assert.equal(canLoadSecondaryResources('server', true), false)
  assert.equal(canLoadSecondaryResources('dev-header', true), true)
})

test('all real session modes expire on unauthorized responses', () => {
  assert.equal(shouldExpireAuthenticatedSession('bearer', 401), true)
  assert.equal(shouldExpireAuthenticatedSession('server', 401), true)
  assert.equal(shouldExpireAuthenticatedSession('bearer', 403), false)
  assert.equal(shouldExpireAuthenticatedSession('dev-header', 401), false)
})

test('real sessions never render a cached workbench before identity resolves', () => {
  assert.equal(canRenderAuthenticatedShell('bearer', true), false)
  assert.equal(canRenderAuthenticatedShell('server', true), false)
  assert.equal(canRenderAuthenticatedShell('server', false, '身份读取失败'), false)
  assert.equal(canRenderAuthenticatedShell('server', false), true)
  assert.equal(canRenderAuthenticatedShell('dev-header', true), true)
})

test('only local acceptance mode may restore a cached role context', () => {
  assert.equal(canUseStoredAcceptanceRole('dev-header'), true)
  assert.equal(canUseStoredAcceptanceRole('bearer'), false)
  assert.equal(canUseStoredAcceptanceRole('server'), false)
})
