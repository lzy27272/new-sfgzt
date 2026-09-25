import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { apiCommand, apiRequest, authMode, changePassword, clearAccessToken, demoFallbackEnabled, establishFederatedSession, hasAccessToken, login, logout } from './api/client'
import { allowsGuestServiceAlbumUpload, evidenceCaptureSourceMatches, focusedWorkFields, isOccurrenceEntry, workPolicyValue } from './app/workEntryPolicy'
import { consumeLogoutEntry } from './app/logoutEntry'
import { useScopedResource } from './shared/useScopedResource'
import { IcpFooter } from './shared/IcpFooter'
import { WorkUsageFields } from './features/storeResources/WorkUsageFields'
import { AiEvidenceRecord } from './features/aiEvidence/AiEvidenceReview'
import { UsageEvidenceSummary } from './features/storeResources/WorkUsagePanel'
import { loadUsageCatalogue, supportsWorkUsage, usageInput, usagePayload, usageValidation, type UsageCatalogue } from './features/storeResources/workUsage'
import { clearManualLogin, rememberManualLogin, requestsManualLogin } from './app/manualLogin'
import {
  bootstrapAssignmentId,
  bootstrapAssignments,
  canLoadSecondaryResources,
  canRenderAuthenticatedShell,
  canUseStoredAcceptanceRole,
} from './app/authBootstrap'
import { validateImageEvidence } from './app/imageEvidence'
import { materializeSelectedFiles, restoreReadableDraftFiles } from './app/uploadFiles'
import {
  addWorkRecordSupplement,
  createWorkRecordDraft,
  delegateExpectation,
  createRuleWithVersion,
  createTaskEvaluation,
  deleteTaskEvidence,
  loadEvaluations,
  loadAttachmentContent,
  loadDelegationCandidates,
  loadEvaluation,
  loadExpectation,
  loadIdentity,
  loadMyWork,
  loadNotifications,
  loadRuleDetail,
  loadTaskEvidenceContent,
  loadRules,
  loadTask,
  loadTasks,
  loadWorkPackages,
  loadWorkRecord,
  publishRuleVersion,
  revokeExpectationDelegation,
  saveRuleVersion,
  submitWorkRecordDraft,
  updateWorkRecordDraft,
  uploadTaskEvidence,
  uploadWorkRecordAttachment,
} from './api/resources'
import { roleContexts } from './data/roles'
import { product } from './product'
import type {
  ApiSource,
  DelegationCandidate,
  IdentitySnapshot,
  ManagementRule,
  ManagementTask,
  Navigate,
  NotificationItem,
  RouteParams,
  RuleDetail,
  RuleVersionDraft,
  RoleContext,
  StandardEvaluation,
  SubmissionPolicy,
  WorkExpectation,
  WorkRecordDetail,
} from './domain'
import { useResource } from './useResource'
import { useWorkBoard, WorkDateToolbar, WorkStatusCards, CompletionRates, WorkQueueAlerts, WorkBoardList } from './features/workbench/WorkBoard'
import { withBoardParams } from './features/workbench/workBoardModel'
import { permissions as permissionCodes } from './app/permissions'
import {
  isDailyFeatureRoute,
  isInvestmentFeatureRoute,
  requiredAllPermissionsForRoute,
  requiredPermissionsForRoute,
  type AppNavigate,
  type AppRouteId,
} from './app/routeConfig'
import {
  isRouteAllowedByRoleDefaults,
  resolveDailyReportsTarget,
  resolveFullAccountPresentationRole,
  resolveRolePresentationPolicy,
  shouldUseRoleDefaultsFallback,
} from './app/rolePresentationPolicy'
import { buildNavigationCenters, canOpenStoreResources, navigationCenterForSection } from './app/navigationModel'
import { navigation, visibleNavigationForContext } from './app/navigationAccess'
import { CenterFeatureNavigation, MainCenterNavigation } from './shared/CenterNavigation'
import { resolveNotificationNavigation } from './app/notificationNavigation'
import { useHashRoute } from './app/useHashRoute'
import { loadExecutiveTasks } from './features/executiveTasks/api'
import { hasDataRetentionAdministrationRole } from './features/dataRetention/policy'
import { PageAccessBoundary } from './shared/PageAccessBoundary'
import { AttachmentPreviewDialog, resolveAttachmentMediaType, type AttachmentPreview } from './shared/AttachmentPreviewDialog'
import { WecomTaskEntryPage } from './features/wecom/WecomTaskEntry'
import { buildAppHashLocation, consumeWecomSessionBootstrap, consumeWecomTaskEntry } from './features/wecom/entryRoute'
import { consumeWecomBindingEntry } from './features/wecom/bindingEntryRoute'
import { WecomBindingEnrollmentEntry } from './features/wecom/WecomBindingEnrollmentEntry'
import { SelfRegistrationButton } from './features/wecom/SelfRegistrationButton'
import { buildWeComSilentAuthUrl, isWeComBrowser } from './features/wecom/silentAuth'
import {
  buildResumableDraftKey,
  fromStoredDraftFile,
  isStoredDraftFile,
  readResumableDraft,
  removeResumableDraft,
  toStoredDraftFile,
  writeResumableDraft,
  type StoredDraftFile,
} from './app/resumableDrafts'
import { AllFunctionsPage } from './shared/MobileAppNavigation'

const icpRecordNumber = (import.meta.env.VITE_ICP_RECORD_NUMBER ?? '').trim()
const loginWorkbenchTitle = '贵州四方馆酒店管理有限公司工作台'

const DailyReportFeature = lazy(() => import('./features/dailyReports/DailyReportRoutes').then((module) => ({ default: module.DailyReportRoutes })))
const DailyReportTemplateFeature = lazy(() => import('./features/dailyReportTemplates/DailyReportTemplateRoutes').then((module) => ({ default: module.DailyReportTemplateRoutes })))
const DailyOperationFeature = lazy(() => import('./features/dailyOperations/DailyOperationRoutes').then((module) => ({ default: module.DailyOperationRoutes })))
const KpiFeature = lazy(() => import('./features/kpi/KpiRoutes').then((module) => ({ default: module.KpiRoutes })))
const InvestmentFeature = lazy(() => import('./features/investments/InvestmentRoutes').then((module) => ({ default: module.InvestmentRoutes })))
const ExecutiveTaskFeature = lazy(() => import('./features/executiveTasks/ExecutiveTaskRoutes').then((module) => ({ default: module.ExecutiveTaskRoutes })))
const initialWecomSessionBootstrap = consumeWecomSessionBootstrap(() => establishFederatedSession())
const RoleWorkbench = lazy(() => import('./features/workbench/RoleWorkbench').then((module) => ({ default: module.RoleWorkbench })))
const HotelDashboardPage = lazy(() => import('./P0Pages').then((module) => ({ default: module.HotelDashboardPage })))
const OperationsDashboardPage = lazy(() => import('./P0Pages').then((module) => ({ default: module.OperationsDashboardPage })))
const TeamWorkPage = lazy(() => import('./P0Pages').then((module) => ({ default: module.TeamWorkPage })))
const OrganizationCenter = lazy(() => import('./ConfigurationPages').then((module) => ({ default: module.OrganizationCenter })))
const WorkPackageCenter = lazy(() => import('./ConfigurationPages').then((module) => ({ default: module.WorkPackageCenter })))
const EnterpriseTemplateCenter = lazy(() => import('./Pilot6Pages').then((module) => ({ default: module.EnterpriseTemplateCenter })))
const TaskCreateDialog = lazy(() => import('./Pilot6Pages').then((module) => ({ default: module.TaskCreateDialog })))
const WecomStoreWebhookConfiguration = lazy(() => import('./features/wecom/WecomStoreWebhookConfiguration').then((module) => ({ default: module.WecomStoreWebhookConfiguration })))
const WecomUserBindingAdministration = lazy(() => import('./features/wecom/WecomUserBindingAdministration').then((module) => ({ default: module.WecomUserBindingAdministration })))
const WecomDirectoryOnboardingAdministration = lazy(() => import('./features/wecom/WecomDirectoryOnboardingAdministration').then((module) => ({ default: module.WecomDirectoryOnboardingAdministration })))
const DataRetentionAdministration = lazy(() => import('./features/dataRetention/DataRetentionAdministration').then((module) => ({ default: module.DataRetentionAdministration })))
const AiDataFeature = lazy(() => import('./features/aiData/AiDataFeature'))
const StoreResourceFeature = lazy(() => import('./features/storeResources/StoreResourceRoutes').then((module) => ({ default: module.StoreResourceRoutes })))
const AcademyFeature = lazy(() => import('./features/academy/AcademyRoutes'))
const FinanceFeature = lazy(() => import('./features/finance/FinanceRoutes'))
const ProcurementFeature = lazy(() => import('./features/procurement/ProcurementRoutes'))
const LostPropertyFeature = lazy(() => import('./features/lostProperties/LostPropertyRoutes'))
const initialWecomTaskEntry = consumeWecomTaskEntry()
const initialWecomBindingEntry = consumeWecomBindingEntry()

const roleStorageKey = 'hotel-ai-os-role:v1'
const statusText: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已停用', PENDING: '待完成',
  PLANNED: '待开放', AVAILABLE: '可填报', NOT_STARTED: '待开始', IN_PROGRESS: '执行中',
  SUBMITTED: '已提交', SATISFIED: '已达标', FAILED: '未达标', MISSED: '已漏交',
  COMPLETED: '已完成', OVERDUE: '已逾期', WAIVED: '已豁免',
  PROPOSED: '待派发', PENDING_ACK: '待确认', RESULT_SUBMITTED: '结果已提交',
  AWAITING_REVIEW: '待验收', REWORK: '返工中', CANCELLED: '已取消',
  PASS: '通过', WARNING: '预警', FAIL: '不通过', PENDING_MANUAL: '待人工', PENDING_AI: '待AI',
  ON_TIME: '正常', DUE_SOON: '即将到期', HIGH: '高', URGENT: '紧急', NORMAL: '普通', LOW: '低',
  CHAIRMAN_DIRECTIVE: '董事长交办', WORK_PLAN: '工作计划生成', MANUAL: '人工创建',
}

function formatDate(value?: string, includeTime = true) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', ...(includeTime ? { hour: '2-digit', minute: '2-digit' } : {}),
  }).format(date)
}

function label(value?: string) {
  if (!value) return '—'
  return statusText[value] ?? value
}

function Status({ value, tone }: { value: string; tone?: string }) {
  return <span className={`status-pill ${tone ?? value.toLowerCase().replaceAll('_', '-')}`}>{label(value)}</span>
}

function PageHeader({ eyebrow, title, description, actions }: { eyebrow: string; title: string; description: string; actions?: React.ReactNode }) {
  return <header className="page-title">
    <div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p></div>
    {actions && <div className="page-actions">{actions}</div>}
  </header>
}

function DataState({ loading, error, empty, onRetry }: { loading: boolean; error?: string; empty?: boolean; onRetry: () => void }) {
  if (loading) return <div className="state-card"><div className="spinner" /><strong>正在读取管理数据</strong><span>数据来自当前任职权限范围</span></div>
  if (error) return <div className="state-card error-state"><b>!</b><strong>数据读取失败</strong><span>{error}</span><button className="secondary" onClick={onRetry}>重新加载</button></div>
  if (empty) return <div className="state-card"><b>◇</b><strong>当前范围暂无数据</strong><span>这里不会用演示数据填充真实 API 的空结果。</span><button className="secondary" onClick={onRetry}>立即刷新</button></div>
  return null
}

function SourceFlag({ source }: { source: ApiSource }) {
  return source === 'demo'
    ? <span className="source-flag demo">演示回退</span>
    : <span className="source-flag api">实时 API</span>
}

function Metric({ label: title, value, hint, tone = 'blue', onClick }: { label: string; value: string | number; hint: string; tone?: string; onClick?: () => void }) {
  const content = <><div>{title.slice(0, 1)}</div><span>{title}<strong>{value}</strong><small>{hint}</small></span></>
  return onClick
    ? <button type="button" className={`metric metric-action ${tone}`} onClick={onClick} aria-label={`${title}：${value}，查看明细`}>{content}</button>
    : <article className={`metric ${tone}`}>{content}</article>
}

function Workbench({ identity, permissions, executiveTasksEnabled, go }: { identity: RoleContext; permissions: string[]; executiveTasksEnabled: boolean; go: Navigate }) {
  const hasAssignment = Boolean(identity.businessActorAssignmentId)
  const allows = (permission: string) => permissions.includes('*') || permissions.includes(permission)
  const isChairman = identity.roleCode === 'GROUP_CHAIRMAN'
  const canReadExecutiveTasks = isChairman && executiveTasksEnabled && hasAssignment && allows(permissionCodes.executiveTask.read)
  const canReadOwnWork = hasAssignment && (allows('work-record.read') || allows('work-record.submit') || allows('work.submit'))
  const canReadTeamWork = allows(permissionCodes.workRecord.readTeam) || allows('work-record.review')
  const canReadTasks = isChairman ? canReadExecutiveTasks : allows('task.read') || allows('task.act') || allows('task.review')
  const canReadNotifications = allows('notification.read')
  const canReview = !isChairman && allows('task.review')
  const primaryTaskView = hasAssignment ? 'mine' : 'team'
  const myWork = useResource(`${identity.key}:my-work:${canReadOwnWork}`, () => canReadOwnWork ? loadMyWork(identity) : Promise.resolve({ data: [], source: 'api' as const }), [], 30_000)
  const tasks = useResource(`${identity.key}:tasks:${canReadTasks}:${isChairman}`, () => canReadTasks
    ? isChairman ? loadExecutiveTasks(identity) : loadTasks(identity, { view: primaryTaskView })
    : Promise.resolve({ data: [], source: 'api' as const }), [], 15_000)
  const reviewTaskResource = useResource(`${identity.key}:review-tasks:${canReview}`, () => canReview ? loadTasks(identity, { view: 'review' }) : Promise.resolve({ data: [], source: 'api' as const }), [], 15_000)
  const notices = useResource(`${identity.key}:notices:${canReadNotifications}`, () => canReadNotifications ? loadNotifications(identity) : Promise.resolve({ data: [], source: 'api' as const }), [], 15_000)
  const loading = myWork.loading || tasks.loading || reviewTaskResource.loading || notices.loading
  const scopedWork = myWork.data
  const scopedTasks = tasks.data
  const scopedNotices = notices.data
  const pendingWork = scopedWork.filter((item) => !['SATISFIED', 'SUBMITTED', 'WAIVED', 'CANCELLED'].includes(item.status))
  const activeTasks = scopedTasks.filter((item) => !['COMPLETED', 'CANCELLED'].includes(item.status))
  const reviewTasks = reviewTaskResource.data.filter((item) => ['RESULT_SUBMITTED', 'AWAITING_REVIEW'].includes(item.status))
  const unread = scopedNotices.filter((item) => !item.readAt)
  const source: ApiSource = [myWork.source, tasks.source, reviewTaskResource.source, notices.source].includes('demo') ? 'demo' : 'api'

  return <>
    <section className="hero">
      <div><span className="eyebrow">当前岗位工作</span><h1>{identity.label}工作台</h1><p>{identity.focus}</p></div>
      <div className="hero-context"><span>当前有效任职</span><strong>{identity.label}</strong><small>{identity.orgName}</small><SourceFlag source={source} /></div>
    </section>
    {loading ? <DataState loading onRetry={() => void Promise.all([myWork.reload(), tasks.reload(), reviewTaskResource.reload(), notices.reload()])} /> : <>
      <section className="metrics-grid">
        {(canReadOwnWork || canReadTeamWork) && <Metric label={hasAssignment ? '今日待完成' : '岗位待办'} value={pendingWork.length} hint={hasAssignment ? `当前任职工作 ${scopedWork.length} 项` : '管理账号查看授权团队工作'} onClick={() => go(hasAssignment ? 'my-work' : 'team-work', { status: 'PENDING_WORK' })} />}
        {canReadTasks && <Metric label="执行中任务" value={activeTasks.length} hint={`${scopedTasks.filter((x) => x.slaStatus === 'OVERDUE').length} 项已逾期`} tone="teal" onClick={() => go('tasks', { view: primaryTaskView, status: 'ACTIVE' })} />}
        {isChairman && canReadTasks && <Metric label="待我验收" value={scopedTasks.filter((item) => ['RESULT_SUBMITTED', 'AWAITING_REVIEW'].includes(item.status) && item.reviewerAssignmentId === identity.businessActorAssignmentId && item.creationSource === 'CHAIRMAN_DIRECTIVE').length} hint="仅验收本人交办的高管任务" tone="gold" onClick={() => go('tasks')} />}
        {canReview && <Metric label="待我验收" value={reviewTasks.length} hint="有标准按标准评价，无标准由验收人人工判定" tone="gold" onClick={() => go('tasks', { view: 'review' })} />}
        {canReadNotifications && <Metric label="未读通知" value={unread.length} hint="任务、逾期与升级提醒" tone="violet" onClick={() => go('notifications', { unread: 'true' })} />}
      </section>
      <section className="dashboard-grid">
        {(canReadOwnWork || canReadTeamWork) && <article className="panel span-2"><header><div><span className="panel-kicker">{hasAssignment ? '今日工作' : '管理范围'}</span><h2>{hasAssignment ? '今日岗位工作' : '集团管理视图'}</h2></div><button className="link-button" onClick={() => go(hasAssignment ? 'my-work' : 'team-work')}>{hasAssignment ? '查看全部' : '查看团队执行'}</button></header>
          {hasAssignment ? <div className="compact-list">{scopedWork.slice(0, 5).map((item) => <div key={item.id}><i className={`work-dot ${item.status.toLowerCase()}`} /><span><strong>{item.title}</strong><small>{item.targetOrgName} · {item.packageName}</small></span><span className="compact-meta"><Status value={item.status} /><small>{formatDate(item.dueAt)}</small></span></div>)}</div> : <div className="state-card"><b>◎</b><strong>当前为集团管理账号</strong><span>CEO 等无岗位任职的账号通过角色工作台管理，不生成虚假的个人工作。</span></div>}
        </article>}
        {canReadNotifications && <article className="panel"><header><div><span className="panel-kicker">消息提醒</span><h2>管理提醒</h2></div><button className="link-button" onClick={() => go('notifications')}>通知中心</button></header>
          <div className="notice-list">{scopedNotices.slice(0, 4).map((item) => <div className={item.readAt ? 'read' : ''} key={item.id}><i /><span><strong>{item.title}</strong><small>{item.content}</small></span></div>)}</div>
        </article>}
        {canReadTasks && <article className="panel span-3"><header><div><span className="panel-kicker">任务执行</span><h2>执行任务</h2></div><button className="link-button" onClick={() => go('tasks', { view: primaryTaskView })}>进入任务中心</button></header>
          <TaskRows tasks={scopedTasks.slice(0, 5)} onSelect={(task) => go('tasks', { view: primaryTaskView, taskId: task.id })} />
        </article>}
      </section>
    </>}
  </>
}

function WorkPackages({ identity }: { identity: RoleContext }) {
  const resource = useResource(`${identity.key}:packages`, () => loadWorkPackages(identity), [])
  return <section className="page-section">
    <PageHeader eyebrow="WORK PACKAGE CENTER" title="工作包中心" description="把已发布标准转化为岗位每日、每周、每月必须完成的结构化工作。" actions={<><SourceFlag source={resource.source} /><button className="primary" disabled>＋ 新建工作包</button></>} />
    <DataState loading={resource.loading} error={resource.error} empty={!resource.data.length} onRetry={resource.reload} />
    {!resource.loading && !resource.error && !!resource.data.length && <div className="card-grid">{resource.data.map((item) => <article className="package-card" key={item.id}>
      <div className="card-top"><span>{item.code}</span><Status value={item.lifecycleStatus} /></div>
      <h2>{item.name}</h2><p>{item.positionName} · {item.scopeName}</p>
      <div className="package-stats"><span><strong>V{item.versionNo}</strong>当前版本</span><span><strong>{item.itemCount ?? '—'}</strong>工作项</span><span><strong>{item.completionRate === undefined ? '—' : `${item.completionRate}%`}</strong>今日完成</span></div>
      <div className="progress"><i style={{ width: `${item.completionRate ?? 0}%` }} /></div>
      <button className="secondary">查看版本与分配</button>
    </article>)}</div>}
  </section>
}

function WorkTable({ items, own = false, onFill }: { items: WorkExpectation[]; own?: boolean; onFill?: (item: WorkExpectation) => void }) {
  return <div className="data-table work-table">
    <div className="table-row table-head"><span>工作项</span><span>目标组织</span><span>负责人</span><span>截止时间</span><span>评价</span><span>状态</span>{own && <span>操作</span>}</div>
    {items.map((item) => <div className="table-row" key={item.id}>
      <span><strong>{item.title}</strong><small>{item.packageName} · {item.itemName}</small></span>
      <span>{item.targetOrgName}</span><span>{item.delegatedEmployeeName ? `${item.delegatedEmployeeName}（已转交）` : item.assigneeName}</span><span>{formatDate(item.dueAt)}</span>
      <span>{item.evaluationOutcome ? <Status value={item.evaluationOutcome} /> : '—'}</span><span><Status value={item.status} /></span>
      {own && <span><button className="link-button" disabled={['WAIVED', 'CANCELLED'].includes(item.status)} onClick={() => onFill?.(item)}>{item.executionPolicy?.repeatableDailyRecords && ['SUBMITTED', 'SATISFIED'].includes(item.status) ? '新增/查看记录' : ['SUBMITTED', 'SATISFIED'].includes(item.status) ? '查看提交' : item.status === 'FAILED' ? '查看并重提' : item.status === 'MISSED' ? '逾期补交' : '填报'}</button></span>}
    </div>)}
  </div>
}

