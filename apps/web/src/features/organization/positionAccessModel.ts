import { navigation, visibleNavigationForContext } from '../../app/navigationAccess'
import { buildNavigationCenters } from '../../app/navigationModel'
import { resolveRolePresentationPolicy } from '../../app/rolePresentationPolicy'
import { resolveTaskDispatchMode } from '../tasks/taskDispatchAccess'

export type PermissionOption = { permissionCode: string; label: string; category: string; delegable: boolean; restrictionReason?: string }
export type AuthorizationScope = 'SELF' | 'ORG_UNIT' | 'ORG_TREE' | 'ASSIGNED_HOTELS' | 'TENANT'
export type ProfileContext = {
  roleCode: string
  authorizationScopeTypes: AuthorizationScope[]
  retainedPermissions: PermissionOption[]
  selfSelectionBlockedReason?: string
  workPlansEnabled: boolean
  executiveTasksEnabled: boolean
}

export const scopeDescriptions: Record<AuthorizationScope, { label: string; description: string }> = {
  SELF: { label: '仅本人', description: '仅处理本人工作；不会因为岗位适用于全部门店就获得其他员工或门店的数据。' },
  ORG_UNIT: { label: '任职所在组织', description: '范围限于任职所在组织，不自动包含下级部门；实际操作仍需对应权限。' },
  ORG_TREE: { label: '任职组织及下级', description: '以员工任职组织为起点包含下级部门。在集团任职时可能覆盖多个门店，请核对任职组织。' },
  ASSIGNED_HOTELS: { label: '指定负责门店（按员工配置）', description: '到“人员列表 → 员工 → 编辑”分别配置负责门店。同一岗位可负责不同门店；未配置时无门店数据权限。' },
  TENANT: { label: '集团全部范围', description: '高影响范围：相应操作权限可作用于集团内全部组织与门店，请谨慎发布。' },
}

export const isModuleOption = (option: PermissionOption) => option.category === 'UI_MODULE' || option.permissionCode.startsWith('ui.module.')
const centers = buildNavigationCenters(navigation, '')
const modules = new Map(centers.flatMap((center) => center.items.map((item) => [item.id, item] as const)))
const supplementalModules: Record<string, { label: string; description: string }> = {
  'ui.module.all-functions': { label: '移动端全部功能与我的', description: '移动端入口，不是桌面的独立业务中心。' },
  'ui.module.work-plans': { label: '周/月工作计划（预留）', description: '保留现有配置；当前版本尚无员工端工作计划入口，勾选不会生成可用页面。' },
  'ui.module.hotel-dashboard': { label: '原门店驾驶舱（兼容）', description: '旧入口已合并到工作总览；保留历史配置，不在新菜单重复显示。' },
  'ui.module.operations-dashboard': { label: '原区域多门店（兼容）', description: '旧入口已合并到工作总览；此项不启用被冻结的区域经理岗位。' },
}

export function permissionLabel(option: PermissionOption): string {
  if (!isModuleOption(option)) return option.label
  if (option.permissionCode === 'ui.module.organization') return '组织与人员 / 组织与权限'
  return supplementalModules[option.permissionCode]?.label
    ?? modules.get(option.permissionCode.slice('ui.module.'.length) as typeof navigation[number]['id'])?.label
    ?? option.label
}

export function permissionDescription(option: PermissionOption): string {
  if (!option.delegable) return option.restrictionReason ?? '受保护权限，不可下放'
  return supplementalModules[option.permissionCode]?.description
    ?? (isModuleOption(option) ? '菜单入口；还需对应的页面操作权限。' : option.permissionCode)
}

function permissionGroup(option: PermissionOption): string {
  const code = option.permissionCode
  if (isModuleOption(option)) {
    if (supplementalModules[code]) return 'supplemental'
    return centers.find((center) => center.items.some((item) => code === `ui.module.${item.id}`))?.id ?? 'other'
  }
  if (/^(dashboard\.|work-record\.|work-plan\.|work\.|task\.|executive-task\.|notification\.)/.test(code)) return 'workbench'
  if (/^(daily-report\.|daily-operation\.|issue\.|task-candidate\.|operation-|ai-recommendation\.|investment\.|store-resource\.|metric\.)/.test(code)) return 'operations'
  if (/^(org\.|wecom-binding\.|wecom-onboarding\.)/.test(code)) return 'people'
  if (/^(kpi\.|evaluation\.)/.test(code)) return 'performance'
  if (/^(template\.|daily-report-template\.|work-package\.|rule\.|standard\.|position-profile\.|iam\.|audit\.)/.test(code)) return 'configuration'
  return 'other'
}

