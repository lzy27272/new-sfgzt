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

const parentId = '23456789-2345-2345-2345-234567890123'
const hotelId = '34567890-3456-3456-3456-345678901234'
const typedSource = (sourceType, businessTable, more = {}) => ({ sourceType, sourceId: id, hotelId, metadata: { businessTable, businessId: id, ...more } })
test('new typed procurement, recurring and cash correction sources open their actual parent resources', () => {
  assert.deepEqual(accountingSourceTarget(typedSource('FS_PROCUREMENT', 'fs_recognition', { receiptId: parentId }), ['finance.accounting.read']), { tab: 'supply', screen: 'procurement', hotelId, receiptId: parentId })
  assert.deepEqual(accountingSourceTarget(typedSource('FAR_ACCRUAL', 'far_run', { planId: parentId }), ['finance.accounting.read']), { tab: 'accrual', screen: 'plans', planId: parentId })
  assert.deepEqual(accountingSourceTarget(typedSource('FX_CASH_CORRECTION', 'fx_cash_correction'), ['finance.read']), { tab: 'cash-errors', correctionId: id })
  assert.equal(accountingSourceTarget(typedSource('FX_CASH_CORRECTION', 'fx_cash_correction'), ['finance.accounting.read']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FS_PROCUREMENT', 'fs_recognition', { receiptId: parentId }), ['finance.submit']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FAR_ACCRUAL', 'far_run', { planId: parentId }), ['finance.pay']), undefined)
})
test('receivable losses and recoveries have separate immutable typed detail targets', () => {
  assert.deepEqual(accountingSourceTarget(typedSource('FB_RECEIVABLE_ADJUSTMENT', 'fb_receivable_adjustment'), ['finance.review']), { tab: 'business-exceptions', section: 'adjustments', exceptionId: id })
  assert.deepEqual(accountingSourceTarget(typedSource('FB_RECEIVABLE_RECOVERY', 'fb_receivable_recovery'), ['finance.read']), { tab: 'business-exceptions', section: 'recoveries', exceptionId: id })
  assert.equal(accountingSourceTarget(typedSource('FB_RECEIVABLE_ADJUSTMENT', 'fb_receivable_recovery'), ['*']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FB_RECEIVABLE_RECOVERY', 'fb_receivable_adjustment'), ['*']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FB_RECEIVABLE_RECOVERY', 'fb_receivable_recovery'), ['finance.pay']), undefined)
})
test('revenue entries only open a saved batch and payroll/intercompany keep their true parent identity', () => {
  assert.deepEqual(accountingSourceTarget(typedSource('FB_REVENUE_AR', 'fb_revenue_entry', { batchId: parentId }), ['finance.read']), { tab: 'business', screen: 'revenue', section: 'imports', businessId: parentId })
  assert.equal(accountingSourceTarget(typedSource('FB_REVENUE_AR', 'fb_revenue_entry', { entryId: id }), ['*']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FB_REVENUE_AR', 'fb_revenue_entry', { batchId: parentId }), ['finance.pay']), undefined)
  assert.deepEqual(accountingSourceTarget(typedSource('FB_PAYROLL', 'fb_payroll_batch', { payrollBatchId: id }), ['finance.read', 'finance.payroll']), { tab: 'business', screen: 'payroll', section: 'batches', businessId: id })
  assert.deepEqual(accountingSourceTarget(typedSource('FB_PAYROLL', 'fb_payroll_batch'), ['finance.pay', 'finance.payroll']), { tab: 'business', screen: 'payroll', section: 'batches', businessId: id })
  assert.equal(accountingSourceTarget(typedSource('FB_PAYROLL', 'fb_payroll_batch', { payrollBatchId: parentId }), ['*']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FB_PAYROLL', 'fb_payroll_batch', { payrollBatchId: '' }), ['*']), undefined)
  assert.deepEqual(accountingSourceTarget(typedSource('FB_INTERCOMPANY_CASH', 'fb_intercompany', { transactionId: id }), ['finance.read']), { tab: 'business', screen: 'intercompany', section: 'transactions', businessId: id })
  assert.equal(accountingSourceTarget(typedSource('FB_INTERCOMPANY_CASH', 'fb_intercompany', { transactionId: parentId }), ['*']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FB_INTERCOMPANY_CASH', 'fb_intercompany', { transactionId: null }), ['*']), undefined)
})
test('sensitive payroll and tax sources never gain links through generic read or correction wrappers', () => {
  const payroll = typedSource('FB_PAYROLL', 'fb_payroll_batch')
  assert.equal(accountingSourceTarget(payroll, ['finance.read']), undefined)
  assert.equal(accountingSourceTarget(payroll, ['finance.payroll']), undefined, 'payroll alone does not expose a management tab')
  assert.equal(accountingSourceTarget({ sourceType: 'FO_PAYROLL', metadata: { documentId: id, documentType: 'PAYROLL' } }, ['finance.read']), undefined)
  assert.deepEqual(accountingSourceTarget({ sourceType: 'FO_PAYROLL', metadata: { documentId: id, documentType: 'PAYROLL' } }, ['finance.read', 'finance.payroll']), { tab: 'operations', screen: 'documents', documentId: id, type: 'PAYROLL' })
  assert.equal(accountingSourceTarget(typedSource('FX_CASH_CORRECTION', 'fx_cash_correction', { sourceType: 'FO_PAYROLL' }), ['finance.read']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FX_CASH_CORRECTION', 'fx_cash_correction', { sourceType: 'fo_payroll' }), ['finance.read']), undefined)
  assert.deepEqual(accountingSourceTarget(typedSource('FX_CASH_CORRECTION', 'fx_cash_correction', { sourceType: 'FO_PAYROLL' }), ['finance.read', 'finance.payroll']), { tab: 'cash-errors', correctionId: id })
  assert.equal(accountingSourceTarget({ sourceType: 'FO_TAX_TASK', metadata: { documentId: id, documentType: 'TAX_TASK' } }, ['finance.read']), undefined)
  assert.deepEqual(accountingSourceTarget({ sourceType: 'FO_TAX_TASK', metadata: { documentId: id, documentType: 'TAX_TASK' } }, ['finance.read', 'finance.tax']), { tab: 'operations', screen: 'documents', documentId: id, type: 'TAX_TASK' })
  assert.equal(accountingSourceTarget(typedSource('FX_CASH_CORRECTION', 'fx_cash_correction', { sourceType: 'FO_TAX_TASK' }), ['finance.read']), undefined)
})
test('missing IDs, foreign typed tables, injected paths and unrelated source UUIDs do not produce guessed links', () => {
  for (const unsafe of ['//evil.test/a', 'https://evil.test', 'javascript:alert(1)', '../other', `${id}?tab=mine`, '', null, 123]) {
    assert.equal(accountingSourceTarget(typedSource('FS_PROCUREMENT', 'fs_recognition', { receiptId: unsafe }), ['*']), undefined)
    assert.equal(accountingSourceTarget(typedSource('FAR_ACCRUAL', 'far_run', { planId: unsafe }), ['*']), undefined)
    assert.equal(accountingSourceTarget(typedSource('FX_CASH_CORRECTION', 'fx_cash_correction', { businessId: unsafe }), ['*']), undefined)
    assert.equal(accountingSourceTarget(typedSource('FB_RECEIVABLE_ADJUSTMENT', 'fb_receivable_adjustment', { businessId: unsafe }), ['*']), undefined)
  }
  assert.equal(accountingSourceTarget({ ...typedSource('FS_PROCUREMENT', 'fs_recognition', { receiptId: parentId }), hotelId: undefined }, ['*']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FS_PROCUREMENT', 'fs_return', { receiptId: parentId }), ['*']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FAR_ACCRUAL', 'fo_document', { planId: parentId }), ['*']), undefined)
  assert.equal(accountingSourceTarget(typedSource('FX_CASH_CORRECTION', 'fb_cash_correction'), ['*']), undefined)
  assert.equal(accountingSourceTarget({ sourceType: 'FB_RECEIVABLE_RECOVERY', sourceId: id }, ['*']), undefined)
  assert.equal(accountingSourceTarget({ sourceType: 'FO_PAYROLL', metadata: { documentId: id, documentType: 'ASSET' } }, ['*']), undefined)
  assert.equal(accountingSourceTarget({ sourceType: 'REIMBURSEMENT_UNKNOWN', metadata: { claimId: id } }, ['*']), undefined)
})
test('inventory links use only the real hotel and movement relationship, without claiming an unsupported detail view', () => {
  assert.deepEqual(accountingSourceTarget(typedSource('FS_INVENTORY', 'fs_inventory_event', { movementId: id }), ['finance.read']), { tab: 'supply', screen: 'inventory', hotelId })
  assert.equal(accountingSourceTarget(typedSource('FS_INVENTORY', 'fs_inventory_event', { movementId: parentId }), ['*']), undefined)
  assert.equal(accountingSourceTarget({ ...typedSource('FS_INVENTORY', 'fs_inventory_event', { movementId: id, hotelId }), hotelId: undefined }, ['*']), undefined)
})
test('real gain and tax accrual sources preserve separate typed read gates', () => {
  const gain = typedSource('FS_ASSET_GAIN', 'fs_asset_gain')
  assert.deepEqual(accountingSourceTarget(gain, ['finance.accounting.read']), { tab: 'supply', screen: 'asset-gains', gainId: id })
  assert.equal(accountingSourceTarget(gain, ['finance.review', 'finance.accounting.write']), undefined)
  assert.equal(accountingSourceTarget({ ...gain, hotelId: undefined }, ['*']), undefined)
  const tax = typedSource('FB_TAX_ACCRUAL', 'ft_tax_accrual')
  assert.equal(accountingSourceTarget(tax, ['finance.read']), undefined)
  assert.deepEqual(accountingSourceTarget(tax, ['finance.tax']), { tab: 'tax-control', section: 'accruals', accrualId: id })
})
