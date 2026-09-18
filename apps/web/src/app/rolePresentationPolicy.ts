import type { AppRouteId } from './routeConfig'

export type RolePresentationKey =
  | 'EMPLOYEE'
  | 'OTA_ASSISTANT'
  | 'SUPERVISOR'
  | 'ASSISTANT_MANAGER'
  | 'HOTEL_MANAGER'
  | 'REGIONAL_OPERATIONS'
  | 'HR'
  | 'HR_ADMINISTRATION_SUPERVISOR'
  | 'HR_ADMINISTRATION'
  | 'GROUP_CHAIRMAN'
  | 'GROUP_VICE_PRESIDENT'
  | 'CEO'
  | 'PLATFORM_ADMIN'
  | 'GENERIC'

export type MobilePresentationTab = Readonly<{
  slot: 'primary' | 'secondary' | 'domain' | 'notifications' | 'profile'
  label: string
  target: MobilePresentationTarget
}>

export type MobilePresentationTarget =
  | 'workbench'
  | 'my-work'
  | 'tasks'
  | 'daily-reports-my'
  | 'team-work'
  | 'daily-operations'
  | 'hotel-dashboard'
  | 'operations-dashboard'
  | 'work-packages'
  | 'rules'
  | 'organization'
  | 'wecom-onboarding'
  | 'wecom-bindings'
  | 'kpi-center'
  | 'investments'
  | 'notifications'
  | 'all-functions'
  | 'account-self-service'

export type RolePresentationPolicy = Readonly<{
  key: RolePresentationKey
  knownRole: boolean
  focus: string
  desktopModuleIds: readonly AppRouteId[] | null
  mobileTabs: readonly MobilePresentationTab[]
}>

const tabs = (
  domainLabel: string,
  domainTarget: MobilePresentationTarget,
  reportLabel = '日报',
  reportTarget: MobilePresentationTarget = 'daily-reports-my',
): readonly MobilePresentationTab[] => Object.freeze([
  Object.freeze({ slot: 'primary', label: '工作台', target: 'workbench' }),
  Object.freeze({ slot: 'secondary', label: reportLabel, target: reportTarget }),
  Object.freeze({ slot: 'domain', label: domainLabel, target: domainTarget }),
  Object.freeze({ slot: 'notifications', label: 'KPI', target: 'kpi-center' }),
  Object.freeze({ slot: 'profile', label: '我的', target: 'all-functions' }),
])

const modules = (...ids: AppRouteId[]): readonly AppRouteId[] => Object.freeze(ids)

const chairmanTabs = (): readonly MobilePresentationTab[] => Object.freeze([
  Object.freeze({ slot: 'primary', label: '工作台', target: 'workbench' }),
  Object.freeze({ slot: 'secondary', label: '日报', target: 'daily-reports-my' }),
  Object.freeze({ slot: 'domain', label: '日运营', target: 'daily-operations' }),
  Object.freeze({ slot: 'notifications', label: '消息', target: 'notifications' }),
  Object.freeze({ slot: 'profile', label: '我的', target: 'account-self-service' }),
])

const platformTabs = (): readonly MobilePresentationTab[] => Object.freeze([
  Object.freeze({ slot: 'primary', label: '工作台', target: 'workbench' }),
  Object.freeze({ slot: 'secondary', label: '工作包', target: 'work-packages' }),
  Object.freeze({ slot: 'domain', label: '系统配置', target: 'organization' }),
  Object.freeze({ slot: 'notifications', label: '规则', target: 'rules' }),
  Object.freeze({ slot: 'profile', label: '我的', target: 'all-functions' }),
])

const hrTabs = (
  domainLabel: string,
  domainTarget: MobilePresentationTarget,
  performanceTarget = true,
): readonly MobilePresentationTab[] => Object.freeze([
  Object.freeze({ slot: 'primary', label: '工作台', target: 'workbench' }),
  Object.freeze({ slot: 'secondary', label: '人员', target: 'organization' }),
  Object.freeze({ slot: 'domain', label: domainLabel, target: domainTarget }),
  Object.freeze({
    slot: 'notifications',
    label: performanceTarget ? 'KPI' : '消息',
    target: performanceTarget ? 'kpi-center' : 'notifications',
  }),
  Object.freeze({ slot: 'profile', label: '我的', target: 'all-functions' }),
])

