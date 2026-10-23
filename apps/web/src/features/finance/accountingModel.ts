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
  const claimId = uuid(metadata.claimId)
  if (claimId && String(source.sourceType).startsWith('REIMBURSEMENT_') && ['finance.submit', 'finance.read', 'finance.review', 'finance.pay'].some(has)) return { tab: has('finance.read') || has('finance.pay') ? 'all' : has('finance.review') ? 'review' : 'mine', id: claimId }
  const documentId = uuid(metadata.documentId)
  const types = ['CONTRACT', 'PAYABLE', 'LOAN', 'PREPAYMENT', 'ASSET', 'PAYROLL', 'REVENUE', 'INTERCOMPANY', 'TAX_TASK']
  const canReadDocuments = ['finance.operations.read', 'finance.operations.write', 'finance.operations.review', 'finance.read', 'finance.review', 'finance.pay'].some(has)
  if (documentId && String(source.sourceType).startsWith('FO_') && types.includes(String(metadata.documentType)) && canReadDocuments) return { tab: 'operations', screen: 'documents', type: String(metadata.documentType), documentId }
  const recurringDocumentId = uuid(metadata.sourceId)
  const recurringType = source.sourceType === 'FA_DEPRECIATION' && metadata.sourceType === 'FO_ASSET' ? 'ASSET'
    : source.sourceType === 'FA_AMORTIZATION' && metadata.sourceType === 'FO_PREPAYMENT' ? 'PREPAYMENT' : undefined
  if (recurringDocumentId && uuid(metadata.planId) && recurringType && canReadDocuments) return { tab: 'operations', screen: 'documents', type: recurringType, documentId: recurringDocumentId }
  return undefined
}
