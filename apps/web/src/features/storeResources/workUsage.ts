import type { RoleContext, WorkExpectation } from '../../domain'
import { featureApiMutation, featureApiRequest } from '../shared/featureApi'
import { baseQuantityPreview } from './unitConversion'

export type UsageConfig = { hotelOrgUnitId: string; enabled: boolean; rowVersion: number; reviewerAssignmentId?: string; reviewerName?: string; reviewerPositionName?: string; reviewerAvailable: boolean }
export type UsageScopeKind = 'POSITION' | 'DEPARTMENT' | 'SHARED'
export type UsageItem = { id: string; name: string; sku?: string; category: string; specification: string; unit: string; issueUnit: string; issueFactor: number; rowVersion: number; usageScopeKind?: UsageScopeKind; applicableToAssignment?: boolean; issuedToday?: boolean; departmentIds?: string[]; usageScopeMode?: string }
export type UsageQuickList = { positionId: string; rowVersion: number; configured: boolean; itemIds: string[] }
export type UsageCatalogue = UsageConfig & { items: UsageItem[]; departments?: Array<{ id: string; name: string }>; referenceUsage?: UsageDetail; mode?: 'ISSUE' | 'USAGE'; quickList?: UsageQuickList; departmentConfigured?: boolean; unconfiguredItemCount?: number }
export type UsageInputLine = { itemId: string; quantity: string; unit: string; expectedItemVersion: number }
export type UsageInput = { schemaVersion: 1; noUsage: boolean; lines: UsageInputLine[] }
export type UsageLine = { itemId: string; itemName: string; category: string; baseUnit: string; inputUnit: string; inputQuantity: number; conversionFactor: number; baseQuantity: number }
export type UsageCorrection = { id: string; revisionNo: number; noUsage: boolean; reason: string; createdAt: string; operatorName: string }
export type UsageDetail = { workRecordId: string; status: string; noUsage: boolean; lines: UsageLine[]; reversalReason?: string; correctionVersion?: number; corrections?: UsageCorrection[]; referenced?: boolean }
export type UsageRow = UsageDetail & { employeeName: string; workTitle: string; businessDate: string; recordVersion: number; workExpectationId?: string; canReview: boolean; canCorrect?: boolean; reviewReason?: string }
export type UsageReviewer = { assignmentId: string; employeeName: string; positionName: string }

