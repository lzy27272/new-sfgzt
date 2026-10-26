import type { DirectoryCandidate, DirectoryOnboardingStatus } from './directoryOnboardingApi'

export type DirectoryCandidateStatusFilter = DirectoryOnboardingStatus | 'PENDING' | ''

export function isPendingDirectoryCandidate(item: DirectoryCandidate): boolean {
  return item.status === 'PENDING_APPROVAL' || item.status === 'CONFLICT'
}

export function directoryCandidateStoreKey(item: DirectoryCandidate): string {
  if (item.requestedHotelId) return `id:${item.requestedHotelId}`
  // Older API responses have only the already-resolved hotel name, not the department ID.
  return item.requestedHotelName ? `name:${item.requestedHotelName}` : 'unassigned'
}

export function directoryCandidateStores(items: DirectoryCandidate[]): { key: string; name: string }[] {
  return Array.from(new Map(items.map(item => [directoryCandidateStoreKey(item), {
    key: directoryCandidateStoreKey(item), name: item.requestedHotelName || '未选择门店',
  }])).values()).sort((a, b) => Number(a.key === 'unassigned') - Number(b.key === 'unassigned') || a.name.localeCompare(b.name, 'zh-CN'))
}

export function selectDirectoryCandidates(
  items: DirectoryCandidate[], status: DirectoryCandidateStatusFilter, storeKey: string, query: string,
): DirectoryCandidate[] {
  const normalized = query.trim().toLowerCase()
  return items.filter(item =>
    (!status || (status === 'PENDING' ? isPendingDirectoryCandidate(item) : item.status === status))
    && (!storeKey || directoryCandidateStoreKey(item) === storeKey)
    && (!normalized || [item.displayName, item.requestedLoginName, item.requestedHotelName,
      item.requestedDepartmentName, item.requestedPositionName].filter(Boolean).join(' ').toLowerCase().includes(normalized)),
  ).sort((a, b) => Number(isPendingDirectoryCandidate(b)) - Number(isPendingDirectoryCandidate(a))
    || (Date.parse(b.updatedAt) || 0) - (Date.parse(a.updatedAt) || 0) || a.id.localeCompare(b.id))
}