const POLICIES: Readonly<Record<RolePresentationKey, RolePresentationPolicy>> = Object.freeze({
  EMPLOYEE: Object.freeze({
    key: 'EMPLOYEE',
    knownRole: true,
    focus: '本人任务、岗位日报、岗位标准与个人KPI',
    desktopModuleIds: modules(
      'workbench', 'my-work', 'tasks', 'daily-reports-my', 'evaluations',
      'kpi-center', 'notifications', 'all-functions',
    ),
    mobileTabs: tabs('工作', 'my-work'),
  }),
  OTA_ASSISTANT: Object.freeze({
    key: 'OTA_ASSISTANT',
    knownRole: true,
    focus: '已分配门店的OTA巡检、日报与个人KPI',
    desktopModuleIds: modules(
      'workbench', 'my-work', 'tasks', 'daily-reports-my', 'kpi-center',
      'notifications', 'all-functions',
    ),
    mobileTabs: tabs('工作', 'my-work'),
  }),
  SUPERVISOR: Object.freeze({
    key: 'SUPERVISOR',
    knownRole: true,
    focus: '部门团队任务、日报、评价、KPI与异常',
    desktopModuleIds: modules(
      'workbench', 'my-work', 'team-work', 'tasks', 'daily-reports-my',
      'daily-operations', 'evaluations', 'kpi-center', 'notifications',
      'all-functions',
    ),
    mobileTabs: tabs('团队', 'team-work'),
  }),
  ASSISTANT_MANAGER: Object.freeze({
    key: 'ASSISTANT_MANAGER',
    knownRole: true,
    focus: '门店团队、日运营、异常确认与跨部门协调',
    desktopModuleIds: modules(
      'workbench', 'hotel-dashboard', 'team-work', 'tasks', 'daily-reports-my',
      'daily-report-templates', 'daily-operations', 'evaluations', 'kpi-center',
      'notifications', 'all-functions',
    ),
    mobileTabs: tabs('日运营', 'daily-operations'),
  }),
  HOTEL_MANAGER: Object.freeze({
    key: 'HOTEL_MANAGER',
    knownRole: true,
    focus: '全店任务、日报、经营指标与验收',
    desktopModuleIds: modules(
      'workbench', 'hotel-dashboard', 'my-work', 'team-work', 'tasks', 'daily-reports-my',
      'daily-operations', 'kpi-center', 'evaluations', 'notifications',
      'all-functions',
    ),
    mobileTabs: tabs('日运营', 'daily-operations'),
  }),
  REGIONAL_OPERATIONS: Object.freeze({
    key: 'REGIONAL_OPERATIONS',
    knownRole: true,
    focus: '授权区域的跨店任务、日运营、OTA巡检与汇总',
    desktopModuleIds: modules(
      'workbench', 'operations-dashboard', 'tasks', 'daily-reports-my',
      'daily-operations', 'kpi-center', 'notifications', 'all-functions',
    ),
    mobileTabs: tabs('日运营', 'daily-operations'),
  }),
  HR: Object.freeze({
    key: 'HR',
    knownRole: true,
    focus: '人员任职、企微绑定、KPI考核与人事审计',
    desktopModuleIds: modules(
      'workbench', 'tasks', 'organization', 'wecom-bindings', 'wecom-onboarding',
      'kpi-center', 'notifications', 'all-functions',
    ),
    mobileTabs: hrTabs('入职', 'wecom-onboarding'),
  }),
  HR_ADMINISTRATION_SUPERVISOR: Object.freeze({
    key: 'HR_ADMINISTRATION_SUPERVISOR',
    knownRole: true,
    focus: '行政人事团队工作、任务审核与计划审批',
    desktopModuleIds: modules(
      'workbench', 'team-work', 'tasks', 'organization', 'wecom-bindings',
      'wecom-onboarding', 'notifications', 'all-functions',
    ),
    mobileTabs: hrTabs('团队', 'team-work', false),
  }),
  HR_ADMINISTRATION: Object.freeze({
    key: 'HR_ADMINISTRATION',
    knownRole: true,
    focus: '行政人事任务执行与个人工作',
    desktopModuleIds: modules(
      'workbench', 'tasks', 'organization', 'wecom-bindings',
      'wecom-onboarding', 'notifications', 'all-functions',
    ),
    mobileTabs: hrTabs('企微绑定', 'wecom-bindings', false),
  }),
  GROUP_CHAIRMAN: Object.freeze({
    key: 'GROUP_CHAIRMAN',
    knownRole: true,
    focus: '集团全局工作观察与总经理、副总经理任务派发',
    desktopModuleIds: modules(
      'workbench', 'hotel-dashboard', 'operations-dashboard', 'team-work',
      'tasks', 'daily-reports-my', 'daily-operations', 'evaluations',
      'notifications',
    ),
    mobileTabs: chairmanTabs(),
  }),
  GROUP_VICE_PRESIDENT: Object.freeze({
    key: 'GROUP_VICE_PRESIDENT',
    knownRole: true,
    focus: '授权范围的集团经营、跨店任务与日报',
    desktopModuleIds: modules(
      'workbench', 'operations-dashboard', 'tasks', 'daily-reports-my',
      'daily-operations', 'kpi-center',
      'notifications', 'all-functions',
    ),
    mobileTabs: tabs('日运营', 'daily-operations'),
  }),
  CEO: Object.freeze({
    key: 'CEO',
    knownRole: true,
    focus: '集团经营决策、投资测算与重大审批',
    desktopModuleIds: modules(
      'workbench', 'hotel-dashboard', 'operations-dashboard', 'investments',
      'team-work', 'tasks', 'daily-reports-my',
      'daily-report-templates', 'daily-operations', 'kpi-center',
      'evaluations', 'templates',
      'organization', 'wecom-bindings', 'wecom-onboarding', 'notifications',
      'all-functions',
    ),
    mobileTabs: tabs('日运营', 'daily-operations'),
  }),
  PLATFORM_ADMIN: Object.freeze({
    key: 'PLATFORM_ADMIN',
    knownRole: true,
    focus: '岗位功能、组织权限、系统配置、审计与故障处理',
    desktopModuleIds: modules(
      'workbench', 'hotel-dashboard', 'operations-dashboard', 'investments',
      'work-packages', 'my-work', 'team-work', 'tasks', 'daily-reports-my',
      'daily-report-templates', 'daily-operations', 'kpi-center', 'rules',
      'evaluations', 'templates', 'organization', 'wecom-webhooks',
      'wecom-bindings', 'wecom-onboarding', 'notifications', 'all-functions',
      'store-resources',
    ),
    mobileTabs: platformTabs(),
  }),
  GENERIC: Object.freeze({
    key: 'GENERIC',
    knownRole: false,
    focus: '当前任职允许的工作与业务功能',
    desktopModuleIds: null,
    mobileTabs: tabs('工作', 'my-work'),
  }),
})