export function supportsWorkUsage(item: Pick<WorkExpectation, 'itemCode' | 'submissionPolicy'>) {
  return ['HKA_ITEM_ISSUE', 'NSA_ITEM_ISSUE', 'HKA_USAGE_RETURN', 'HKA_DAILY_REPORT', 'NSA_DAILY_HANDOVER'].includes(item.itemCode ?? '') || item.submissionPolicy?.inventoryUsage === true
}
export function usageInput(value: unknown): UsageInput | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
  const input = value as Partial<UsageInput>
  if (input.schemaVersion !== 1 || typeof input.noUsage !== 'boolean' || !Array.isArray(input.lines)) return undefined
  if (!input.lines.every((line) => line && typeof line.itemId === 'string' && typeof line.quantity === 'string' && typeof line.unit === 'string' && Number.isSafeInteger(line.expectedItemVersion))) return undefined
  return input as UsageInput
}
export function usageValidation(value: unknown, catalogue: UsageCatalogue): string | undefined {
  if (!catalogue.enabled) return undefined
  if (catalogue.referenceUsage) return undefined
  if (!catalogue.reviewerAvailable && catalogue.mode !== 'ISSUE') return '门店耗用审核人已失效，请联系管理员重新配置。'
  const input = usageInput(value)
  if (!input) return '请填写实际耗用数量，或明确勾选本次无耗用。'
  if (input.noUsage) return input.lines.length ? '无耗用时不能同时填写物品数量。' : undefined
  if (!input.lines.length || input.lines.length > 100) return '请添加1至100种实际使用的物品。'
  const seen = new Set<string>()
  for (const line of input.lines) {
    const item = catalogue.items.find((candidate) => candidate.id === line.itemId)
    if (!item || item.rowVersion !== line.expectedItemVersion) return '物品配置已变化，请刷新清单并重新选择该物品、核对数量。'
    if (seen.has(item.id)) return '同一种物品不能重复填写。'
    seen.add(item.id)
    if (![item.unit, item.issueUnit].includes(line.unit)) return '请选择物品支持的耗用单位。'
    const preview = baseQuantityPreview(line.quantity, line.unit === item.unit ? 1 : item.issueFactor)
    if (preview.error || !preview.quantity || Number(preview.quantity) <= 0) return `${item.name}：${preview.error || '请输入大于0的耗用数量。'}`
  }
  return undefined
}
export const usageStatus = (value: string) => ({ PENDING: '待耗用审核（未扣库）', APPROVED: '审核通过', REJECTED: '已退回（未扣库）', REVERSED: '已冲销（库存及原成本已退回）' } as Record<string, string>)[value] ?? value
export function usageSummary(detail: UsageDetail) {
  if (detail.noUsage) return '本次无耗用'
  return detail.lines.map((line) => `${line.itemName} ${line.inputQuantity} ${line.inputUnit}（${line.baseQuantity} ${line.baseUnit}${line.category === 'LINEN' ? '，布草使用，不扣总库存' : ''}）`).join('；')
}
const path = (hotelId: string) => `/store-resources/hotels/${encodeURIComponent(hotelId)}`
export const loadQuickListPositions = (identity: RoleContext, hotelId: string, signal?: AbortSignal) => featureApiRequest<Array<{ id: string; name: string; code: string }>>(`${path(hotelId)}/usage-quick-list-positions`, identity, { signal })
export const loadUsageQuickList = (identity: RoleContext, hotelId: string, positionId: string, signal?: AbortSignal) => featureApiRequest<UsageQuickList & { items: UsageItem[] }>(`${path(hotelId)}/usage-quick-lists/${encodeURIComponent(positionId)}`, identity, { signal })
export const saveUsageQuickList = (identity: RoleContext, hotelId: string, positionId: string, itemIds: string[], expectedVersion: number) => featureApiMutation<UsageQuickList & { items: UsageItem[] }>(`${path(hotelId)}/usage-quick-lists/${encodeURIComponent(positionId)}`, identity, { method: 'PUT', body: { itemIds }, expectedVersion })
export const loadUsageCatalogue = (identity: RoleContext, orgUnitId: string, signal?: AbortSignal, context?: { businessDate: string; itemCode?: string }) => featureApiRequest<UsageCatalogue>(`/store-resources/usage-catalog?orgUnitId=${encodeURIComponent(orgUnitId)}${context ? `&businessDate=${encodeURIComponent(context.businessDate)}&itemCode=${encodeURIComponent(context.itemCode ?? '')}` : ''}`, identity, { signal })
export const loadUsageConfig = (identity: RoleContext, hotelId: string, signal?: AbortSignal) => featureApiRequest<UsageConfig>(`${path(hotelId)}/usage-review-config`, identity, { signal })
export const loadUsageReviewers = (identity: RoleContext, hotelId: string, signal?: AbortSignal) => featureApiRequest<UsageReviewer[]>(`${path(hotelId)}/usage-reviewers`, identity, { signal })
export const saveUsageConfig = (identity: RoleContext, hotelId: string, reviewerAssignmentId: string, expectedVersion: number) => featureApiMutation<UsageConfig>(`${path(hotelId)}/usage-review-config`, identity, { method: 'PUT', body: { reviewerAssignmentId }, expectedVersion })
export const loadWorkUsages = (identity: RoleContext, hotelId: string, status: string, signal?: AbortSignal) => featureApiRequest<UsageRow[]>(`${path(hotelId)}/work-usage?status=${encodeURIComponent(status)}`, identity, { signal })
export const reverseWorkUsage = (identity: RoleContext, hotelId: string, recordId: string, reason: string) => featureApiMutation<UsageDetail>(`${path(hotelId)}/work-usage/${encodeURIComponent(recordId)}/reverse`, identity, { method: 'POST', body: { reason } })
export const correctWorkUsage = (identity: RoleContext, hotelId: string, row: UsageRow, quantities: UsageInput, reason: string) => featureApiMutation<UsageDetail>(`${path(hotelId)}/work-usage/${encodeURIComponent(row.workRecordId)}/correct`, identity, { method: 'POST', body: { quantities, reason }, expectedVersion: row.correctionVersion ?? 0 })

export function usagePayload(value: unknown, catalogue?: UsageCatalogue) {
  return catalogue?.referenceUsage ? { schemaVersion: 1, referenceRecordId: catalogue.referenceUsage.workRecordId } : value
}