type PendingWorkEvidence = {
  draftFileId: string
  file: File
  captureSource: 'CAMERA' | 'FILE_PICKER'
  checkpointCode?: string
  evidenceInstanceKey?: string
  capturedAtClient?: string
}

type NewWorkEvidence = Omit<PendingWorkEvidence, 'draftFileId'>

const pendingWorkEvidenceLabel = (entry: PendingWorkEvidence) => entry.evidenceInstanceKey
  ? `编号 ${entry.evidenceInstanceKey}`
  : entry.checkpointCode === 'guest_service_photo'
    ? `对客服务照片（${entry.captureSource === 'CAMERA' ? '拍照' : '相册'}）`
    : entry.checkpointCode ? '现场' : '文件'

type WorkRecordObjectValue = Record<string, unknown>
type WorkRecordFieldValue = string | number | boolean | string[] | WorkRecordObjectValue

const isWorkRecordFieldValue = (entry: [string, unknown]): entry is [string, WorkRecordFieldValue] => {
  const value = entry[1]
  if (entry[0] === 'inventoryQuantities' && usageInput(value)) return true
  if (['string', 'number', 'boolean'].includes(typeof value)) return true
  if (Array.isArray(value)) return value.every((item) => typeof item === 'string')
  return Boolean(value && typeof value === 'object'
    && Object.values(value).every((item) => ['string', 'number', 'boolean'].includes(typeof item)))
}

const structuredObjectText = (value: WorkRecordFieldValue | undefined) =>
  value && typeof value === 'object' && !Array.isArray(value)
    ? Object.entries(value).map(([key, entry]) => `${key}=${String(entry)}`).join('\n')
    : ''

const structuredObjectValue = (value: string): WorkRecordObjectValue => Object.fromEntries(value
  .split(/\r?\n/)
  .map((line) => line.trim())
  .filter(Boolean)
  .map((line) => {
    const separator = line.search(/[=：:]/)
    if (separator < 1) return [line, '']
    const key = line.slice(0, separator).trim()
    const raw = line.slice(separator + 1).trim()
    return [key, /^-?\d+(?:\.\d+)?$/.test(raw) ? Number(raw) : raw]
  })
  .filter(([key]) => Boolean(key)))

type WorkRecordSaveProgress = {
  phase: 'draft' | 'upload' | 'submit'
  completed: number
  total: number
}

type WorkRecordFormDraft = {
  schemaVersion: 1
  completion: string
  exception: string
  nextAction: string
  payload: Record<string, WorkRecordFieldValue>
  serverRecordId?: string
  serverRowVersion?: number
}

type StoredWorkEvidence = StoredDraftFile & Omit<PendingWorkEvidence, 'draftFileId' | 'file'>

const hasWorkRecordDraftContent = (draft: WorkRecordFormDraft) => Boolean(
  draft.serverRecordId
  || draft.completion.length
  || draft.exception.length
  || draft.nextAction.length
  || Object.values(draft.payload).some((value) => Array.isArray(value)
    ? value.some((entry) => entry.length > 0)
    : value && typeof value === 'object'
      ? Object.keys(value).length > 0
      : typeof value === 'string' ? value.length > 0 : value !== undefined),
)

const workEvidenceToStored = (entry: PendingWorkEvidence): StoredWorkEvidence => ({
  ...toStoredDraftFile(entry.file, entry.draftFileId),
  captureSource: entry.captureSource,
  checkpointCode: entry.checkpointCode,
  evidenceInstanceKey: entry.evidenceInstanceKey,
  capturedAtClient: entry.capturedAtClient,
})

const storedToWorkEvidence = (entry: StoredWorkEvidence): PendingWorkEvidence => ({
  draftFileId: entry.id,
  file: fromStoredDraftFile(entry),
  captureSource: entry.captureSource,
  checkpointCode: entry.checkpointCode,
  evidenceInstanceKey: entry.evidenceInstanceKey,
  capturedAtClient: entry.capturedAtClient,
})

