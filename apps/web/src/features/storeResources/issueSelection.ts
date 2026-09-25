import type { UsageItem } from './workUsage.ts'

export const issueRanges = { MINE: '我的范围', DEPARTMENT: '跨部门领用', HOTEL: '全店物资' } as const
export type IssueRange = keyof typeof issueRanges
export function matchesIssueRange(item: UsageItem, range: IssueRange, departmentId: string) {
  if (range === 'HOTEL') return true // Server has already limited the catalogue to the current hotel.
  if (range === 'DEPARTMENT') return !!departmentId && (item.departmentIds ?? []).includes(departmentId)
  return item.applicableToAssignment !== false // Compatible with an older, own-scope-only response.
}
export function issueDepartmentLabel(item: UsageItem, departments: Array<{ id: string; name: string }> = []) {
  return item.usageScopeMode === 'ALL_DEPARTMENTS' ? '全店通用' : (item.departmentIds ?? []).map(id => departments.find(d => d.id === id)?.name ?? '原配置部门').join('、') || '本岗位适用'
}
