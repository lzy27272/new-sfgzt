import type { RoleContext } from '../../domain'
import { featureApiMutation, featureApiRequest } from '../shared/featureApi'
import {
  parseFixedAsset,
  parseFixedAssets,
  parseHandoverParticipants,
  parseInventoryMovements,
  parseStoreInventoryBalances,
  parseStoreResourceHandover,
  parseStoreResourceHandoverDetail,
  parseStoreResourceHandovers,
  parseStoreResourceHotels,
  parseStoreResourceItem,
  parseStoreResourceItems,
  type FixedAssetInput,
  type InventoryAdjustmentInput,
  type StoreResourceHandoverInput,
  type StoreResourceItemInput,
} from './types'

const hotelPath = (hotelId: string) => `/store-resources/hotels/${encodeURIComponent(hotelId)}`

export async function loadStoreResourceHotels(identity: RoleContext, signal?: AbortSignal) {
  return parseStoreResourceHotels(await featureApiRequest<unknown>('/store-resources/hotels', identity, { signal }))
}

export async function loadStoreResourceItems(identity: RoleContext, hotelId: string, signal?: AbortSignal) {
  return parseStoreResourceItems(await featureApiRequest<unknown>(`${hotelPath(hotelId)}/items?includeInactive=true`, identity, { signal }))
}

export async function setStoreResourceItemEnabled(identity: RoleContext, hotelId: string, itemId: string, enabled: boolean, expectedVersion: number, idempotencyKey: string) {
  return parseStoreResourceItem(await featureApiMutation<unknown>(`${hotelPath(hotelId)}/items/${encodeURIComponent(itemId)}/actions/${enabled ? 'enable' : 'disable'}`, identity, {
    method: 'POST', body: {}, expectedVersion, idempotencyKey,
  }))
}

export async function createStoreResourceItem(identity: RoleContext, hotelId: string, input: StoreResourceItemInput, idempotencyKey: string) {
  return parseStoreResourceItem(await featureApiMutation<unknown>(`${hotelPath(hotelId)}/items`, identity, {
    method: 'POST', body: input, idempotencyKey,
  }))
}

export async function updateStoreResourceItem(identity: RoleContext, hotelId: string, itemId: string, input: StoreResourceItemInput, expectedVersion: number, idempotencyKey: string) {
  return parseStoreResourceItem(await featureApiMutation<unknown>(`${hotelPath(hotelId)}/items/${encodeURIComponent(itemId)}`, identity, {
    method: 'PUT', body: input, expectedVersion, idempotencyKey,
  }))
}

export async function loadInventoryMovements(identity: RoleContext, hotelId: string, signal?: AbortSignal) {
  return parseInventoryMovements(await featureApiRequest<unknown>(`${hotelPath(hotelId)}/movements?limit=100`, identity, { signal }))
}

export async function loadStoreInventoryBalances(identity: RoleContext, hotelId: string, signal?: AbortSignal) {
  return parseStoreInventoryBalances(await featureApiRequest<unknown>(`${hotelPath(hotelId)}/balances`, identity, { signal }))
}

export async function createInventoryMovement(identity: RoleContext, hotelId: string, input: InventoryAdjustmentInput, idempotencyKey: string) {
  return featureApiMutation<unknown>(`${hotelPath(hotelId)}/movements`, identity, {
    method: 'POST', body: input, idempotencyKey,
  })
}

export async function loadFixedAssets(identity: RoleContext, hotelId: string, signal?: AbortSignal) {
  return parseFixedAssets(await featureApiRequest<unknown>(`${hotelPath(hotelId)}/assets?includeRetired=true`, identity, { signal }))
}

export async function createFixedAsset(identity: RoleContext, hotelId: string, input: FixedAssetInput, idempotencyKey: string) {
  return parseFixedAsset(await featureApiMutation<unknown>(`${hotelPath(hotelId)}/assets`, identity, {
    method: 'POST', body: input, idempotencyKey,
  }))
}

export async function updateFixedAsset(identity: RoleContext, hotelId: string, assetId: string, input: FixedAssetInput, expectedVersion: number, idempotencyKey: string) {
  return parseFixedAsset(await featureApiMutation<unknown>(`${hotelPath(hotelId)}/assets/${encodeURIComponent(assetId)}`, identity, {
    method: 'PUT', body: input, expectedVersion, idempotencyKey,
  }))
}

export async function retireFixedAsset(identity: RoleContext, hotelId: string, assetId: string, idempotencyKey: string) {
  return featureApiMutation<void>(`${hotelPath(hotelId)}/assets/${encodeURIComponent(assetId)}`, identity, {
    method: 'DELETE', idempotencyKey,
  })
}

export async function loadHandoverParticipants(identity: RoleContext, hotelId: string, signal?: AbortSignal) {
  return parseHandoverParticipants(await featureApiRequest<unknown>(`${hotelPath(hotelId)}/assignment-options`, identity, { signal }))
}

export async function loadStoreResourceHandovers(identity: RoleContext, hotelId: string, signal?: AbortSignal) {
  return parseStoreResourceHandovers(await featureApiRequest<unknown>(`${hotelPath(hotelId)}/handovers`, identity, { signal }))
}

export async function createStoreResourceHandover(identity: RoleContext, hotelId: string, input: StoreResourceHandoverInput, idempotencyKey: string) {
  return parseStoreResourceHandover(await featureApiMutation<unknown>(`${hotelPath(hotelId)}/handovers`, identity, {
    method: 'POST', body: input, idempotencyKey,
  }))
}

const handoverPath = (handoverId: string) => `/store-resources/handovers/${encodeURIComponent(handoverId)}`

export async function loadStoreResourceHandover(identity: RoleContext, handoverId: string, signal?: AbortSignal) {
  return parseStoreResourceHandoverDetail(await featureApiRequest<unknown>(handoverPath(handoverId), identity, { signal }))
}

export async function decideStoreResourceHandoverDispute(identity: RoleContext, handoverId: string, hasDispute: boolean, reason: string | undefined, expectedVersion: number, idempotencyKey: string) {
  return parseStoreResourceHandover(await featureApiMutation<unknown>(`${handoverPath(handoverId)}/actions/dispute-decision`, identity, {
    method: 'POST', body: { hasDispute, reason }, expectedVersion, idempotencyKey,
  }))
}

export async function submitStoreResourcePhysicalCount(identity: RoleContext, handoverId: string, lines: Array<{ lineId: string; physicalQuantity?: number; physicalState?: string; differenceReason?: string }>, note: string | undefined, expectedVersion: number, idempotencyKey: string) {
  return parseStoreResourceHandover(await featureApiMutation<unknown>(`${handoverPath(handoverId)}/actions/physical-count`, identity, {
    method: 'POST', body: { lines, note }, expectedVersion, idempotencyKey,
  }))
}

export async function confirmStoreResourceHandover(identity: RoleContext, handoverId: string, party: 'transferor' | 'recipient', expectedVersion: number, idempotencyKey: string) {
  return parseStoreResourceHandover(await featureApiMutation<unknown>(`${handoverPath(handoverId)}/actions/${party}-confirm`, identity, {
    method: 'POST', body: {}, expectedVersion, idempotencyKey,
  }))
}

export async function approveStoreResourceHandover(identity: RoleContext, handoverId: string, approved: boolean, reason: string | undefined, expectedVersion: number, idempotencyKey: string) {
  return parseStoreResourceHandover(await featureApiMutation<unknown>(`${handoverPath(handoverId)}/actions/approve`, identity, {
    method: 'POST', body: { approved, reason }, expectedVersion, idempotencyKey,
  }))
}
