import type { NavigationParams, RouteParams } from '../domain'
import type { AppRouteId, AppSectionId } from './routeConfig'

export type NavigationCenterId = 'workbench' | 'operations' | 'people' | 'performance' | 'configuration'

export type NavigationFeature = {
  id: AppRouteId
  sectionId?: string
  label: string
  icon: string
  group?: string
}

export type CenterNavigationItem = NavigationFeature & { params?: NavigationParams }
export type NavigationCenter = {
  id: NavigationCenterId
  label: string
  shortLabel: string
  items: CenterNavigationItem[]
}

const centerDefinitions: readonly {
  id: NavigationCenterId
  label: string
  modules: readonly AppRouteId[]
}[] = [
  { id: 'workbench', label: '工作台', modules: ['workbench', 'my-work', 'daily-reports-my', 'team-work', 'tasks', 'notifications'] },
  { id: 'operations', label: '经营中心', modules: ['daily-operations', 'investments', 'store-resources', 'ai-data'] },
  { id: 'people', label: '人事中心', modules: ['organization', 'wecom-onboarding', 'wecom-bindings'] },
  { id: 'performance', label: '绩效中心', modules: ['kpi-center', 'evaluations'] },
  { id: 'configuration', label: '配置中心', modules: ['templates', 'work-packages', 'daily-report-templates', 'rules', 'organization', 'wecom-webhooks', 'data-retention'] },
]

/** Presentation only: callers supply modules that have passed both permission and visibility checks. */
export function buildNavigationCenters(visibleFeatures: readonly NavigationFeature[], roleLabel: string): NavigationCenter[] {
  const features = new Map(visibleFeatures.map((feature) => [feature.id, feature]))
  return centerDefinitions.flatMap((definition) => {
    const items = definition.modules.flatMap((id): CenterNavigationItem[] => {
      const feature = features.get(id)
      if (!feature) return []
      if (id === 'organization' && definition.id === 'configuration') {
        return [{ ...feature, label: '组织与权限', params: { center: 'configuration' } }]
      }
      const label = id === 'workbench' ? '工作总览'
        : id === 'daily-reports-my' ? '我的日报'
        : id === 'work-packages' ? '岗位工作模板'
        : id === 'organization' ? '组织与人员'
        : feature.label
      return [{ ...feature, label }]
    })
    return items.length ? [{
      id: definition.id,
      label: definition.id === 'workbench' ? `${roleLabel}工作台` : definition.label,
      shortLabel: definition.label,
      items,
    }] : []
  })
}

/** Existing hash routes keep their identifiers; detail routes already resolve to these sections. */
export function navigationCenterForSection(sectionId: AppSectionId, params: RouteParams = {}): NavigationCenterId {
  if (sectionId === 'organization' && params.center === 'configuration') return 'configuration'
  if (['daily-operations', 'investments', 'store-resources', 'ai-data'].includes(sectionId)) return 'operations'
  if (['organization', 'wecom-onboarding', 'wecom-bindings'].includes(sectionId)) return 'people'
  if (['kpi', 'evaluations'].includes(sectionId)) return 'performance'
  if (['templates', 'work-packages', 'daily-report-templates', 'rules', 'wecom-webhooks', 'data-retention'].includes(sectionId)) return 'configuration'
  return 'workbench'
}

export function isCenterNavigationItemActive(item: CenterNavigationItem, sectionId: AppSectionId): boolean {
  if (item.id === 'workbench' && ['hotel-dashboard', 'operations-dashboard', 'all-functions', 'account-self-service'].includes(sectionId)) return true
  return (item.sectionId ?? item.id) === sectionId
}

export function canOpenStoreResources(permissions: readonly string[], visibleFeatures: readonly Pick<NavigationFeature, 'id'>[]): boolean {
  return (permissions.includes('*') || permissions.includes('store-resource.read'))
    && visibleFeatures.some((feature) => feature.id === 'store-resources')
}
