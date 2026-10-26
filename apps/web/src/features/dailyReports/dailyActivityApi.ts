import type { ApiIdentity } from '../../api/client'
import { featureApiMutation, featureApiRequest } from '../shared/featureApi'
import { normalizeDetail } from './api'
import type { LostPropertyEntry } from './housekeepingActivities'

export type RoutedLostProperty = {
  id: string; entryId: string; state: 'PENDING' | 'RETURNED' | 'IN_CUSTODY' | 'COLLECTED';
  rowVersion: number; payload: LostPropertyEntry
}

export function loadReportLostProperties(identity: ApiIdentity, reportId: string, signal?: AbortSignal) {
  return featureApiRequest<RoutedLostProperty[]>(`/daily-reports/${encodeURIComponent(reportId)}/lost-property-entries`, identity, { signal })
}

export async function submitLostPropertyEntry(identity: ApiIdentity, reportId: string, entry: LostPropertyEntry, expectedVersion: number, key: string) {
  const result = await featureApiMutation<unknown>(`/daily-reports/${encodeURIComponent(reportId)}/lost-property-entries/${encodeURIComponent(entry.id)}/submit`, identity, {
    body: { expectedVersion, entry }, idempotencyKey: key,
  })
  return normalizeDetail(result, identity)
}
