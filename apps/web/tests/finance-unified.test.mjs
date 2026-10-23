import test from 'node:test'
import assert from 'node:assert/strict'
import { claimLineAmount } from '../src/features/finance/model.ts'
import { exactDecimalTotal, financeCsv, financeTodoType, todoTarget, voucherBalanced } from '../src/features/finance/unifiedModel.ts'
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
test('financial notifications retain permission checks and do not send business document IDs to reimbursement routes', () => {
  assert.equal(resolveNotificationNavigation({ sourceType: 'FINANCE', sourceId: 'c1' }, []), undefined)
  assert.deepEqual(resolveNotificationNavigation({ sourceType: 'FINANCE', sourceId: 'c1', type: 'FINANCE_RETURNED' }, ['finance.submit']).params, { tab: 'mine', id: 'c1' })
  assert.deepEqual(resolveNotificationNavigation({ sourceType: 'FINANCE_OPERATION', sourceId: 'op1' }, ['finance.review']).params, { tab: 'operations', screen: 'documents', documentId: 'op1' })
})