const ROLE_ALIASES: Readonly<Record<Exclude<RolePresentationKey, 'GENERIC'>, readonly string[]>> = Object.freeze({
  EMPLOYEE: Object.freeze([
    'FRONT_DESK', 'FRONT_DESK_EMPLOYEE', 'front-desk', '前台员工', '前台',
    'HOUSEKEEPING_ATTENDANT', 'ROOM_ATTENDANT', 'housekeeping-attendant', '客房服务员', '客房员工',
  ]),
  OTA_ASSISTANT: Object.freeze([
    'OTA_OPERATION_ASSISTANT', 'OTA_ASSISTANT', 'ota-assistant', 'OTA运营助理',
  ]),
  SUPERVISOR: Object.freeze([
    'FRONT_OFFICE_SUPERVISOR', 'FRONT_SUPERVISOR', 'front-supervisor', '前厅主管',
    'HOUSEKEEPING_SUPERVISOR', 'HK_SUPERVISOR', 'housekeeping-supervisor', '客房主管',
  ]),
  ASSISTANT_MANAGER: Object.freeze([
    'ASSISTANT_GENERAL_MANAGER', 'ASSISTANT_GM', 'assistant-gm', '店助', '店长助理',
  ]),
  HOTEL_MANAGER: Object.freeze([
    'GENERAL_MANAGER', 'HOTEL_GENERAL_MANAGER', 'HOTEL_MANAGER', 'STORE_MANAGER',
    'general-manager', '店长', '店总',
  ]),
  REGIONAL_OPERATIONS: Object.freeze([
    'OTA_OPERATION_MANAGER', 'ota-operation-manager', 'OTA运营经理',
  ]),
  HR: Object.freeze([
    'HR_KPI_ADMIN', 'hr-kpi-admin', '行政人事KPI管理员',
  ]),
  HR_ADMINISTRATION_SUPERVISOR: Object.freeze([
    'HR_ADMINISTRATION_SUPERVISOR', 'hr-administration-supervisor', '行政人事主管',
  ]),
  HR_ADMINISTRATION: Object.freeze([
    'HR_ADMINISTRATION', 'hr-administration', '行政人事',
  ]),
  GROUP_CHAIRMAN: Object.freeze([
    'GROUP_CHAIRMAN', 'group-chairman', '集团董事长', '董事长',
  ]),
  GROUP_VICE_PRESIDENT: Object.freeze([
    'GROUP_VICE_PRESIDENT', 'GROUP_VP', 'group-vice-president', '集团副总',
  ]),
  CEO: Object.freeze(['CEO', 'GROUP_CEO', 'GROUP_GENERAL_MANAGER', 'ceo', '集团CEO', '集团总经理']),
  PLATFORM_ADMIN: Object.freeze([
    'PLATFORM_ADMIN', 'GROUP_ADMIN', 'SYSTEM_ADMIN', 'platform-admin', '平台管理员', '系统管理员',
  ]),
})