export function WorkRecordDialog({ item, identity, onClose, onSaved, onDelegated }: {
  item: WorkExpectation
  identity: RoleContext
  onClose: () => void
  onSaved: () => Promise<void>
  onDelegated: (message: string) => void
}) {
  const [completion, setCompletion] = useState('')
  const [exception, setException] = useState('')
  const [nextAction, setNextAction] = useState('')
  const [payload, setPayload] = useState<Record<string, WorkRecordFieldValue>>({})
  const [attachments, setAttachments] = useState<PendingWorkEvidence[]>([])
  const [validatingAttachments, setValidatingAttachments] = useState(false)
  const [existing, setExisting] = useState<WorkRecordDetail>()
  const [loadingRecord, setLoadingRecord] = useState(true)
  const [recordLoadFailed, setRecordLoadFailed] = useState(false)
  const [draftHydrated, setDraftHydrated] = useState(false)
  const [conflictingLocalForm, setConflictingLocalForm] = useState<WorkRecordFormDraft>()
  const [supplement, setSupplement] = useState('')
  const [saving, setSaving] = useState<'draft' | 'submit' | 'supplement'>()
  const [saveProgress, setSaveProgress] = useState<WorkRecordSaveProgress>()
  const [delegating, setDelegating] = useState(false)
  const [candidates, setCandidates] = useState<DelegationCandidate[]>([])
  const [delegateAssignmentId, setDelegateAssignmentId] = useState('')
  const [ownerResting, setOwnerResting] = useState(false)
  const [delegationReason, setDelegationReason] = useState('')
  const [message, setMessage] = useState<string>()
  const [draftNotice, setDraftNotice] = useState<string>()
  const [attachmentPreview, setAttachmentPreview] = useState<AttachmentPreview>()
  const employeeId = identity.employeeId ?? item.employeeId
  const executionAssignmentId = identity.businessActorAssignmentId ?? item.assignmentId
  const assignmentOrgUnitId = identity.assignmentOrgUnitId
  const workDraftParts = [identity.tenantId, identity.actorId, executionAssignmentId, item.id, item.businessDate]
  const formDraftKey = buildResumableDraftKey('work-record-form', workDraftParts)
  const filesDraftKey = buildResumableDraftKey('work-record-files', workDraftParts)
  const activeRecordIdRef = useRef<string | undefined>(item.recordId)
  const formDraftRef = useRef<WorkRecordFormDraft>({ schemaVersion: 1, completion: '', exception: '', nextAction: '', payload: {}, serverRecordId: item.recordId })
  const filesDraftRef = useRef<StoredWorkEvidence[]>([])
  const draftStorageReadyRef = useRef(false)
  const executionAllowed = !item.delegateAssignmentId || item.delegateAssignmentId === executionAssignmentId
  const repeatableDailyRecords = item.executionPolicy?.repeatableDailyRecords === true
  const canSubmit = item.orgUnitId && assignmentOrgUnitId && employeeId && executionAssignmentId && item.formVersionId && executionAllowed
  const properties = focusedWorkFields(item.formSchema?.properties ?? {}, item.itemCode)
  const occurrenceEntry = isOccurrenceEntry(item.itemCode) || item.executionPolicy?.registrationMode === 'WHEN_OCCURRED'
  const usageSupported = supportsWorkUsage(item)
  const usageCatalogue = useScopedResource<UsageCatalogue | undefined>(`${identity.key}:${item.orgUnitId}:${item.businessDate}:${item.itemCode}:${usageSupported}:work-usage`,
    (signal) => usageSupported && item.orgUnitId ? loadUsageCatalogue(identity, item.orgUnitId, signal, { businessDate: item.businessDate, itemCode: item.itemCode }) : Promise.resolve(undefined), undefined)
  const required = (item.formSchema?.required ?? []).filter((key) => key in properties)
  const policy = item.submissionPolicy ?? { completionStatementRequired: false, exceptionStatementRequired: false, nextActionRequired: false, attachmentRequired: false, maxAttachments: 10, maxFileSizeBytes: 20 * 1024 * 1024, allowedExtensions: ['jpg', 'jpeg', 'png', 'pdf', 'docx', 'xlsx'], evidenceRequirements: [] }
  const applicabilityConditionField = typeof item.applicabilityPolicy?.conditionField === 'string'
    ? item.applicabilityPolicy.conditionField
    : item.applicabilityPolicy?.condition === 'HAS_SPECIAL_ARRIVAL_REQUIREMENT'
      ? 'specialArrivalRequirement'
      : undefined
  const itemSubmissionApplicable = applicabilityConditionField
    ? payload[applicabilityConditionField] === undefined || payload[applicabilityConditionField] === ''
      ? undefined
      : item.applicabilityPolicy?.condition === 'HAS_SPECIAL_ARRIVAL_REQUIREMENT'
        ? payload[applicabilityConditionField] === true
        : payload[applicabilityConditionField] === item.applicabilityPolicy?.equals
    : true
  const viewOnly = Boolean(existing && ['SUBMITTED', 'APPROVED'].includes(existing.status)
    && (repeatableDailyRecords || item.status !== 'FAILED'))
  const fieldLabels: Record<string, string> = { checkins: '今日入住数', complaints: '客诉数量', vipReception: 'VIP接待情况', roomsChecked: '检查房间数', issues: '发现问题数', attendance: '出勤人数', employeeStatus: '员工状态', handover: '交接事项', operationsReviewed: '已查看经营数据', otaChecked: '已完成OTA巡查', employeeTalks: '员工沟通人数', summary: '完成情况', roomNumber: '房号', serviceContent: '服务内容描述', location: '房号', complaintContent: '客诉内容', initialHandling: '初步处理情况' }
  const requiredReady = required.every((key) => {
    const value = usageSupported && key === 'inventoryQuantities'
      ? usagePayload(payload[key], usageCatalogue.data)
      : payload[key]
    return Array.isArray(value)
      ? value.length > 0 && value.every((entry) => entry.trim().length > 0)
      : value && typeof value === 'object'
        ? Object.keys(value).length > 0
      : typeof value === 'string' ? value.trim().length > 0 : value !== undefined && value !== null
  })
  const isOwner = item.assignmentId === identity.businessActorAssignmentId
    || (item.executionPolicy?.sharedPerOrg === true
      && item.executionPolicy.allowedPositionCodes?.includes(identity.roleCode) === true)
  const facilityFlagKeys: Record<string, string> = {
    BREAKFAST_AREA: 'breakfastArea', KITCHEN: 'kitchen', AFTERNOON_TEA: 'afternoonTea',
    BAR: 'bar', PROJECTION: 'projection', GYM: 'gym', TERRACE: 'terrace',
    TRENDY_PLAY_AREA: 'trendyPlayArea',
  }
  const requirementApplies = (requirement: SubmissionPolicy['evidenceRequirements'][number]) => {
    if (requirement.requiredWhen && workPolicyValue(payload, requirement.requiredWhen.field) !== requirement.requiredWhen.equals) return false
    if (!requirement.conditionalFacility) return true
    const flagKey = facilityFlagKeys[requirement.conditionalFacility]
    return Boolean(flagKey && item.operationalFeatureFlags?.[flagKey] === true)
  }
  const requirementMinimum = (requirement: SubmissionPolicy['evidenceRequirements'][number]) => {
    if (!requirementApplies(requirement)) return 0
    const perFloor = requirement.minimumPerHotelFloor ?? 0
    return Math.max(requirement.minimum ?? 0, perFloor * Math.max(1, item.guestRoomFloorCount ?? 1))
  }
  const requirementInstanceField = (requirement: SubmissionPolicy['evidenceRequirements'][number]) =>
    requirement.instanceField ?? requirement.configuredFacilityInstanceField ?? requirement.configuredAssetInstanceField
  const requirementInstanceLabel = (requirement: SubmissionPolicy['evidenceRequirements'][number]) =>
    requirement.instanceLabel ?? (requirement.minimumPerConfiguredAsset !== undefined ? '设备编号' : requirement.minimumPerConfiguredFacility !== undefined ? '设施编号' : '房号')
  const requirementInstanceMinimum = (requirement: SubmissionPolicy['evidenceRequirements'][number]) =>
    requirement.minimumPerInstance ?? requirement.minimumPerConfiguredFacility ?? requirement.minimumPerConfiguredAsset ?? 0
  const requirementUsesStoreConfiguration = (requirement: SubmissionPolicy['evidenceRequirements'][number]) =>
    requirement.minimumPerConfiguredFacility !== undefined || requirement.minimumPerConfiguredAsset !== undefined
  const configuredInstanceValues = (requirement: SubmissionPolicy['evidenceRequirements'][number]) => {
    const field = requirement.minimumPerConfiguredFacility !== undefined
      ? requirement.configuredFacilityInstanceField
      : requirement.configuredAssetInstanceField
    if (!field) return []
    const configured = requirement.minimumPerConfiguredFacility !== undefined
      ? item.evidenceInstanceConfiguration?.facilityInstances[field]
      : item.evidenceInstanceConfiguration?.assetInstances[field]
    return configured ?? []
  }
  const activeRequirements = policy.evidenceRequirements.filter((requirement) =>
    itemSubmissionApplicable !== false && requirementApplies(requirement) && ((requirement.requiredInstances ?? 0) > 0
      || requirementInstanceMinimum(requirement) > 0
      || requirementMinimum(requirement) > 0))
  const hasInstanceRequirements = activeRequirements.some((requirement) =>
    Boolean(requirementInstanceField(requirement)) && requirementInstanceMinimum(requirement) > 0)
  const guestServiceAlbumUpload = activeRequirements.some((requirement) =>
    allowsGuestServiceAlbumUpload(item.itemCode, requirement))
  const normalizedInstanceKey = (value: string) => value.trim().toUpperCase()
  const instanceValues = (requirement: SubmissionPolicy['evidenceRequirements'][number]) => {
    if (requirementUsesStoreConfiguration(requirement)) return configuredInstanceValues(requirement)
    const count = requirement.requiredInstances ?? 0
    const field = requirementInstanceField(requirement)
    const source = field && Array.isArray(payload[field])
      ? payload[field] as string[]
      : []
    return count > 0 ? Array.from({ length: count }, (_, index) => source[index] ?? '') : source
  }
  const updateInstanceValue = (requirement: SubmissionPolicy['evidenceRequirements'][number], index: number, value: string) => {
    if (requirementUsesStoreConfiguration(requirement)) return
    const field = requirementInstanceField(requirement)
    if (!field) return
    setPayload((current) => {
      const count = requirement.requiredInstances ?? 0
      const currentValue = current[field]
      const source = Array.isArray(currentValue) ? currentValue : []
      const values = count > 0
        ? Array.from({ length: count }, (_, currentIndex) => source[currentIndex] ?? '')
        : [...source]
      values[index] = value
      return { ...current, [field]: values }
    })
  }
  const addInstanceValue = (requirement: SubmissionPolicy['evidenceRequirements'][number]) => {
    if (requirementUsesStoreConfiguration(requirement)) return
    const field = requirementInstanceField(requirement)
    if (!field || (requirement.requiredInstances ?? 0) > 0) return
    setPayload((current) => {
      const currentValue = current[field]
      const values = Array.isArray(currentValue) ? currentValue : []
      return values.length >= 100 ? current : { ...current, [field]: [...values, ''] }
    })
  }
  const removeInstanceValue = (requirement: SubmissionPolicy['evidenceRequirements'][number], index: number) => {
    if (requirementUsesStoreConfiguration(requirement)) return
    const field = requirementInstanceField(requirement)
    if (!field || (requirement.requiredInstances ?? 0) > 0) return
    setPayload((current) => {
      const currentValue = current[field]
      const values = Array.isArray(currentValue) ? [...currentValue] : []
      values.splice(index, 1)
      return { ...current, [field]: values }
    })
  }
  const requirementAllowsImages = (requirement: SubmissionPolicy['evidenceRequirements'][number]) =>
    !requirement.allowedKinds?.length || requirement.allowedKinds.includes('IMAGE')
  const requirementAllowsDocuments = (requirement: SubmissionPolicy['evidenceRequirements'][number]) =>
    requirement.allowedKinds?.some((kind) => ['DOCUMENT', 'PDF', 'WORD', 'EXCEL'].includes(kind)) === true
  const requirementDocumentAccept = (requirement: SubmissionPolicy['evidenceRequirements'][number]) => {
    const extensions = new Set<string>()
    for (const kind of requirement.allowedKinds ?? []) {
      if (kind === 'DOCUMENT') ['.pdf', '.docx', '.xlsx'].forEach((extension) => extensions.add(extension))
      if (kind === 'PDF') extensions.add('.pdf')
      if (kind === 'WORD') extensions.add('.docx')
      if (kind === 'EXCEL') extensions.add('.xlsx')
    }
    return [...extensions].join(',')
  }
  const persistWorkFiles = (entries: PendingWorkEvidence[]) => {
    const stored = entries.map(workEvidenceToStored)
    filesDraftRef.current = stored
    if (!draftStorageReadyRef.current) {
      setMessage('设备草稿暂时无法读取，新增附件尚未在本机自动暂存；请保持页面打开并点击“保存草稿”。')
      return
    }
    const operation = stored.length
      ? writeResumableDraft(filesDraftKey, stored)
      : removeResumableDraft(filesDraftKey)
    void operation.catch((reason) => setMessage(reason instanceof Error ? reason.message : '照片暂存失败'))
  }
  const appendAttachments = (entries: PendingWorkEvidence[]) => {
    const storedCount = existing?.status === 'DRAFT' ? existing.attachments.length : 0
    setAttachments((current) => {
      const available = Math.max(0, policy.maxAttachments - storedCount - current.length)
      if (entries.length > available) {
        setMessage(`整单安全上限为 ${policy.maxAttachments} 个附件，本次仅保留前 ${available} 个。`)
      }
      if (!available) return current
      const next = [...current, ...entries.slice(0, available)]
      persistWorkFiles(next)
      return next
    })
  }
  const removePendingAttachment = (draftFileId: string) => {
    setAttachments((current) => {
      const next = current.filter((entry) => entry.draftFileId !== draftFileId)
      persistWorkFiles(next)
      return next
    })
  }
  const appendValidatedAttachments = async (entries: NewWorkEvidence[]) => {
    if (!entries.length) return
    setValidatingAttachments(true)
    setMessage(undefined)
    try {
      const result = await validateImageEvidence(entries)
      const materialized = await materializeSelectedFiles(result.accepted)
      if (materialized.accepted.length) appendAttachments(materialized.accepted.map((entry) => ({
        ...entry,
        draftFileId: globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`,
      })))
      const rejected = [
        ...result.rejected.map((entry) => ({ fileName: entry.entry.file.name, reason: entry.reason })),
        ...materialized.rejected.map((entry) => ({ fileName: entry.entry.file.name, reason: entry.reason })),
      ]
      if (rejected.length) {
        const first = rejected[0]
        const remaining = rejected.length - 1
        setMessage(`${first.fileName}：${first.reason}${remaining ? `；另有 ${remaining} 个附件未加入` : ''}`)
      }
    } finally {
      setValidatingAttachments(false)
    }
  }
  useEffect(() => {
    let active = true
    setLoadingRecord(true)
    setDraftHydrated(false)
    setConflictingLocalForm(undefined)
    setRecordLoadFailed(false)
    draftStorageReadyRef.current = false
    void Promise.all([
      readResumableDraft<WorkRecordFormDraft>(formDraftKey),
      readResumableDraft<StoredWorkEvidence[]>(filesDraftKey),
    ]).then(async ([localForm, localFiles]) => {
      const localRecordId = localForm?.schemaVersion === 1 ? localForm.serverRecordId : undefined
      const recordId = item.recordId ?? localRecordId
      let serverRecord: WorkRecordDetail | undefined
      let loadError: unknown
      if (recordId) {
        try { serverRecord = (await loadWorkRecord(identity, recordId)).data }
        catch (reason) { loadError = reason }
      }
      if (!active) return
      // A resting owner's in-progress draft remains immutable evidence.  The
      // delegate starts a new attempt instead of accidentally editing a draft
      // created under another assignment.
      const inheritedOwnerDraft = Boolean(serverRecord
        && serverRecord.status === 'DRAFT'
        && serverRecord.positionAssignmentId
        && serverRecord.positionAssignmentId !== executionAssignmentId)
      if (inheritedOwnerDraft) serverRecord = undefined
      activeRecordIdRef.current = inheritedOwnerDraft ? undefined : (serverRecord?.id ?? recordId)
      setExisting(serverRecord)
      const serverLocked = Boolean(serverRecord && ['SUBMITTED', 'APPROVED'].includes(serverRecord.status) && item.status !== 'FAILED')
      const serverPayload = serverRecord
        ? Object.fromEntries(Object.entries(serverRecord.payload).filter(isWorkRecordFieldValue))
        : {}
      const serverForm: WorkRecordFormDraft | undefined = serverRecord ? {
        schemaVersion: 1,
        completion: serverRecord.completionStatement ?? '',
        exception: serverRecord.exceptionStatement ?? '',
        nextAction: serverRecord.nextAction ?? '',
        payload: serverPayload,
        serverRecordId: serverRecord.id,
        serverRowVersion: serverRecord.rowVersion,
      } : undefined
      const validLocalForm = localForm?.schemaVersion === 1 && !inheritedOwnerDraft ? localForm : undefined
      const localHasContent = Boolean(validLocalForm && hasWorkRecordDraftContent(validLocalForm))
      const localConflictsWithServer = Boolean(
        serverRecord
        && ['DRAFT', 'REJECTED'].includes(serverRecord.status)
        && localHasContent
        && (validLocalForm?.serverRecordId !== serverRecord.id
          || validLocalForm.serverRowVersion !== serverRecord.rowVersion),
      )
      if (serverLocked) {
        setCompletion(serverForm?.completion ?? '')
        setException(serverForm?.exception ?? '')
        setNextAction(serverForm?.nextAction ?? '')
        setPayload(serverForm?.payload ?? {})
        if (serverForm) formDraftRef.current = serverForm
        await removeResumableDraft(formDraftKey)
      } else if (localConflictsWithServer && validLocalForm && serverForm) {
        setCompletion(serverForm.completion)
        setException(serverForm.exception)
        setNextAction(serverForm.nextAction)
        setPayload(serverForm.payload)
        formDraftRef.current = validLocalForm
        setConflictingLocalForm(validLocalForm)
      } else if (validLocalForm) {
        const currentLocalForm = {
          ...validLocalForm,
          serverRecordId: serverRecord?.id ?? validLocalForm.serverRecordId,
          serverRowVersion: serverRecord?.rowVersion ?? validLocalForm.serverRowVersion,
        }
        setCompletion(currentLocalForm.completion)
        setException(currentLocalForm.exception)
        setNextAction(currentLocalForm.nextAction)
        setPayload(currentLocalForm.payload)
        formDraftRef.current = currentLocalForm
      } else if (serverForm && ['DRAFT', 'REJECTED'].includes(serverRecord?.status ?? '')) {
        setCompletion(serverForm.completion)
        setException(serverForm.exception)
        setNextAction(serverForm.nextAction)
        setPayload(serverForm.payload)
        formDraftRef.current = serverForm
      }
      const storedFiles = (localFiles ?? [])
        .filter((entry): entry is StoredWorkEvidence => isStoredDraftFile(entry)
          && ['CAMERA', 'FILE_PICKER'].includes(entry.captureSource))
        .map(storedToWorkEvidence)
      const restored = await restoreReadableDraftFiles(storedFiles)
      if (!active) return
      const restoredFiles = restored.accepted
      setAttachments(restoredFiles)
      filesDraftRef.current = restoredFiles.map(workEvidenceToStored)
      // Rewrite every restored entry, including readable legacy entries, so
      // IndexedDB keeps the self-contained Blob instead of an expiring mobile
      // picker handle. This makes the repair durable across the next reload.
      if (storedFiles.length) {
        try {
          await (filesDraftRef.current.length
            ? writeResumableDraft(filesDraftKey, filesDraftRef.current)
            : removeResumableDraft(filesDraftKey))
        } catch {
          setMessage('无法清理已失效的本机附件，请重新打开后手动移除。')
        }
      }
      const removedDraftNotice = restored.rejected.length
        ? `已自动移除 ${restored.rejected.length} 个无法读取的旧附件，请重新拍摄或选择。`
        : ''
      if (inheritedOwnerDraft) {
        setDraftNotice(`原执行人的草稿已保留；你将以受托人身份新建工作记录。${removedDraftNotice ? ` ${removedDraftNotice}` : ''}`)
      } else if (!localConflictsWithServer && ((localForm?.schemaVersion === 1 && hasWorkRecordDraftContent(localForm)) || restoredFiles.length) && !serverLocked) {
        setDraftNotice(`已恢复上次未完成的内容${restoredFiles.length ? `和 ${restoredFiles.length} 个待上传附件` : ''}。${removedDraftNotice ? ` ${removedDraftNotice}` : ''}`)
      } else if (removedDraftNotice) {
        setDraftNotice(removedDraftNotice)
      } else if (loadError) {
        setMessage(loadError instanceof Error ? loadError.message : '工作记录加载失败')
      }
      setRecordLoadFailed(Boolean(loadError && recordId))
      draftStorageReadyRef.current = true
      setDraftHydrated(true)
    }).catch(async (error) => {
      if (active) {
        let serverLoadError: unknown
        if (item.recordId) {
          try {
            const serverRecord = (await loadWorkRecord(identity, item.recordId)).data
            if (!active) return
            const inheritedOwnerDraft = serverRecord.status === 'DRAFT'
              && Boolean(serverRecord.positionAssignmentId)
              && serverRecord.positionAssignmentId !== executionAssignmentId
            if (inheritedOwnerDraft) {
              activeRecordIdRef.current = undefined
              setExisting(undefined)
              setDraftNotice('原执行人的草稿已保留；你将以受托人身份新建工作记录。')
            } else {
              activeRecordIdRef.current = serverRecord.id
              setExisting(serverRecord)
            const recoveredPayload = Object.fromEntries(Object.entries(serverRecord.payload).filter(isWorkRecordFieldValue))
              setCompletion(serverRecord.completionStatement ?? '')
              setException(serverRecord.exceptionStatement ?? '')
              setNextAction(serverRecord.nextAction ?? '')
              setPayload(recoveredPayload)
              formDraftRef.current = {
                schemaVersion: 1,
                completion: serverRecord.completionStatement ?? '',
                exception: serverRecord.exceptionStatement ?? '',
                nextAction: serverRecord.nextAction ?? '',
                payload: recoveredPayload,
                serverRecordId: serverRecord.id,
                serverRowVersion: serverRecord.rowVersion,
              }
            }
          } catch (reason) {
            serverLoadError = reason
          }
        }
        if (!active) return
        setRecordLoadFailed(Boolean(item.recordId && serverLoadError))
        draftStorageReadyRef.current = false
        setMessage(`设备草稿暂时无法读取。为避免覆盖已保存内容，本页不会自动改写设备草稿；${serverLoadError ? '服务器草稿也读取失败，请刷新后重试。' : '可点击“保存草稿”写入服务端。'}${error instanceof Error ? `（${error.message}）` : ''}`)
      }
    }).finally(() => {
      if (active) setLoadingRecord(false)
    })
    return () => { active = false }
  }, [identity.key, item.id, item.recordId, formDraftKey, filesDraftKey])
  const chooseServerDraft = () => {
    if (!existing) return
    const serverForm: WorkRecordFormDraft = {
      schemaVersion: 1,
      completion: existing.completionStatement ?? '',
      exception: existing.exceptionStatement ?? '',
      nextAction: existing.nextAction ?? '',
      payload: Object.fromEntries(Object.entries(existing.payload).filter(isWorkRecordFieldValue)),
      serverRecordId: existing.id,
      serverRowVersion: existing.rowVersion,
    }
    setCompletion(serverForm.completion)
    setException(serverForm.exception)
    setNextAction(serverForm.nextAction)
    setPayload(serverForm.payload)
    formDraftRef.current = serverForm
    setConflictingLocalForm(undefined)
    setDraftNotice('已使用服务器上的最新草稿。')
    void writeResumableDraft(formDraftKey, serverForm).catch((reason) =>
      setMessage(reason instanceof Error ? reason.message : '服务器草稿暂存失败'))
  }
  const chooseLocalDraft = () => {
    if (!conflictingLocalForm) return
    const restored: WorkRecordFormDraft = {
      ...conflictingLocalForm,
      serverRecordId: existing?.id ?? conflictingLocalForm.serverRecordId,
      serverRowVersion: existing?.rowVersion ?? conflictingLocalForm.serverRowVersion,
    }
    setCompletion(restored.completion)
    setException(restored.exception)
    setNextAction(restored.nextAction)
    setPayload(restored.payload)
    formDraftRef.current = restored
    setConflictingLocalForm(undefined)
    setDraftNotice('已恢复本机未提交内容；保存时会基于服务器最新版本更新。')
    void writeResumableDraft(formDraftKey, restored).catch((reason) =>
      setMessage(reason instanceof Error ? reason.message : '本机草稿暂存失败'))
  }
  const downloadPendingAttachment = (entry: PendingWorkEvidence) => {
    const url = URL.createObjectURL(entry.file)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = entry.file.name
    anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
  }
  const discardLockedPendingAttachments = async () => {
    if (!window.confirm(`确认丢弃本机保留的 ${attachments.length} 个未上传附件吗？此操作不可恢复。`)) return
    try {
      await removeResumableDraft(filesDraftKey)
      filesDraftRef.current = []
      setAttachments([])
      setDraftNotice('本机未上传附件已按你的确认清除。')
    } catch (reason) {
      setMessage(reason instanceof Error ? reason.message : '本机附件清除失败')
    }
  }
  useEffect(() => {
    if (!draftHydrated || viewOnly || conflictingLocalForm) return
    const snapshot: WorkRecordFormDraft = {
      schemaVersion: 1,
      completion,
      exception,
      nextAction,
      payload,
      serverRecordId: activeRecordIdRef.current,
      serverRowVersion: existing?.rowVersion,
    }
    formDraftRef.current = snapshot
    const timeout = window.setTimeout(() => {
      const operation = hasWorkRecordDraftContent(snapshot)
        ? writeResumableDraft(formDraftKey, snapshot)
        : removeResumableDraft(formDraftKey)
      void operation.catch((reason) =>
        setMessage(reason instanceof Error ? reason.message : '文字草稿暂存失败'))
    }, 250)
    return () => window.clearTimeout(timeout)
  }, [completion, conflictingLocalForm, draftHydrated, exception, existing?.rowVersion, formDraftKey, nextAction, payload, viewOnly])
  useEffect(() => {
    if (!draftHydrated || viewOnly) return
    persistWorkFiles(attachments)
  }, [attachments, draftHydrated, filesDraftKey, viewOnly])
  useEffect(() => {
    if (!draftHydrated || viewOnly) return
    const flushDraft = () => {
      const form = formDraftRef.current
      void (hasWorkRecordDraftContent(form) ? writeResumableDraft(formDraftKey, form) : removeResumableDraft(formDraftKey))
      const stored = filesDraftRef.current
      void (stored.length ? writeResumableDraft(filesDraftKey, stored) : removeResumableDraft(filesDraftKey))
    }
    const flushWhenHidden = () => { if (document.visibilityState === 'hidden') flushDraft() }
    window.addEventListener('pagehide', flushDraft)
    document.addEventListener('visibilitychange', flushWhenHidden)
    return () => {
      window.removeEventListener('pagehide', flushDraft)
      document.removeEventListener('visibilitychange', flushWhenHidden)
      flushDraft()
    }
  }, [conflictingLocalForm, draftHydrated, filesDraftKey, formDraftKey, viewOnly])
  useEffect(() => {
    if (!isOwner || (!item.executionPolicy?.delegationAllowed && item.itemCode !== 'HKA_LOST_PROPERTY')) return
    void loadDelegationCandidates(identity, item.id).then(setCandidates).catch(() => { setCandidates([]); if (item.itemCode === 'HKA_LOST_PROPERTY') setMessage('接收人列表读取失败，请关闭后重新打开；请勿填写任职编号代替人员选择。') })
  }, [identity.key, isOwner, item.id, item.itemCode, item.executionPolicy?.delegationAllowed])
  const recordPayload = () => Object.keys(properties).length ? (usageSupported && usageCatalogue.data?.referenceUsage
    ? { ...payload, inventoryQuantities: usagePayload(payload.inventoryQuantities, usageCatalogue.data) } : payload) : payload
  const validate = (forSubmission: boolean) => {
    if (!canSubmit) throw new Error('接口结果缺少组织、员工、任职或表单版本，无法安全提交。')
    if (forSubmission && usageSupported) {
      if (usageCatalogue.loading || usageCatalogue.error || !usageCatalogue.data) throw new Error('物品清单尚未读取成功，请刷新后再提交。')
      const usageError = usageValidation(payload.inventoryQuantities, usageCatalogue.data)
      if (usageError) throw new Error(usageError)
    }
    if (forSubmission && !requiredReady) throw new Error('请完成表单中的所有必填项。')
    // Legacy completion statements remain in saved drafts; new submissions do not require them.
    if (forSubmission && policy.exceptionStatementRequired && !exception.trim()) throw new Error('请填写异常与协同事项。')
    if (forSubmission && policy.nextActionRequired && !nextAction.trim()) throw new Error('请填写下一步行动。')
    if (forSubmission && applicabilityConditionField && itemSubmissionApplicable === undefined) {
      throw new Error(`请明确选择“${properties[applicabilityConditionField]?.title ?? applicabilityConditionField}”。`)
    }
    const totalAttachments = (existing?.status === 'DRAFT' ? existing.attachments.length : 0) + attachments.length
    if (totalAttachments > policy.maxAttachments) throw new Error(`最多上传 ${policy.maxAttachments} 个附件。`)
    const oversized = attachments.find((entry) => entry.file.size > policy.maxFileSizeBytes)
    if (oversized) throw new Error(`${oversized.file.name} 超过单文件大小上限。`)
    const allowedExtensions = new Set(policy.allowedExtensions.map((extension) => extension.toLowerCase()))
    const unsupported = attachments.find((entry) => {
      const extension = entry.file.name.toLowerCase().split('.').pop() ?? ''
      return !allowedExtensions.has(extension)
    })
    if (unsupported) throw new Error(`${unsupported.file.name} 的文件格式不在该工作模板允许范围内。`)
    if (forSubmission && item.executionPolicy?.requiredRoomCountFormula === 'max(ceil(completedRooms*0.20),onDutyHousekeepers*5)') {
      const completedRooms = payload.completedRooms
      const onDutyHousekeepers = payload.onDutyHousekeepers
      if (typeof completedRooms !== 'number' || !Number.isInteger(completedRooms) || completedRooms < 0) throw new Error('请填写非负整数“已完成客房数”。')
      if (typeof onDutyHousekeepers !== 'number' || !Number.isInteger(onDutyHousekeepers) || onDutyHousekeepers < 0) throw new Error('请填写非负整数“当班客房服务员人数”。')
      const requiredRoomCount = Math.max(Math.ceil(completedRooms * 0.2), onDutyHousekeepers * 5)
      const roomNumbers = Array.isArray(payload.roomNumbers) ? payload.roomNumbers.map(normalizedInstanceKey).filter(Boolean) : []
      if (new Set(roomNumbers).size < requiredRoomCount) {
        throw new Error(`按完成房数和当班人数计算，本次至少需要填写 ${requiredRoomCount} 个不重复查房房号。`)
      }
    }
    if (forSubmission) for (const requirement of activeRequirements) {
      const instanceField = requirementInstanceField(requirement)
      const minimumPerInstance = requirementInstanceMinimum(requirement)
      if (instanceField && minimumPerInstance > 0) {
        const values = instanceValues(requirement)
        const normalizedValues = values.map(normalizedInstanceKey)
        if ((requirement.requiredInstances ?? 0) > 0 && normalizedValues.some((value) => !value)) {
          throw new Error(`${requirement.label}需填写 ${requirement.requiredInstances} 个${requirement.instanceLabel ?? '编号'}。`)
        }
        if ((requirement.requiredInstances ?? 0) === 0 && normalizedValues.some((value) => !value)) {
          throw new Error(`${requirement.label}中的${requirementInstanceLabel(requirement)}不能为空。`)
        }
        if (new Set(normalizedValues).size !== normalizedValues.length) {
          throw new Error(`${requirement.label}中的${requirementInstanceLabel(requirement)}不能重复。`)
        }
        for (const value of normalizedValues) {
          const stored = existing?.status === 'DRAFT'
            ? existing.attachments.filter((entry) => entry.checkpointCode === requirement.checkpointCode
              && evidenceCaptureSourceMatches(entry.captureSource, requirement.captureSource)
              && normalizedInstanceKey(entry.evidenceInstanceKey ?? '') === value).length
            : 0
          const pending = attachments.filter((entry) => entry.checkpointCode === requirement.checkpointCode
            && evidenceCaptureSourceMatches(entry.captureSource, requirement.captureSource)
            && normalizedInstanceKey(entry.evidenceInstanceKey ?? '') === value).length
          if (stored + pending < minimumPerInstance) {
            throw new Error(`${requirement.label}·${value}至少需要 ${minimumPerInstance} 张现场照片。`)
          }
        }
        continue
      }
      const stored = existing?.status === 'DRAFT'
        ? existing.attachments.filter((entry) => entry.checkpointCode === requirement.checkpointCode
          && evidenceCaptureSourceMatches(entry.captureSource, requirement.captureSource)).length
        : 0
      const pending = attachments.filter((entry) => entry.checkpointCode === requirement.checkpointCode
        && evidenceCaptureSourceMatches(entry.captureSource, requirement.captureSource)).length
      const minimum = requirementMinimum(requirement)
      if (stored + pending < minimum) throw new Error(`${requirement.label}至少需要 ${minimum} 张${allowsGuestServiceAlbumUpload(item.itemCode, requirement) ? '照片，可从相册选择后上传' : '现场照片'}。`)
    }
    if (forSubmission && itemSubmissionApplicable !== false && policy.attachmentRequired && totalAttachments === 0) throw new Error('该岗位工作要求必须上传附件证据。')
  }
  const persist = async (submitAfterUpload: boolean) => {
    if (recordLoadFailed) {
      setMessage('服务器草稿尚未成功读取。为避免生成重复记录，请刷新后重试。')
      return
    }
    if (conflictingLocalForm) {
      setMessage('服务器草稿与本机内容不一致，请先选择要继续使用的版本。')
      return
    }
    try { validate(submitAfterUpload) } catch (reason) { setMessage(reason instanceof Error ? reason.message : '提交校验失败'); return }
    const pendingAttachments = [...attachments]
    let activeRecordId: string | undefined
    let uploadedCount = 0
    setSaving(submitAfterUpload ? 'submit' : 'draft'); setMessage(undefined)
    setSaveProgress({ phase: 'draft', completed: 0, total: pendingAttachments.length })
    try {
      const draftInput = { payload: recordPayload(), completionStatement: completion, exceptionStatement: exception || undefined, nextAction: nextAction || undefined }
      const created = existing?.status === 'DRAFT'
        ? await updateWorkRecordDraft(identity, existing.id, { ...draftInput, expectedVersion: existing.rowVersion })
        : await createWorkRecordDraft(identity, {
          orgUnitId: assignmentOrgUnitId, employeeId, positionAssignmentId: executionAssignmentId,
          formVersionId: item.formVersionId, businessDate: item.businessDate,
          workPackageVersionId: item.workPackageVersionId, workPackageItemId: item.workPackageItemId,
          workExpectationId: item.id, targetOrgUnitId: item.orgUnitId,
          occurredAt: new Date().toISOString(), ...draftInput,
          supersedesWorkRecordId: item.status === 'FAILED' ? existing?.id ?? item.recordId : null,
        })
      const recordId = existing?.status === 'DRAFT' ? existing.id : String(created.id ?? created.recordId ?? '')
      if (!recordId) throw new Error('服务端未返回工作记录编号。')
      activeRecordId = recordId
      activeRecordIdRef.current = recordId
      formDraftRef.current = {
        ...formDraftRef.current,
        serverRecordId: recordId,
        serverRowVersion: Number(created.rowVersion ?? created.row_version ?? existing?.rowVersion ?? 0),
      }
      await writeResumableDraft(formDraftKey, formDraftRef.current)
      for (const [index, entry] of pendingAttachments.entries()) {
        setSaveProgress({ phase: 'upload', completed: index, total: pendingAttachments.length })
        await uploadWorkRecordAttachment(identity, recordId, entry.file, {
          captureSource: entry.captureSource,
          checkpointCode: entry.checkpointCode,
          evidenceInstanceKey: entry.evidenceInstanceKey,
          capturedAtClient: entry.capturedAtClient,
        })
        uploadedCount = index + 1
        const remaining = pendingAttachments.slice(index + 1)
        filesDraftRef.current = remaining.map(workEvidenceToStored)
        await (remaining.length
          ? writeResumableDraft(filesDraftKey, filesDraftRef.current)
          : removeResumableDraft(filesDraftKey))
        setAttachments((current) => current.filter((candidate) => candidate.draftFileId !== entry.draftFileId))
      }
      if (submitAfterUpload) {
        setSaveProgress({ phase: 'submit', completed: uploadedCount, total: pendingAttachments.length })
        await submitWorkRecordDraft(identity, recordId, Number(created.rowVersion ?? created.row_version ?? 0))
        await Promise.all([removeResumableDraft(formDraftKey), removeResumableDraft(filesDraftKey)])
      }
      await Promise.resolve(onSaved()); onClose()
    } catch (error) {
      if (activeRecordId) {
        try {
          const refreshed = await loadWorkRecord(identity, activeRecordId)
          setExisting(refreshed.data)
        } catch { /* keep the original actionable error */ }
      }
      const reason = error instanceof Error ? error.message : '工作记录保存失败'
      const remainingCount = Math.max(0, pendingAttachments.length - uploadedCount)
      setMessage(uploadedCount > 0
        ? `已安全保存 ${uploadedCount} 个附件，剩余 ${remainingCount} 个已保存在设备草稿；可直接重试或稍后返回继续。${reason}`
        : reason)
    } finally {
      setSaving(undefined)
      setSaveProgress(undefined)
    }
  }
  const startNewOccurrence = async () => {
    activeRecordIdRef.current = undefined
    formDraftRef.current = { schemaVersion: 1, completion: '', exception: '', nextAction: '', payload: {} }
    filesDraftRef.current = []
    setExisting(undefined)
    setCompletion('')
    setException('')
    setNextAction('')
    setPayload({})
    setAttachments([])
    setConflictingLocalForm(undefined)
    setRecordLoadFailed(false)
    setMessage(undefined)
    try {
      await Promise.all([removeResumableDraft(formDraftKey), removeResumableDraft(filesDraftKey)])
      setDraftNotice(`正在新增当日第 ${(item.records?.filter((record) => ['SUBMITTED', 'APPROVED'].includes(record.status)).length ?? 0) + 1} 条记录；提交后不会覆盖此前记录。`)
    } catch {
      setDraftNotice('已开始填写一条新记录；本机旧草稿清理失败，但不会覆盖服务器上的历史记录。')
    }
  }
  const openOccurrence = async (recordId: string) => {
    setLoadingRecord(true)
    setMessage(undefined)
    setDraftNotice(undefined)
    setConflictingLocalForm(undefined)
    setRecordLoadFailed(false)
    try {
      const record = (await loadWorkRecord(identity, recordId)).data
      const recordPayload = Object.fromEntries(Object.entries(record.payload).filter(isWorkRecordFieldValue))
      activeRecordIdRef.current = record.id
      setExisting(record)
      setCompletion(record.completionStatement ?? '')
      setException(record.exceptionStatement ?? '')
      setNextAction(record.nextAction ?? '')
      setPayload(recordPayload)
      formDraftRef.current = {
        schemaVersion: 1,
        completion: record.completionStatement ?? '',
        exception: record.exceptionStatement ?? '',
        nextAction: record.nextAction ?? '',
        payload: recordPayload,
        serverRecordId: record.id,
        serverRowVersion: record.rowVersion,
      }
    } catch (reason) {
      setRecordLoadFailed(true)
      setMessage(reason instanceof Error ? reason.message : '工作记录加载失败')
    } finally {
      setLoadingRecord(false)
    }
  }
  const addSupplement = async () => {
    if (!existing || !identity.businessActorAssignmentId || !supplement.trim()) return
    setSaving('supplement'); setMessage(undefined)
    try {
      await addWorkRecordSupplement(identity, existing.id, identity.businessActorAssignmentId, supplement)
      const refreshed = await loadWorkRecord(identity, existing.id, existing)
      setExisting(refreshed.data); setSupplement(''); onSaved()
    } catch (error) { setMessage(error instanceof Error ? error.message : '补充说明失败') }
    finally { setSaving(undefined) }
  }
  const preview = async (attachmentId: string, originalName: string, declaredType?: string) => {
    try {
      const blob = await loadAttachmentContent(identity, attachmentId)
      setAttachmentPreview({
        url: URL.createObjectURL(blob),
        name: originalName,
        mediaType: resolveAttachmentMediaType(blob, originalName, declaredType),
      })
    } catch (error) { setMessage(error instanceof Error ? error.message : '附件打开失败') }
  }
  const saveDelegation = async () => {
    if (!delegateAssignmentId) { setMessage('请选择同店受托员工。'); return }
    setDelegating(true); setMessage(undefined)
    try {
      const result = await delegateExpectation(identity, item.id, { delegateAssignmentId, ownerResting, reason: delegationReason || undefined, expectedVersion: item.rowVersion ?? 0 })
      onDelegated(ownerResting && item.executionPolicy?.oneClickRestTransfer
        ? `已将本人今日当前及后续 ${result.transferredCount} 项工作转交给受托人。`
        : `已成功转交 ${result.transferredCount} 项工作。`)
      await onSaved(); onClose()
    } catch (error) { setMessage(error instanceof Error ? error.message : '转交失败') }
    finally { setDelegating(false) }
  }
  const revokeDelegation = async () => {
    setDelegating(true); setMessage(undefined)
    try { await revokeExpectationDelegation(identity, item.id, item.rowVersion ?? 0); onSaved(); onClose() }
    catch (error) { setMessage(error instanceof Error ? error.message : '撤销转交失败') }
    finally { setDelegating(false) }
  }
  const saveProgressText = saveProgress?.phase === 'upload'
    ? `正在上传并安全扫描第 ${Math.min(saveProgress.completed + 1, saveProgress.total)}/${saveProgress.total} 个附件，请勿退出`
    : saveProgress?.phase === 'submit'
      ? '全部附件已安全保存，正在提交工作记录'
      : saveProgress?.phase === 'draft'
        ? '正在保存工作记录草稿'
        : undefined
  const issueEditor = !viewOnly && usageCatalogue.data?.mode === 'ISSUE'
  const submitButtonText = saving === 'submit'
    ? saveProgress?.phase === 'upload'
      ? `上传 ${saveProgress.completed}/${saveProgress.total}`
      : saveProgress?.phase === 'submit' ? '正在提交…' : '正在保存…'
    : item.status === 'FAILED' ? '重新提交' : issueEditor ? '提交领用' : '上传并提交'
  return <div className="modal-backdrop" role="presentation"><section className={`modal work-record-modal${issueEditor ? ' issue-work-record-modal' : ''}`} role="dialog" aria-modal="true">
    <header><div><span className="panel-kicker">WORK RECORD · REAL API</span><h2>{issueEditor ? '物品领用' : item.title}</h2></div><button className="close" aria-label="关闭" disabled={!!saving || validatingAttachments} onClick={onClose}>×</button></header>
    <div className="form-body">
      {!viewOnly && usageSupported && <section className="work-usage-slot">
        {usageCatalogue.loading && <p role="status">正在读取门店物品清单…</p>}
        {!!usageCatalogue.error && <div className="inline-error" role="alert">门店物品清单读取失败，暂不能提交。<button type="button" className="secondary" onClick={() => void usageCatalogue.reload()}>重新读取</button></div>}
        {usageCatalogue.data?.enabled && !loadingRecord && !conflictingLocalForm && <WorkUsageFields catalogue={usageCatalogue.data} value={payload.inventoryQuantities} disabled={!!saving || validatingAttachments} onChange={(value) => setPayload((current) => ({ ...current, inventoryQuantities: value }))} onRefresh={() => void usageCatalogue.reload()} />}
        {usageCatalogue.data && !usageCatalogue.data.enabled && <p className="inline-warning">本店尚未启用耗用审核联动，下方文字数量只作为工作记录保存，不自动扣库存。请联系管理员在“日清耗用审核”配置审核人。</p>}
      </section>}
      <details className={issueEditor ? 'issue-supplements' : 'work-record-supplements'} open={!issueEditor || !!conflictingLocalForm || !!attachments.length}><summary hidden={!issueEditor}>补充记录与附件{policy.attachmentRequired ? '（必填）' : '（可选）'}</summary><div className="supplement-fields"><div className="form-context"><strong>{item.formName ?? '岗位工作记录'}</strong><small>{item.formCode ?? '结构化表单'} · {policy.attachmentCountUnlimited ? `每个房号照片数量不限，整单安全上限 ${policy.maxAttachments} 个附件` : `最多 ${policy.maxAttachments} 个附件`} · 单文件 ≤ {Math.round(policy.maxFileSizeBytes / 1024 / 1024)}MB</small></div>
      {item.delegateAssignmentId && <div className="inline-warning">本事项已转交给 {item.delegatedEmployeeName ?? '指定员工'}{item.ownerResting ? '，原执行人今日休息' : ''}。</div>}
      {isOwner && item.executionPolicy?.delegationAllowed && !viewOnly && <section className="action-box"><label>转交本项工作<select value={delegateAssignmentId} onChange={(event) => setDelegateAssignmentId(event.target.value)}><option value="">请选择同店员工</option>{candidates.map((candidate) => <option value={candidate.assignmentId} key={candidate.assignmentId}>{candidate.employeeName} · {candidate.positionName}</option>)}</select></label>{item.executionPolicy.oneClickRestTransfer && <label className="checkbox-line"><input type="checkbox" checked={ownerResting} onChange={(event) => setOwnerResting(event.target.checked)} />本人今日休息，将当日后续工作一键转交</label>}<label>转交说明<input value={delegationReason} onChange={(event) => setDelegationReason(event.target.value)} placeholder="选填" /></label><div className="button-row"><button className="secondary" disabled={delegating || !delegateAssignmentId} onClick={() => void saveDelegation()}>{delegating ? '处理中…' : ownerResting ? '确认一键转交' : '确认转交'}</button>{item.delegateAssignmentId && <button className="secondary" disabled={delegating} onClick={() => void revokeDelegation()}>撤销转交</button>}</div></section>}
      {loadingRecord && <div className="state-card"><div className="spinner" /><strong>正在读取已提交记录</strong></div>}
      {existing?.inventoryUsage && <UsageEvidenceSummary usage={existing.inventoryUsage} />}

      {conflictingLocalForm && <div className="inline-warning"><strong>发现两个版本的未提交内容</strong><p>服务器草稿已在其他页面或设备更新，本机仍保留一份未提交内容。请选择一个版本后再继续，系统不会自动覆盖。</p><div className="button-row"><button className="secondary" onClick={chooseServerDraft}>使用服务器最新草稿</button><button className="secondary" onClick={chooseLocalDraft}>恢复本机未提交内容</button></div></div>}
      {occurrenceEntry && <div className="inline-success"><strong>发生时登记</strong><p>未发生无需填写，不催交、不计逾期，也不计入完成率分母。每次发生可独立登记，全部汇总到日报。</p></div>}
      {existing && ['SUBMITTED', 'APPROVED'].includes(existing.status) && <AiEvidenceRecord key={existing.id} identity={identity} recordId={existing.id} />}
      {repeatableDailyRecords && <section className="detail-section"><h3>当日业务记录</h3><p className="muted">已形成 {item.records?.filter((record) => ['SUBMITTED', 'APPROVED'].includes(record.status)).length ?? 0} 条有效记录；每条记录独立保留照片、内容和审核结果，并全部汇总到今日日报。</p>{viewOnly && <div className="button-row"><button type="button" className="primary" onClick={() => void startNewOccurrence()}>+ 新增一条记录</button>{item.records?.map((record) => <button type="button" className={existing?.id === record.id ? 'primary' : 'secondary'} key={record.id} onClick={() => void openOccurrence(record.id)}>第 {record.seriesEntryNo ?? 1} 条 · {label(record.status)}</button>)}</div>}</section>}
      {existing && <section className="detail-section"><h3>最近一次提交 · {label(existing.status)}</h3><dl><div><dt>完成情况</dt><dd>{existing.completionStatement ?? String(existing.payload.serviceContent ?? existing.payload.complaintContent ?? existing.payload.summary ?? '—')}</dd></div><div><dt>异常事项</dt><dd>{existing.exceptionStatement ?? String(existing.payload.exception ?? '无')}</dd></div><div><dt>下一步行动</dt><dd>{existing.nextAction ?? '—'}</dd></div><div><dt>提交时间</dt><dd>{formatDate(existing.submittedAt)}</dd></div></dl>{existing.reviewReason && <div className="inline-warning">复核意见：{existing.reviewReason}</div>}<div className="attachment-list">{existing.attachments.map((attachment) => <button className="secondary" key={attachment.id} onClick={() => void preview(attachment.id, attachment.originalName, attachment.mediaType)}>{attachment.evidenceInstanceKey ? `编号 ${attachment.evidenceInstanceKey} · ` : ''}{attachment.originalName} · {Math.ceil(attachment.sizeBytes / 1024)}KB</button>)}</div>{existing.supplements.map((entry) => <p className="muted" key={entry.id}>补充：{entry.content} · {entry.submittedByName}</p>)}</section>}
      {viewOnly && !!attachments.length && <section className="detail-section"><div className="inline-warning"><strong>本机还有 {attachments.length} 个未上传附件</strong><p>该工作记录已在其他页面或设备提交，系统没有自动删除这些照片。你可以先逐个导出留存，再明确选择是否丢弃。</p></div><div className="pending-evidence-list">{attachments.map((entry) => <button type="button" className="secondary" key={entry.draftFileId} onClick={() => downloadPendingAttachment(entry)}>{pendingWorkEvidenceLabel(entry)} · 导出 {entry.file.name}</button>)}</div><button type="button" className="danger-button" onClick={() => void discardLockedPendingAttachments()}>丢弃本机未上传附件</button></section>}
      {viewOnly && existing?.status === 'SUBMITTED' && <section className="action-box"><label>补充说明<textarea rows={3} value={supplement} onChange={(event) => setSupplement(event.target.value)} placeholder="待主管复核前可追加说明，原提交内容不会被覆盖" /></label><button className="secondary" disabled={saving === 'supplement' || !supplement.trim()} onClick={() => void addSupplement()}>{saving === 'supplement' ? '补充中…' : '追加说明'}</button></section>}
      {!viewOnly && !loadingRecord && !conflictingLocalForm && <>{item.status === 'FAILED' && <div className="inline-warning">上一版未通过，本次提交会生成新的尝试记录，不覆盖历史证据。</div>}{Object.values(properties).some((definition) => definition.type !== 'array') && <div className="dynamic-form">{Object.entries(properties).filter(([key, definition]) => definition.type !== 'array' && !(key === 'inventoryQuantities' && usageSupported && (usageCatalogue.loading || usageCatalogue.error || usageCatalogue.data?.enabled))).map(([key, definition]) => <label key={key}>{fieldLabels[key] ?? definition.title ?? key}{required.includes(key) ? ' *' : ''}{key === 'handoverRecipientAssignmentId' && item.itemCode === 'HKA_LOST_PROPERTY' ? <select value={String(payload[key] ?? '')} onChange={(event) => setPayload((current) => ({ ...current, [key]: event.target.value }))}><option value="">请选择同店接收人</option>{candidates.map((candidate) => <option value={candidate.assignmentId} key={candidate.assignmentId}>{candidate.employeeName} · {candidate.positionName}</option>)}</select> : key === 'foundAt' ? <input type="datetime-local" value={String(payload[key] ?? '')} onChange={(event) => setPayload((current) => ({ ...current, [key]: event.target.value }))} /> : definition.type === 'object' ? <textarea rows={4} value={structuredObjectText(payload[key])} placeholder="每行填写一项，例如：牙刷=2" onChange={(event) => setPayload((current) => ({ ...current, [key]: structuredObjectValue(event.target.value) }))} /> : definition.enum?.length ? <select value={String(payload[key] ?? '')} onChange={(event) => setPayload((current) => ({ ...current, [key]: event.target.value }))}><option value="">请选择</option>{definition.enum.map((option) => <option key={String(option)} value={String(option)}>{String(option)}</option>)}</select> : definition.type === 'boolean' ? <select value={String(payload[key] ?? '')} onChange={(event) => setPayload((current) => ({ ...current, [key]: event.target.value === '' ? '' : event.target.value === 'true' }))}><option value="">请选择</option><option value="true">是</option><option value="false">否</option></select> : ['integer', 'number'].includes(definition.type ?? '') ? <input type="number" min={definition.minimum} max={definition.maximum} value={String(payload[key] ?? '')} onChange={(event) => setPayload((current) => ({ ...current, [key]: event.target.value === '' ? '' : Number(event.target.value) }))} /> : <textarea rows={3} value={String(payload[key] ?? '')} onChange={(event) => setPayload((current) => ({ ...current, [key]: event.target.value }))} />}{definition.type === 'object' && <small>按“名称=数量或读数”逐行填写，系统会保存为结构化数据。</small>}{definition.description && <small>{definition.description}</small>}</label>)}</div>}
        {itemSubmissionApplicable === false && <div className="inline-success">本项已明确为当日不适用；提交后将保留记录并从完成率分母排除，无需上传证据。</div>}
        {policy.exceptionStatementRequired && <label>需协同事项 *<textarea rows={3} value={exception} onChange={(event) => setException(event.target.value)} /></label>}
        {policy.nextActionRequired && <label>下一步行动 *<textarea rows={2} value={nextAction} onChange={(event) => setNextAction(event.target.value)} /></label>}
        {!!activeRequirements.length && <section className="camera-evidence">
          <div><strong>{guestServiceAlbumUpload ? '服务照片' : '现场证据'}</strong><small>{guestServiceAlbumUpload
            ? '可从相册选择之前拍摄的照片，不要求实时拍摄；也可直接拍照。可先保存草稿，之后补齐照片再提交。相册照片会标明上传来源，不作为现场拍摄凭证。'
            : <>照片可横拍或竖拍，系统会校正手机照片方向并按实际比例展示；上传后由服务端加盖门店、可信时间和证据编号水印{hasInstanceRequirements ? '；按房号、设施或设备逐项留证，不得混用编号' : `；楼层公区按本店 ${item.guestRoomFloorCount ?? 1} 层计算`}</>}</small></div>
          {activeRequirements.map((requirement) => {
            const instanceField = requirementInstanceField(requirement)
            const minimumPerInstance = requirementInstanceMinimum(requirement)
            if (instanceField && minimumPerInstance > 0) {
              const fixedCount = requirement.requiredInstances ?? 0
              const instanceLabel = requirementInstanceLabel(requirement)
              const storeConfigured = requirementUsesStoreConfiguration(requirement)
              const configuredValues = instanceValues(requirement)
              return <section className="room-evidence-group" key={requirement.checkpointCode}>
                <header><strong>{requirement.label}</strong><small>{storeConfigured ? `门店后台已配置 ${configuredValues.length} 个${instanceLabel}` : fixedCount > 0 ? `需填写 ${fixedCount} 个${instanceLabel}` : `逐项填写${instanceLabel}`}，每项至少 {minimumPerInstance} 份证据且编号不可重复</small>{!storeConfigured && fixedCount === 0 && <button type="button" className="secondary" onClick={() => addInstanceValue(requirement)}>+ 添加{instanceLabel}</button>}</header>
                {!configuredValues.length && fixedCount === 0 && <div className="inline-warning">门店后台尚未配置该证据点的{instanceLabel}清单，请联系管理员完成配置后再提交。</div>}
                <div className="room-evidence-grid">{configuredValues.map((instanceValue, instanceIndex) => {
                  const instanceKey = normalizedInstanceKey(instanceValue)
                  const storedCount = existing?.status === 'DRAFT' ? existing.attachments.filter((entry) => entry.checkpointCode === requirement.checkpointCode && normalizedInstanceKey(entry.evidenceInstanceKey ?? '') === instanceKey).length : 0
                  const pendingCount = attachments.filter((entry) => entry.checkpointCode === requirement.checkpointCode && normalizedInstanceKey(entry.evidenceInstanceKey ?? '') === instanceKey).length
                  const evidenceCount = instanceKey ? storedCount + pendingCount : 0
                  return <article className={evidenceCount >= minimumPerInstance ? 'room-evidence-card completed' : 'room-evidence-card'} key={`${requirement.checkpointCode}-${instanceIndex}`}>
                    <label>{instanceLabel} {instanceIndex + 1} *<input value={instanceValue} maxLength={120} readOnly={storeConfigured} aria-readonly={storeConfigured} placeholder={instanceLabel === '房号' ? '例如 1208' : '例如 设备-01'} onChange={(event) => updateInstanceValue(requirement, instanceIndex, event.target.value)} onBlur={(event) => updateInstanceValue(requirement, instanceIndex, normalizedInstanceKey(event.target.value))} /></label>
                    <div><span>{evidenceCount} 份{requirement.unlimited ? '·数量不限' : ''}</span>{requirementAllowsImages(requirement) && <label className="room-photo-button"><input type="file" accept="image/jpeg,image/png" capture="environment" multiple disabled={!instanceKey || validatingAttachments || !!saving} onChange={(event) => { const selected = Array.from(event.target.files ?? []).map((file) => ({ file, captureSource: 'CAMERA' as const, checkpointCode: requirement.checkpointCode, evidenceInstanceKey: instanceKey, capturedAtClient: new Date().toISOString() })); void appendValidatedAttachments(selected); event.currentTarget.value = '' }} />{validatingAttachments ? '校验中…' : evidenceCount ? '继续拍照' : '拍照上传'}</label>}{requirementAllowsDocuments(requirement) && <label className="room-photo-button"><input type="file" accept={requirementDocumentAccept(requirement)} multiple disabled={!instanceKey || validatingAttachments || !!saving} onChange={(event) => { const selected = Array.from(event.target.files ?? []).map((file) => ({ file, captureSource: 'FILE_PICKER' as const, checkpointCode: requirement.checkpointCode, evidenceInstanceKey: instanceKey })); void appendValidatedAttachments(selected); event.currentTarget.value = '' }} />上传文件</label>}{!storeConfigured && fixedCount === 0 && <button type="button" className="secondary" disabled={!!saving} onClick={() => removeInstanceValue(requirement, instanceIndex)}>删除此项</button>}</div>
                  </article>
                })}</div>
              </section>
            }
            const minimum = requirementMinimum(requirement)
            const count = attachments.filter((entry) => entry.checkpointCode === requirement.checkpointCode).length + (existing?.status === 'DRAFT' ? existing.attachments.filter((entry) => entry.checkpointCode === requirement.checkpointCode).length : 0)
            const allowsAlbumUpload = allowsGuestServiceAlbumUpload(item.itemCode, requirement)
            const checkpointClassName = count >= minimum ? 'camera-checkpoint completed' : 'camera-checkpoint'
            return <div className="camera-grid" key={requirement.checkpointCode}>
              {requirementAllowsImages(requirement) && allowsAlbumUpload && <label className={checkpointClassName}>
                <span>{requirement.label} · 相册<small>{count}/{minimum} 份，可稍后补齐</small></span>
                <input type="file" accept="image/jpeg,image/png" multiple disabled={validatingAttachments || !!saving} onChange={(event) => {
                  const selected = Array.from(event.target.files ?? []).map((file) => ({ file, captureSource: 'FILE_PICKER' as const, checkpointCode: requirement.checkpointCode }))
                  void appendValidatedAttachments(selected)
                  event.currentTarget.value = ''
                }} />
                <b>{validatingAttachments ? '校验中…' : count >= minimum ? '继续选择照片' : '选择已有照片'}</b>
              </label>}
              {requirementAllowsImages(requirement) && (!allowsAlbumUpload || requirement.captureSource !== 'FILE_PICKER') && <label className={checkpointClassName}>
                <span>{requirement.label}{allowsAlbumUpload ? ' · 拍照' : ''}<small>{count}/{minimum} 份{requirement.recommendedMaximum ? `，建议不超过 ${requirement.recommendedMaximum}` : ''}</small></span>
                <input type="file" accept="image/jpeg,image/png" capture="environment" multiple disabled={validatingAttachments || !!saving} onChange={(event) => {
                  const selected = Array.from(event.target.files ?? []).map((file) => ({ file, captureSource: 'CAMERA' as const, checkpointCode: requirement.checkpointCode, capturedAtClient: new Date().toISOString() }))
                  void appendValidatedAttachments(selected)
                  event.currentTarget.value = ''
                }} />
                <b>{validatingAttachments ? '校验中…' : allowsAlbumUpload ? '直接拍照（可选）' : count >= minimum ? '已满足，可继续上传' : '拍照上传'}</b>
              </label>}
              {requirementAllowsDocuments(requirement) && <label className={checkpointClassName}><span>{requirement.label}文件<small>支持该证据点允许的文档格式</small></span><input type="file" accept={requirementDocumentAccept(requirement)} multiple disabled={validatingAttachments || !!saving} onChange={(event) => { const selected = Array.from(event.target.files ?? []).map((file) => ({ file, captureSource: 'FILE_PICKER' as const, checkpointCode: requirement.checkpointCode })); void appendValidatedAttachments(selected); event.currentTarget.value = '' }} /><b>选择文件</b></label>}
            </div>
          })}
        </section>}
        <label className="attachment-picker">{guestServiceAlbumUpload ? '补充服务照片' : '补充文件'}{policy.attachmentRequired && itemSubmissionApplicable !== false ? ' *' : '（可选）'}<input type="file" multiple disabled={validatingAttachments || !!saving} accept={policy.allowedExtensions.map((extension) => `.${extension}`).join(',')} onChange={(event) => { const selected = Array.from(event.target.files ?? []).map((file) => ({ file, captureSource: 'FILE_PICKER' as const, ...(guestServiceAlbumUpload && /\.(jpe?g|png)$/i.test(file.name) ? { checkpointCode: 'guest_service_photo' } : {}) })); void appendValidatedAttachments(selected); event.currentTarget.value = '' }} /><small>{validatingAttachments ? '正在校验图片…' : attachments.length ? `待上传 ${attachments.length} 个：${attachments.map((entry) => entry.file.name).join('、')}` : `选中的文件会先保存在本机草稿，切出后可继续；允许 ${policy.allowedExtensions.map((extension) => extension.toUpperCase()).join('、')}，提交时服务端会再次校验`}</small></label>{!!attachments.length && <div className="pending-evidence-list">{attachments.map((entry) => <button type="button" className="secondary" key={entry.draftFileId} onClick={() => removePendingAttachment(entry.draftFileId)}>{pendingWorkEvidenceLabel(entry)} · {entry.file.name} ×</button>)}</div>}</>}
      </div></details><div className="work-record-feedback">
      {!canSubmit && !viewOnly && <div className="inline-warning">此条接口数据尚未返回完整提交上下文；页面不会猜测任职或表单版本。</div>}{saveProgressText && <div className="work-save-progress" role="status" aria-live="polite"><strong>{saveProgressText}</strong>{saveProgress?.phase === 'upload' && <><progress value={saveProgress.completed} max={Math.max(1, saveProgress.total)} /><small>每个附件都要完成服务端安全扫描；已成功的附件会立即从待上传列表移除，失败后可断点重试。</small></>}</div>}{draftNotice && <div className="inline-success">{draftNotice}</div>}{message && <div className="inline-error">{message}</div>}
      </div>
    </div><footer><button className="secondary" disabled={!!saving || validatingAttachments} onClick={onClose}>关闭</button>{!viewOnly && !loadingRecord && <><button className="secondary" disabled={!!saving || validatingAttachments || !!conflictingLocalForm} onClick={() => void persist(false)}>{saving === 'draft' ? saveProgress?.phase === 'upload' ? `上传 ${saveProgress.completed}/${saveProgress.total}` : '保存中…' : '保存草稿'}</button><button className="primary" disabled={!!saving || validatingAttachments || !!conflictingLocalForm} onClick={() => void persist(true)}>{submitButtonText}</button></>}</footer>
    {attachmentPreview && <AttachmentPreviewDialog preview={attachmentPreview} onClose={() => setAttachmentPreview(undefined)} />}
  </section></div>
}

const workFilters = ['ALL', 'PENDING_WORK', 'AVAILABLE', 'PLANNED', 'IN_PROGRESS', 'SUBMITTED', 'SATISFIED', 'FAILED', 'MISSED'] as const

function MyWork({ identity, routeParams, go }: { identity: RoleContext; routeParams: RouteParams; go: AppNavigate }) {
  const resource = useWorkBoard(identity, 'my', routeParams)
  const [editing, setEditing] = useState<WorkExpectation>()
  const [opening, setOpening] = useState(false)
  const [openError, setOpenError] = useState<string>()
  const [delegationNotice, setDelegationNotice] = useState<string>()
  const change = (patch: import('./domain').NavigationParams) => go('my-work', withBoardParams(routeParams, patch))
  useEffect(() => {
    let active = true
    setEditing(undefined); setOpenError(undefined); setOpening(false)
    if (routeParams.expectationId) {
      setOpening(true)
      loadExpectation(identity, routeParams.expectationId).then(result => {
        if (!active) return
        if ((result.data.itemCode === 'HKA_DAILY_REPORT' && routeParams.entry !== 'legacy')
          || (['HKA_GUEST_SERVICE_EVENT', 'HKA_LOST_PROPERTY'].includes(result.data.itemCode ?? '') && !result.data.recordId)) {
          go('daily-reports-my', { businessDate: result.data.businessDate, open: 'today' })
        } else if (result.data.itemCode === 'GM_W_EMPLOYEE') {
          go('workbench', { cycle: 'WEEK', cycleDate: result.data.businessDate, assessmentId: result.data.id })
        } else setEditing(result.data)
      })
        .catch(reason => { if (active) setOpenError(reason instanceof Error ? reason.message : '工作详情加载失败') })
        .finally(() => { if (active) setOpening(false) })
    }
    return () => { active = false }
  }, [identity.key, routeParams.expectationId, routeParams.entry])
  return <section className="wb-page">
    <header className="wb-page-heading"><div><h1>我的工作</h1><p>当前任职：{identity.label} · {identity.orgName}。切换岗位不会混入其他任职。</p></div>
      <button className="secondary" onClick={() => go('workbench', withBoardParams(routeParams, { status: undefined, queue: undefined }))}>返回工作台</button></header>
    <WorkDateToolbar params={routeParams} businessDate={resource.data.businessDate} onChange={change} />
    <article className="wb-panel"><WorkStatusCards metric={resource.data.period} loading={resource.loading || Boolean(resource.error)}
      onSelect={status => change({ status, queue: undefined })} selected={resource.query.status} />
      {!resource.loading && !resource.error && <CompletionRates metric={resource.data.period} />}</article>
    {!resource.loading && !resource.error && <WorkQueueAlerts data={resource.data} onOpen={change} />}
    {openError && <div className="inline-error" role="alert">{openError}</div>}
    {opening && <p role="status">正在打开工作详情…</p>}
    {delegationNotice && <div className="inline-success" role="status">{delegationNotice}</div>}
    <WorkBoardList data={resource.data} loading={resource.loading} error={resource.error} params={routeParams}
      onChange={change} own assignmentId={identity.businessActorAssignmentId} onOpen={item => go('my-work', { ...routeParams, expectationId: item.id })} onRetry={resource.reload} />
    {editing && <WorkRecordDialog key={identity.key + ':' + editing.id} item={editing} identity={identity}
      onClose={() => go('my-work', { ...routeParams, expectationId: undefined })} onSaved={resource.reload} onDelegated={setDelegationNotice} />}
  </section>
}

function TeamWork({ identity, permissions, routeParams, go }: { identity: RoleContext; permissions: string[]; routeParams: RouteParams; go: Navigate }) {
  return <TeamWorkPage identity={identity} permissions={permissions} routeParams={routeParams} go={go} />
}

const defaultCondition = '{\n  "op": "EXISTS",\n  "fact": "workRecordId"\n}'
const defaultActions = '[\n  {\n    "key": "notify-owner",\n    "type": "CREATE_NOTIFICATION",\n    "recipientResolver": "CURRENT_ASSIGNMENT",\n    "title": "请处理规则命中事项"\n  }\n]'

function RuleEditor({ identity, detail, permissions, onClose, onSaved }: {
  identity: RoleContext
  detail?: RuleDetail
  permissions: string[]
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const latest = detail?.versions[0]
  const [code, setCode] = useState(detail?.code ?? '')
  const [name, setName] = useState(detail?.name ?? '')
  const [eventType, setEventType] = useState(detail?.eventType ?? 'WORK_RECORD_SUBMITTED')
  const [description, setDescription] = useState(detail?.description ?? '')
  const [condition, setCondition] = useState(latest ? JSON.stringify(latest.conditionAst, null, 2) : defaultCondition)
  const [actions, setActions] = useState(latest ? JSON.stringify(latest.actions, null, 2) : defaultActions)
  const [priority, setPriority] = useState(String(latest?.priority ?? 100))
  const [cooldown, setCooldown] = useState(String(latest?.cooldownMinutes ?? 0))
  const [busy, setBusy] = useState<'save' | 'publish'>()
  const [error, setError] = useState<string>()

  const readVersion = (): RuleVersionDraft => {
    const conditionAst: unknown = JSON.parse(condition)
    const parsedActions: unknown = JSON.parse(actions)
    if (!conditionAst || typeof conditionAst !== 'object' || Array.isArray(conditionAst)) throw new Error('判断条件必须是JSON对象。')
    if (!Array.isArray(parsedActions) || parsedActions.length === 0) throw new Error('执行动作必须是非空JSON数组。')
    return {
      conditionAst: conditionAst as Record<string, unknown>, actions: parsedActions as Array<Record<string, unknown>>,
      priority: Number(priority), cooldownMinutes: Number(cooldown), scopes: latest?.scopes.length ? latest.scopes : [{ scopeType: 'TENANT' }],
    }
  }

  const save = async () => {
    setBusy('save'); setError(undefined)
    try {
      const version = readVersion()
      if (!detail) await createRuleWithVersion(identity, { code, name, eventType, description: description || undefined }, version)
      else if (latest) await saveRuleVersion(identity, detail.id, latest, version)
      else throw new Error('规则详情缺少版本上下文，无法安全修改。')
      await onSaved(); onClose()
    } catch (failure) { setError(failure instanceof Error ? failure.message : '规则保存失败。') }
    finally { setBusy(undefined) }
  }

  const publish = async () => {
    if (!detail || !latest || latest.lifecycleStatus !== 'DRAFT') return
    setBusy('publish'); setError(undefined)
    try { await publishRuleVersion(identity, detail.id, latest); await onSaved(); onClose() }
    catch (failure) { setError(failure instanceof Error ? failure.message : '规则发布失败。') }
    finally { setBusy(undefined) }
  }

  return <div className="modal-backdrop" role="presentation"><section className="modal rule-editor" role="dialog" aria-modal="true" aria-label={detail ? '修改规则' : '创建规则'}>
    <header><div><span className="panel-kicker">RULE VERSION</span><h2>{detail ? `${detail.name} · ${latest?.lifecycleStatus === 'DRAFT' ? '修改草稿' : '创建新版本'}` : '创建企业规则'}</h2></div><button className="close" onClick={onClose}>×</button></header>
    <div className="form-body">
      {detail && latest?.lifecycleStatus !== 'DRAFT' && <div className="inline-warning">已发布版本不可覆盖；本次保存将自动创建新的草稿版本。</div>}
      <div className="rule-definition-grid"><label>规则编码<input value={code} disabled={!!detail} onChange={(event) => setCode(event.target.value.toUpperCase())} placeholder="RULE-WORK-MISSED" /></label><label>规则名称<input value={name} disabled={!!detail} onChange={(event) => setName(event.target.value)} /></label></div>
      <label>监听事件<input value={eventType} disabled={!!detail} onChange={(event) => setEventType(event.target.value.toUpperCase())} list="rule-event-types" /><datalist id="rule-event-types"><option value="WORK_RECORD_SUBMITTED" /><option value="WORK_EXPECTATION_MISSED" /><option value="METRIC_TREND_DETECTED" /></datalist></label>
      <label>说明<textarea rows={2} value={description} disabled={!!detail} onChange={(event) => setDescription(event.target.value)} /></label>
      <div className="rule-number-grid"><label>优先级（0-1000）<input type="number" min="0" max="1000" value={priority} onChange={(event) => setPriority(event.target.value)} /></label><label>冷却时间（分钟）<input type="number" min="0" value={cooldown} onChange={(event) => setCooldown(event.target.value)} /></label></div>
      <label>判断条件 JSON<textarea className="code-editor" rows={7} value={condition} onChange={(event) => setCondition(event.target.value)} /></label>
      <label>执行动作 JSON<textarea className="code-editor" rows={9} value={actions} onChange={(event) => setActions(event.target.value)} /></label>
      <div className="inline-warning">当前基础版使用租户范围。阈值、时限、通知和升级必须写入结构化规则，不交给大模型判断。</div>
      {error && <div className="inline-error">{error}</div>}
    </div><footer><button className="secondary" onClick={onClose}>取消</button>{detail && latest?.lifecycleStatus === 'DRAFT' && permissions.includes('rule.publish') && <button className="secondary" disabled={!!busy} onClick={publish}>{busy === 'publish' ? '发布中…' : '发布当前草稿'}</button>}<button className="primary" disabled={!!busy || !code.trim() || !name.trim() || !eventType.trim()} onClick={save}>{busy === 'save' ? '保存中…' : latest?.lifecycleStatus === 'PUBLISHED' ? '保存为新版本' : '保存草稿'}</button></footer>
  </section></div>
}

function Rules({ identity, permissions }: { identity: RoleContext; permissions: string[] }) {
  const resource = useResource(`${identity.key}:rules`, () => loadRules(identity), [])
  const [editor, setEditor] = useState<{ key: string; detail?: RuleDetail }>()
  const [opening, setOpening] = useState<string>()
  const [openError, setOpenError] = useState<string>()
  const canManage = permissions.includes('rule.manage')
  const openExisting = async (item: ManagementRule) => {
    setOpening(item.id); setOpenError(undefined)
    try { setEditor({ key: `${item.id}:${Date.now()}`, detail: await loadRuleDetail(identity, item.id) }) }
    catch (failure) { setOpenError(failure instanceof Error ? failure.message : '规则详情读取失败。') }
    finally { setOpening(undefined) }
  }
  return <section className="page-section"><PageHeader eyebrow="RULE ENGINE" title="企业规则中心" description="确定性的事件＋条件＋动作：规则决定何时行动，AI不参与阈值、时限和升级判断。" actions={<><SourceFlag source={resource.source} />{canManage && <button className="primary" onClick={() => setEditor({ key: `new:${Date.now()}` })}>＋ 创建规则</button>}</>} />
    {openError && <div className="inline-error page-error">{openError}</div>}
    <article className="panel table-panel"><DataState loading={resource.loading} error={resource.error} empty={!resource.data.length} onRetry={resource.reload} />
      {!resource.loading && !resource.error && !!resource.data.length && <div className="data-table rule-table"><div className="table-row table-head"><span>规则</span><span>监听事件</span><span>范围</span><span>命中</span><span>版本</span><span>状态</span>{canManage && <span>操作</span>}</div>{resource.data.map((item) => <div className="table-row" key={item.id}><span><strong>{item.name}</strong><small>{item.code}</small></span><span><code>{item.eventType}</code></span><span>{item.scopeName}</span><span>{item.hitCount ?? '—'}</span><span>V{item.versionNo}</span><span><Status value={item.status} /></span>{canManage && <span><button className="link-button" disabled={opening === item.id} onClick={() => void openExisting(item)}>{opening === item.id ? '读取中…' : '修改 / 新版本'}</button></span>}</div>)}</div>}
    </article>
    {editor && <RuleEditor key={editor.key} identity={identity} detail={editor.detail} permissions={permissions} onClose={() => setEditor(undefined)} onSaved={resource.reload} />}
  </section>
}

function TaskRows({ tasks, onSelect }: { tasks: ManagementTask[]; onSelect: (task: ManagementTask) => void }) {
  return <div className="data-table task-table"><div className="table-row table-head"><span>任务</span><span>目标组织</span><span>负责人 / 验收人</span><span>SLA</span><span>状态</span><span>操作</span></div>
    {tasks.map((task) => <div className="table-row" key={task.id}><span><strong>{task.title}</strong><small>{task.code} · {label(task.priority)}优先级</small></span><span>{task.targetOrgName}</span><span>{task.assigneeName}<small>验收：{task.reviewerName}</small></span><span><Status value={task.slaStatus} /></span><span><Status value={task.status} /></span><span><button className="link-button" onClick={() => onSelect(task)}>查看与处理</button></span></div>)}
  </div>
}

const actionsByStatus: Record<string, Array<{ command: string; label: string; tone?: string }>> = {
  PROPOSED: [{ command: 'dispatch', label: '派发任务' }],
  PENDING_ACK: [{ command: 'acknowledge', label: '确认接单' }],
  IN_PROGRESS: [{ command: 'submit-result', label: '提交执行结果' }],
  REWORK: [{ command: 'start', label: '开始返工' }],
  RESULT_SUBMITTED: [{ command: 'approve', label: '验收通过' }, { command: 'reject', label: '退回整改', tone: 'danger' }],
  AWAITING_REVIEW: [{ command: 'approve', label: '验收通过' }, { command: 'rework', label: '打回返工', tone: 'danger' }],
}

type PendingTaskEvidence = {
  draftFileId: string
  file: File
}

type TaskFormDraft = {
  schemaVersion: 1
  remark: string
}

function TaskDetail({ initial, identity, permissions, onClose, onChanged }: { initial: ManagementTask; identity: RoleContext; permissions: string[]; onClose: () => void; onChanged: () => void }) {
  const resource = useResource(`${identity.key}:task:${initial.id}`, () => loadTask(identity, initial.id, initial), initial)
  const task = resource.data
  const [remark, setRemark] = useState('')
  const [busy, setBusy] = useState<string>()
  const [error, setError] = useState<string>()
  const [draftNotice, setDraftNotice] = useState<string>()
  const [evidenceFiles, setEvidenceFiles] = useState<PendingTaskEvidence[]>([])
  const [draftHydrated, setDraftHydrated] = useState(false)
  const [draftHydrationSettled, setDraftHydrationSettled] = useState(false)
  const [validatingEvidence, setValidatingEvidence] = useState(false)
  const [attachmentPreview, setAttachmentPreview] = useState<AttachmentPreview>()
  const taskDraftParts = [identity.tenantId, identity.actorId, identity.businessActorAssignmentId, initial.id]
  const taskFormDraftKey = buildResumableDraftKey('task-form', taskDraftParts)
  const taskFilesDraftKey = buildResumableDraftKey('task-files', taskDraftParts)
  const taskFormDraftRef = useRef<TaskFormDraft>({ schemaVersion: 1, remark: '' })
  const taskFilesDraftRef = useRef<StoredDraftFile[]>([])
  const taskDraftStorageReadyRef = useRef(false)
  const isAssignee = Boolean(identity.businessActorAssignmentId && identity.businessActorAssignmentId === task.assigneeAssignmentId)
  const isReviewer = Boolean(identity.businessActorAssignmentId && identity.businessActorAssignmentId === task.reviewerAssignmentId)
  const dispatchReferenceEvidence = task.evidence?.filter((evidence) => evidence.structuredResult?.purpose === 'DISPATCH_REFERENCE') ?? []
  const executionEvidence = task.evidence?.filter((evidence) => evidence.structuredResult?.purpose !== 'DISPATCH_REFERENCE') ?? []
  const evidenceReferenceNote = dispatchReferenceEvidence.find((evidence) => typeof evidence.structuredResult?.note === 'string')?.structuredResult?.note
  const dispatchReferenceNote: string | undefined = typeof task.sourceSnapshot?.dispatchReferenceNote === 'string'
    ? task.sourceSnapshot.dispatchReferenceNote
    : typeof evidenceReferenceNote === 'string' ? evidenceReferenceNote : undefined
  const canEditEvidence = Boolean(identity.businessActorAssignmentId && identity.businessActorAssignmentId === task.assigneeAssignmentId && ['IN_PROGRESS', 'REWORK'].includes(task.status))
  const allowedActions = (actionsByStatus[task.status] ?? []).filter((action) => {
    if (task.status === 'RESULT_SUBMITTED' && task.standardVersionId) return false
    if (['approve', 'rework', 'reject'].includes(action.command)) return isReviewer && permissions.includes('task.review')
    if (action.command === 'dispatch') return permissions.includes('task.dispatch')
    return isAssignee && permissions.includes('task.act')
  })
  useEffect(() => {
    let active = true
    setDraftHydrated(false)
    setDraftHydrationSettled(false)
    taskDraftStorageReadyRef.current = false
    void Promise.all([
      readResumableDraft<TaskFormDraft>(taskFormDraftKey),
      readResumableDraft<StoredDraftFile[]>(taskFilesDraftKey),
    ]).then(async ([storedForm, storedFiles]) => {
      if (!active) return
      const pendingFiles = (storedFiles ?? [])
        .filter(isStoredDraftFile)
        .map((stored) => ({ draftFileId: stored.id, file: fromStoredDraftFile(stored) }))
      const restored = await restoreReadableDraftFiles(pendingFiles)
      if (!active) return
      const restoredFiles = restored.accepted
      if (storedForm?.schemaVersion === 1) setRemark(storedForm.remark)
      setEvidenceFiles(restoredFiles)
      taskFilesDraftRef.current = restoredFiles.map((entry) => toStoredDraftFile(entry.file, entry.draftFileId))
      // Migrate readable legacy task drafts to self-contained Blobs as well as
      // removing entries whose temporary picker handles have already expired.
      if (pendingFiles.length) {
        try {
          await (taskFilesDraftRef.current.length
            ? writeResumableDraft(taskFilesDraftKey, taskFilesDraftRef.current)
            : removeResumableDraft(taskFilesDraftKey))
        } catch {
          setError('无法清理已失效的本机附件，请重新打开后手动移除。')
        }
      }
      if (storedForm?.remark || restoredFiles.length || restored.rejected.length) {
          setDraftNotice(`已恢复上次未完成的处理说明${restoredFiles.length ? `和 ${restoredFiles.length} 个待上传附件` : ''}。${restored.rejected.length ? ` 已自动移除 ${restored.rejected.length} 个无法读取的旧附件，请重新拍摄或选择。` : ''}`)
      }
      taskDraftStorageReadyRef.current = true
      setDraftHydrated(true)
    }).catch((reason) => {
      if (active) {
        taskDraftStorageReadyRef.current = false
        setError(`设备草稿暂时无法读取。为避免覆盖已保存内容，本页不会自动改写设备草稿。${reason instanceof Error ? `（${reason.message}）` : ''}`)
      }
    }).finally(() => { if (active) setDraftHydrationSettled(true) })
    return () => { active = false }
  }, [taskFilesDraftKey, taskFormDraftKey])
  useEffect(() => {
    if (!draftHydrated) return
    const snapshot: TaskFormDraft = { schemaVersion: 1, remark }
    taskFormDraftRef.current = snapshot
    const timeout = window.setTimeout(() => {
      const operation = snapshot.remark.length
        ? writeResumableDraft(taskFormDraftKey, snapshot)
        : removeResumableDraft(taskFormDraftKey)
      void operation.catch((reason) =>
        setError(reason instanceof Error ? reason.message : '处理说明暂存失败'))
    }, 250)
    return () => window.clearTimeout(timeout)
  }, [draftHydrated, remark, taskFormDraftKey])
  useEffect(() => {
    if (!draftHydrated) return
    const stored = evidenceFiles.map((entry) => toStoredDraftFile(entry.file, entry.draftFileId))
    taskFilesDraftRef.current = stored
    void (stored.length ? writeResumableDraft(taskFilesDraftKey, stored) : removeResumableDraft(taskFilesDraftKey))
      .catch((reason) => setError(reason instanceof Error ? reason.message : '任务附件暂存失败'))
  }, [draftHydrated, evidenceFiles, taskFilesDraftKey])
  useEffect(() => {
    if (!draftHydrated) return
    const flushDraft = () => {
      const form = taskFormDraftRef.current
      void (form.remark.length ? writeResumableDraft(taskFormDraftKey, form) : removeResumableDraft(taskFormDraftKey))
      const stored = taskFilesDraftRef.current
      void (stored.length ? writeResumableDraft(taskFilesDraftKey, stored) : removeResumableDraft(taskFilesDraftKey))
    }
    const flushWhenHidden = () => { if (document.visibilityState === 'hidden') flushDraft() }
    window.addEventListener('pagehide', flushDraft)
    document.addEventListener('visibilitychange', flushWhenHidden)
    return () => {
      window.removeEventListener('pagehide', flushDraft)
      document.removeEventListener('visibilitychange', flushWhenHidden)
      flushDraft()
    }
  }, [draftHydrated, taskFilesDraftKey, taskFormDraftKey])
  const run = async (command: string) => {
    if (command === 'submit-result' && evidenceFiles.length) {
      setError(`还有 ${evidenceFiles.length} 个待上传证据，请先点击“上传证据”，全部上传成功后再提交执行结果。`)
      return
    }
    setBusy(command); setError(undefined)
    try {
      await apiCommand(`/tasks/${task.id}/actions/${command}`, identity, {
        actorAssignmentId: identity.businessActorAssignmentId,
        payload: { remark, ...(command === 'submit-result' ? { result: { summary: remark } } : {}) },
      }, task.version)
      setRemark('')
      await removeResumableDraft(taskFormDraftKey)
      await resource.reload(); onChanged()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '任务操作失败') }
    finally { setBusy(undefined) }
  }
  const evaluateResult = async () => {
    setBusy('evaluate-result'); setError(undefined)
    try {
      await createTaskEvaluation(identity, task)
      await resource.reload(); onChanged()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '任务结果评价失败') }
    finally { setBusy(undefined) }
  }
  const uploadEvidence = async () => {
    if (!identity.businessActorAssignmentId || !evidenceFiles.length) return
    setBusy('upload-evidence'); setError(undefined)
    try {
      const pendingFiles = [...evidenceFiles]
      for (const [index, entry] of pendingFiles.entries()) {
        await uploadTaskEvidence(identity, task.id, identity.businessActorAssignmentId, entry.file)
        const remaining = pendingFiles.slice(index + 1)
        taskFilesDraftRef.current = remaining.map((candidate) => toStoredDraftFile(candidate.file, candidate.draftFileId))
        await (remaining.length
          ? writeResumableDraft(taskFilesDraftKey, taskFilesDraftRef.current)
          : removeResumableDraft(taskFilesDraftKey))
        setEvidenceFiles((current) => current.filter((candidate) => candidate.draftFileId !== entry.draftFileId))
      }
      await resource.reload(); onChanged()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '执行证据上传失败') }
    finally { setBusy(undefined) }
  }
  const selectEvidenceFiles = async (files: File[]) => {
    setValidatingEvidence(true)
    setError(undefined)
    try {
      const result = await validateImageEvidence(files.slice(0, 10).map((file) => ({ file })))
      const materialized = await materializeSelectedFiles(result.accepted)
      const accepted = materialized.accepted.map((entry) => ({
        draftFileId: globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`,
        file: entry.file,
      }))
      setEvidenceFiles((current) => {
        const available = Math.max(0, 10 - current.length)
        const next = [...current, ...accepted.slice(0, available)]
        const stored = next.map((entry) => toStoredDraftFile(entry.file, entry.draftFileId))
        taskFilesDraftRef.current = stored
        if (taskDraftStorageReadyRef.current) {
          void (stored.length ? writeResumableDraft(taskFilesDraftKey, stored) : removeResumableDraft(taskFilesDraftKey))
            .catch((reason) => setError(reason instanceof Error ? reason.message : '任务附件暂存失败'))
        } else if (accepted.length) {
          setError('设备草稿暂时不可用；附件已保留在当前页面，请保持页面打开并立即上传。')
        }
        if (accepted.length > available) setError(`任务最多暂存 10 个待上传附件，本次仅加入 ${available} 个。`)
        return next
      })
      const rejected = [
        ...result.rejected.map((entry) => ({ fileName: entry.entry.file.name, reason: entry.reason })),
        ...materialized.rejected.map((entry) => ({ fileName: entry.entry.file.name, reason: entry.reason })),
      ]
      if (rejected.length) {
        const first = rejected[0]
        const remaining = rejected.length - 1
        setError(`${first.fileName}：${first.reason}${remaining ? `；另有 ${remaining} 个附件未加入` : ''}`)
      }
    } finally {
      setValidatingEvidence(false)
    }
  }
  const previewEvidence = async (evidenceId: string, originalName: string, declaredType?: string) => {
    try {
      const blob = await loadTaskEvidenceContent(identity, task.id, evidenceId)
      setAttachmentPreview({ url: URL.createObjectURL(blob), name: originalName, mediaType: resolveAttachmentMediaType(blob, originalName, declaredType) })
    }
    catch (reason) { setError(reason instanceof Error ? reason.message : '执行证据打开失败') }
  }
  const removeEvidence = async (evidenceId: string) => {
    if (!identity.businessActorAssignmentId) return
    setBusy(`delete:${evidenceId}`); setError(undefined)
    try { await deleteTaskEvidence(identity, task.id, evidenceId, identity.businessActorAssignmentId); await resource.reload(); onChanged() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '执行证据删除失败') }
    finally { setBusy(undefined) }
  }
  if (!draftHydrationSettled) {
    return <div className="drawer-backdrop" role="presentation"><aside className="drawer" role="dialog" aria-modal="true"><header><div><span className="panel-kicker">TASK DETAIL</span><h2>{task.title}</h2><small>{task.code}</small></div><button className="close" onClick={onClose}>×</button></header><div className="drawer-body"><div className="state-card"><div className="spinner" /><strong>正在恢复未提交的任务内容</strong><small>恢复完成前暂不开放编辑，避免覆盖本机草稿。</small></div></div></aside></div>
  }
  return <div className="drawer-backdrop" role="presentation"><aside className="drawer" role="dialog" aria-modal="true">
    <header><div><span className="panel-kicker">TASK DETAIL</span><h2>{task.title}</h2><small>{task.code}</small></div><button className="close" onClick={onClose}>×</button></header>
    <div className="drawer-body"><div className="task-summary"><span><small>任务状态</small><Status value={task.status} /></span><span><small>SLA状态</small><Status value={task.slaStatus} /></span><span><small>优先级</small><Status value={task.priority} /></span></div>
      <dl><div><dt>目标组织</dt><dd>{task.targetOrgName}</dd></div><div><dt>负责人</dt><dd>{task.assigneeName}</dd></div><div><dt>验收人</dt><dd>{task.reviewerName}</dd></div><div><dt>截止时间</dt><dd>{formatDate(task.dueAt)}</dd></div><div><dt>来源</dt><dd>{task.creationSource ? label(task.creationSource) : task.sourceTitle ?? label(task.sourceType)}</dd></div></dl>
      <section className="detail-section"><h3>执行要求</h3><p>{task.description ?? '任务来源已记录，执行结果需提交结构化说明和证据。'}</p></section>
      {(dispatchReferenceNote || dispatchReferenceEvidence.length > 0) && <section className="detail-section"><h3>下达参考</h3>{dispatchReferenceNote && <p>{String(dispatchReferenceNote)}</p>}{dispatchReferenceEvidence.length > 0 && <div className="attachment-list">{dispatchReferenceEvidence.map((evidence) => { const evidenceName = evidence.originalName || '参考图片'; return <span className="evidence-chip" key={evidence.id}><button className="secondary" disabled={!evidence.objectKey} onClick={() => void previewEvidence(evidence.id, evidenceName, evidence.mediaType)}>{evidenceName} · 查看参考</button></span> })}</div>}</section>}
      <section className="detail-section"><h3>执行证据</h3>{draftNotice && <div className="inline-success">{draftNotice}</div>}{executionEvidence.length ? <div className="attachment-list">{executionEvidence.map((evidence) => { const evidenceName = evidence.originalName || label(evidence.evidenceType); return <span className="evidence-chip" key={evidence.id}><button className="secondary" disabled={!evidence.objectKey} onClick={() => void previewEvidence(evidence.id, evidenceName, evidence.mediaType)}>{evidenceName} · {evidence.scanStatus}</button>{canEditEvidence && evidence.submittedByAssignmentId === identity.businessActorAssignmentId && <button className="text-action danger" disabled={busy === `delete:${evidence.id}`} onClick={() => void removeEvidence(evidence.id)}>删除</button>}</span> })}</div> : <p className="muted">尚未上传图片或文档证据。</p>}{canEditEvidence && <div className="evidence-uploader"><input type="file" multiple disabled={validatingEvidence || !!busy} accept=".jpg,.jpeg,.png,.pdf,.docx,.xlsx" onChange={(event) => { void selectEvidenceFiles(Array.from(event.target.files ?? [])); event.currentTarget.value = '' }} /><button className="secondary" disabled={!!busy || validatingEvidence || !evidenceFiles.length} onClick={() => void uploadEvidence()}>{validatingEvidence ? '校验图片中…' : busy === 'upload-evidence' ? '上传中…' : `上传证据${evidenceFiles.length ? `（${evidenceFiles.length}）` : ''}`}</button><small>照片支持横拍或竖拍；支持 JPG、PNG、PDF、Word、Excel，单文件不超过20MB。选中的文件会先保存在本机草稿，切出后可继续。</small></div>}{!!evidenceFiles.length && <div className="inline-warning">还有 {evidenceFiles.length} 个证据仅保存在本机，上传成功前不能提交执行结果。</div>}{error && <div className="inline-error">{error}</div>}</section>
      {!!allowedActions.length && <section className="action-box"><label>处理说明<textarea rows={3} value={remark} onChange={(event) => setRemark(event.target.value)} placeholder="填写执行结果、验收意见或返工原因" /></label><div>{allowedActions.map((action) => <button className={action.tone === 'danger' ? 'danger-button' : 'primary'} disabled={!!busy || !remark.trim() || (action.command === 'submit-result' && evidenceFiles.length > 0)} onClick={() => run(action.command)} key={action.command}>{busy === action.command ? '处理中…' : action.label}</button>)}</div></section>}
      {task.status === 'RESULT_SUBMITTED' && task.standardVersionId && isReviewer && permissions.includes('evaluation.manual-review') && <section className="action-box"><h3>任务结果标准评价</h3><p className="muted">系统使用任务创建时冻结的标准版本评价执行结果；评价完成后任务进入待验收状态。</p>{error && <div className="inline-error">{error}</div>}<div><button className="primary" disabled={!!busy} onClick={evaluateResult}>{busy === 'evaluate-result' ? '评价中…' : '按绑定标准评价结果'}</button></div></section>}
      <section className="detail-section"><h3>不可变时间线</h3><div className="timeline">{task.timeline?.length ? task.timeline.map((item) => <div key={item.id}><i /><span><strong>{label(item.toStatus)}</strong><small>{item.actorName} · {formatDate(item.occurredAt)}</small>{item.remark && <p>{item.remark}</p>}</span></div>) : <p className="muted">暂无流转记录。</p>}</div></section>
    </div>
    {attachmentPreview && <AttachmentPreviewDialog preview={attachmentPreview} onClose={() => setAttachmentPreview(undefined)} />}
  </aside></div>
}

