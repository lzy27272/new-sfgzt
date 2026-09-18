import assert from 'node:assert/strict'
import test from 'node:test'
import { invitationExpiryLabel, invitationValidity } from '../src/features/wecom/invitationValidity.ts'

const now = Date.parse('2026-09-18T10:00:00+08:00')
const expiresIn = (minutes) => new Date(now + minutes * 60_000).toISOString()

test('shared invitations remain valid across days and show the actual remaining duration', () => {
  assert.deepEqual(invitationValidity(expiresIn(7 * 1440), 'onboarding', now), {
    expired: false, expiringSoon: false, remainingText: '剩余7天',
  })
  assert.equal(invitationValidity(expiresIn(1440 + 61), 'onboarding', now).remainingText, '剩余1天1小时1分钟')
})

test('shared expiry warning begins in the last day; personal warning remains 30 minutes', () => {
  assert.equal(invitationValidity(expiresIn(1441), 'onboarding', now).expiringSoon, false)
  assert.equal(invitationValidity(expiresIn(1440), 'onboarding', now).expiringSoon, true)
  assert.equal(invitationValidity(expiresIn(31), 'binding', now).expiringSoon, false)
  assert.equal(invitationValidity(expiresIn(30), 'binding', now).expiringSoon, true)
})

test('expired or invalid dates disable invitations exactly at the boundary', () => {
  for (const date of [expiresIn(0), expiresIn(-1), '', 'invalid']) {
    assert.equal(invitationValidity(date, 'onboarding', now).expired, true)
  }
  assert.equal(invitationValidity(expiresIn(0.5), 'onboarding', now).remainingText, '剩余不足1分钟')
  assert.equal(invitationValidity(expiresIn(0.5), 'onboarding', now).expired, false)
})

test('returning after expiry recalculates instead of extending the invitation', () => {
  const expiry = expiresIn(5)
  assert.equal(invitationValidity(expiry, 'onboarding', now).expired, false)
  assert.equal(invitationValidity(expiry, 'onboarding', now + 10 * 60_000).expired, true)
})

test('expiry display always uses Beijing time and handles invalid dates', () => {
  assert.equal(invitationExpiryLabel('2026-09-18T16:40:00Z'), '2026/09/19 00:40')
  assert.equal(invitationExpiryLabel('invalid'), '有效期无效')
})