export function groupPermissionOptions(options: PermissionOption[], query = '') {
  const search = query.trim().toLocaleLowerCase()
  const matches = options.filter((option) => !search || `${permissionLabel(option)} ${option.label} ${option.permissionCode} ${permissionDescription(option)}`.toLocaleLowerCase().includes(search))
  return [
    ...centers.map((center) => ({ id: center.id, label: center.shortLabel })),
    { id: 'supplemental', label: '移动端、工作计划与兼容入口' },
    { id: 'other', label: '其他功能' },
  ].flatMap((group) => {
    const items = matches.filter((option) => permissionGroup(option) === group.id)
    return items.length ? [{ ...group, items }] : []
  })
}

/** Preview the selected position only, never union the current administrator's permissions. */
export function previewPermissions(draft: string[], context: ProfileContext): string[] {
  const accountWideRole = ['CEO', 'PLATFORM_ADMIN', 'HR_KPI_ADMIN'].includes(context.roleCode)
  return [...new Set([
    ...draft,
    ...(accountWideRole ? context.retainedPermissions.map((option) => option.permissionCode) : []),
    ...(context.roleCode === 'PLATFORM_ADMIN' ? ['*'] : []),
  ])]
}

export function buildPositionPreview(draft: string[], context: ProfileContext, label: string) {
  return buildNavigationCenters(visibleNavigationForContext({
    permissions: previewPermissions(draft, context), roleCode: context.roleCode,
    hasAssignment: true, executiveTasksEnabled: context.executiveTasksEnabled,
    isPlatformAdmin: context.roleCode === 'PLATFORM_ADMIN',
    isDataRetentionAdministrator: ['PLATFORM_ADMIN', 'GROUP_ADMIN'].includes(context.roleCode),
  }), label)
}

export function positionFeatureChecks(draft: string[], context: ProfileContext) {
  const granted = previewPermissions(draft, context)
  const has = (code: string) => granted.includes('*') || granted.includes(code)
  const menu = new Set(buildPositionPreview(draft, context, '').flatMap((center) => center.items.map((item) => item.id)))
  const chairman = context.roleCode === 'GROUP_CHAIRMAN'
  const dispatch = resolveTaskDispatchMode(resolveRolePresentationPolicy(context.roleCode).key, context.executiveTasksEnabled, granted)
  return [
    { label: '一键下达任务', ready: menu.has('workbench') && Boolean(dispatch), detail: chairman
      ? '董事长交办需“董事长派发任务”权限、工作台入口及集团任务开关；仅可派给总经理 / 副总经理。'
      : '需工作台入口、创建任务和下达任务权限；不会要求开放模板管理后台。' },
    { label: '门店 / 下属工作完成率', ready: menu.has('workbench') && has('work-record.team-read'), detail: '需工作台入口和查看管理范围工作记录；门店、部门及员工明细仍按任职数据范围隔离。' },
    { label: '日报汇总与 AI 建议查看', ready: menu.has('daily-operations') && has('ai-recommendation.read'), detail: '需日运营入口、日运营读取和 AI 建议读取；自动汇总及提醒由服务端计划控制，勾选权限不会启动推送。' },
    { label: '入职审核', ready: menu.has('wecom-onboarding') && has('wecom-onboarding.review'), detail: '员工自选与审核员分配相互独立；总部受保护岗位还需组织管理权限及组织范围，不能靠菜单勾选获得。' },
    { label: '库存与固定资产查看', ready: menu.has('store-resources'), detail: '需库存与固定资产入口和读取权限；出入库、盘点、资产维护等写操作必须另外授权。' },
    { label: '周 / 月工作计划', ready: false, statusLabel: '入口待接入', detail: `服务端开关${context.workPlansEnabled ? '已开启' : '未开启'}，但当前版本尚无员工端工作计划页面。这里只保留既有权限配置，不代表可以开始填报或审批。` },
  ]
}
