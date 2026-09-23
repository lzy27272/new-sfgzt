export type UsageScopeOption = { id: string; code: string; name: string }
export type UsageScopeOptions = { departments: UsageScopeOption[]; positions: UsageScopeOption[] }
export type ItemUsageScope = {
  mode: 'LEGACY' | 'ALL_DEPARTMENTS' | 'DEPARTMENTS'
  departmentIds: string[]
  positionIds: string[]
  departments?: UsageScopeOption[]
  positions?: UsageScopeOption[]
}

export function usageScopeValidation(scope: ItemUsageScope, options: UsageScopeOptions): string | undefined {
  if (scope.mode === 'LEGACY') return
  if (scope.mode === 'DEPARTMENTS' && !scope.departmentIds.length) return '请至少选择一个适用部门，或明确选择全店通用。'
  if (scope.departmentIds.length > 200 || scope.positionIds.length > 200) return '部门和岗位分别最多选择200项。'
  if (scope.departmentIds.some(id => !options.departments.some(option => option.id === id)) || scope.positionIds.some(id => !options.positions.some(option => option.id === id))) return '所选部门或岗位已不可用，请重新选择后保存。'
}

export function usageScopeSummary(scope?: ItemUsageScope, options?: UsageScopeOptions): { departments: string; positions: string } {
  if (!scope || scope.mode === 'LEGACY') return { departments: '使用范围待配置', positions: '保留原有领用及常用品行为' }
  const names = (ids: string[], current: UsageScopeOption[], saved: UsageScopeOption[]) => ids.map(id => current.find(o => o.id === id)?.name ?? saved.find(o => o.id === id)?.name ?? '未找到的配置项').join('、')
  return {
    departments: scope.mode === 'ALL_DEPARTMENTS' ? '全店通用' : names(scope.departmentIds, options?.departments ?? [], scope.departments ?? []) || '尚未选择部门',
    positions: scope.positionIds.length ? names(scope.positionIds, options?.positions ?? [], scope.positions ?? []) : '所选部门内全部岗位',
  }
}
