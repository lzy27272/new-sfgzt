import type { NotificationItem, NavigationParams } from '../domain'
import type { AppRouteId } from './routeConfig'

export type NotificationNavigationTarget = {
  view: AppRouteId
  params?: NavigationParams
  actionLabel: string
}

function normalized(value?: string): string {
  return value?.trim().toUpperCase() ?? ''
}

export function resolveNotificationNavigation(
  notification: Pick<NotificationItem, 'type' | 'sourceType' | 'sourceId'>,
  grantedPermissions: readonly string[] = [],
): NotificationNavigationTarget | undefined {
  const type = normalized(notification.type)
  const sourceType = normalized(notification.sourceType)
  const sourceId = notification.sourceId?.trim()
  const has = (...required: string[]) => grantedPermissions.includes('*') || required.some((permission) => grantedPermissions.includes(permission))
  const canGovernWecom = has('wecom-binding.read', 'wecom-binding.manage', 'wecom-binding.approve')
  const canGovernPositions = has('position-profile.read', 'position-profile.manage')
  const personalBindingResult = ['WECOM_BINDING_ACTIVE', 'WECOM_BINDING_REJECTED', 'WECOM_BINDING_SUSPENDED', 'WECOM_BINDING_RESUMED', 'WECOM_BINDING_REVOKED'].includes(type)

  if (sourceType === 'PROCUREMENT' && sourceId && has('procurement.read', 'procurement.request', 'procurement.configure')) {
    return { view: 'procurement', params: { orderId: sourceId }, actionLabel: '查看采购与收货' }
  }

  if (
    ['WECOM_ONBOARDING', 'WECOM_PERSON_ONBOARDING'].includes(sourceType)
    || type.startsWith('WECOM_ONBOARDING_')
    || type.startsWith('WECOM_DIRECTORY_')
  ) {
    if (!canGovernWecom) {
      if (type === 'WECOM_ONBOARDING_APPROVED' || type === 'WECOM_BINDING_IDENTITY_TRANSFERRED') {
        return { view: 'workbench', actionLabel: '查看我的状态' }
      }
      return undefined
    }
    const actionLabel = type.includes('PENDING') ? '去审核'
      : type.includes('DEAD_LETTER') || type.includes('RETRY') ? '处理同步异常'
        : '查看入职申请'
    const params = sourceId
      ? type.includes('DEAD_LETTER') || type.includes('DIRECTORY_EVENT')
        ? { directoryEventId: sourceId }
        : { candidateId: sourceId }
      : undefined
    return { view: 'wecom-onboarding', params, actionLabel }
  }

  if (sourceType === 'WECOM_BINDING_REQUEST') {
    if (!canGovernWecom) {
      return personalBindingResult
        ? { view: 'workbench', actionLabel: '查看我的状态' }
        : undefined
    }
    return {
      view: 'wecom-bindings',
      params: sourceId ? { requestId: sourceId } : undefined,
      actionLabel: type === 'WECOM_BINDING_PENDING' ? '去确认' : '处理绑定',
    }
  }
  if (sourceType === 'WECOM_BINDING' || type.startsWith('WECOM_BINDING_')) {
    return canGovernWecom
      ? { view: 'wecom-bindings', actionLabel: '查看绑定' }
      : personalBindingResult ? { view: 'workbench', actionLabel: '查看我的状态' } : undefined
  }
  if (sourceType === 'POSITION' || type.startsWith('POSITION_ACCESS_')) {
    if (!canGovernPositions) {
      return type === 'POSITION_ACCESS_AUTO_SUSPENDED'
        ? { view: 'workbench', actionLabel: '查看当前任职' }
        : undefined
    }
    return {
      view: 'organization',
      params: { tab: 'position', positionId: sourceId },
      actionLabel: type.includes('SUSPENDED') ? '检查岗位影响' : '查看岗位',
    }
  }
  if (sourceType === 'DAILY_REPORT' || type.startsWith('DAILY_REPORT_')) {
    return sourceId
      ? { view: 'daily-report-detail', params: { reportId: sourceId }, actionLabel: '去填报' }
      : { view: 'daily-reports-my', actionLabel: '查看日报' }
  }
  if (sourceType === 'TASK' || type.startsWith('TASK_')) {
    return {
      view: 'tasks',
      params: sourceId ? { taskId: sourceId } : undefined,
      actionLabel: type === 'TASK_RESULT_SUBMITTED' ? '去验收' : '处理任务',
    }
  }
  if (sourceType === 'KPI_SCORECARD') {
    return sourceId
      ? { view: 'kpi-scorecard-detail', params: { scorecardId: sourceId }, actionLabel: '查看考核' }
      : { view: 'kpi-center', actionLabel: '查看考核' }
  }
  if (sourceType.startsWith('KPI_') || type.startsWith('KPI_')) {
    return { view: 'kpi-center', actionLabel: sourceType === 'KPI_DISPUTE' ? '处理异议' : '查看绩效' }
  }
  if (sourceType === 'ISSUE' || sourceType === 'OPERATION_ISSUE') {
    return sourceId
      ? { view: 'daily-operation-issue-detail', params: { issueId: sourceId }, actionLabel: '处理异常' }
      : { view: 'daily-operation-issues', actionLabel: '查看异常' }
  }
  if (sourceType === 'STANDARD_EVALUATION' || sourceType === 'EVALUATION') {
    return {
      view: 'evaluations',
      params: sourceId ? { evaluationId: sourceId } : undefined,
      actionLabel: '查看评价',
    }
  }
  if (sourceType === 'WORK_EXPECTATION') {
    if (type === 'WORK_EXPECTATION_SUPERVISION') {
      return { view: 'workbench', params: { scope: 'all' }, actionLabel: '查看督办' }
    }
    return {
      view: 'my-work',
      params: sourceId ? { expectationId: sourceId } : undefined,
      actionLabel: '去完成',
    }
  }
  if (sourceType === 'WORK_RECORD' || type.startsWith('WORK_')) {
    return { view: type.includes('SUBMITTED') ? 'team-work' : 'my-work', actionLabel: '查看工作' }
  }
  if (sourceType === 'RULE' || sourceType === 'MANAGEMENT_EVENT' || type.startsWith('RULE_')) {
    return { view: 'rules', actionLabel: '查看规则' }
  }
  return undefined
}