const taskViews = ['mine', 'team', 'review'] as const

function Tasks({ identity, permissions, routeParams, go }: { identity: RoleContext; permissions: string[]; routeParams: RouteParams; go: Navigate }) {
  const requestedView = taskViews.includes(routeParams.view as typeof taskViews[number])
    ? routeParams.view as typeof taskViews[number]
    : identity.businessActorAssignmentId ? 'mine' : 'team'
  const [taskView, setTaskView] = useState<typeof taskViews[number]>(requestedView)
  useEffect(() => setTaskView(requestedView), [requestedView])
  const statusFilter = (routeParams.status || 'ALL').toUpperCase()
  const serverStatus = ['ALL', 'ACTIVE', 'OVERDUE'].includes(statusFilter) ? undefined : statusFilter
  const taskQueryKey = `${taskView}:${serverStatus ?? 'all'}:${routeParams.hotelId ?? 'all'}`
  const resource = useResource(`${identity.key}:tasks:${taskQueryKey}`, () => loadTasks(identity, {
    view: taskView,
    status: serverStatus,
    orgUnitId: routeParams.hotelId,
  }), [], 15_000)
  const [selected, setSelected] = useState<ManagementTask>()
  const [linkedTaskError, setLinkedTaskError] = useState<string>()
  const [creating, setCreating] = useState(false)
  useEffect(() => {
    if (routeParams.create === 'true' && (permissions.includes('*') || permissions.includes('task.create'))) setCreating(true)
  }, [permissions, routeParams.create])
  const tasks = resource.data.filter((item) => {
    if (statusFilter === 'ALL') return true
    if (statusFilter === 'ACTIVE') return !['COMPLETED', 'CANCELLED'].includes(item.status)
    if (statusFilter === 'OVERDUE') return item.slaStatus === 'OVERDUE'
    return item.status === statusFilter
  })
  useEffect(() => {
    if (!routeParams.taskId) return
    const linkedTask = resource.data.find((item) => item.id === routeParams.taskId)
    if (linkedTask) { setSelected(linkedTask); setLinkedTaskError(undefined); return }
    if (resource.loading) return
    let active = true
    setLinkedTaskError(undefined)
    void loadTask(identity, routeParams.taskId)
      .then((task) => { if (active) setSelected(task.data) })
      .catch((reason) => { if (active) setLinkedTaskError(reason instanceof Error ? reason.message : '任务详情加载失败') })
    return () => { active = false }
  }, [identity, resource.data, resource.loading, routeParams.taskId])
  const switchView = (next: typeof taskViews[number]) => {
    setTaskView(next)
    go('tasks', { ...routeParams, view: next, taskId: undefined })
  }
  const switchStatus = (next: string) => go('tasks', { ...routeParams, status: next === 'ALL' ? undefined : next, taskId: undefined })
  return <section className="page-section"><PageHeader eyebrow="TASK EXECUTION CENTER" title="任务中心" description="任务绑定责任任职、验收任职、来源标准和完整状态时间线。" actions={<><SourceFlag source={resource.source} />{permissions.includes('task.create') && <button className="primary" onClick={() => setCreating(true)}>＋ 新建任务</button>}</>} />
    <div className="filters"><button className={taskView === 'mine' ? 'active' : ''} onClick={() => switchView('mine')}>我的任务</button>{permissions.includes('task.review') && <><button className={taskView === 'review' ? 'active' : ''} onClick={() => switchView('review')}>待我验收</button><button className={taskView === 'team' ? 'active' : ''} onClick={() => switchView('team')}>团队任务</button></>}</div>
    <div className="filters task-status-filters">{['ALL', 'ACTIVE', 'AWAITING_REVIEW', 'OVERDUE', 'COMPLETED'].map((item) => <button className={statusFilter === item ? 'active' : ''} onClick={() => switchStatus(item)} key={item}>{item === 'ALL' ? '全部状态' : item === 'ACTIVE' ? '未完成' : item === 'OVERDUE' ? '已逾期' : label(item)}</button>)}</div>
    {linkedTaskError && <div className="inline-error page-error">{linkedTaskError}</div>}
    <article className="panel table-panel"><DataState loading={resource.loading} error={resource.error} empty={!tasks.length} onRetry={resource.reload} />{!resource.loading && !resource.error && !!tasks.length && <TaskRows tasks={tasks} onSelect={(task) => { setSelected(task); if (routeParams.taskId !== task.id) go('tasks', { ...routeParams, taskId: task.id }) }} />}</article>
    {selected && <TaskDetail key={`${identity.key}:${selected.id}`} initial={selected} identity={identity} permissions={permissions} onClose={() => { setSelected(undefined); if (routeParams.taskId) go('tasks', { ...routeParams, taskId: undefined }) }} onChanged={resource.reload} />}
    {creating && <TaskCreateDialog identity={identity} permissions={permissions} onClose={() => { setCreating(false); if (routeParams.create) go('tasks', { ...routeParams, create: undefined }) }} onCreated={async () => { await resource.reload(); if (routeParams.create) go('tasks', { ...routeParams, create: undefined }) }} />}
  </section>
}

