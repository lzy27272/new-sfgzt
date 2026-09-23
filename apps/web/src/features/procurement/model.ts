export type ProcurementConfig = {
  version: number; enabled: boolean; manager_id: string; buyer_id: string; reviewer_id: string
  finance_id: string; finance_director_id: string; deputy_id: string; general_id: string; receiver_ids: string[]
}
export type CatalogItem = {
  id: string; sku: string; name: string; specification: string; category: string; unit: string
  purchase_unit: string; purchase_factor: number; minimum_quantity: number; current_quantity: number
  target_quantity?: number; replenishment_enabled?: boolean; rule_version: number; outstanding_quantity: number
}
export type HotelContext = {
  config: Partial<ProcurementConfig>; items: CatalogItem[]; canConfigure: boolean; canRequest: boolean
  canManage: boolean; canPurchase: boolean; canReview: boolean; canReceive: boolean
}
export type ProcurementContext = { hotels: { id: string; name: string }[]; accounts: { id: string; display_name: string }[]; canRequest: boolean; canConfigure: boolean }
export type DraftLine = { itemId: string; quantity: number; unit: string }
export type OrderLine = { id: string; item_id: string; item_name: string; sku: string; specification: string; input_quantity: number; input_unit: string; base_unit: string; factor: number; quantity: number; approved_quantity: number; pending_quantity: number; current_quantity: number; unit_price?: number }
export type ReceiptLine = { line_id: string; item_name: string; base_unit: string; arrived: number; accepted: number; rejected: number; quantity_before?: number; quantity_after?: number; value_delta?: number }
export type Receipt = { id: string; receiver_id: string; receiver_name: string; status: string; note: string; review_note: string; version: number; submitted_at?: string; reviewed_at?: string; lines: ReceiptLine[] }
export type Evidence = { id: string; receipt_id?: string; original_name: string; media_type: string; size_bytes: number; created_at: string; purged_at?: string }
export type Order = {
  id: string; hotel_id: string; hotel_name?: string; title: string; source: string; status: string; note: string; supplier: string
  version: number; expected_on?: string; first_difference_at?: string; alerted_at?: string; updated_at: string
  pending_receipts?: number; difference_days?: number; todo_label?: string; line_count?: number
}
export type OrderDetail = Order & {
  lines: OrderLine[]; receipts: Receipt[]; attachments: Evidence[]; config: ProcurementConfig
  canEdit: boolean; canPurchase: boolean; canReview: boolean; canReceive: boolean
  events: { event_type: string; created_at: string; actor_name?: string; detail: { note?: string } }[]
  notifications: { title: string; created_at: string; recipient_name: string; delivery_status: string; read_at?: string }[]
}
export type ImportPreview = { valid: boolean; rows: { row: number; itemId?: string; name?: string; quantity?: number; unit?: string; baseQuantity?: number; error: string }[] }
export const statusLabels: Record<string, string> = { DRAFT: '待审核提交', RETURNED: '已退回补充', SUBMITTED: '待采购办理', ORDERED: '待收货', PARTIAL: '差异待跟进', COMPLETED: '已全部入库', APPROVED: '已复核入库' }
export const receiptLabels: Record<string, string> = { DRAFT: '收货草稿', RETURNED: '退回核对', SUBMITTED: '待复核 · 未入库', APPROVED: '已复核入库', VOIDED: '草稿已撤销 · 记录保留' }
export const eventLabels: Record<string, string> = { DRAFT_CREATED: '创建采购需求', DRAFT_SAVED: '调整采购清单', SUBMITTED: '审核并提交采购', PURCHASED: '采购办理完成', REQUEST_RETURNED: '采购申请退回', RECEIPT_SAVED: '保存收货数量', RECEIPT_SUBMITTED: '提交收货复核', RECEIPT_APPROVED: '复核通过并入库', RECEIPT_RETURNED: '收货复核退回', RECEIPT_DRAFT_VOIDED: '撤销未提交收货草稿（保留记录）', ATTACHMENT_UPLOADED: '上传采购证据', LOW_STOCK_GENERATED: '低库存自动生成清单', OVERDUE_30_DAYS: '差异30天预警' }
export const deliveryLabels: Record<string, string> = { SENT: '企微已发送', UNBOUND: '未绑定企微 · 站内通知已生成', NOT_QUEUED: '站内已生成 · 企微待发送', PENDING: '企微待发送', FAILED: '企微失败待重试', INACTIVE: '接收账号已停用 · 请更新配置' }
export const number = (value: number | undefined) => new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value ?? 0)
export const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value)
export const date = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '—'
export const canProcure = (permissions: readonly string[]) => permissions.includes('*') || ['procurement.read', 'procurement.request', 'procurement.configure'].some(p => permissions.includes(p))
export function filterCatalog(items: CatalogItem[], query: string) {
  const text = query.trim().toLocaleLowerCase()
  return items.filter(i => !text || `${i.name} ${i.sku} ${i.specification}`.toLocaleLowerCase().includes(text))
}
export function remaining(line: OrderLine) { return Math.max(0, Number(line.quantity) - Number(line.approved_quantity) - Number(line.pending_quantity)) }
export function mergeImport(current: DraftLine[], incoming: DraftLine[]): DraftLine[] {
  const ids = new Set(current.map(l => l.itemId))
  if (incoming.some(l => ids.has(l.itemId))) throw new Error('导入含已选物品，请移除重复项后重试，不会自动重复累加。')
  if (current.length + incoming.length > 200) throw new Error('每单最多200项物品')
  return [...current, ...incoming]
}
