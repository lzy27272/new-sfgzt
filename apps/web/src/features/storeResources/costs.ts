export const costKinds: Record<string, string> = {
  CONSUMABLE: '耗品使用', CLEANING_SUPPLY: '清洁用品使用', OPERATING_ITEM: '经营物品使用',
  LINEN_USAGE: '布草耗减', LAUNDRY: '布草洗涤', LOSS: '报损损失', ADJUSTMENT: '盘点调整（单列）',
}
export type CostSummary = {
  month: string; currentMonth: string; closed: boolean; canClosePeriod: boolean; previewToken: string
  usageTotal: string; lossTotal: string; costTotal: string; adjustmentTotal: string; entryCount: number
  pendingUsageCount: number; openLaundryCount: number; zeroAmountCount: number; legacyCount: number; priorPeriodCount: number
  closedAt?: string; closedByName?: string; closeNote?: string
  categories: Array<{ costKind: string; amount: string; entryCount: number }>
  daily: Array<{ postingDate: string; amount: string; adjustmentAmount: string }>
  items: Array<{ itemId: string; itemName: string; baseUnit: string; costKind: string; quantity: string; amount: string; entryCount: number }>
}
export type CostEntry = {
  id: string; itemId: string; itemName: string; baseUnit: string; costKind: string; quantity: string; amount: string
  businessDate: string; postingDate: string; sourceKind: string; sourceId: string; sourceParentId?: string
  direction: number; reversesEntryId?: string; reason: string; legacyClassification: boolean; priorPeriod: boolean
  sourceRecordedAt: string; operatorName: string
}
export type CostPage<T> = { items: T[]; page: number; hasMore: boolean }
export type CostClosedMonth = { id: string; month: string; costTotal: string; adjustmentTotal: string; closedAt: string; closedByName: string; note: string }
export type CostCloseInput = { month: string; previewToken: string; note: string; acknowledgeWarnings: boolean }

// Format the exact decimal text returned by PostgreSQL. Never round large currency through Number.
export function costMoney(value: string): string {
  if (!/^-?\d+(\.\d{1,4})?$/.test(value)) return '金额暂不可用'
  const negative = value.startsWith('-'), [whole, fraction = ''] = value.replace(/^-/, '').split('.')
  return `${negative ? '-' : ''}¥${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction.padEnd(2, '0')}`
}
export function closeBlockReason(summary: CostSummary, canClose: boolean): string | undefined {
  if (summary.closed) return '本月已关账，后续更正记入当前月份，原快照不变。'
  if (!canClose) return '当前岗位仅可查看成本；月结需管理员授予“核对并锁定授权门店的成本月结”权限。'
  if (summary.pendingUsageCount > 0) return `仍有 ${summary.pendingUsageCount} 条待审核耗用，请先审核或退回。`
  if (!summary.canClosePeriod) return '本月尚未结束，只能预览；请在下月核对后月结。'
  return undefined
}