function EvaluationDetail({ initial, identity, onClose }: { initial: StandardEvaluation; identity: RoleContext; onClose: () => void }) {
  const resource = useResource(`${identity.key}:evaluation:${initial.id}`, () => loadEvaluation(identity, initial.id, initial), initial)
  const evaluation = resource.data
  return <div className="drawer-backdrop"><aside className="drawer"><header><div><span className="panel-kicker">STANDARD EVALUATION</span><h2>{evaluation.subjectTitle}</h2><small>{evaluation.standardCode} · V{evaluation.standardVersion}</small></div><button className="close" onClick={onClose}>×</button></header><div className="drawer-body">
    <DataState loading={resource.loading} error={resource.error} onRetry={resource.reload} />
    {!resource.loading && !resource.error && <><div className="score-block"><div><strong>{evaluation.score ?? '—'}</strong><span>标准评分</span></div><span><Status value={evaluation.outcome} /><small>{evaluation.standardTitle}</small></span></div>
    <dl><div><dt>评价对象</dt><dd>{label(evaluation.subjectType)}</dd></div><div><dt>目标组织</dt><dd>{evaluation.targetOrgName}</dd></div><div><dt>执行状态</dt><dd>{label(evaluation.executionStatus)}</dd></div><div><dt>评价时间</dt><dd>{formatDate(evaluation.evaluatedAt)}</dd></div></dl>
    <section className="detail-section"><h3>逐项判断</h3>{evaluation.items?.length ? <div className="evaluation-items">{evaluation.items.map((item) => <div key={item.id}><span><strong>{item.itemName}</strong><small>{item.reason ?? `${item.actual ?? '—'} / ${item.expected ?? '—'}`}</small></span><Status value={item.outcome} /></div>)}</div> : <p className="muted">当前评价没有逐项结果。</p>}</section></>}
  </div></aside></div>
}

