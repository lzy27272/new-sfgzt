import { apiRequest } from '../../api/client'
import type { RoleContext } from '../../domain'
import {
  parseDataRetentionPolicy,
  parseDataRetentionPreview,
  type DataRetentionPolicy,
  type DataRetentionPreview,
  type DataRetentionUpdateRequest,
} from './policy'

const policyPath = '/admin/data-retention-policy'
const previewPath = '/admin/data-retention-preview'

export async function loadDataRetentionPolicy(
  identity: RoleContext,
  signal?: AbortSignal,
): Promise<DataRetentionPolicy> {
  return parseDataRetentionPolicy(await apiRequest<unknown>(policyPath, identity, { signal }))
}

export async function updateDataRetentionPolicy(
  identity: RoleContext,
  request: DataRetentionUpdateRequest,
): Promise<DataRetentionPolicy> {
  return parseDataRetentionPolicy(await apiRequest<unknown>(policyPath, identity, {
    method: 'PUT',
    body: JSON.stringify(request),
  }))
}

export async function loadDataRetentionPreview(
  identity: RoleContext,
  signal?: AbortSignal,
): Promise<DataRetentionPreview> {
  return parseDataRetentionPreview(await apiRequest<unknown>(previewPath, identity, { signal }))
}
