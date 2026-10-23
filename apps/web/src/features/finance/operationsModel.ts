import type { FinanceRow } from './unifiedModel'

// These are creation kinds, not settlement actions. Refunds belong to the original approved document.
export const revenueKindOptions = [
  { value: 'ROOM', label: '客房收入' }, { value: 'OTHER', label: '其他收入' },
  { value: 'DEPOSIT', label: '押金（不直接计收入）' }, { value: 'PREPAID', label: '预收（不直接计收入）' },
]
export const operationActionLabels: Record<string, string> = {
  PAYMENT: '登记实际付款', RECEIPT: '登记实际收款', REFUND: '登记退款 / 冲回', REPAYMENT: '归还借款', WRITEOFF: '关联核销',
  DEPRECIATE: '计提本期折旧', AMORTIZE: '确认本期摊销', DISPOSE: '资产处置', COUNTERSIGN: '对方确认往来',
  ISSUE_INVOICE: '归档真实开票结果', RED_INVOICE: '归档真实红字票结果', TAX_FILE: '归档真实申报回执',
  SUBMIT: '提交审核', APPROVE: '本节点通过', RETURN: '退回补充', REJECT: '拒绝', CANCEL: '取消未批申请', SKIP_SELF: '按配置回避首段自审',
}
const receiptLabels: Record<string, string> = {
  ARCHIVED: '回单原件已上传归档', EXTERNAL_REFERENCE: '仅有外部归档引用，原件未上传',
  MISSING: '待补回单原件', NOT_APPLICABLE: '非资金动作，无需付款回单',
}
export function operationRows(value: unknown): FinanceRow[] {
  return Array.isArray(value) ? value.filter(row => row && typeof row === 'object' && !Array.isArray(row)) as FinanceRow[] : []
}
export function operationRecord(value: unknown): FinanceRow {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as FinanceRow : {}
}
export function operationReceiptStatus(value: unknown): string {
  return receiptLabels[String(value)] ?? (value ? `未识别回单状态（${String(value)}）` : '服务端未提供回单状态，不能认定已归档')
}
export function operationReviewStage(row: FinanceRow): string {
  const stage = operationRecord(row.snapshot).stage
  if (stage === undefined || stage === null) return ({ SUBMIT: '申请提交', SKIP_SELF: '首段回避', CANCEL: '未批申请取消' } as Record<string, string>)[String(row.action)] ?? '未提供审核阶段快照'
  // APPROVE freezes the advanced index (1–4); RETURN/REJECT freeze the current index (0–3).
  const index = typeof stage === 'number' && Number.isInteger(stage) ? row.action === 'APPROVE' ? stage - 1 : stage : -1
  return ['门店初审', '门店财务复核', '财务总监审核', 'CEO终审'][index] ?? `阶段快照异常（${String(stage)}）`
}
export function operationReviewRows(value: unknown): FinanceRow[] {
  return operationRows(value).map(row => ({
    id: row.id, stageLabel: operationReviewStage(row), actorId: row.actorId,
    actionLabel: operationActionLabels[String(row.action)] ?? row.action, note: row.note, createdAt: row.createdAt,
  }))
}
export function bankUnmatchedAmount(row: FinanceRow): string | null {
  const amount = row.amount; const matched = row.matchedAmount
  if (typeof amount !== 'string' || typeof matched !== 'string' || !/^\d{1,14}(\.\d{1,2})?$/.test(amount) || !/^\d{1,14}(\.\d{1,2})?$/.test(matched)) return null
  // Restrict to the server's nonnegative decimal contract, then subtract integer cents, never Number money.
  const cents = (value: string) => { const [whole, fraction = ''] = value.split('.'); return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0')) }
  const difference = cents(amount) - cents(matched); const absolute = difference < 0n ? -difference : difference
  return `${difference < 0n ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`
}
export function bankExplanationStatus(row: FinanceRow): string {
  const remaining = bankUnmatchedAmount(row)
  if (remaining === null) return '金额未读全，不能确认对账状态'
  if (remaining.startsWith('-')) return '匹配金额超过原流水，需核对异常'
  if (remaining === '0.00') return '已全额匹配，无未达需解释'
  return ({ VALID: '未达解释有效（不代表已匹配）', STALE: '匹配情况已变，解释须重新核对', NONE: '未达原因尚未确认' } as Record<string, string>)[String(row.explanationStatus)] ?? '服务端未提供解释状态'
}
export function bankExplanationRequest(row: FinanceRow, reason: string, requestId: string) {
  const remaining = bankUnmatchedAmount(row)
  if (remaining === null || remaining === '0.00' || remaining.startsWith('-')) throw new Error('当前没有可确认的正未达金额，请刷新核对后重试。')
  if (!reason.trim() || reason.length > 2000) throw new Error('请提供1至2000字真实未达原因。')
  let expectedVersion: number
  if (row.explanationStatus === 'NONE' && row.explanationVersion == null) expectedVersion = -1
  else if (['VALID', 'STALE'].includes(String(row.explanationStatus)) && typeof row.explanationVersion === 'number' && Number.isInteger(row.explanationVersion) && row.explanationVersion >= 0) expectedVersion = row.explanationVersion
  else throw new Error('服务端未读取到有效的解释状态及版本，请刷新后重试，不能按新建覆盖。')
  return { requestId, expectedVersion, reason }
}
