import assert from 'node:assert/strict'
import { test } from 'node:test'
import { costMoney, closeBlockReason } from '../src/features/storeResources/costs.ts'

test('currency preserves four decimal precision and large values without floating point', () => {
  assert.equal(costMoney('9007199254740993.0001'), '¥9,007,199,254,740,993.0001')
  assert.equal(costMoney('-27.5000'), '-¥27.5000')
  assert.equal(costMoney('0'), '¥0.00')
  for (const value of ['NaN', '1e9', '1.12345', '<script>', '']) assert.equal(costMoney(value), '金额暂不可用')
})
test('close eligibility is fail closed and distinguishes permissions, pending usage and period', () => {
  const ready = { closed: false, canClosePeriod: true, pendingUsageCount: 0 }
  assert.equal(closeBlockReason(ready, true), undefined)
  assert.match(closeBlockReason(ready, false), /权限/)
  assert.match(closeBlockReason({ ...ready, pendingUsageCount: 2 }, true), /2 条待审核/)
  assert.match(closeBlockReason({ ...ready, canClosePeriod: false }, true), /本月尚未结束/)
  assert.match(closeBlockReason({ ...ready, closed: true }, true), /本月已关账/)
})
