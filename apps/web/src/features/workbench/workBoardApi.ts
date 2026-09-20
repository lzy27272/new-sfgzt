import { apiRequest, type ApiIdentity } from '../../api/client'
import { normalizeExpectation } from '../../api/resources'
import type { WorkBoard } from './workBoardModel'
import { boardQuery } from './workBoardModel'
import type { RouteParams } from '../../domain'

export async function loadWorkBoard(identity: ApiIdentity, scope: 'my' | 'team', params: RouteParams, today?: string) {
  const query = boardQuery(params, today)
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (key !== 'mode' && value !== undefined) search.set(key, String(value))
  }
  const response = await apiRequest<Omit<WorkBoard, 'items' | 'recentSubmissions' | 'upcomingItems'> & { items: Record<string, unknown>[]; recentSubmissions: Record<string, unknown>[]; upcomingItems: Record<string, unknown>[] }>(
    '/' + scope + '/work-board?' + search.toString(), identity)
  return { data: { ...response, items: response.items.map(normalizeExpectation),
    recentSubmissions: (response.recentSubmissions ?? []).map(normalizeExpectation),
    upcomingItems: (response.upcomingItems ?? []).map(normalizeExpectation) }, source: 'api' as const }
}