function normalizeAlias(value: string): string {
  return value.trim().replace(/[\s\-/／]+/g, '_').toUpperCase()
}

const ALIAS_TO_POLICY = new Map<string, Exclude<RolePresentationKey, 'GENERIC'>>()
Object.entries(ROLE_ALIASES).forEach(([key, aliases]) => {
  aliases.forEach((alias) => ALIAS_TO_POLICY.set(
    normalizeAlias(alias),
    key as Exclude<RolePresentationKey, 'GENERIC'>,
  ))
})

const DEFAULT_MODULE_SETS = new Map<RolePresentationKey, ReadonlySet<AppRouteId>>(
  Object.values(POLICIES)
    .filter((policy) => policy.desktopModuleIds !== null)
    .map((policy) => [policy.key, new Set(policy.desktopModuleIds ?? [])]),
)

export const MODULE_PERMISSION_PREFIX = 'ui.module.'

export function modulePermissionCode(moduleId: AppRouteId): string {
  return `${MODULE_PERMISSION_PREFIX}${moduleId}`
}

export function moduleIdFromPermission(permissionCode: string): AppRouteId | undefined {
  if (!permissionCode.startsWith(MODULE_PERMISSION_PREFIX)) return undefined
  const moduleId = permissionCode.slice(MODULE_PERMISSION_PREFIX.length)
  return moduleId ? moduleId as AppRouteId : undefined
}

/** Resolve only explicit, reviewed role codes and aliases. Custom positions fail open to backend visibility. */
export function resolveRolePresentationPolicy(roleCodeOrAlias?: string | null): RolePresentationPolicy {
  if (!roleCodeOrAlias?.trim()) return POLICIES.GENERIC
  const key = ALIAS_TO_POLICY.get(normalizeAlias(roleCodeOrAlias))
  return key ? POLICIES[key] : POLICIES.GENERIC
}

export function shouldUseRoleDefaultsFallback(input: {
  identitySource: 'api' | 'demo'
  fullAccountLevel: boolean
  assignmentPermissionsPresent: boolean
}): boolean {
  return input.identitySource === 'demo'
    || (!input.fullAccountLevel && !input.assignmentPermissionsPresent)
}

/** Keep account-wide UI semantics aligned with EffectiveIdentityService. */
export function isFullAccountPresentationRole(roleCode?: string | null): boolean {
  return roleCode === 'PLATFORM_ADMIN' || roleCode === 'CEO'
}

export function resolveFullAccountPresentationRole(roleCodes: readonly string[]): 'PLATFORM_ADMIN' | 'CEO' | undefined {
  if (roleCodes.includes('PLATFORM_ADMIN')) return 'PLATFORM_ADMIN'
  if (roleCodes.includes('CEO')) return 'CEO'
  return undefined
}

/**
 * Last-resort compatibility for demo data or an older identity payload that does not
 * expose assignment permissionCodes. New profiles use explicit ui.module.* grants;
 * this matrix remains the reviewed fallback for pre-migration identities.
 */
