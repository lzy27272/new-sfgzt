import type { RoleContext } from '../../domain'
import { featureApiMutation, featureApiRequest } from '../shared/featureApi'
import type { CostCloseInput, CostClosedMonth, CostEntry, CostPage, CostSummary } from './costs'

const path = (hotelId: string) => `/store-resources/hotels/${encodeURIComponent(hotelId)}/costs`
export const loadCostSummary = (identity: RoleContext, hotelId: string, month: string, signal?: AbortSignal) => featureApiRequest<CostSummary>(`${path(hotelId)}${month ? `?month=${encodeURIComponent(month)}` : ''}`, identity, { signal })
export const loadCostEntries = (identity: RoleContext, hotelId: string, month: string, page: number, kind: string, day: string, signal?: AbortSignal) => featureApiRequest<CostPage<CostEntry>>(`${path(hotelId)}/entries?month=${encodeURIComponent(month)}&page=${page}&kind=${encodeURIComponent(kind)}${day ? `&day=${encodeURIComponent(day)}` : ''}`, identity, { signal })
export const loadCostHistory = (identity: RoleContext, hotelId: string, page: number, signal?: AbortSignal) => featureApiRequest<CostPage<CostClosedMonth>>(`${path(hotelId)}/closed-months?page=${page}`, identity, { signal })
export const closeCostMonth = (identity: RoleContext, hotelId: string, input: CostCloseInput) => featureApiMutation<CostSummary>(`${path(hotelId)}/close`, identity, { method: 'POST', body: input })
