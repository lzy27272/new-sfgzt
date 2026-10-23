import test from 'node:test'
import assert from 'node:assert/strict'
import {
  accountingChecks, accountingExportPath, accountingRecord, accountingReportRows, accountingReportTotals,
  accountingRows, accountingSourceTarget, shortVoucherId, validAccountingLine,
} from '../src/features/finance/accountingModel.ts'

test('accounting rows follow real array/list/lines contracts without silently flattening nested reports', () => {
  assert.deepEqual(accountingRows({ items: [{ id: 'a' }], total: 1 }), [{ id: 'a' }])
  assert.deepEqual(accountingRows([null, 'wrong', [], { id: 'a' }]), [{ id: 'a' }])
  assert.deepEqual(accountingRows({ categories: { OPERATING: '100.00' } }), [])
  assert.deepEqual(accountingRecord(null), {})
})

test('period and report completeness render code/passed/message, not nonexistent name/status/count', () => {
  const input = { checks: [{ code: 'OPENING_BALANCE', passed: false, message: '尚无期初' }, { code: 'TRIAL_BALANCE', passed: true, message: '已核对' }] }
  assert.deepEqual(accountingChecks(input), [
    { code: 'OPENING_BALANCE', passed: false, message: '尚无期初', checkName: '期初余额', result: '未通过' },
    { code: 'TRIAL_BALANCE', passed: true, message: '已核对', checkName: '借贷试算', result: '已通过' },
  ])
  assert.deepEqual(accountingChecks({ checks: [{ code: 'FUTURE_CHECK', passed: 'true', message: '未知格式不可假定通过' }] })[0].result, '未通过')
})

test('all statement summaries preserve precise server amounts and cash-flow category maps', () => {
  const report = {
    incomeStatement: { lines: [{ reportLine: '营业收入', amount: '90071992547409.93' }], revenue: '90071992547409.93', expense: '0.10', netProfit: '90071992547409.83' },
    balanceSheet: { lines: [{ accountCode: '1001', amount: '100.00' }], assets: '100.00', liabilities: '20.00', equity: '30.00', unclosedProfit: '50.00', difference: '0.00' },
    cashFlowStatement: { categories: { OPERATING: '100.00', INVESTING: '-60.00', FINANCING: '0.00', TRANSFER: '0.00', UNCLASSIFIED: '0.00' }, beginningCash: '20.00', endingCash: '60.00', netMovement: '40.00', difference: '0.00' },
  }
  assert.deepEqual(accountingReportRows(report, 'incomeStatement'), report.incomeStatement.lines)
  assert.deepEqual(accountingReportTotals(report, 'incomeStatement'), { revenue: '90071992547409.93', expense: '0.10', netProfit: '90071992547409.83' })
  assert.deepEqual(accountingReportTotals(report, 'balanceSheet'), { assets: '100.00', liabilities: '20.00', equity: '30.00', unclosedProfit: '50.00', difference: '0.00' })
  assert.equal(accountingReportRows(report, 'cashFlowStatement').length, 5)
  assert.deepEqual(accountingReportRows(report, 'cashFlowStatement')[1], { category: 'INVESTING', categoryName: '投资活动', amount: '-60.00' })
  assert.deepEqual(accountingReportTotals(report, 'cashFlowStatement'), { beginningCash: '20.00', endingCash: '60.00', netMovement: '40.00', difference: '0.00' })
})

test('XLSX/PDF export carries selected report type and the exact loaded version', () => {
  const url = new URL(accountingExportPath('book / 1', '2026-10', 'xlsx', 'cashFlowStatement', 'sha+version&'), 'https://local.test')
  assert.equal(url.pathname, '/accounting/reports/export')
  assert.equal(url.searchParams.get('bookId'), 'book / 1')
  assert.equal(url.searchParams.get('reportType'), 'CASH_FLOW')
  assert.equal(url.searchParams.get('format'), 'xlsx')
  assert.equal(url.searchParams.get('expectedReportVersion'), 'sha+version&')
  assert.equal(new URL(accountingExportPath('b', '2026-10', 'pdf', 'ALL', 'v1'), 'https://local.test').searchParams.get('reportType'), 'ALL')
  assert.throws(() => accountingExportPath('b', '2026-10', 'xlsx', 'trialBalance', undefined), /版本/)
  assert.throws(() => accountingExportPath('b', 'bad', 'pdf', 'trialBalance', 'v1'), /版本/)
})

