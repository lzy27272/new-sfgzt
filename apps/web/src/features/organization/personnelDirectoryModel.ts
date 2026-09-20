export type DirectoryOrg = { id: string; parentId?: string; name: string; unitType: string; status: string; city?: string }
export type DirectoryEmployee = {
  id: string; name: string; loginName?: string; employmentStatus: string; accountStatus?: string
  orgUnitId?: string; orgUnitName?: string; positionId?: string; positionName?: string; assignmentId?: string
  assignmentStatus?: string; primary?: boolean; validFrom?: string; validTo?: string
  responsibleHotelNames?: string
}
export function currentAssignment(item: DirectoryEmployee, today: string) {
  return Boolean(item.assignmentId && item.employmentStatus === 'ACTIVE'
    && (!item.accountStatus || item.accountStatus === 'ACTIVE')
    && (!item.assignmentStatus || item.assignmentStatus === 'ACTIVE')
    && (!item.validFrom || item.validFrom <= today) && (!item.validTo || item.validTo >= today))
}
export function personnelScope(item: DirectoryEmployee, orgs: DirectoryOrg[]) {
  const byId = new Map(orgs.map(org => [org.id, org]))
  let org = item.orgUnitId ? byId.get(item.orgUnitId) : undefined
  const visited = new Set<string>()
  let headOffice: string | undefined
  while (org && !visited.has(org.id)) {
    visited.add(org.id)
    if (org.unitType === 'HOTEL') return org.id
    if (org.unitType === 'GROUP') headOffice = org.id
    const parent = org.parentId ? byId.get(org.parentId) : undefined
    if (!parent && !headOffice) headOffice = org.id
    org = parent
  }
  return headOffice ?? 'unassigned'
}
export function groupPersonnel<T extends DirectoryEmployee>(items: T[], orgs: DirectoryOrg[], scope: string, today: string, history = false) {
  const currentEmployeeIds = new Set(items.filter(item => currentAssignment(item, today)).map(item => item.id))
  const groups = new Map<string, T[]>()
  for (const item of items) {
    const unassigned = !currentEmployeeIds.has(item.id)
    const included = scope === 'unassigned' ? unassigned
      : personnelScope(item, orgs) === scope && (history || currentAssignment(item, today))
    if (included) {
      const rows = groups.get(item.id) ?? []
      if (!rows.some(row => row.assignmentId === item.assignmentId)) rows.push(item)
      groups.set(item.id, rows)
    }
  }
  return [...groups.values()].sort((a, b) => a[0].name.localeCompare(b[0].name, 'zh-CN'))
}