export function applyRoleDefaultsForFallback<T extends { id: AppRouteId }>(
  permissionVisibleItems: readonly T[],
  roleCodeOrAlias?: string | null,
): T[] {
  const policy = resolveRolePresentationPolicy(roleCodeOrAlias)
  const allowed = DEFAULT_MODULE_SETS.get(policy.key)
  if (!allowed) return [...permissionVisibleItems]
  return permissionVisibleItems.filter((item) => allowed.has(item.id))
}

/**
 * Top-level module visibility is an explicit presentation capability. Action
 * permissions are evaluated before this function and continue to protect page
 * controls and API calls independently. Profiles created before module grants
 * existed retain the reviewed role defaults as a compatibility fallback.
 */
export function applyPublishedModuleVisibility<T extends { id: AppRouteId }>(
  permissionVisibleItems: readonly T[],
  grantedPermissionCodes: readonly string[],
  roleCodeOrAlias?: string | null,
): T[] {
  const enabledModules = new Set(
    grantedPermissionCodes
      .map(moduleIdFromPermission)
      .filter((moduleId): moduleId is AppRouteId => Boolean(moduleId)),
  )
  if (!enabledModules.size) {
    return applyRoleDefaultsForFallback(permissionVisibleItems, roleCodeOrAlias)
  }
  return permissionVisibleItems.filter((item) => enabledModules.has(item.id))
}

/** Normalize a page-level route to the desktop module that owns it. */
export function desktopModuleForRoute(route: AppRouteId): AppRouteId {
  if (
    route === 'daily-reports-team'
    || route === 'daily-report-detail'
    || route === 'daily-report-correction'
  ) return 'daily-reports-my'
  if (
    route === 'daily-report-template-detail'
    || route === 'daily-report-template-version'
  ) return 'daily-report-templates'
  if (
    route === 'daily-operation-action-items'
    || route === 'daily-operation-issues'
    || route === 'daily-operation-issue-detail'
    || route === 'daily-operation-snapshots'
    || route === 'daily-operation-snapshot-detail'
    || route === 'daily-operation-exports'
  ) return 'daily-operations'
  if (
    route === 'kpi-scorecards'
    || route === 'kpi-scorecard-detail'
    || route === 'kpi-templates'
    || route === 'kpi-template-detail'
    || route === 'kpi-relations'
    || route === 'kpi-settlements'
    || route === 'kpi-inspections'
  ) return 'kpi-center'
  if (
    route === 'investment-project'
    || route === 'investment-parameters'
    || route === 'investment-professional'
  ) return 'investments'
  return route
}

/**
 * Companion guard for the same demo/legacy fallback only. Live page access is
 * decided by the published assignment permissions and backend policy.
 */
export function isRouteAllowedByRoleDefaults(
  roleCodeOrAlias: string | null | undefined,
  route: AppRouteId,
  backendAllowed: boolean,
): boolean {
  if (!backendAllowed) return false
  const policy = resolveRolePresentationPolicy(roleCodeOrAlias)
  const allowed = DEFAULT_MODULE_SETS.get(policy.key)
  return allowed ? allowed.has(desktopModuleForRoute(route)) : true
}

const TEAM_DAILY_REPORT_ROLES = new Set<RolePresentationKey>([
  'SUPERVISOR',
  'ASSISTANT_MANAGER',
  'HOTEL_MANAGER',
  'REGIONAL_OPERATIONS',
  'GROUP_VICE_PRESIDENT',
  'CEO',
  'PLATFORM_ADMIN',
])

/** Managers land on the team/store/region report view even when they can also read their own report. */
export function prefersTeamDailyReports(roleCodeOrAlias?: string | null): boolean {
  return TEAM_DAILY_REPORT_ROLES.has(resolveRolePresentationPolicy(roleCodeOrAlias).key)
}

export function resolveDailyReportsTarget(input: {
  roleCode?: string | null
  hasAssignment: boolean
  canUseOwnReport: boolean
  canUseTeamReport: boolean
}): 'daily-reports-my' | 'daily-reports-team' {
  if (prefersTeamDailyReports(input.roleCode) && input.canUseTeamReport) return 'daily-reports-team'
  if (input.hasAssignment && input.canUseOwnReport) return 'daily-reports-my'
  if (input.canUseTeamReport) return 'daily-reports-team'
  return 'daily-reports-my'
}