const id = '12345678-1234-1234-1234-123456789012'
test('voucher references use real IDs and never invent sequential voucher numbers', () => {
  assert.equal(shortVoucherId('a762304b-1234-1234-1234-123456789012'), 'A762304B')
  assert.equal(shortVoucherId(undefined), '—')
})

test('a voucher line needs a real account and exactly one positive debit or credit side', () => {
  assert.equal(validAccountingLine({ accountCode: '1001', debit: '0.10', credit: '0.00' }), true)
  assert.equal(validAccountingLine({ accountCode: '2201', debit: '0.00', credit: '0.30' }), true)
  assert.equal(validAccountingLine({ accountCode: '', debit: '1.00', credit: '0.00' }), false)
  assert.equal(validAccountingLine({ accountCode: '1001', debit: '0.30', credit: '0.10' }), false)
  assert.equal(validAccountingLine({ accountCode: '1001', debit: '0.00', credit: '0.00' }), false)
  assert.equal(validAccountingLine({ accountCode: '1001', debit: '-1.00', credit: '0.00' }), false)
  assert.equal(validAccountingLine({ accountCode: '1001', debit: '0.001', credit: '0.00' }), false)
})

test('source drilldown is based on saved typed relationships and actual target permissions', () => {
  assert.deepEqual(accountingSourceTarget({ sourceType: 'REIMBURSEMENT_EXPENSE', metadata: { claimId: id } }, ['finance.submit']), { tab: 'mine', id })
  assert.deepEqual(accountingSourceTarget({ sourceType: 'REIMBURSEMENT_PAYMENT', metadata: { claimId: id } }, ['finance.read']), { tab: 'all', id })
  assert.deepEqual(accountingSourceTarget({ sourceType: 'REIMBURSEMENT_EXPENSE', metadata: { claimId: id } }, ['finance.review']), { tab: 'review', id })
  assert.deepEqual(accountingSourceTarget({ sourceType: 'FO_ASSET', metadata: { documentId: id, documentType: 'ASSET' } }, ['finance.operations.read']), { tab: 'operations', screen: 'documents', documentId: id, type: 'ASSET' })
  assert.deepEqual(accountingSourceTarget({ sourceType: 'FA_DEPRECIATION', metadata: { sourceType: 'FO_ASSET', sourceId: id, planId: id } }, ['finance.read']), { tab: 'operations', screen: 'documents', documentId: id, type: 'ASSET' })
  assert.deepEqual(accountingSourceTarget({ sourceType: 'FA_AMORTIZATION', metadata: { sourceType: 'FO_PREPAYMENT', sourceId: id, planId: id } }, ['finance.read']), { tab: 'operations', screen: 'documents', documentId: id, type: 'PREPAYMENT' })
  assert.equal(accountingSourceTarget({ sourceType: 'FA_DEPRECIATION', metadata: { sourceType: 'FO_PREPAYMENT', sourceId: id, planId: id } }, ['finance.read']), undefined)
  assert.equal(accountingSourceTarget({ sourceType: 'FO_ASSET', metadata: { documentId: id, documentType: 'ASSET' } }, ['finance.accounting.read']), undefined)
  assert.equal(accountingSourceTarget({ sourceType: 'OTHER', metadata: { claimId: id } }, ['*']), undefined)
  assert.equal(accountingSourceTarget({ sourceType: 'REIMBURSEMENT_EXPENSE', sourceId: id }, ['*']), undefined)
  assert.equal(accountingSourceTarget({ sourceType: 'FO_ASSET', metadata: { documentId: '//evil.test', documentType: 'ASSET' } }, ['*']), undefined)
  assert.equal(accountingSourceTarget({ sourceType: 'FO_ASSET', metadata: { documentId: id, documentType: 'UNKNOWN' } }, ['*']), undefined)
})
