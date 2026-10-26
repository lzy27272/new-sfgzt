import test from 'node:test'
import assert from 'node:assert/strict'
import { approvedLoanRows, loanClearCandidateRows, loanClearRequest, loanExitStatus, controlScreens } from '../src/features/finance/controlModel.ts'
const loan = { id: 'actual-loan', type: 'LOAN', status: 'APPROVED', version: 3, businessDate: '2026-10-01', amount: '1000', outstandingAmount: '80.10' }
const claim = { id: 'actual-claim', version: 6, approvedAmount: '90', unsettledAmount: '50.05' }
const values = { amount: '25.01', occurredOn: '2026-10-24', note: '真实费用原件与借款用途逐项核对' }
test('loan screen and selectors use actual approved sources and valid unsettled server snapshots', () => {
  assert.match(controlScreens.loans, /借款核销/)
  assert.deepEqual(approvedLoanRows([loan, { ...loan, id: 'draft', status: 'DRAFT' }, { ...loan, id: 'payable', type: 'PAYABLE' }]).map(row => row.id), ['actual-loan'])
  assert.deepEqual(loanClearCandidateRows([claim, { ...claim, id: 'paid', unsettledAmount: '0.00' }, { ...claim, id: 'missing', unsettledAmount: undefined }, { ...claim, id: 'bad-version', version: '6' }]).map(row => row.id), ['actual-claim'])
})
test('partial loan offset sends exact cents and original versions, never a second payment instruction', () => {
  const result = loanClearRequest(loan, claim, values, 'same-request', '2026-10-24')
  assert.deepEqual(result, { requestId: 'same-request', claimId: 'actual-claim', loanVersion: 3, claimVersion: 6, amount: '25.01', occurredOn: '2026-10-24', note: values.note })
  assert.ok(!('bankAccountId' in result)); assert.ok(!('action' in result))
  assert.equal(loanClearRequest({ ...loan, outstandingAmount: '99999999999999.99' }, { ...claim, unsettledAmount: '99999999999999.99' }, { ...values, amount: '99999999999999.98' }, 'exact', '2026-10-24').amount, '99999999999999.98')
})
test('loan offsets fail closed on missing or stale snapshots and either actual balance cap', () => {
  for (const changed of [{ ...loan, status: 'DRAFT' }, { ...loan, type: 'PAYABLE' }, { ...loan, version: undefined }, { ...loan, outstandingAmount: undefined }, { ...loan, outstandingAmount: '25.00' }]) assert.throws(() => loanClearRequest(changed, claim, values, 'r', '2026-10-24'))
  assert.throws(() => loanClearRequest(loan, { ...claim, unsettledAmount: '25' }, values, 'r', '2026-10-24'), /不能超过/)
  for (const amount of ['0', '-1', '1e2', '1.001', '100000000000000', 'NaN']) assert.throws(() => loanClearRequest(loan, claim, { ...values, amount }, 'r', '2026-10-24'))
})
test('loan offset date and evidence must be genuine, not future or impossible dates', () => {
  for (const occurredOn of ['2026-09-30', '2026-10-25', '2026-02-30', '2026-13-01', '2026-10-24T12:00']) assert.throws(() => loanClearRequest(loan, claim, { ...values, occurredOn }, 'r', '2026-10-24'))
  assert.throws(() => loanClearRequest(loan, claim, { ...values, note: '  ' }, 'r', '2026-10-24'))
  assert.throws(() => loanClearRequest(loan, claim, { ...values, note: 'x'.repeat(2001) }, 'r', '2026-10-24'))
})
test('exit review never labels missing information or positive debt as clear, and no check authorizes departure', () => {
  assert.match(loanExitStatus({}), /不完整/)
  assert.match(loanExitStatus({ items: [{ outstanding: '0.00' }], hasOutstanding: false }), /不是离职审批通过/)
  assert.match(loanExitStatus({ items: [{ outstanding: '0.01' }], hasOutstanding: false }), /仍有未清/)
  assert.match(loanExitStatus({ items: [{ outstanding: null }], hasOutstanding: false }), /不完整/)
  assert.match(loanExitStatus({ items: [], hasOutstanding: false }), /其他组织/)
  assert.match(loanExitStatus({ items: [], hasOutstanding: true }), /仍有未清/)
})
