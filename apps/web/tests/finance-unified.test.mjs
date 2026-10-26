import test from 'node:test'
import assert from 'node:assert/strict'
import { claimLineAmount } from '../src/features/finance/model.ts'
import { exactDecimalTotal, financeCsv, financeTodoType, financeTodoState, todoTarget, voucherBalanced } from '../src/features/finance/unifiedModel.ts'
import { resolveNotificationNavigation } from '../src/app/notificationNavigation.ts'

test('line quantity times price uses exact four-place decimals with explicit HALF_UP cents', () => {
  assert.equal(claimLineAmount('3', '0.1'), '0.30')
  assert.equal(claimLineAmount('0.5', '0.01'), '0.01')
  assert.equal(claimLineAmount('1.2345', '12345678901234.1234'), '15240740603573.53')
  assert.equal(claimLineAmount('1.00001', '2'), null)
  assert.equal(claimLineAmount('-1', '2'), null)
})
test('voucher balance never relies on floating point and zero or malformed vouchers are not balanced', () => {
  assert.equal(exactDecimalTotal(['0.10', '0.20']), '0.30')
  assert.equal(exactDecimalTotal(['100.00', '-100.01']), '-0.01')
  assert.equal(exactDecimalTotal(['0.001']), null)
  assert.equal(voucherBalanced([{ debit: '0.10', credit: '0.00' }, { debit: '0.20', credit: '0.30' }]), true)
  assert.equal(voucherBalanced([{ debit: '0.00', credit: '0.00' }, { debit: '0.00', credit: '0.00' }]), false)
  assert.equal(voucherBalanced([{ debit: '0.30', credit: '0.10' }, { debit: '0.00', credit: '0.19' }]), false)
})
test('CSV export escapes quotes, newlines and spreadsheet formulas without inventing workbook extensions', () => {
  const csv = financeCsv([{ note: '=HYPERLINK("bad")', amount: '0.30' }], [{ key: 'note', label: '说明' }, { key: 'amount', label: '金额' }])
  assert.match(csv, /'=HYPERLINK/)
  assert.match(csv, /"0.30"/)
  assert.ok(csv.startsWith('\ufeff'))
})
test('financial todo follows protected server target including business documents and returned drafts', () => {
  assert.deepEqual(todoTarget({ id: 'c1', kind: 'returned' }), { tab: 'mine', id: 'c1' })
  assert.deepEqual(todoTarget({ id: 'c1', kind: 'receipt' }), { tab: 'all', id: 'c1' })
  const target = { tab: 'operations', screen: 'documents', documentId: 'op1', type: 'LOAN' }
  assert.deepEqual(todoTarget({ id: 'op1', target }), target)
})
test('financial todo labels distinguish reimbursements and real business document types', () => {
  assert.equal(financeTodoType({ domain: 'CLAIM' }), '报销')
  assert.equal(financeTodoType({ domain: 'OPERATION', target: { type: 'PAYABLE' } }), '采购与应付')
  assert.equal(financeTodoType({ domain: 'OPERATION', target: { type: 'UNKNOWN' } }), '财务业务单据')
})
test('current work actions are not mislabeled as accounting open periods', () => {
  assert.equal(financeTodoState({ domain: 'INSIGHT_ACTION', kind: 'work', status: 'OPEN' }), '待本人跟进')
  assert.equal(financeTodoState({ domain: 'INSIGHT_ACTION', kind: 'review', status: 'SUBMITTED' }), '待独立核实')
  assert.equal(financeTodoState({ domain: 'CASH_INSTRUCTION', kind: 'work', status: 'APPROVED' }), '旧指令待作废重建')
  assert.equal(financeTodoState({ domain: 'ACCRUAL', kind: 'work', status: 'PENDING' }), '计提异常待处理')
  assert.equal(financeTodoType({ domain: 'AR_RECOVERY' }), '应收损失恢复')
})
test('financial notifications retain permission checks and do not send business document IDs to reimbursement routes', () => {
  assert.equal(resolveNotificationNavigation({ sourceType: 'FINANCE', sourceId: 'c1' }, []), undefined)
  assert.deepEqual(resolveNotificationNavigation({ sourceType: 'FINANCE', sourceId: 'c1', type: 'FINANCE_RETURNED' }, ['finance.submit']).params, { tab: 'mine', id: 'c1' })
  assert.deepEqual(resolveNotificationNavigation({ sourceType: 'FINANCE_OPERATION', sourceId: 'op1' }, ['finance.review']).params, { tab: 'operations', screen: 'documents', documentId: 'op1' })
})
test('V2 notifications route to actual modules and sensitive grants remain independent', () => {
  const cases = [
    ['FINANCE_CASH_STALE', { tab: 'control', screen: 'cash', instructionId: 'source' }],
    ['FINANCE_AR_RECOVERY', { tab: 'business-exceptions', section: 'recoveries', exceptionId: 'source' }],
    ['FINANCE_INSIGHT_ACTION', { tab: 'insight-actions', actionId: 'source' }],
    ['FINANCE_ACCRUAL', { tab: 'accrual', screen: 'plans', planId: 'source' }],
  ]
  for (const [sourceType, target] of cases) {
    assert.equal(resolveNotificationNavigation({ sourceType, sourceId: 'source' }, []), undefined)
    assert.deepEqual(resolveNotificationNavigation({ sourceType, sourceId: 'source' }, ['finance.review'])?.params, target)
  }
  assert.equal(resolveNotificationNavigation({ sourceType: 'FINANCE_PAYROLL', sourceId: 'source' }, ['finance.review']), undefined)
  assert.equal(resolveNotificationNavigation({ sourceType: 'FINANCE_TAX_CALENDAR', sourceId: 'source' }, ['finance.review']), undefined)
  assert.equal(resolveNotificationNavigation({ sourceType: 'FINANCE_ARCHIVE_REQUEST', sourceId: 'source' }, ['finance.read']), undefined)
  assert.deepEqual(resolveNotificationNavigation({ sourceType: 'FINANCE_ARCHIVE_REQUEST', sourceId: 'source' }, ['finance.archive.grant'])?.params, { tab: 'overview' })
  assert.equal(resolveNotificationNavigation({ sourceType: 'FINANCE_UNKNOWN', sourceId: 'source' }, ['*']), undefined)
})