function Evaluations({ identity, routeParams, go }: { identity: RoleContext; routeParams: RouteParams; go: Navigate }) {
  const outcomeFilter = (routeParams.outcome || 'ALL').toUpperCase()
  const evaluationQueryKey = `${routeParams.hotelId ?? 'all'}:${outcomeFilter}`
  const resource = useResource(`${identity.key}:evaluations:${evaluationQueryKey}`, () => loadEvaluations(identity, {
    orgUnitId: routeParams.hotelId,
    outcome: outcomeFilter === 'ALL' ? undefined : outcomeFilter,
  }), [], 30_000)
  const [selected, setSelected] = useState<StandardEvaluation>()
  const [linkedEvaluationError, setLinkedEvaluationError] = useState<string>()
  const evaluations = outcomeFilter === 'ALL' ? resource.data : resource.data.filter((item) => item.outcome === outcomeFilter)
  useEffect(() => {
    if (!routeParams.evaluationId) return
    const linkedEvaluation = resource.data.find((item) => item.id === routeParams.evaluationId)
    if (linkedEvaluation) { setSelected(linkedEvaluation); setLinkedEvaluationError(undefined); return }
    if (resource.loading) return
    let active = true
    setLinkedEvaluationError(undefined)
    void loadEvaluation(identity, routeParams.evaluationId)
      .then((evaluation) => { if (active) setSelected(evaluation.data) })
      .catch((reason) => { if (active) setLinkedEvaluationError(reason instanceof Error ? reason.message : '评价详情加载失败') })
    return () => { active = false }
  }, [identity, resource.data, resource.loading, routeParams.evaluationId])
  return <section className="page-section"><PageHeader eyebrow="STANDARD EVALUATION" title="标准评价" description="评价永远引用已发布标准版本和输入快照，结果可解释、可复算。" actions={<SourceFlag source={resource.source} />} />
    <div className="filters">{['ALL', 'FAIL', 'WARNING', 'PASS', 'PENDING'].map((item) => <button className={outcomeFilter === item ? 'active' : ''} onClick={() => go('evaluations', { ...routeParams, outcome: item === 'ALL' ? undefined : item, evaluationId: undefined })} key={item}>{item === 'ALL' ? '全部结果' : label(item)}</button>)}</div>
    {linkedEvaluationError && <div className="inline-error page-error">{linkedEvaluationError}</div>}
    <article className="panel table-panel"><DataState loading={resource.loading} error={resource.error} empty={!evaluations.length} onRetry={resource.reload} />{!resource.loading && !resource.error && !!evaluations.length && <div className="data-table evaluation-table"><div className="table-row table-head"><span>评价对象</span><span>引用标准</span><span>目标组织</span><span>评分</span><span>结果</span><span>操作</span></div>{evaluations.map((item) => <div className="table-row" key={item.id}><span><strong>{item.subjectTitle}</strong><small>{label(item.subjectType)} · {formatDate(item.evaluatedAt)}</small></span><span>{item.standardTitle}<small>{item.standardCode} · V{item.standardVersion}</small></span><span>{item.targetOrgName}</span><span className="score">{item.score ?? '—'}</span><span><Status value={item.outcome} /></span><span><button className="link-button" onClick={() => setSelected(item)}>查看逐项判断</button></span></div>)}</div>}</article>
    {selected && <EvaluationDetail initial={selected} identity={identity} onClose={() => { setSelected(undefined); if (routeParams.evaluationId) go('evaluations', { ...routeParams, evaluationId: undefined }) }} />}
  </section>
}

