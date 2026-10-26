import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  bankExplanationRequest, bankExplanationStatus, bankUnmatchedAmount, operationActionLabels,
  operationReceiptStatus, operationReviewRows, operationReviewStage, operationRows, revenueKindOptions,
} from '../src/features/finance/operationsModel.ts'

test('four-stage actions use fo_action snapshot.stage rather than nonexistent reviewerName/decision fields', () => {
  const labels = ['门店初审', '门店财务复核', '财务总监审核', 'CEO终审']
  for (const [index, label] of labels.entries()) {
    assert.equal(operationReviewStage({ action: 'APPROVE', snapshot: { stage: index + 1 } }), label)
    assert.equal(operationReviewStage({ action: 'RETURN', snapshot: { stage: index } }), label)
    assert.equal(operationReviewStage({ action: 'REJECT', snapshot: { stage: index } }), label)
  }
  const action = { id: 'action-1', action: 'APPROVE', actorId: 'actual-account-id', snapshot: { stage: 1 }, note: '已核对原件', createdAt: '2026-10-24T08:00:00Z', reviewerName: '不得使用假姓名', decision: 'REJECT', stage: 4 }
  assert.deepEqual(operationReviewRows([action]), [{ id: 'action-1', stageLabel: '门店初审', actorId: 'actual-account-id', actionLabel: '本节点通过', note: '已核对原件', createdAt: '2026-10-24T08:00:00Z' }])
  assert.equal(action.snapshot.stage, 1)
})

test('submit, self-review avoidance and cancellation do not fabricate a reviewer node', () => {
  assert.equal(operationReviewStage({ action: 'SUBMIT', snapshot: {} }), '申请提交')
  assert.equal(operationReviewStage({ action: 'SKIP_SELF', snapshot: {} }), '首段回避')
  assert.equal(operationReviewStage({ action: 'CANCEL', snapshot: {} }), '未批申请取消')
  assert.match(operationReviewStage({ action: 'APPROVE', snapshot: {} }), /未提供/)
  for (const stage of ['1', 1.5, -1, 9]) assert.match(operationReviewStage({ action: 'APPROVE', snapshot: { stage } }), /异常/)
})

test('receipt originals, external references, missing receipts and noncash actions stay distinct', () => {
  assert.equal(operationReceiptStatus('ARCHIVED'), '回单原件已上传归档')
  assert.equal(operationReceiptStatus('EXTERNAL_REFERENCE'), '仅有外部归档引用，原件未上传')
  assert.equal(operationReceiptStatus('MISSING'), '待补回单原件')
  assert.equal(operationReceiptStatus('NOT_APPLICABLE'), '非资金动作，无需付款回单')
  assert.match(operationReceiptStatus(undefined), /不能认定已归档/)
  assert.match(operationReceiptStatus('UNRECOGNIZED'), /未识别/)
})

test('revenue creation offers only supported economic kinds; refund remains an original-document action', () => {
  assert.deepEqual(revenueKindOptions.map(option => option.value), ['ROOM', 'OTHER', 'DEPOSIT', 'PREPAID'])
  assert.equal(revenueKindOptions.some(option => option.value === 'REFUND'), false)
  assert.equal(operationActionLabels.REFUND, '登记退款 / 冲回')
})

test('bank differences preserve exact cents above Number safe integer precision and never assume missing amounts are zero', () => {
  assert.equal(bankUnmatchedAmount({ amount: '8600.00', matchedAmount: '4000.00' }), '4600.00')
  assert.equal(bankUnmatchedAmount({ amount: '0.30', matchedAmount: '0.10' }), '0.20')
  assert.equal(bankUnmatchedAmount({ amount: '90071992547409.93', matchedAmount: '90071992547409.92' }), '0.01')
  assert.equal(bankUnmatchedAmount({ amount: '100', matchedAmount: '1.1' }), '98.90')
  assert.equal(bankUnmatchedAmount({ amount: '100.00', matchedAmount: '100.00' }), '0.00')
  assert.equal(bankUnmatchedAmount({ amount: '100.00', matchedAmount: '101.00' }), '-1.00')
  for (const row of [{ amount: '100.00' }, { amount: 100, matchedAmount: '0.00' }, { amount: '-1.00', matchedAmount: '0.00' }, { amount: '1.001', matchedAmount: '0.00' }, { amount: '100.00', matchedAmount: '' }]) assert.equal(bankUnmatchedAmount(row), null)
})

