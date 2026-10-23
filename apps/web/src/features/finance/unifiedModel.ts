import type { NavigationParams } from '../../domain'

export type FinanceRow = Record<string, unknown>
export type FinanceList<T = FinanceRow> = { items: T[]; total: number }
export type FinanceTodoItem = { id: string; domain?: string; kind: string; title: string; hotelName: string; applicantName: string; amount: string; status: string; dueAt?: string; waitingSince?: string; mismatch?: boolean; version: number; target?: NavigationParams }
export type FinanceTodos = { items: FinanceTodoItem[]; total: number; counts: { review: number; payment: number; returned: number; receipt?: number } }
export const emptyTodos: FinanceTodos = { items: [], total: 0, counts: { review: 0, payment: 0, returned: 0, receipt: 0 } }
export const operationTypes = {
  CONTRACT: '合同与付款计划', PAYABLE: '采购与应付', LOAN: '借款与备用金', PREPAYMENT: '预付与待摊',
  ASSET: '资产核算', PAYROLL: '薪酬计提', REVENUE: '收入与应收', INTERCOMPANY: '内部往来', TAX_TASK: '税务任务',
} as const
export type OperationType = keyof typeof operationTypes
export function financeTodoType(item: FinanceTodoItem): string {
  if (item.domain !== 'OPERATION') return '报销'
  const type = item.target?.type
  return typeof type === 'string' && type in operationTypes ? operationTypes[type as OperationType] : '财务业务单据'
}
export const financeStateLabels: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '待审核', REVIEWED: '已复核 · 待过账', APPROVED: '审批通过', POSTED: '已过账',
  REJECTED: '已拒绝', RETURNED: '退回补充', CANCELLED: '已取消', CLOSED: '已结账', OPEN: '开放期间',
  PARTIAL: '部分结算', SETTLED: '已结算', PAID: '已登记付款', UNMATCHED: '未匹配', MATCHED: '已匹配',
  PENDING_MAPPING: '待配置记账映射', PENDING_BOOK: '待关联账套', PENDING_PERIOD: '待开放期间',
  INCOMPLETE: '资料不完整', COMPLETE: '来源已核对', RECEIPT_PENDING: '待补回单', VERIFIED: '已外部查验',
  MANUAL_CONFIRMED: '已人工核对（非税务验真）', NOT_VERIFIED: '尚未外部查验',
  MANAGER_REVIEW: '待初审', FINANCE_REVIEW: '待门店财务复核', DIRECTOR_REVIEW: '待财务总监审核', LEADER_REVIEW: '待CEO终审', LINKED: '已关联报销',
  PENDING_ENTITY_MISMATCH: '来源法人缺失或与账套不符', REVIEW: '待当前节点审核',
}
export function textValue(value: unknown, fallback = '—'): string {
  if (value === null || value === undefined || value === '') return fallback
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
export function rowText(row: FinanceRow, field: string, fallback = '—') { return textValue(row[field], fallback) }
export function financeState(value: unknown) { const key = textValue(value); return financeStateLabels[key] ?? key }
export function financeWaiting(since?: string, now = Date.now()): string {
  const timestamp = since ? Date.parse(since) : NaN
  if (!Number.isFinite(timestamp)) return '等待时间待核对'
  const hours = Math.max(0, Math.floor((now - timestamp) / 3600000))
  return hours >= 24 ? `已等待 ${Math.floor(hours / 24)} 天 ${hours % 24} 小时` : hours > 0 ? `已等待 ${hours} 小时` : '等待不足 1 小时'
}
export function exactDecimalTotal(values: readonly string[]): string | null {
  let total = 0n
  for (const raw of values) {
    if (!/^-?\d{1,14}(\.\d{1,2})?$/.test(raw)) return null
    const negative = raw.startsWith('-'); const [whole, fraction = ''] = (negative ? raw.slice(1) : raw).split('.')
    total += (BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))) * (negative ? -1n : 1n)
  }
  const absolute = total < 0n ? -total : total
  return `${total < 0n ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`
}
export function voucherBalanced(lines: readonly { debit: string; credit: string }[]): boolean {
  const debit = exactDecimalTotal(lines.map(line => line.debit)); const credit = exactDecimalTotal(lines.map(line => line.credit))
  return lines.length >= 2 && debit !== null && debit !== '0.00' && debit === credit
}
export function todoTarget(item: FinanceTodoItem): NavigationParams {
  if (item.target) return item.target
  const kind = item.kind.toUpperCase()
  return { tab: kind === 'RETURNED' ? 'mine' : ['PAYMENT', 'RECEIPT'].includes(kind) ? 'all' : 'review', id: item.id }
}
// Spreadsheet-safe text export. This is CSV, never mislabelled as an Excel workbook.
export function financeCsv(rows: readonly FinanceRow[], columns: readonly { key: string; label: string }[]): string {
  const cell = (value: unknown) => {
    const raw = textValue(value, ''); const safe = /^[\s]*[=+@-]/.test(raw) ? `'${raw}` : raw
    return `"${safe.replace(/"/g, '""')}"`
  }
  return '\ufeff' + [columns.map(c => cell(c.label)).join(','), ...rows.map(row => columns.map(c => cell(row[c.key])).join(','))].join('\r\n')
}