function Notifications({ identity, permissions, routeParams, go }: { identity: RoleContext; permissions: string[]; routeParams: RouteParams; go: AppNavigate }) {
  const resource = useResource(`${identity.key}:notifications`, () => loadNotifications(identity), [], 15_000)
  const [busy, setBusy] = useState<string>()
  const [commandError, setCommandError] = useState<string>()
  const unreadOnly = routeParams.unread === 'true'
  const notices = unreadOnly ? resource.data.filter((item) => !item.readAt) : resource.data
  const wecomLabels: Record<string, string> = { SENT: '企微已发送（不代表已读）', UNBOUND: '未绑定企微，仅站内可见', PENDING: '企微待发送', FAILED: '企微发送失败，等待重试', DEAD: '企微发送失败，请联系管理员', NOT_QUEUED: '站内通知，企微未发送' }
  const markRead = async (id: string) => {
    setBusy(id); setCommandError(undefined)
    try {
      const item = resource.data.find((notice) => notice.id === id)
      await apiCommand(`/notifications/${id}/read`, identity, {}, item?.version ?? 0)
      resource.setData(resource.data.map((item) => item.id === id ? { ...item, readAt: new Date().toISOString(), version: item.version + 1 } : item))
    } catch (error) { setCommandError(error instanceof Error ? error.message : '通知处理失败') }
    finally { setBusy(undefined) }
  }
  return <section className="page-section"><PageHeader eyebrow="NOTIFICATION CENTER" title="通知中心" description="任务提醒、逾期、返工和升级集中送达；通知不替代任务主状态。" actions={<SourceFlag source={resource.source} />} />
    {resource.data.some((item) => item.wecomDeliveryStatus === 'UNBOUND') && <div className="inline-warning"><strong>当前账号未绑定企业微信，无法收到个人企微提醒。</strong><p>请联系管理员发起绑定邀请，并由您本人在企业微信内验证。完成绑定后将恢复有效提醒；历史过期日清提醒不会集中补发。</p></div>}<div className="filters"><button className={!unreadOnly ? 'active' : ''} onClick={() => go('notifications')}>全部通知</button><button className={unreadOnly ? 'active' : ''} onClick={() => go('notifications', { unread: 'true' })}>仅未读</button></div>
    {commandError && <div className="inline-error page-error">{commandError}</div>}
    <DataState loading={resource.loading} error={resource.error} empty={!notices.length} onRetry={resource.reload} />
    {!resource.loading && !resource.error && <div className="notification-page">{notices.map((item) => {
      const target = resolveNotificationNavigation(item, permissions)
      return <article className={item.readAt ? 'read' : ''} key={item.id}><i /><div><span><Status value={item.type} /><small>{formatDate(item.createdAt)}</small></span><h2>{item.title}</h2><p>{item.content}</p>{item.wecomDeliveryStatus && <small>{wecomLabels[item.wecomDeliveryStatus] ?? '企微发送状态待核查'}</small>}</div><div className="notification-actions">{target && <button className="primary" onClick={() => go(target.view, target.params)}>{target.actionLabel}</button>}{!item.readAt && <button className="secondary" disabled={busy === item.id} onClick={() => markRead(item.id)}>{busy === item.id ? '处理中…' : '标记已读'}</button>}</div></article>
    })}</div>}
  </section>
}

function LoginPage({ onAuthenticated, onWecomLogin }: { onAuthenticated: () => void; onWecomLogin?: () => void }) {
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setBusy(true); setError(undefined)
    try {
      await login(loginName, password)
      onAuthenticated()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '登录失败')
    } finally { setBusy(false) }
  }
  return <main className={`login-auth-screen${icpRecordNumber ? ' has-icp-footer' : ''}`}>
    <div className="login-auth-layout">
      <section className="login-auth-brand" aria-label={loginWorkbenchTitle}>
        <div className="login-auth-brandmark"><div>四</div><span><strong>{product.shortName}</strong></span></div>
        <div className="login-auth-intro"><h1>{loginWorkbenchTitle}</h1></div>
      </section>
      <section className="login-auth-panel">
        <form className="login-auth-card" aria-label="中台账号登录" aria-busy={busy} onSubmit={submit}>
          <header><h2>账号登录</h2><p>请输入中台账号和密码。未绑定企业微信的账号也可以在此登录。</p></header>
          <label>登录账号<input name="username" autoFocus autoComplete="username" disabled={busy} aria-invalid={Boolean(error)} aria-describedby={error ? 'login-error' : undefined} value={loginName} onChange={(event) => setLoginName(event.target.value)} placeholder="请输入登录账号" /></label>
          <label>登录密码<input name="password" type="password" autoComplete="current-password" disabled={busy} aria-invalid={Boolean(error)} aria-describedby={error ? 'login-error' : undefined} value={password} onChange={(event) => setPassword(event.target.value)} placeholder="请输入登录密码" /></label>
          {error && <div id="login-error" className="inline-error" role="alert" aria-live="polite">{error}</div>}
          <button className="primary login-auth-submit" disabled={busy || !loginName.trim() || !password}>{busy ? '正在验证…' : '登录中台'}</button>
          {onWecomLogin && <button className="secondary" type="button" disabled={busy} onClick={onWecomLogin}>改用企业微信登录</button>}
          <SelfRegistrationButton />
        </form>
      </section>
    </div>
    <IcpFooter recordNumber={icpRecordNumber} />
  </main>
}

function SessionBootstrapPage({ error, onRetry, onReturnToLogin }: {
  error?: string
  onRetry: () => void
  onReturnToLogin?: () => void
}) {
  return <main className={`login-auth-screen${icpRecordNumber ? ' has-icp-footer' : ''}`}>
    <div className="login-auth-layout">
      <section className="login-auth-brand" aria-label={loginWorkbenchTitle}>
        <div className="login-auth-brandmark"><div>四</div><span><strong>{product.shortName}</strong></span></div>
        <div className="login-auth-intro"><h1>{loginWorkbenchTitle}</h1></div>
      </section>
      <section className="login-auth-panel">
        <div className="login-auth-card" role="status" aria-live="polite">
          <header><h2>{error ? '无法读取账号权限' : '正在验证登录状态'}</h2><p>{error ?? '验证完成后将自动进入当前账号对应的岗位工作台。'}</p></header>
          {!error && <div className="state-card"><div className="spinner" /><strong>正在读取身份与权限</strong></div>}
          {error && <>
            <button type="button" className="primary login-auth-submit" onClick={onRetry}>重新验证</button>
          </>}
          {onReturnToLogin && <button type="button" className="secondary" onClick={onReturnToLogin}>使用账号密码登录</button>}
        </div>
      </section>
    </div>
    <IcpFooter recordNumber={icpRecordNumber} />
  </main>
}

function ChangePasswordDialog({ identity, onClose, onChanged }: { identity: RoleContext; onClose: () => void; onChanged: () => void }) {
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setError(undefined)
    if (newPassword.length < 10) { setError('新密码至少10位'); return }
    if (newPassword !== confirmation) { setError('两次输入的新密码不一致'); return }
    setBusy(true)
    try {
      await changePassword(identity, currentPassword, newPassword)
      onChanged()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '密码修改失败')
    } finally { setBusy(false) }
  }
  return <div className="modal-backdrop" role="presentation"><form className="modal password-modal" role="dialog" aria-modal="true" onSubmit={submit}>
    <header><div><span className="panel-kicker">ACCOUNT SECURITY</span><h2>修改登录密码</h2></div><button type="button" className="close" onClick={onClose}>×</button></header>
    <div className="form-body">
      <label>当前密码<input autoFocus type="password" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} /></label>
      <label>新密码<input type="password" autoComplete="new-password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} placeholder="至少10位" /></label>
      <label>确认新密码<input type="password" autoComplete="new-password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} /></label>
      <div className="inline-warning">修改成功后当前浏览器会退出，请使用新密码重新登录。密码不会显示在日志或审计记录中。</div>
      {error && <div className="inline-error">{error}</div>}
    </div>
    <footer><button type="button" className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={busy || !currentPassword || !newPassword || !confirmation}>{busy ? '修改中…' : '确认修改'}</button></footer>
  </form></div>
}

function AccountSelfService({ roleLabel, orgName, onChangePassword, onLogout }: {
  roleLabel: string
  orgName: string
  onChangePassword?: () => void
  onLogout?: () => void
}) {
  return <section className="all-functions-page">
    <header><h1>我的</h1><p>仅提供当前账号自助功能，不展示管理后台或全部功能目录。</p><div className="mobile-identity-summary"><strong>{roleLabel}</strong><span>{orgName}</span></div></header>
    <div className="all-function-groups"><section><h2>账号与安全</h2><div className="all-function-list">
      {onChangePassword && <button type="button" onClick={onChangePassword}><i aria-hidden="true">⌁</i><span>修改密码</span><b aria-hidden="true">›</b></button>}
      {onLogout && <button type="button" onClick={onLogout}><i aria-hidden="true">↪</i><span>退出登录</span><b aria-hidden="true">›</b></button>}
      {!onChangePassword && !onLogout && <div className="state-card"><strong>本地验收模式</strong><span>正式会话登录后可修改密码或退出。</span></div>}
    </div></section></div>
  </section>
}