test('bank explanation NONE/VALID/STALE does not convert explanations into matches', () => {
  const row = { amount: '100.00', matchedAmount: '50.00' }
  assert.match(bankExplanationStatus({ ...row, explanationStatus: 'NONE' }), /尚未确认/)
  assert.match(bankExplanationStatus({ ...row, explanationStatus: 'VALID' }), /不代表已匹配/)
  assert.match(bankExplanationStatus({ ...row, explanationStatus: 'STALE' }), /重新核对/)
  assert.match(bankExplanationStatus({ ...row, matchedAmount: '100.00', explanationStatus: 'STALE' }), /已全额匹配/)
  assert.match(bankExplanationStatus({ ...row, matchedAmount: '101.00', explanationStatus: 'VALID' }), /异常/)
  assert.match(bankExplanationStatus({ amount: '100.00', explanationStatus: 'NONE' }), /金额未读全/)
})

test('explanation requests preserve existing version zero and later versions, never default stale state to new', () => {
  const row = { amount: '100.00', matchedAmount: '50.00' }
  assert.deepEqual(bankExplanationRequest({ ...row, explanationStatus: 'NONE', explanationVersion: null }, '跨日到账，已核对流水', 'request-1'), { requestId: 'request-1', expectedVersion: -1, reason: '跨日到账，已核对流水' })
  for (const explanationStatus of ['VALID', 'STALE']) {
    for (const explanationVersion of [0, 4]) assert.equal(bankExplanationRequest({ ...row, explanationStatus, explanationVersion }, '主动复核最新匹配状态', 'request-2').expectedVersion, explanationVersion)
    for (const explanationVersion of [undefined, null, -1, '0', 0.5]) assert.throws(() => bankExplanationRequest({ ...row, explanationStatus, explanationVersion }, '原因', 'request-3'), /不能按新建覆盖/)
  }
  assert.throws(() => bankExplanationRequest({ ...row, explanationStatus: 'UNKNOWN' }, '原因', 'request-4'), /状态及版本/)
  assert.equal(row.matchedAmount, '50.00')
})

test('fully matched, anomalous and incomplete amounts cannot submit explanations; reasons are explicit and bounded', () => {
  const row = { amount: '100.00', matchedAmount: '50.00', explanationStatus: 'NONE' }
  for (const reason of ['', '   ', '字'.repeat(2001)]) assert.throws(() => bankExplanationRequest(row, reason, 'request'), /1至2000字/)
  for (const matchedAmount of ['100.00', '101.00', undefined]) assert.throws(() => bankExplanationRequest({ ...row, matchedAmount }, '原因', 'request'), /正未达金额/)
  assert.equal(bankExplanationRequest(row, '字'.repeat(2000), 'request').reason.length, 2000)
})

test('only real object rows become table rows, never completeness strings or nulls', () => {
  assert.deepEqual(operationRows(['真实说明', null, [], { id: 'a' }]), [{ id: 'a' }])
  assert.deepEqual(operationRows({ items: [] }), [])
})

test('rendered page consumes tested helpers and calls budget execution honestly', () => {
  const page = readFileSync(new URL('../src/features/finance/FinanceOperations.tsx', import.meta.url), 'utf8')
  assert.match(page, /options: revenueKindOptions/)
  assert.match(page, /rows=\{operationReviewRows\(data\.reviews\)\}/)
  assert.match(page, /bankExplanationRequest\(activeTransaction, values\.reason, requestId\)/)
  assert.ok(page.includes("['budgets', '预算执行']"))
  assert.ok(!page.includes('预算与资金计划'))
  assert.ok(!page.includes("key: 'reviewerName'"))
})
