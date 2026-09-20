import type { RoleContext } from '../../domain'
import { featureApiMutation, featureApiRequest } from '../shared/featureApi'
import type { LaundryDetail, LaundryDispatch, LaundryItem, LaundryPage, LaundryReceive } from './laundry'

const path = (hotelId: string) => `/store-resources/hotels/${encodeURIComponent(hotelId)}/laundry`
export const loadLaundryCatalog = (identity: RoleContext, hotelId: string, signal?: AbortSignal) => featureApiRequest<LaundryItem[]>(`${path(hotelId)}/catalog`, identity, { signal })
export const loadLaundryOrders = (identity: RoleContext, hotelId: string, page: number, status: string, signal?: AbortSignal) => featureApiRequest<LaundryPage>(`${path(hotelId)}/orders?page=${page}&status=${encodeURIComponent(status)}`, identity, { signal })
export const loadLaundryOrder = (identity: RoleContext, hotelId: string, id: string, signal?: AbortSignal) => featureApiRequest<LaundryDetail>(`${path(hotelId)}/orders/${encodeURIComponent(id)}`, identity, { signal })
export const saveLaundryRate = (identity: RoleContext, hotelId: string, item: LaundryItem, unitPrice: string) => featureApiMutation(`${path(hotelId)}/rates/${encodeURIComponent(item.itemId)}`, identity, {
  method: 'PUT', expectedVersion: item.rateVersion, body: { unitPrice, expectedItemVersion: item.itemVersion },
})
export const dispatchLaundry = (identity: RoleContext, hotelId: string, input: LaundryDispatch, key: string) => featureApiMutation<LaundryDetail>(`${path(hotelId)}/orders`, identity, { method: 'POST', body: input, idempotencyKey: key })
export const receiveLaundry = (identity: RoleContext, hotelId: string, id: string, input: LaundryReceive, key: string) => featureApiMutation<LaundryDetail>(`${path(hotelId)}/orders/${encodeURIComponent(id)}/receipts`, identity, { method: 'POST', body: input, idempotencyKey: key })
export const correctLaundry = (identity: RoleContext, hotelId: string, id: string, receiptId: string | undefined, reason: string, expectedVersion: number) => featureApiMutation<LaundryDetail>(`${path(hotelId)}/orders/${encodeURIComponent(id)}/${receiptId ? `receipts/${encodeURIComponent(receiptId)}/reverse` : 'cancel'}`, identity, { method: 'POST', body: { reason }, expectedVersion })
