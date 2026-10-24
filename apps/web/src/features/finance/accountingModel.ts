import type { FinanceRow } from './unifiedModel'

export const accountingReportLabels = {
  trialBalance: '科目余额与试算', generalLedger: '总账', detailLedger: '明细账', cashJournal: '现金与银行日记账',
  incomeStatement: '利润表', balanceSheet: '资产负债表', cashFlowStatement: '现金流量表',
} as const
export type AccountingReportType = keyof typeof accountingReportLabels
const exportTypes: Record<AccountingReportType, string> = {
  trialBalance: 'TRIAL_BALANCE', generalLedger: 'GENERAL_LEDGER', detailLedger: 'DETAIL_LEDGER', cashJournal: 'CASH_JOURNAL',
  incomeStatement: 'INCOME_STATEMENT', balanceSheet: 'BALANCE_SHEET', cashFlowStatement: 'CASH_FLOW',
}
export const cashFlowLabels: Record<string, string> = {
  OPERATING: '经营活动', INVESTING: '投资活动', FINANCING: '筹资活动', TRANSFER: '资金内部划转', UNCLASSIFIED: '未分类（需核对）',
}
export const checkLabels: Record<string, string> = {
  OPENING_BALANCE: '期初余额', SOURCE_COVERAGE: '来源覆盖与对账', PENDING_SOURCE_EVENTS: '来源记账状态',
  UNPOSTED_VOUCHERS: '未过账凭证', RECURRING_TASKS: '周期计提及摊销', PRIOR_PERIODS: '前期关账',
  TRIAL_BALANCE: '借贷试算', CASH_FLOW_CLASSIFICATION: '现金流分类', YEAR_END_TRANSFER: '年末损益结转',
}
export function accountingRecord(value: unknown): FinanceRow {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as FinanceRow : {}
}
export function accountingRows(value: unknown): FinanceRow[] {
  if (Array.isArray(value)) return value.filter(row => row !== null && typeof row === 'object' && !Array.isArray(row)) as FinanceRow[]
  const record = accountingRecord(value)
  for (const key of ['items', 'lines', 'rows']) if (Array.isArray(record[key])) return accountingRows(record[key])
  return []
}
export function accountingReportRows(report: FinanceRow, type: AccountingReportType): FinanceRow[] {
  const section = report[type]
  if (type !== 'cashFlowStatement') return accountingRows(section)
  return Object.entries(accountingRecord(accountingRecord(section).categories))
    .map(([category, amount]) => ({ category, categoryName: cashFlowLabels[category] ?? category, amount }))
}
export function accountingReportTotals(report: FinanceRow, type: AccountingReportType): FinanceRow {
  const keys: Partial<Record<AccountingReportType, string[]>> = {
    incomeStatement: ['revenue', 'expense', 'netProfit'],
    balanceSheet: ['assets', 'liabilities', 'equity', 'unclosedProfit', 'difference'],
    cashFlowStatement: ['beginningCash', 'endingCash', 'netMovement', 'difference'],
  }
  const section = accountingRecord(report[type])
  return Object.fromEntries((keys[type] ?? []).filter(key => section[key] !== undefined).map(key => [key, section[key]]))
}
export function accountingChecks(value: unknown): FinanceRow[] {
  return accountingRows(accountingRecord(value).checks).map(row => ({ ...row, checkName: checkLabels[String(row.code)] ?? row.code, result: row.passed === true ? '已通过' : '未通过' }))
}
export function accountingExportPath(bookId: string, month: string, format: 'xlsx' | 'pdf', reportType: AccountingReportType | 'ALL', reportVersion: unknown): string {
  if (!bookId || !/^\d{4}-\d{2}$/.test(month) || typeof reportVersion !== 'string' || !reportVersion) throw new Error('请先读取本期实时报告版本，再导出；冻结快照不会被实时数据替代。')
  const query = new URLSearchParams({ bookId, month, format, reportType: reportType === 'ALL' ? 'ALL' : exportTypes[reportType], expectedReportVersion: reportVersion })
  return `/accounting/reports/export?${query}`
}
export function shortVoucherId(value: unknown): string { return typeof value === 'string' && value ? value.slice(0, 8).toUpperCase() : '—' }
export function validAccountingLine(line: { accountCode: string; debit: string; credit: string }): boolean {
  const amount = (value: string) => /^\d{1,14}(\.\d{1,2})?$/.test(value)
  const positive = (value: string) => /[1-9]/.test(value)
  return !!line.accountCode && amount(line.debit) && amount(line.credit) && positive(line.debit) !== positive(line.credit)
}
// Only real, typed source relationships produce in-app links. Never guess a claim from an arbitrary source UUID.
export function accountingSourceTarget(source: FinanceRow, permissions: readonly string[]): Record<string, string> | undefined {
  const metadata = accountingRecord(source.metadata)
  const has = (permission: string) => permissions.includes('*') || permissions.includes(permission)
  const uuid = (value: unknown) => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value) ? value : undefined
  const sourceType = typeof source.sourceType === 'string' ? source.sourceType : ''
  // Sensitive origin survives wrappers such as an independently approved cash-error correction.
  const origins = ([sourceType, metadata.sourceType, metadata.originalSourceType, metadata.documentType].filter(value => typeof value === 'string') as string[]).map(value => value.toUpperCase())
  if (origins.some(value => value.includes('PAYROLL')) && !has('finance.payroll')) return undefined
  if (origins.some(value => value.includes('TAX')) && !has('finance.tax')) return undefined
  const claimId = uuid(metadata.claimId)
  const reimbursementTypes = ['REIMBURSEMENT_EXPENSE', 'REIMBURSEMENT_INVENTORY', 'REIMBURSEMENT_ASSET', 'REIMBURSEMENT_PREPAID', 'REIMBURSEMENT_ALREADY_RECORDED', 'REIMBURSEMENT_PAYMENT', 'REIMBURSEMENT_LOAN_OFFSET']
  if (claimId && reimbursementTypes.includes(sourceType) && ['finance.submit', 'finance.read', 'finance.review', 'finance.pay'].some(has)) return { tab: has('finance.read') || has('finance.pay') ? 'all' : has('finance.review') ? 'review' : 'mine', id: claimId }
  const documentId = uuid(metadata.documentId)
  const types = ['CONTRACT', 'PAYABLE', 'LOAN', 'PREPAYMENT', 'ASSET', 'PAYROLL', 'REVENUE', 'INTERCOMPANY', 'TAX_TASK']
  const canReadDocuments = ['finance.operations.read', 'finance.operations.write', 'finance.operations.review', 'finance.read', 'finance.review', 'finance.pay'].some(has)
  const documentType = String(metadata.documentType)
  const matchesDocumentType = sourceType === `FO_${documentType}` || documentType === 'PAYABLE' && ['EXPENSE', 'INVENTORY', 'ASSET', 'PREPAID'].some(kind => sourceType === `FO_PAYABLE_${kind}`) || documentType === 'REVENUE' && ['ROOM', 'OTHER', 'DEPOSIT', 'PREPAID'].some(kind => sourceType === `FO_REVENUE_${kind}`)
  if (documentId && matchesDocumentType && types.includes(documentType) && canReadDocuments) return { tab: 'operations', screen: 'documents', type: documentType, documentId }
  const recurringDocumentId = uuid(metadata.sourceId)
  const recurringType = sourceType === 'FA_DEPRECIATION' && metadata.sourceType === 'FO_ASSET' ? 'ASSET'
    : sourceType === 'FA_AMORTIZATION' && metadata.sourceType === 'FO_PREPAYMENT' ? 'PREPAYMENT' : undefined
  if (recurringDocumentId && uuid(metadata.planId) && recurringType && canReadDocuments) return { tab: 'operations', screen: 'documents', type: recurringType, documentId: recurringDocumentId }
  const hotelId = uuid(source.hotelId); const businessId = uuid(metadata.businessId)
  const canReadSupply = ['finance.read', 'finance.review', 'finance.pay', 'finance.accounting.read', 'finance.accounting.write'].some(has)
  const receiptId = uuid(metadata.receiptId)
  if (sourceType === 'FS_ASSET_GAIN' && metadata.businessTable === 'fs_asset_gain' && businessId && hotelId && ['finance.read', 'finance.accounting.read'].some(has)) return { tab: 'supply', screen: 'asset-gains', gainId: businessId }
  if (sourceType === 'FB_TAX_ACCRUAL' && metadata.businessTable === 'ft_tax_accrual' && businessId && has('finance.tax')) return { tab: 'tax-control', section: 'accruals', accrualId: businessId }
  if (sourceType === 'FS_PROCUREMENT' && metadata.businessTable === 'fs_recognition' && businessId && receiptId && hotelId && canReadSupply) return { tab: 'supply', screen: 'procurement', hotelId, receiptId }
  if (sourceType === 'FS_INVENTORY' && metadata.businessTable === 'fs_inventory_event' && businessId && uuid(metadata.movementId) === businessId && hotelId && canReadSupply) return { tab: 'supply', screen: 'inventory', hotelId }
  const hasAccountingTab = ['finance.read', 'finance.configure', 'finance.accounting.read', 'finance.accounting.write', 'finance.accounting.review', 'finance.accounting.post', 'finance.accounting.close'].some(has)
  if (sourceType === 'FAR_ACCRUAL' && metadata.businessTable === 'far_run' && businessId && uuid(metadata.planId) && hasAccountingTab && ['finance.read', 'finance.accounting.read', 'finance.review'].some(has)) return { tab: 'accrual', screen: 'plans', planId: uuid(metadata.planId)! }
  if (sourceType === 'FX_CASH_CORRECTION' && metadata.businessTable === 'fx_cash_correction' && businessId && ['finance.read', 'finance.review', 'finance.pay'].some(has)) return { tab: 'cash-errors', correctionId: businessId }
  if (businessId && ['finance.read', 'finance.review'].some(has)) {
    if (sourceType === 'FB_RECEIVABLE_ADJUSTMENT' && metadata.businessTable === 'fb_receivable_adjustment') return { tab: 'business-exceptions', section: 'adjustments', exceptionId: businessId }
    if (sourceType === 'FB_RECEIVABLE_RECOVERY' && metadata.businessTable === 'fb_receivable_recovery') return { tab: 'business-exceptions', section: 'recoveries', exceptionId: businessId }
    const batchId = uuid(metadata.batchId)
    if (['FB_REVENUE_INCOME', 'FB_REVENUE_AR', 'FB_REVENUE_DEPOSIT', 'FB_REVENUE_PREPAYMENT', 'FB_REVENUE_STORED_VALUE'].includes(sourceType) && metadata.businessTable === 'fb_revenue_entry' && batchId) return { tab: 'business', screen: 'revenue', section: 'imports', businessId: batchId }
  }
  const managedFinance = ['finance.read', 'finance.review', 'finance.pay', 'finance.configure'].some(has)
  if (sourceType === 'FB_PAYROLL' && metadata.businessTable === 'fb_payroll_batch' && businessId && (metadata.payrollBatchId === undefined || uuid(metadata.payrollBatchId) === businessId) && has('finance.payroll') && managedFinance) return { tab: 'business', screen: 'payroll', section: 'batches', businessId }
  if (['FB_INTERCOMPANY', 'FB_INTERCOMPANY_CASH'].includes(sourceType) && metadata.businessTable === 'fb_intercompany' && businessId && (metadata.transactionId === undefined || uuid(metadata.transactionId) === businessId) && ['finance.read', 'finance.review', 'finance.pay'].some(has)) return { tab: 'business', screen: 'intercompany', section: 'transactions', businessId }
  return undefined
}
