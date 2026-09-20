export type LaundryItem = {
  itemId: string; itemName: string; baseUnit: string; itemVersion: number
  unitPrice: number | null; rateVersion: number; ownedQuantity: number; pendingQuantity: number; availableQuantity: number
}
export type LaundryLine = {
  id: string; itemId: string; itemName: string; baseUnit: string; quantity: number; unitPrice: number
  acceptedQuantity: number; writtenOffQuantity: number; pendingQuantity: number; fee: number
}
export type LaundryReceiptLine = {
  orderLineId: string; itemName: string; baseUnit: string; unitPrice: number; acceptedQuantity: number
  rewashQuantity: number; writtenOffQuantity: number; fee: number; differenceReason: string
}
export type LaundryReceipt = {
  id: string; receivedOn: string; note: string; status: 'POSTED' | 'REVERSED'; operatorName: string
  createdAt: string; reversedAt?: string; reversedByName?: string; reversalReason?: string; lines: LaundryReceiptLine[]
}
export type LaundryOrder = {
  id: string; supplier: string; dispatchedOn: string; note: string; rowVersion: number; createdAt: string
  operatorName: string; cancellationReason?: string; fee: number; lineCount: number
  status: 'SENT' | 'PARTIAL' | 'COMPLETED' | 'CANCELLED'
}
export type LaundryDetail = LaundryOrder & { lines: LaundryLine[]; receipts: LaundryReceipt[] }
export type LaundryPage = { items: LaundryOrder[]; page: number; hasMore: boolean }
export type LaundryDispatchLine = { itemId: string; quantity: string; expectedRateVersion: number; expectedItemVersion: number }
export type LaundryDispatch = { supplier: string; dispatchedOn: string; note: string; lines: LaundryDispatchLine[] }
export type LaundryReceiveLine = { lineId: string; acceptedQuantity: string; rewashQuantity: string; writtenOffQuantity: string; differenceReason: string }
export type LaundryReceive = { receivedOn: string; note: string; confirmWriteOff: boolean; expectedVersion: number; lines: LaundryReceiveLine[] }

export const laundryStatus = (status: string) => ({ SENT: '已送洗，待回收', PARTIAL: '部分回收 / 仍有在洗', COMPLETED: '已全部核对', CANCELLED: '已取消' } as Record<string, string>)[status] ?? '未知状态'
export const receiptStatus = (status: string) => status === 'REVERSED' ? '已冲销（不再计费）' : '已记账'
export function laundryDecimal(value: string): bigint | undefined {
  const match = /^(\d{1,14})(?:\.(\d{1,4}))?$/.exec(value.trim())
  return match ? BigInt(match[1]) * 10_000n + BigInt((match[2] ?? '').padEnd(4, '0')) : undefined
}
export function laundryFeePreview(quantity: string, price: number): string {
  const q = laundryDecimal(quantity), p = laundryDecimal(String(price))
  if (q === undefined || p === undefined) return '—'
  const scaled = (q * p + 5_000n) / 10_000n
  return `${scaled / 10_000n}.${String(scaled % 10_000n).padStart(4, '0')}`
}
export function dispatchValidation(input: LaundryDispatch, catalog: LaundryItem[]): string | undefined {
  if (!input.supplier.trim() || !input.dispatchedOn) return '请填写洗涤供应商及实际送洗日期。'
  if (!input.lines.length || input.lines.length > 100) return '请添加1至100种送洗布草。'
  const seen = new Set<string>()
  for (const line of input.lines) {
    const item = catalog.find((candidate) => candidate.itemId === line.itemId)
    if (!item || item.unitPrice === null || item.rateVersion < 0) return '请先配置所选布草的洗涤单价。'
    if (item.itemVersion !== line.expectedItemVersion || item.rateVersion !== line.expectedRateVersion) return '物品或洗涤单价已变化，请重新选择并核对该项。'
    if (seen.has(item.itemId)) return '同种布草不能重复添加。'
    seen.add(item.itemId)
    const q = laundryDecimal(line.quantity), available = laundryDecimal(String(item.availableQuantity))
    if (q === undefined || q <= 0n) return `${item.itemName}：请填写大于0的数量，最多4位小数。`
    if (available === undefined || q > available) return `${item.itemName}：数量超过可送洗库存。`
  }
}
export function receiveValidation(input: LaundryReceive, order: LaundryDetail): string | undefined {
  if (!input.receivedOn || input.receivedOn < order.dispatchedOn) return '回收日期不得早于送洗日期。'
  if (input.expectedVersion !== order.rowVersion) return '洗涤单已变化，请刷新后重试。'
  if (['CANCELLED', 'COMPLETED'].includes(order.status)) return '该单已全部核对或取消，不能继续登记回收。'
  if (!input.lines.length || input.lines.length > 100) return '请填写至少一项本次合格回收、返洗或报损数量。'
  const seen = new Set<string>()
  for (const inputLine of input.lines) {
    const line = order.lines.find((candidate) => candidate.id === inputLine.lineId)
    if (!line || seen.has(inputLine.lineId)) return '回收明细不存在或重复。'
    seen.add(inputLine.lineId)
    const accepted = laundryDecimal(inputLine.acceptedQuantity), rewash = laundryDecimal(inputLine.rewashQuantity), lost = laundryDecimal(inputLine.writtenOffQuantity)
    const pending = laundryDecimal(String(line.pendingQuantity))
    if (accepted === undefined || rewash === undefined || lost === undefined || pending === undefined) return '数量须为非负数，最多4位小数。'
    if (accepted + rewash + lost <= 0n || accepted + rewash + lost > pending) return `${line.itemName}：合格、返洗及报损合计须大于0且不超过尚在洗数量。`
    if (rewash + lost > 0n && !inputLine.differenceReason.trim()) return `${line.itemName}：请填写返洗或报损原因。`
    if (lost > 0n && !input.confirmWriteOff) return '请勾选确认报损，本操作会扣减总库存并登记损失。'
  }
}
export function localLaundryDate() {
  const date = new Date()
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
