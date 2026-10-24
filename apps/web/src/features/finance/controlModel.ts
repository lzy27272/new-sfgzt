import type { FinanceRow } from './unifiedModel'
export const controlScreens = { cash: '受控付款', loans: '借款核销与离职核对', policies: '版本化策略', bank: '流水导入与对账', contracts: '合同变更', travel: '差旅信息', archive: '档案索引' }
export const policyKinds = { WORKFLOW: '分类金额审批', TRAVEL: '差旅标准', ENTITY: '主体账务资料', CASH_PLAN: '资金计划', RETENTION: '档案保管', CATEGORY: '成本分类', BUDGET: '预算方案' }
export function listRows(value: unknown): FinanceRow[] { return Array.isArray(value) ? value.filter(x => x && typeof x === 'object') as FinanceRow[] : [] }
export function policySpec(kind: string, v: Record<string, string>) {
  switch (kind) {
    case 'WORKFLOW': return { documentType: v.documentType, minimum: v.minimum || '0', maximum: v.maximum || null, reviewerIds: [v.manager, v.finance, v.director, v.leader], cashierId: v.cashier, skipInitialSelf: v.skipInitialSelf === 'true' }
    case 'TRAVEL': return { lodgingPerNight: v.lodgingPerNight, mealPerDay: v.mealPerDay }
    case 'ENTITY': return { companyId: v.companyId, accountingStandard: v.accountingStandard, taxpayerType: v.taxpayerType, currency: 'CNY', fiscalYearStart: v.fiscalYearStart, bankAccountIds: v.bankAccountId ? [v.bankAccountId] : [] }
    case 'CASH_PLAN': return { month: v.month, amount: v.amount, direction: v.direction, control: v.control }
    case 'BUDGET': return { month: v.month, amount: v.amount, categoryId: v.categoryId || null, categoryCode: v.categoryCode, departmentId: v.departmentId || null, projectKind: v.projectKind || 'ALL', projectId: v.projectId || null, budgetKind: v.budgetKind, control: v.control }
    case 'RETENTION': return { years: v.years, basisReference: v.basisReference }
    default: throw new Error('请使用成本分类配置表逐项填写，不接受不透明配置。')
  }
}

// Source amounts are decimal strings. Never use floating point or treat a missing balance as zero.
function loanCents(value: unknown): bigint | null {
  if (typeof value !== 'string' || !/^\d{1,14}(\.\d{1,2})?$/.test(value)) return null
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}
const validVersion = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
export function approvedLoanRows(rows: FinanceRow[]): FinanceRow[] {
  return rows.filter(row => row.type === 'LOAN' && row.status === 'APPROVED' && typeof row.id === 'string' && row.id.length > 0)
}
export function loanClearCandidateRows(rows: FinanceRow[]): FinanceRow[] {
  // The endpoint has already checked borrower, company, EXPENSE treatment and approval. Reject incomplete snapshots here as well.
  return rows.filter(row => typeof row.id === 'string' && row.id.length > 0 && validVersion(row.version) && (loanCents(row.unsettledAmount) ?? 0n) > 0n)
}
export function loanClearRequest(loan: FinanceRow, claim: FinanceRow, values: Record<string, string>, requestId: string, today: string) {
  if (loan.type !== 'LOAN' || loan.status !== 'APPROVED' || !loan.id || !claim.id || !validVersion(loan.version) || !validVersion(claim.version)) throw new Error('借款或报销的实际审批状态及版本未读全，请刷新后重新选择。')
  const amount = loanCents(values.amount); const outstanding = loanCents(loan.outstandingAmount); const unsettled = loanCents(claim.unsettledAmount)
  if (amount === null || amount <= 0n) throw new Error('核销金额须为正数，最多14位整数和2位小数，不支持科学计数。')
  if (outstanding === null || unsettled === null) throw new Error('服务端实际未清余额未读全，不能按原批准金额核销。')
  if (amount > outstanding || amount > unsettled) throw new Error('本次核销不能超过借款实际净未清余额或所选费用报销的待清偿金额。')
  const day = values.occurredOn
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day ?? '') || !/^\d{4}-\d{2}-\d{2}$/.test(today) || !Number.isFinite(Date.parse(`${day}T00:00:00Z`)) || new Date(`${day}T00:00:00Z`).toISOString().slice(0, 10) !== day || day > today || typeof loan.businessDate !== 'string' || day < loan.businessDate) throw new Error('核销日须是真实有效日期，不早于借款业务日，且不晚于北京时间今天。')
  if (!values.note?.trim() || values.note.length > 2000) throw new Error('请填写1至2000字的真实核销依据。')
  return { requestId, claimId: claim.id, loanVersion: loan.version, claimVersion: claim.version, amount: values.amount, occurredOn: day, note: values.note.trim() }
}
export function loanExitStatus(result: FinanceRow): string {
  const rows = listRows(result.items)
  if (!Array.isArray(result.items) || typeof result.hasOutstanding !== 'boolean' || rows.length !== result.items.length || rows.some(row => loanCents(row.outstanding) === null)) return '未清信息不完整，不能认定已结清，请联系财务核对。'
  if (result.hasOutstanding || rows.some(row => (loanCents(row.outstanding) ?? 0n) > 0n)) return '该员工在本组织仍有未清借款，须由财务核对实际还款或费用核销；不能直接办理结清。'
  return '本组织当前已登记借款未发现正未清余额；这不是离职审批通过，也不代表其他组织已经结清。'
}