function AuthenticatedApp({ onLogout }: { onLogout?: () => void }) {
  const [route, navigate] = useHashRoute()
  const { view, params: routeParams, sectionId } = route
  let storedRole: string | null = null
  if (canUseStoredAcceptanceRole(authMode)) {
    try { storedRole = localStorage.getItem(roleStorageKey) } catch { /* private mode may disable storage */ }
  }
  const [identity, setIdentity] = useState<RoleContext>(() => roleContexts.find((role) => role.key === storedRole) ?? roleContexts.find((role) => role.key === 'front-desk') ?? roleContexts[0])
  const fallbackIdentity: IdentitySnapshot = useMemo(() => ({
    accountId: identity.actorId,
    displayName: identity.userName,
    primaryRoleCode: identity.roleCode,
    roleCodes: [identity.roleCode],
    permissions: [],
    tenantScope: false,
    orgScopes: identity.orgScopes,
    assignments: bootstrapAssignments(authMode, identity.businessActorAssignmentId ? [{
      id: identity.businessActorAssignmentId,
      orgUnitId: identity.orgScopes[0] ?? '',
      orgName: identity.orgName,
      positionId: '',
      positionCode: identity.roleCode,
      positionName: identity.label,
      primary: true,
      assignmentType: 'PERMANENT',
    }] : []),
    businessActorAssignmentId: undefined,
    capabilities: { groupManagement: { workPlansEnabled: false, executiveTasksEnabled: false } },
  }), [identity])
  const me = useResource(`${identity.key}:me`, () => loadIdentity(identity, fallbackIdentity), fallbackIdentity)
  const [selectedAssignmentId, setSelectedAssignmentId] = useState(() => bootstrapAssignmentId(authMode, identity.businessActorAssignmentId))
  const [changingPassword, setChangingPassword] = useState(false)
  const compatibleAssignments = useMemo(() => me.data.assignments, [me.data.assignments])
  const custodyEntryId = view === 'lost-properties' ? routeParams.id : undefined
  const custodyEntry = useScopedResource<{ assignmentId?: string }>(`${identity.key}:${me.data.accountId}:${!me.loading}:custody-entry:${custodyEntryId ?? ''}`,
    signal => custodyEntryId && !me.loading && !me.error ? apiRequest(`/lost-properties/${encodeURIComponent(custodyEntryId)}/entry-assignment`,
      { ...identity, actorId: me.data.accountId, businessActorAssignmentId: undefined }, { signal }) : Promise.resolve({}), {})
  useEffect(() => {
    const preferred = compatibleAssignments.find((item) => item.id === custodyEntry.data.assignmentId)
      ?? compatibleAssignments.find((item) => item.primary) ?? compatibleAssignments[0]
    setSelectedAssignmentId(preferred?.id ?? bootstrapAssignmentId(authMode, identity.businessActorAssignmentId))
  }, [identity.key, compatibleAssignments, custodyEntry.data.assignmentId])
  const selectedAssignment = compatibleAssignments.find((item) => item.id === selectedAssignmentId)
    ?? compatibleAssignments.find((item) => item.primary)
    ?? compatibleAssignments[0]
  const fullAccountRoleCode = resolveFullAccountPresentationRole(me.data.roleCodes)
  const platformRoleCode = fullAccountRoleCode === 'PLATFORM_ADMIN' ? fullAccountRoleCode : undefined
  const ceoRoleCode = fullAccountRoleCode === 'CEO' ? fullAccountRoleCode : undefined
  const isPlatformAdmin = Boolean(platformRoleCode)
  const isDataRetentionAdministrator = hasDataRetentionAdministrationRole(me.data.roleCodes)
  const isCeo = Boolean(ceoRoleCode)
  const isFullAccountLevel = isPlatformAdmin || isCeo
  const accountPresentationRoleCode = platformRoleCode ?? ceoRoleCode ?? me.data.primaryRoleCode
  const accountPresentationFamily = resolveRolePresentationPolicy(accountPresentationRoleCode).key
  const accountRoleContext = roleContexts.find((role) => role.roleCode === accountPresentationRoleCode)
    ?? roleContexts.find((role) => resolveRolePresentationPolicy(role.roleCode).key === accountPresentationFamily)
  const selectedPresentationFamily = resolveRolePresentationPolicy(selectedAssignment?.positionCode).key
  const selectedRoleContext = isFullAccountLevel ? undefined : roleContexts.find((role) => role.roleCode === selectedAssignment?.positionCode)
    ?? roleContexts.find((role) => resolveRolePresentationPolicy(role.roleCode).key === selectedPresentationFamily)
  const resolvedRoleContext = isFullAccountLevel ? accountRoleContext : selectedRoleContext
  const presentationRoleCode = useMemo(() => {
    if (isFullAccountLevel) return accountPresentationRoleCode
    const assignmentRoleCode = selectedAssignment?.positionCode ?? me.data.primaryRoleCode ?? identity.roleCode
    if (resolveRolePresentationPolicy(assignmentRoleCode).knownRole) return assignmentRoleCode
    return me.data.roleCodes.includes('HR_KPI_ADMIN') ? 'HR_KPI_ADMIN' : assignmentRoleCode
  }, [accountPresentationRoleCode, identity.roleCode, isFullAccountLevel, me.data.primaryRoleCode, me.data.roleCodes, selectedAssignment?.positionCode])
  const presentationPolicy = useMemo(
    () => resolveRolePresentationPolicy(presentationRoleCode),
    [presentationRoleCode],
  )
  const activeIdentity: RoleContext = useMemo(() => ({
    ...(resolvedRoleContext ?? identity),
    key: `${resolvedRoleContext?.key ?? identity.key}:${isFullAccountLevel ? 'account' : selectedAssignment?.id ?? 'account'}`,
    actorId: me.data.accountId || identity.actorId,
    userName: me.data.displayName || identity.userName,
    employeeId: me.data.employeeId,
    assignmentOrgUnitId: selectedAssignment?.orgUnitId,
    roleCode: isFullAccountLevel ? accountPresentationRoleCode : selectedAssignment?.positionCode ?? (me.data.primaryRoleCode || identity.roleCode),
    orgScopes: authMode === 'dev-header' && !me.data.orgScopes.length ? identity.orgScopes : me.data.orgScopes,
    businessActorAssignmentId: selectedAssignment?.id
      ?? (authMode === 'dev-header' ? identity.businessActorAssignmentId : undefined),
    label: isFullAccountLevel ? resolvedRoleContext?.label ?? identity.label : selectedAssignment?.positionName ?? resolvedRoleContext?.label ?? identity.label,
    orgName: isFullAccountLevel ? resolvedRoleContext?.orgName ?? identity.orgName : selectedAssignment?.orgName ?? resolvedRoleContext?.orgName ?? identity.orgName,
    focus: resolvedRoleContext?.focus ?? presentationPolicy.focus,
  }), [identity, isFullAccountLevel, accountPresentationRoleCode, me.data, presentationPolicy.focus, resolvedRoleContext, selectedAssignment])
  const activePermissions = useMemo(() => {
    if (isFullAccountLevel) return me.data.permissions
    return selectedAssignment?.permissionCodes !== undefined ? selectedAssignment.permissionCodes : me.data.permissions
  }, [isFullAccountLevel, me.data.permissions, selectedAssignment?.permissionCodes])
  const useRoleDefaultsFallback = shouldUseRoleDefaultsFallback({
    identitySource: me.source,
    fullAccountLevel: isFullAccountLevel,
    assignmentPermissionsPresent: selectedAssignment?.permissionCodes !== undefined,
  })
  const secondaryIdentityReady = canLoadSecondaryResources(authMode, me.loading, me.error)
  const canReadSidebarNotifications = activePermissions.includes('*')
    || activePermissions.includes('notification.read')
  const unreadResource = useResource<NotificationItem[]>(
    `${activeIdentity.key}:sidebar-notices:${secondaryIdentityReady && canReadSidebarNotifications ? 'ready' : 'pending'}`,
    async () => secondaryIdentityReady && canReadSidebarNotifications
      ? loadNotifications(activeIdentity)
      : { data: [], source: 'api' as const },
    [],
    15_000,
  )
  const unreadCount = unreadResource.data.filter((item) => !item.readAt).length
  const pilotDemoMode = demoFallbackEnabled && me.source === 'demo'
  const isChairmanContext = presentationRoleCode === 'GROUP_CHAIRMAN'
  const executiveTasksEnabled = me.data.capabilities.groupManagement.executiveTasksEnabled
  const visibleNavigation = useMemo(() => visibleNavigationForContext({
    permissions: activePermissions,
    roleCode: presentationRoleCode,
    hasAssignment: Boolean(activeIdentity.businessActorAssignmentId),
    executiveTasksEnabled,
    isPlatformAdmin,
    isDataRetentionAdministrator,
    demo: demoFallbackEnabled && me.source === 'demo',
  }), [activeIdentity.businessActorAssignmentId, activePermissions, executiveTasksEnabled, isDataRetentionAdministrator, isPlatformAdmin, me.source, presentationRoleCode])
  const navigationCenters = useMemo(() => buildNavigationCenters(visibleNavigation, activeIdentity.label), [visibleNavigation, activeIdentity.label])
  const activeCenterId = navigationCenterForSection(sectionId, routeParams)
  const activeCenter = navigationCenters.find((center) => center.id === activeCenterId)
  const storeResourcesAvailable = canOpenStoreResources(activePermissions, visibleNavigation)
  const dailyReportsAvailable = visibleNavigation.some(item => item.id === 'daily-reports-my')
  const navigationTarget = (id: AppRouteId): AppRouteId => {
    if (id !== 'daily-reports-my') return id
    const canUseTeamReport = activePermissions.includes('*')
      || activePermissions.includes(permissionCodes.dailyReport.readTeam)
      || activePermissions.includes(permissionCodes.dailyReport.reviewException)
      || activePermissions.includes(permissionCodes.dailyReport.reviewCorrection)
    const canUseOwnReport = Boolean(activeIdentity.businessActorAssignmentId) &&
      (activePermissions.includes('*') || activePermissions.includes(permissionCodes.dailyReport.readOwn) || activePermissions.includes(permissionCodes.dailyReport.submit))
    if (canUseOwnReport) return 'daily-reports-my'
    return resolveDailyReportsTarget({
      roleCode: presentationRoleCode,
      hasAssignment: Boolean(activeIdentity.businessActorAssignmentId),
      canUseOwnReport,
      canUseTeamReport,
    })
  }
  useEffect(() => {
    if (me.loading || me.error || route.explicit || view !== 'workbench') return
    const target = presentationPolicy.mobileTabs[0]?.target ?? 'workbench'
    if (target !== 'workbench' && visibleNavigation.some((item) => item.id === target)) navigate(navigationTarget(target))
  }, [me.error, me.loading, navigate, presentationPolicy, route.explicit, view, visibleNavigation])
  const routePresentationAllowed = (view === 'data-retention' && isDataRetentionAdministrator)
    || (view === 'account-self-service' && isChairmanContext)
    || !useRoleDefaultsFallback
    || isRouteAllowedByRoleDefaults(presentationRoleCode, view, true)
  const page = useMemo(() => {
    if (custodyEntryId && custodyEntry.loading) return <div className="state-card" role="status">正在核对遗留物接收任职…</div>
    if (custodyEntryId && custodyEntry.error) return <div className="state-card"><strong>暂时无法打开这条遗留物记录</strong><p>{custodyEntry.error instanceof Error ? custodyEntry.error.message : '请重试或联系管理员核对接收权限。'}</p><button className="secondary" onClick={() => void custodyEntry.reload()}>重新加载</button></div>
    if (!canRenderAuthenticatedShell(authMode, me.loading, me.error)) {
      return <section className="page-section"><div className="empty-state"><strong>{me.error ? '身份与权限读取失败' : '正在读取身份与权限'}</strong><span>{me.error ?? '系统将在权限解析完成后加载业务页面，避免使用错误的岗位或组织范围。'}</span></div></section>
    }
    if (isInvestmentFeatureRoute(view)) {
      return <PageAccessBoundary
        permissions={activePermissions}
        requiredAny={requiredPermissionsForRoute(view)}
        requiredAll={requiredAllPermissionsForRoute(view, routeParams)}
        allowed={routePresentationAllowed}
      >
        <Suspense fallback={<div className="state-card"><div className="spinner" /><strong>正在加载投资测算模块</strong></div>}>
          <InvestmentFeature view={view} params={routeParams} identity={activeIdentity} grantedPermissions={activePermissions} go={navigate} />
        </Suspense>
      </PageAccessBoundary>
    }
    if (isDailyFeatureRoute(view)) {
      const feature = view.startsWith('kpi-') || view === 'kpi-center'
        ? <KpiFeature view={view} params={routeParams} identity={activeIdentity} grantedPermissions={activePermissions} go={navigate} />
        : view.startsWith('daily-report-template')
        ? <DailyReportTemplateFeature view={view} params={routeParams} identity={activeIdentity} grantedPermissions={activePermissions} go={navigate} />
        : view.startsWith('daily-report')
          ? <DailyReportFeature view={view} params={routeParams} identity={activeIdentity} grantedPermissions={activePermissions} go={navigate} />
          : <DailyOperationFeature view={view} params={routeParams} identity={activeIdentity} grantedPermissions={activePermissions} go={navigate} />
      return <PageAccessBoundary
        permissions={activePermissions}
        requiredAny={requiredPermissionsForRoute(view)}
        requiredAll={requiredAllPermissionsForRoute(view, routeParams)}
        allowed={routePresentationAllowed}
      >
        <Suspense fallback={<div className="state-card"><div className="spinner" /><strong>正在加载业务模块</strong></div>}>{feature}</Suspense>
      </PageAccessBoundary>
    }
    let legacyPage: React.ReactNode
    switch (view) {
      case 'workbench': legacyPage = <RoleWorkbench identity={activeIdentity} permissions={activePermissions} presentationKey={presentationPolicy.key} executiveTasksEnabled={executiveTasksEnabled} storeResourcesAvailable={storeResourcesAvailable} dailyReportsAvailable={dailyReportsAvailable} routeParams={routeParams} go={navigate} navigation={<CenterFeatureNavigation center={activeCenter} sectionId={sectionId} go={navigate} resolveTarget={navigationTarget} unreadCount={unreadCount} />} />; break
      case 'all-functions': legacyPage = <AllFunctionsPage
        items={visibleNavigation}
        roleLabel={activeIdentity.label}
        orgName={activeIdentity.orgName}
        go={navigate}
        resolveTarget={navigationTarget}
        onChangePassword={authMode !== 'dev-header' ? () => setChangingPassword(true) : undefined}
        onLogout={authMode !== 'dev-header' ? onLogout : undefined}
      />; break
      case 'account-self-service': legacyPage = <AccountSelfService roleLabel={activeIdentity.label} orgName={activeIdentity.orgName} onChangePassword={authMode !== 'dev-header' ? () => setChangingPassword(true) : undefined} onLogout={authMode !== 'dev-header' ? onLogout : undefined} />; break
      case 'hotel-dashboard': legacyPage = <HotelDashboardPage identity={activeIdentity} routeParams={routeParams} go={navigate} />; break
      case 'operations-dashboard': legacyPage = <OperationsDashboardPage identity={activeIdentity} />; break
      case 'work-packages': legacyPage = <WorkPackageCenter identity={activeIdentity} permissions={activePermissions} />; break
      case 'my-work': legacyPage = <MyWork identity={activeIdentity} routeParams={routeParams} go={navigate} />; break
      case 'team-work': legacyPage = <TeamWork identity={activeIdentity} permissions={activePermissions} routeParams={routeParams} go={navigate} />; break
      case 'rules': legacyPage = <Rules identity={activeIdentity} permissions={activePermissions} />; break
      case 'tasks': legacyPage = isChairmanContext && executiveTasksEnabled
        ? <Suspense fallback={<div className="state-card"><div className="spinner" /><strong>正在加载董事长任务模块</strong></div>}><ExecutiveTaskFeature identity={activeIdentity} grantedPermissions={activePermissions} routeParams={routeParams} go={navigate} /></Suspense>
        : <Tasks identity={activeIdentity} permissions={activePermissions} routeParams={routeParams} go={navigate} />; break
      case 'evaluations': legacyPage = <Evaluations identity={activeIdentity} routeParams={routeParams} go={navigate} />; break
      case 'notifications': legacyPage = <Notifications identity={activeIdentity} permissions={activePermissions} routeParams={routeParams} go={navigate} />; break
      case 'templates': legacyPage = <EnterpriseTemplateCenter identity={activeIdentity} permissions={activePermissions} />; break
      case 'organization': legacyPage = <OrganizationCenter identity={activeIdentity} permissions={activePermissions} routeParams={routeParams} />; break
      case 'store-resources': legacyPage = <StoreResourceFeature identity={activeIdentity} grantedPermissions={activePermissions} params={routeParams} go={navigate} />; break
      case 'academy': legacyPage = <AcademyFeature key={activeIdentity.key} identity={activeIdentity} permissions={activePermissions} params={routeParams} go={navigate} />; break
      case 'finance': legacyPage = <FinanceFeature key={activeIdentity.key} identity={activeIdentity} permissions={activePermissions} params={routeParams} go={navigate} />; break
      case 'procurement': legacyPage = <ProcurementFeature key={activeIdentity.key} identity={activeIdentity} permissions={activePermissions} params={routeParams} go={navigate} />; break
      case 'lost-properties': legacyPage = <LostPropertyFeature key={activeIdentity.key} identity={activeIdentity} params={routeParams} go={navigate} />; break
      case 'data-retention': legacyPage = <DataRetentionAdministration identity={activeIdentity} />; break
      case 'ai-data': legacyPage = <AiDataFeature key={activeIdentity.key} identity={activeIdentity} />; break
      case 'wecom-webhooks': legacyPage = <WecomStoreWebhookConfiguration identity={activeIdentity} permissions={activePermissions} />; break
      case 'wecom-bindings': legacyPage = <WecomUserBindingAdministration identity={activeIdentity} requestId={routeParams.requestId} onClearRequest={() => navigate('wecom-bindings')} />; break
      case 'wecom-onboarding': legacyPage = <WecomDirectoryOnboardingAdministration
        identity={activeIdentity}
        canApprove={activePermissions.includes('*')
          || activePermissions.includes(permissionCodes.wecomBinding.approve)
          || activePermissions.includes(permissionCodes.wecomBinding.onboardingReview)}
        canManage={activePermissions.includes('*') || activePermissions.includes(permissionCodes.wecomBinding.manage)}
        candidateId={routeParams.candidateId}
        directoryEventId={routeParams.directoryEventId}
        onClearTarget={() => navigate('wecom-onboarding')}
      />; break
    }
    const routeAccess = navigation.find((item) => item.id === view)
    const chairmanTaskAllowed = view !== 'tasks' || !isChairmanContext || (executiveTasksEnabled && Boolean(activeIdentity.businessActorAssignmentId))
    const accountSelfServiceAllowed = view !== 'account-self-service' || isChairmanContext
    const administratorRouteAllowed = !['rules', 'work-packages'].includes(view) || isPlatformAdmin
    const dataRetentionRouteAllowed = view !== 'data-retention' || isDataRetentionAdministrator
    const contextAllowed = (view !== 'my-work' || Boolean(activeIdentity.businessActorAssignmentId)) && chairmanTaskAllowed && accountSelfServiceAllowed && administratorRouteAllowed && dataRetentionRouteAllowed && routePresentationAllowed
    const boundaryPermissions = demoFallbackEnabled && me.source === 'demo' ? ['*'] : activePermissions
    return <PageAccessBoundary
      permissions={boundaryPermissions}
      requiredAny={routeAccess?.permissions ?? []}
      allowed={contextAllowed}
    >
      <Suspense fallback={<div className="state-card"><div className="spinner" /><strong>正在加载当前岗位功能</strong></div>}>{legacyPage}</Suspense>
    </PageAccessBoundary>
  }, [view, routeParams, activeIdentity, activePermissions, executiveTasksEnabled, isChairmanContext, isDataRetentionAdministrator, isPlatformAdmin, me.error, me.loading, presentationPolicy.key, presentationRoleCode, routePresentationAllowed, visibleNavigation, activeCenter, sectionId, storeResourcesAvailable, dailyReportsAvailable, unreadCount, custodyEntryId, custodyEntry.loading, custodyEntry.error])
  const changeRole = (key: string) => {
    const next = roleContexts.find((role) => role.key === key)
    if (!next) return
    try { localStorage.setItem(roleStorageKey, next.key) } catch { /* role still changes for this session */ }
    setIdentity(next)
    navigate('workbench')
  }

  if (!canRenderAuthenticatedShell(authMode, me.loading, me.error)) {
    return <SessionBootstrapPage error={me.error} onRetry={me.reload} onReturnToLogin={onLogout} />
  }

  return <div className={`shell${icpRecordNumber ? ' has-icp-footer' : ''}`}>
    <aside className="sidebar"><div className="brand-mark"><div>四</div><span><strong>{product.name}</strong><small>{product.edition} · {product.editionLabel}</small></span></div>
      <MainCenterNavigation centers={navigationCenters} activeCenter={activeCenterId} go={navigate} resolveTarget={navigationTarget} />
      <div className="sidebar-footer">
        <span>{product.version}</span>
        <small>标准 → 工作 → 任务 → 执行 → 验收</small>
        <IcpFooter recordNumber={icpRecordNumber} placement="sidebar" />
      </div>
    </aside>
    <main><header className="topbar"><div className="mobile-top-brand" aria-label={product.name}><span>四</span></div><div className={`connection ${me.error ? 'offline' : pilotDemoMode ? 'demo' : ''}`}><span className="live-dot" />{pilotDemoMode ? 'Pilot 演示数据' : me.error ? '身份接口异常' : '服务端权限已解析'}<small>{pilotDemoMode ? '仅用于界面与流程走查，不代表真实业务数据或权限' : authMode === 'dev-header' ? '本地验收账号 · 权限由数据库决定' : 'JWT/SSO 会话身份'}</small></div><span className="pilot-badge">{product.editionLabel}</span>
      {authMode === 'dev-header' && <label className="context-select account-context"><span>验收账号</span><select value={identity.key} onChange={(event) => changeRole(event.target.value)}>{roleContexts.map((role) => <option value={role.key} key={role.key}>{role.label} · {role.userName}</option>)}</select></label>}
      {!!compatibleAssignments.length && <label className="context-select assignment-context"><span>业务任职</span><select value={selectedAssignment?.id ?? ''} onChange={(event) => setSelectedAssignmentId(event.target.value)}>{compatibleAssignments.map((assignment) => <option value={assignment.id} key={assignment.id}>{assignment.positionName} · {assignment.orgName}{assignment.primary ? '（主岗）' : ''}</option>)}</select></label>}
      {isFullAccountLevel && <span className="mobile-current-context">{activeIdentity.label}</span>}
      {canReadSidebarNotifications && <button className="bell" onClick={() => navigate('notifications')} aria-label={unreadCount > 0 ? `消息，${unreadCount}条未读` : '消息'}>消息{unreadCount > 0 && <b>{unreadCount}</b>}</button>}
      <div className="user"><span>{activeIdentity.userName.slice(-1)}</span><div><strong>{activeIdentity.userName}</strong><small>{activeIdentity.label}</small></div></div>
      <button type="button" className="mobile-account-button" onClick={() => navigate(isChairmanContext ? 'account-self-service' : 'all-functions')} aria-label="个人与系统">我的</button>
      {authMode !== 'dev-header' && <button className="logout-button" onClick={() => setChangingPassword(true)}>修改密码</button>}
      {authMode !== 'dev-header' && <button className="logout-button" onClick={onLogout}>退出</button>}
    </header>
      {demoFallbackEnabled && <div className="demo-warning">已显式启用演示回退：仅当真实 API 请求失败时展示演示数据；API 返回空结果时仍显示空状态。</div>}
      <div className="canvas">{view !== 'workbench' && <CenterFeatureNavigation center={activeCenter} sectionId={sectionId} go={navigate} resolveTarget={navigationTarget} unreadCount={unreadCount} />}{page}</div>
      <MainCenterNavigation centers={navigationCenters} activeCenter={activeCenterId} go={navigate} resolveTarget={navigationTarget} mobile />
      {changingPassword && <ChangePasswordDialog identity={activeIdentity} onClose={() => setChangingPassword(false)} onChanged={() => { setChangingPassword(false); onLogout?.() }} />}
    </main>
    <IcpFooter recordNumber={icpRecordNumber} placement="mobile" />
  </div>
}

export default function App() {
  const [manualLogin, setManualLogin] = useState(() => {
    if (initialWecomSessionBootstrap) clearManualLogin()
    if (authMode !== 'dev-header' && consumeLogoutEntry(clearAccessToken)) {
      rememberManualLogin()
      window.history.replaceState(null, '', buildAppHashLocation('#/login', import.meta.env.BASE_URL))
    }
    return requestsManualLogin()
  })
  const [authenticated, setAuthenticated] = useState(() => {
    if (authMode !== 'dev-header' && manualLogin && !initialWecomSessionBootstrap) return false
    return authMode !== 'bearer' || initialWecomSessionBootstrap || hasAccessToken()
  })
  const [wecomTaskEntry, setWecomTaskEntry] = useState(initialWecomTaskEntry)
  const [wecomBindingEntry, setWecomBindingEntry] = useState(initialWecomBindingEntry)
  const completeWecomTaskEntry = useCallback(() => {
    clearManualLogin()
    setManualLogin(false)
    setWecomTaskEntry(undefined)
    setAuthenticated(true)
  }, [])
  const cancelWecomTaskEntry = useCallback(() => {
    clearAccessToken()
    rememberManualLogin()
    window.history.replaceState(null, '', buildAppHashLocation('#/login', import.meta.env.BASE_URL))
    setManualLogin(true)
    setAuthenticated(false)
    setWecomTaskEntry(undefined)
    setWecomBindingEntry(undefined)
  }, [])
  useEffect(() => {
    const expired = () => cancelWecomTaskEntry()
    window.addEventListener('hotel-ai-os:auth-expired', expired)
    return () => window.removeEventListener('hotel-ai-os:auth-expired', expired)
  }, [cancelWecomTaskEntry])
  if (wecomTaskEntry) return <WecomTaskEntryPage
    entry={wecomTaskEntry}
    onAuthenticated={completeWecomTaskEntry}
    onCancel={cancelWecomTaskEntry}
  />
  if (wecomBindingEntry) return <WecomBindingEnrollmentEntry
    entry={wecomBindingEntry}
    onReturn={cancelWecomTaskEntry}
  />
  if (!authenticated) return <LoginPage onAuthenticated={() => {
    window.history.replaceState(null, '', buildAppHashLocation('#/workbench', import.meta.env.BASE_URL))
    setAuthenticated(true)
  }} onWecomLogin={isWeComBrowser() ? () => {
    clearManualLogin()
    window.location.replace(buildWeComSilentAuthUrl())
  } : undefined} />
  return <AuthenticatedApp onLogout={() => { void logout(); cancelWecomTaskEntry() }} />
}
