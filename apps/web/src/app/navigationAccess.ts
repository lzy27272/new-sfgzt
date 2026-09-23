import { permissions as permissionCodes } from './permissions'
import { isDailyFeatureRoute, isInvestmentFeatureRoute, type AppRouteId } from './routeConfig'
import { applyPublishedModuleVisibility } from './rolePresentationPolicy'

export const navigation: Array<{ id: AppRouteId; sectionId?: string; label: string; icon: string; group?: string; permissions?: string[]; administratorOnly?: boolean }> = [
  { id: 'workbench', label: '角色工作台', icon: '⌂' },
  { id: 'hotel-dashboard', label: '门店工作台', icon: '▤', permissions: ['dashboard.hotel'] },
  { id: 'operations-dashboard', label: '多门店工作台', icon: '▥', permissions: ['dashboard.operations'] },
  { id: 'investments', label: '投资测算', icon: '¥', group: '投资决策', permissions: [permissionCodes.investment.read] },
  { id: 'work-packages', label: '岗位工作模板', icon: '▦', group: '标准与工作', permissions: ['work-package.read', 'work-package.manage', 'standard.read'] },
  { id: 'my-work', label: '我的工作', icon: '✓', permissions: ['work-record.read', 'work-record.submit', 'work.submit'] },
  { id: 'team-work', label: '团队工作', icon: '◎', permissions: [permissionCodes.workRecord.readTeam, 'work-record.review'] },
  { id: 'daily-reports-my', sectionId: 'daily-reports', label: '日报中心', icon: '▣', group: '日报与运营', permissions: [permissionCodes.dailyReport.readOwn, permissionCodes.dailyReport.submit, permissionCodes.dailyReport.readTeam] },
  { id: 'daily-operations', label: '日运营中心', icon: '◫', permissions: [permissionCodes.dailyOperations.readHotel, permissionCodes.dailyOperations.readCrossHotel] },
  { id: 'kpi-center', sectionId: 'kpi', label: 'KPI绩效中心', icon: '◎', group: '行政人事', permissions: [permissionCodes.kpi.scorecardReadOwn, permissionCodes.kpi.scorecardReadTeam, permissionCodes.kpi.scorecardReadAll, permissionCodes.kpi.templateRead] },
  { id: 'rules', label: '企业规则中心', icon: '◇', group: '管理闭环', permissions: ['rule.read', 'rule.manage'] },
  { id: 'tasks', label: '任务中心', icon: '↗', permissions: ['task.read', 'task.act', 'task.review', permissionCodes.executiveTask.read, permissionCodes.executiveTask.assign] },
  { id: 'evaluations', label: '标准评价', icon: '★', permissions: ['evaluation.read', 'evaluation.manual-review'] },
  { id: 'notifications', label: '通知中心', icon: '◉', permissions: ['notification.read'] },
  { id: 'templates', label: '集团模板配置', icon: '▧', group: '系统配置', permissions: ['template.manage'] },
  { id: 'daily-report-templates', label: '日报模板中心', icon: '▤', permissions: [permissionCodes.dailyReportTemplate.read, permissionCodes.dailyReportTemplate.edit, permissionCodes.dailyReportTemplate.publish] },
  { id: 'organization', label: '组织与权限', icon: '⚙', group: '系统配置', permissions: ['org.manage', 'position-profile.read'] },
  { id: 'store-resources', label: '库存与固定资产', icon: '库', group: '系统配置', permissions: [permissionCodes.storeResource.read] },
  { id: 'finance', label: '报销与费用', icon: '¥', group: '财务中心', permissions: ['finance.submit', 'finance.review', 'finance.read', 'finance.pay', 'finance.configure'] },
  { id: 'procurement', label: '采购与收货', icon: '购', permissions: ['procurement.read', 'procurement.request', 'procurement.configure'] },
  { id: 'data-retention', label: '数据留存策略', icon: '◷', group: '系统配置', administratorOnly: true },
  { id: 'ai-data', label: 'AI 数据与复盘', icon: '◈', permissions: ['ui.module.ai-data'] },
  { id: 'wecom-webhooks', label: '企业微信 Webhook', icon: '◉', group: '系统配置', permissions: ['org.manage'] },
  { id: 'wecom-onboarding', label: '企业微信入职审核', icon: '人', group: '系统配置', permissions: [permissionCodes.wecomBinding.read] },
  { id: 'wecom-bindings', label: '企微绑定与异常', icon: '◎', permissions: [permissionCodes.wecomBinding.read] },
]

export type NavigationAccessContext = {
  permissions: readonly string[]
  roleCode: string
  hasAssignment: boolean
  executiveTasksEnabled: boolean
  isPlatformAdmin: boolean
  isDataRetentionAdministrator: boolean
  demo?: boolean
}

/** Shared by the live navigation and the position draft preview; never grants API access. */
export function visibleNavigationForContext(context: NavigationAccessContext) {
  const { permissions, roleCode, hasAssignment, executiveTasksEnabled, isPlatformAdmin, isDataRetentionAdministrator, demo } = context
  const permissionVisible = navigation.filter((item) => {
    if (item.administratorOnly && !isDataRetentionAdministrator) return false
    if (['hotel-dashboard', 'operations-dashboard'].includes(item.id)) return false
    if (['rules', 'work-packages'].includes(item.id) && !isPlatformAdmin) return false
    if (item.id === 'my-work' && !hasAssignment) return false
    if (item.id === 'tasks' && roleCode === 'GROUP_CHAIRMAN') {
      return executiveTasksEnabled && hasAssignment &&
        (permissions.includes('*') || permissions.includes(permissionCodes.executiveTask.read) || permissions.includes(permissionCodes.executiveTask.assign))
    }
    return !item.permissions?.length ||
      (demo && !isDailyFeatureRoute(item.id) && !isInvestmentFeatureRoute(item.id)) ||
      permissions.includes('*') || item.permissions.some((permission) => permissions.includes(permission))
  })
  const published = applyPublishedModuleVisibility(permissionVisible, permissions, roleCode)
  if (!isDataRetentionAdministrator || published.some((item) => item.id === 'data-retention')) return published
  const visibleIds = new Set(published.map((item) => item.id))
  return navigation.filter((item) => visibleIds.has(item.id) || item.id === 'data-retention')
}
