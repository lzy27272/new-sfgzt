import { useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  loadAccessibleHotels,
  loadMyWork,
  loadNotifications,
  loadOperationsDashboard,
  loadTasks,
  loadTeamWork,
  loadWorkbenchSummary,
} from '../../api/resources'
import { resolveNotificationNavigation } from '../../app/notificationNavigation'
import { permissions as permissionCodes } from '../../app/permissions'
import type { AppNavigate } from '../../app/routeConfig'
import type { RolePresentationKey } from '../../app/rolePresentationPolicy'
import type {
  AccessibleHotel,
  ApiSource,
  ManagementTask,
  NotificationItem,
  OperationsHotel,
  RoleContext,
  RouteParams,
  WorkCompletionMetric,
  WorkExpectation,
  WorkbenchSummary,
} from '../../domain'
import { TeamWorkDrawer } from '../../P0Pages'
import { TaskCreateDialog } from '../../Pilot6Pages'
import { useResource } from '../../useResource'
import { resolveTaskDispatchMode } from '../tasks/taskDispatchAccess'

const completedStatuses = new Set(['SUBMITTED', 'SATISFIED', 'COMPLETED', 'APPROVED'])
const overdueStatuses = new Set(['OVERDUE', 'MISSED', 'FAILED'])
const excludedStatuses = new Set(['WAIVED', 'CANCELLED'])
const executiveKeys = new Set<RolePresentationKey>([
  'GROUP_CHAIRMAN', 'GROUP_VICE_PRESIDENT', 'CEO', 'REGIONAL_OPERATIONS', 'PLATFORM_ADMIN',
])
const hotelManagementKeys = new Set<RolePresentationKey>(['HOTEL_MANAGER', 'ASSISTANT_MANAGER'])
const emptyMetric: WorkCompletionMetric = {
  expected: 0, completed: 0, onTimeCompleted: 0, lateSubmitted: 0, pending: 0, overdue: 0, completionRate: 0,
}

type HotelRow = AccessibleHotel & Partial<OperationsHotel>
type WorkbenchStatus = 'ALL' | 'COMPLETED' | 'PENDING' | 'OVERDUE' | 'LATE_SUBMITTED'

function allows(granted: string[], permission: string) {
  return granted.includes('*') || granted.includes(permission)
}

function businessDate() {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
}

function emptySummary(today = businessDate()): WorkbenchSummary {
  return {
    asOfDate: today,
    monthStart: `${today.slice(0, 8)}01`,
    today: { ...emptyMetric },
    monthToDate: { ...emptyMetric },
    hotels: [],
  }
}

function formatDate(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

function isDaily(item: WorkExpectation) {
  return item.periodType === 'DAY' || item.periodKey?.startsWith('DAY:') === true
}

export function isLateSubmitted(item: WorkExpectation) {
  const submittedAt = item.firstSubmittedAt ?? item.latestSubmittedAt ?? item.records?.at(-1)?.submittedAt
  if (!submittedAt || !item.dueAt) return false
  const submitted = new Date(submittedAt).getTime()
  const due = new Date(item.dueAt).getTime()
  return Number.isFinite(submitted) && Number.isFinite(due) && submitted > due
}

function currentScopeWork(items: WorkExpectation[], today: string) {
  const month = today.slice(0, 7)
  return items.filter((item) => !excludedStatuses.has(item.status)
    && item.businessDate.startsWith(month)
    && item.businessDate <= today
    && (!isDaily(item) || item.businessDate === today || isLateSubmitted(item)))
}

function hotelIdFor(item: WorkExpectation) {
  return item.hotelOrgUnitId ?? item.orgUnitId
}

function statusFor(item: WorkExpectation): Exclude<WorkbenchStatus, 'ALL'> {
  if (isLateSubmitted(item)) return 'LATE_SUBMITTED'
  if (overdueStatuses.has(item.status)) return 'OVERDUE'
  if (completedStatuses.has(item.status)) return 'COMPLETED'
  return 'PENDING'
}

function countByStatus(items: WorkExpectation[]) {
  return items.reduce((counts, item) => {
    counts[statusFor(item)] += 1
    return counts
  }, { COMPLETED: 0, PENDING: 0, OVERDUE: 0, LATE_SUBMITTED: 0 })
}

function completionFromItems(items: WorkExpectation[], monthToDate = false, today = businessDate()): WorkCompletionMetric {
  const month = today.slice(0, 7)
  const scoped = items.filter((item) => !excludedStatuses.has(item.status)
    && (monthToDate ? item.businessDate.startsWith(month) && item.businessDate <= today : item.businessDate === today))
  const counts = countByStatus(scoped)
  const onTimeCompleted = counts.COMPLETED
  return {
    expected: scoped.length,
    completed: counts.COMPLETED + counts.LATE_SUBMITTED,
    onTimeCompleted,
    lateSubmitted: counts.LATE_SUBMITTED,
    pending: counts.PENDING,
    overdue: counts.OVERDUE,
    completionRate: scoped.length ? Math.round(onTimeCompleted * 100 / scoped.length) : 0,
  }
}

function statusLabel(status: WorkbenchStatus) {
  return ({ ALL: '全部工作', COMPLETED: '已完成', PENDING: '待提交', OVERDUE: '仍未提交', LATE_SUBMITTED: '已补交' })[status]
}

function departmentLabel(item: WorkExpectation) {
  if (item.departmentName) return item.departmentName
  const source = `${item.positionJobFamily ?? ''} ${item.positionName ?? ''}`.toUpperCase()
  if (/FRONT|前厅|前台/.test(source)) return '前厅部'
  if (/HOUSEKEEP|ROOM|客房/.test(source)) return '客房部'
  if (/FOOD|BEVERAGE|RESTAUR|餐饮/.test(source)) return '餐饮部'
  if (/ENGINEER|MAINTENANCE|工程|维修/.test(source)) return '工程部'
  if (/SALES|MARKET|OTA|市场|销售/.test(source)) return '市场销售部'
  if (/HUMAN|ADMIN|人事|行政/.test(source)) return '行政人事部'
  if (/MANAGEMENT|MANAGER|店总|店助/.test(source)) return '综合管理'
  return '未归属部门'
}

function EmptyState({ title, description }: { title: string; description: string }) {
  return <div className="workbench-empty"><span>◇</span><strong>{title}</strong><small>{description}</small></div>
}

function WorkRows({ items, onSelect }: { items: WorkExpectation[]; onSelect: (item: WorkExpectation) => void }) {
  if (!items.length) return <EmptyState title="当前分类暂无工作" description="切换其他状态或门店查看；系统不会用演示记录填充真实空结果。" />
  return <div className="workbench-record-list">
    {items.map((item) => <button type="button" key={item.id} onClick={() => onSelect(item)}>
      <span className={`workbench-record-state ${statusFor(item).toLowerCase()}`} />
      <span className="workbench-record-main"><strong>{item.title}</strong><small>{departmentLabel(item)} · {item.assigneeName}{item.positionName ? ` · ${item.positionName}` : ''}</small></span>
      <span className="workbench-record-time"><small>截止 {formatDate(item.dueAt)}</small>{isLateSubmitted(item) && <em>补交 {formatDate(item.firstSubmittedAt ?? item.latestSubmittedAt ?? item.records?.at(-1)?.submittedAt)}</em>}</span>
      <b>{statusLabel(statusFor(item))} ›</b>
    </button>)}
  </div>
}

type SummaryRow = { id: string; name: string; today: WorkCompletionMetric; monthToDate: WorkCompletionMetric }

function SummaryTable({ title, subtitle, rows }: { title: string; subtitle: string; rows: SummaryRow[] }) {
  return <section className="workbench-summary-table">
    <header><div><span className="panel-kicker">SCOPE SUMMARY</span><h3>{title}</h3></div><small>{subtitle}</small></header>
    {!rows.length ? <p className="muted">当前范围暂无可统计记录。</p> : <div>
      <div className="workbench-summary-row head"><span>范围</span><span>当日完成率</span><span>本月完成率</span><span>今日待提交</span><span>当前逾期</span></div>
      {rows.map((row) => <div className="workbench-summary-row" key={row.id}><strong>{row.name}</strong><span className="workbench-rate-cell"><b>{row.today.completionRate}%</b><small>{row.today.onTimeCompleted}/{row.today.expected}</small></span><span className="workbench-rate-cell"><b>{row.monthToDate.completionRate}%</b><small>{row.monthToDate.onTimeCompleted}/{row.monthToDate.expected}</small></span><span>{row.today.pending}</span><span className={row.today.overdue ? 'danger-text' : ''}>{row.today.overdue}</span></div>)}
    </div>}
  </section>
}

function aggregate(items: WorkExpectation[], key: (item: WorkExpectation) => string, today: string) {
  const groups = new Map<string, WorkExpectation[]>()
  items.forEach((item) => {
    const name = key(item)
    const scoped = groups.get(name)
    if (scoped) scoped.push(item)
    else groups.set(name, [item])
  })
  return [...groups.entries()].map(([name, scoped]) => ({
    id: name,
    name,
    today: completionFromItems(scoped, false, today),
    monthToDate: completionFromItems(scoped, true, today),
  })).sort((left, right) => right.today.overdue - left.today.overdue
    || right.today.expected - left.today.expected)
}

function RateCard({ label, metric, loading, note }: { label: string; metric: WorkCompletionMetric; loading: boolean; note: string }) {
  const width = `${Math.max(0, Math.min(100, metric.completionRate))}%`
  return <article className="workbench-rate-card">
    <div><span>{label}</span><strong>{loading ? '—' : `${metric.completionRate}%`}</strong></div>
    <div className="workbench-rate-track" aria-label={`${label}${metric.completionRate}%`}><span style={{ width }} /></div>
    <small>{loading ? '正在核算' : `${metric.onTimeCompleted}/${metric.expected} 项按时完成 · ${metric.lateSubmitted} 项补交`}</small>
    <em>{note}</em>
  </article>
}

function ManagementTaskList({ items, go }: { items: ManagementTask[]; go: AppNavigate }) {
  if (!items.length) return <p className="workbench-compact-empty">暂无执行中任务</p>
  return <div className="workbench-compact-list">{items.slice(0, 4).map((task) => <button type="button" key={task.id} onClick={() => go('tasks', { view: 'mine', taskId: task.id })}>
    <span><strong>{task.title}</strong><small>{task.targetOrgName} · 截止 {formatDate(task.dueAt)}</small></span><b>{task.slaStatus === 'OVERDUE' ? '已逾期' : '去处理'} ›</b>
  </button>)}</div>
}

function NoticeList({ items, permissions, go }: { items: NotificationItem[]; permissions: string[]; go: AppNavigate }) {
  if (!items.length) return <p className="workbench-compact-empty">暂无未读管理提醒</p>
  return <div className="workbench-compact-list">{items.slice(0, 4).map((notice) => {
    const target = resolveNotificationNavigation(notice, permissions)
    return <button type="button" key={notice.id} onClick={() => target ? go(target.view, target.params) : go('notifications')}>
      <span><strong>{notice.title}</strong><small>{notice.content} · {formatDate(notice.createdAt)}</small></span><b>{target?.actionLabel ?? '查看'} ›</b>
    </button>
  })}</div>
}

export function RoleWorkbench({
  identity,
  permissions,
  presentationKey,
  executiveTasksEnabled,
  storeResourcesAvailable = false,
  navigation,
  routeParams = {},
  go,
}: {
  identity: RoleContext
  permissions: string[]
  presentationKey: RolePresentationKey
  executiveTasksEnabled: boolean
  storeResourcesAvailable?: boolean
  navigation?: ReactNode
  routeParams?: RouteParams
  go: AppNavigate
}) {
  const hasAssignment = Boolean(identity.businessActorAssignmentId)
  const canReadTeam = allows(permissions, permissionCodes.workRecord.readTeam) || allows(permissions, 'work-record.review')
  const canReadOwn = hasAssignment && (allows(permissions, 'work-record.read') || allows(permissions, 'work-record.submit') || allows(permissions, 'work.submit'))
  const canReadTasks = allows(permissions, 'task.read') || allows(permissions, 'task.act') || allows(permissions, 'task.review') || allows(permissions, permissionCodes.executiveTask.read)
  const dispatchMode = resolveTaskDispatchMode(presentationKey, executiveTasksEnabled, permissions)
  const canManageDispatch = dispatchMode === 'MANAGEMENT'
  const canExecutiveDispatch = dispatchMode === 'EXECUTIVE'
  const canDispatch = Boolean(dispatchMode)
  const canReadNotices = allows(permissions, 'notification.read')
  const canReadOperations = allows(permissions, 'dashboard.operations')
  const canReadHotels = allows(permissions, 'dashboard.hotel')
  const canReadReports = allows(permissions, permissionCodes.dailyReport.readOwn) || allows(permissions, permissionCodes.dailyReport.readTeam)
  const canReadDailyOperations = allows(permissions, permissionCodes.dailyOperations.readHotel) || allows(permissions, permissionCodes.dailyOperations.readCrossHotel)
  const canReadKpi = allows(permissions, permissionCodes.kpi.scorecardReadOwn) || allows(permissions, permissionCodes.kpi.scorecardReadTeam) || allows(permissions, permissionCodes.kpi.scorecardReadAll) || allows(permissions, permissionCodes.kpi.templateRead)
  const canReadStoreResources = storeResourcesAvailable && allows(permissions, permissionCodes.storeResource.read)
  const executive = executiveKeys.has(presentationKey)
  const [currentBusinessDate, setCurrentBusinessDate] = useState(businessDate)
  const monthStart = `${currentBusinessDate.slice(0, 8)}01`
  const workQuery = { fromBusinessDate: monthStart, toBusinessDate: currentBusinessDate }
  const work = useResource(`${identity.key}:role-workbench-work:${canReadTeam}:${canReadOwn}:${currentBusinessDate}`, () => canReadTeam
    ? loadTeamWork(identity, workQuery)
    : canReadOwn ? loadMyWork(identity, workQuery) : Promise.resolve({ data: [], source: 'api' as const }), [], 30_000)
  // Hotel-management scope need not include the employee's headquarters org.
  // Keep personal work on the existing ownership-checked API, without widening team scope.
  const ownWork = useResource(`${identity.key}:role-workbench-own:${canReadTeam}:${canReadOwn}:${currentBusinessDate}`, () => canReadTeam && canReadOwn
    ? loadMyWork(identity, workQuery) : Promise.resolve({ data: [], source: 'api' as const }), [], 30_000)
  const completion = useResource<WorkbenchSummary>(`${identity.key}:role-workbench-completion:${canReadTeam}:${currentBusinessDate}`, () => canReadTeam
    ? loadWorkbenchSummary(identity, currentBusinessDate)
    : Promise.resolve({ data: emptySummary(currentBusinessDate), source: 'api' as const }), emptySummary(currentBusinessDate), 60_000)
  const tasks = useResource(`${identity.key}:role-workbench-tasks:${canReadTasks}`, () => canReadTasks && presentationKey !== 'GROUP_CHAIRMAN'
    ? loadTasks(identity, { view: hasAssignment ? 'mine' : 'team' })
    : Promise.resolve({ data: [], source: 'api' as const }), [], 30_000)
  const notices = useResource(`${identity.key}:role-workbench-notices:${canReadNotices}`, () => canReadNotices
    ? loadNotifications(identity)
    : Promise.resolve({ data: [], source: 'api' as const }), [], 30_000)
  const hotelDirectory = useResource(`${identity.key}:role-workbench-hotels:${canReadOperations}:${canReadHotels}`, async () => {
    if (canReadOperations) {
      const result = await loadOperationsDashboard(identity)
      return { data: result.data.hotels as HotelRow[], source: result.source }
    }
    if (canReadHotels) {
      const result = await loadAccessibleHotels(identity)
      return { data: result.data.hotels as HotelRow[], source: result.source }
    }
    return { data: [], source: 'api' as const }
  }, [], 60_000)
  const [selected, setSelected] = useState<WorkExpectation>()
  const [creatingTask, setCreatingTask] = useState(false)
  const scopedWork = useMemo(() => currentScopeWork(work.data, currentBusinessDate), [currentBusinessDate, work.data])
  const derivedHotels = useMemo(() => {
    const result = new Map<string, HotelRow>()
    scopedWork.forEach((item) => {
      // Group and headquarters work must not be presented as a hotel.
      const id = item.hotelOrgUnitId
      if (id) result.set(id, { id, name: item.hotelName ?? item.targetOrgName })
    })
    return [...result.values()]
  }, [scopedWork])
  const hotels = useMemo(() => {
    const rows = new Map<string, HotelRow>()
    hotelDirectory.data.forEach((hotel) => rows.set(hotel.id, hotel))
    derivedHotels.forEach((hotel) => { if (!rows.has(hotel.id)) rows.set(hotel.id, hotel) })
    return [...rows.values()].sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'))
  }, [derivedHotels, hotelDirectory.data])
  const allScopeDrilldown = routeParams.scope === 'all'
  const selectedHotel = allScopeDrilldown ? undefined : hotels.find((hotel) => hotel.id === routeParams.hotelId)
    ?? (hotelManagementKeys.has(presentationKey) ? hotels[0] : undefined)
  const selectedHotelItems = useMemo(() => selectedHotel
    ? scopedWork.filter((item) => hotelIdFor(item) === selectedHotel.id)
    : scopedWork, [scopedWork, selectedHotel])
  const requestedStatus = (routeParams.status || 'ALL').toUpperCase() as WorkbenchStatus
  const statusFilter: WorkbenchStatus = ['ALL', 'COMPLETED', 'PENDING', 'OVERDUE', 'LATE_SUBMITTED'].includes(requestedStatus) ? requestedStatus : 'ALL'
  const filteredWork = useMemo(() => selectedHotelItems
    .filter((item) => statusFilter === 'ALL' || statusFor(item) === statusFilter)
    .sort((left, right) => (right.latestSubmittedAt ?? right.dueAt ?? right.businessDate).localeCompare(left.latestSubmittedAt ?? left.dueAt ?? left.businessDate)), [selectedHotelItems, statusFilter])
  const allCounts = useMemo(() => countByStatus(scopedWork), [scopedWork])
  const hotelCounts = useMemo(() => countByStatus(selectedHotelItems), [selectedHotelItems])
  const metricCounts = executive ? allCounts : selectedHotel ? hotelCounts : allCounts
  const unreadNotices = useMemo(() => notices.data.filter((notice) => !notice.readAt), [notices.data])
  const hotelTasks = useMemo(() => tasks.data.filter((task) => !selectedHotel
    || task.targetOrgUnitId === selectedHotel.id
    || task.targetOrgName.includes(selectedHotel.name)), [selectedHotel, tasks.data])
  const activeTasks = useMemo(() => hotelTasks.filter((task) => !['COMPLETED', 'CANCELLED'].includes(task.status)), [hotelTasks])
  const source: ApiSource = [work.source, completion.source, tasks.source, notices.source, hotelDirectory.source].includes('demo') ? 'demo' : 'api'
  const loading = work.loading || (executive && hotelDirectory.loading)
  const error = work.error
  const selectedHotelAllItems = useMemo(() => selectedHotel
    ? work.data.filter((item) => hotelIdFor(item) === selectedHotel.id)
    : work.data, [selectedHotel, work.data])
  const personalWork = useMemo(() => (canReadTeam ? ownWork.data : scopedWork).filter((item) => item.businessDate === currentBusinessDate
    && (!identity.businessActorAssignmentId || item.assignmentId === identity.businessActorAssignmentId)), [canReadTeam, ownWork.data, currentBusinessDate, identity.businessActorAssignmentId, scopedWork])
  const completionData = completion.data.asOfDate === currentBusinessDate ? completion.data : emptySummary(currentBusinessDate)
  const completionLoading = completion.loading || completion.data.asOfDate !== currentBusinessDate
  const summaryHotel = selectedHotel ? completionData.hotels.find((hotel) => hotel.id === selectedHotel.id) : undefined
  const summaryOrganizations = useMemo(() => [...completionData.hotels, ...(completionData.organizations ?? [])], [completionData.hotels, completionData.organizations])
  const departmentRows = useMemo<SummaryRow[]>(() => {
    if (summaryHotel?.departments.length) return summaryHotel.departments
    if (selectedHotel) return aggregate(selectedHotelAllItems, departmentLabel, currentBusinessDate)
    const rows = summaryOrganizations.flatMap((hotel) => hotel.departments.map((department) => ({
      ...department,
      id: `${hotel.id}:${department.id}`,
      name: `${hotel.name} · ${department.name}`,
    })))
    return rows.length ? rows : aggregate(work.data, departmentLabel, currentBusinessDate)
  }, [summaryOrganizations, currentBusinessDate, selectedHotel, selectedHotelAllItems, summaryHotel, work.data])
  const employeeRows = useMemo<SummaryRow[]>(() => {
    if (summaryHotel?.departments.length) return summaryHotel.departments.flatMap((department) => department.employees.map((employee) => ({
      ...employee,
      name: `${employee.name}${employee.positionName ? ` · ${employee.positionName}` : ''}`,
    })))
    if (selectedHotel) return aggregate(selectedHotelAllItems, (item) => `${item.assigneeName}${item.positionName ? ` · ${item.positionName}` : ''}`, currentBusinessDate)
    const rows = summaryOrganizations.flatMap((hotel) => hotel.departments.flatMap((department) => department.employees.map((employee) => ({
      ...employee,
      id: `${hotel.id}:${employee.id}`,
      name: `${hotel.name} · ${department.name} · ${employee.name}${employee.positionName ? ` · ${employee.positionName}` : ''}`,
    }))))
    return rows.length ? rows : aggregate(work.data, (item) => `${item.assigneeName}${item.positionName ? ` · ${item.positionName}` : ''}`, currentBusinessDate)
  }, [summaryOrganizations, currentBusinessDate, selectedHotel, selectedHotelAllItems, summaryHotel, work.data])
  const todayMetric = canReadTeam
    ? executive ? completionData.today : summaryHotel?.today ?? completionData.today
    : completionFromItems(work.data, false, currentBusinessDate)
  const monthMetric = canReadTeam
    ? executive ? completionData.monthToDate : summaryHotel?.monthToDate ?? completionData.monthToDate
    : completionFromItems(work.data, true, currentBusinessDate)

  useEffect(() => {
    const refreshBusinessDate = () => {
      const next = businessDate()
      setCurrentBusinessDate((current) => current === next ? current : next)
    }
    const timer = window.setInterval(refreshBusinessDate, 30_000)
    const refreshWhenVisible = () => { if (!document.hidden) refreshBusinessDate() }
    window.addEventListener('focus', refreshBusinessDate)
    document.addEventListener('visibilitychange', refreshWhenVisible)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', refreshBusinessDate)
      document.removeEventListener('visibilitychange', refreshWhenVisible)
    }
  }, [])

  useEffect(() => {
    if (!routeParams.expectationId) return
    const linked = scopedWork.find((item) => item.id === routeParams.expectationId)
    if (linked) setSelected(linked)
  }, [routeParams.expectationId, scopedWork])

  useEffect(() => {
    if (loading || routeParams.focus !== 'work-status') return
    const frame = window.requestAnimationFrame(() => {
      const target = document.getElementById('workbench-status-detail')
      target?.focus({ preventScroll: true })
      target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [loading, routeParams.focus, routeParams.hotelId, routeParams.scope, statusFilter])

  const openHotelStatus = (hotelId: string, status: WorkbenchStatus = 'ALL') => go('workbench', { hotelId, status })
  const openMetricStatus = (status: Exclude<WorkbenchStatus, 'ALL'>) => {
    if (!executive && selectedHotel) {
      go('workbench', { hotelId: selectedHotel.id, status, focus: 'work-status' })
      return
    }
    go('workbench', { scope: 'all', status, focus: 'work-status' })
  }
  const openTaskCreate = () => canExecutiveDispatch
    ? go('tasks', { create: 'true' })
    : setCreatingTask(true)

  return <section className="role-workbench">
    <section className="role-workbench-hero">
      <div><h1>{identity.label}工作台</h1><p>{identity.focus}</p></div>
      <div className="role-workbench-actions">
        {canDispatch && <button type="button" className="primary" onClick={openTaskCreate} aria-label="一键下达任务"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m21 3-6 18-4-8-8-4 18-6ZM11 13 21 3" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" /></svg>工作下达</button>}
        {canReadNotices && <button type="button" className="secondary" onClick={() => go('notifications')}><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 10a7 7 0 0 1 14 0v4l2 3H3l2-3v-4Zm5 11h4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" /></svg>消息提醒{unreadNotices.length ? ` ${unreadNotices.length}` : ''}</button>}
        {source === 'demo' && <span className="source-flag demo">演示回退</span>}
      </div>
    </section>

    {navigation}
    {loading && <div className="state-card"><div className="spinner" /><strong>正在汇总当前岗位工作台</strong><span>门店、部门、人员与工作状态正在统一读取</span></div>}
    {error && <div className="state-card error-state"><b>!</b><strong>工作台读取失败</strong><span>{error}</span><button className="secondary" onClick={() => void work.reload()}>重新加载</button></div>}
    {!loading && !error && <>
      <section className="workbench-metrics">
        <button type="button" aria-label={`查看待提交工作明细，共 ${metricCounts.PENDING} 项`} onClick={() => openMetricStatus('PENDING')}><span>待</span><small>待提交</small><strong>{metricCounts.PENDING}</strong><em>仍需员工完成 · 点击查看 ›</em></button>
        <button type="button" aria-label={`查看已完成工作明细，共 ${metricCounts.COMPLETED} 项`} onClick={() => openMetricStatus('COMPLETED')}><span>完</span><small>已完成</small><strong>{metricCounts.COMPLETED}</strong><em>已按时提交或达标 · 点击查看 ›</em></button>
        <button type="button" className="danger" aria-label={`查看当前逾期工作明细，共 ${metricCounts.OVERDUE} 项`} onClick={() => openMetricStatus('OVERDUE')}><span>逾</span><small>当前逾期</small><strong>{metricCounts.OVERDUE}</strong><em>尚未完成提交 · 点击查看 ›</em></button>
        <button type="button" className="warning" aria-label={`查看逾期后补交工作明细，共 ${metricCounts.LATE_SUBMITTED} 项`} onClick={() => openMetricStatus('LATE_SUBMITTED')}><span>补</span><small>逾期后补交</small><strong>{metricCounts.LATE_SUBMITTED}</strong><em>保留完整证据链 · 点击查看 ›</em></button>
      </section>

      <section className="workbench-rate-grid" aria-label="工作完成率统计">
        <RateCard label="当日完成率" metric={todayMetric} loading={completionLoading && canReadTeam} note="当日按时完成项 ÷ 当日应完成项" />
        <RateCard label="截至当日月工作完成率" metric={monthMetric} loading={completionLoading && canReadTeam} note="本月截至今日按时完成项 ÷ 同期应完成项" />
      </section>
      {completion.error && canReadTeam && <p className="workbench-rate-error">完成率暂未读取：{completion.error}</p>}

      {executive && <section className="workbench-portfolio panel">
        <header><div><span className="panel-kicker">HOTEL PORTFOLIO</span><h2>负责门店</h2><p>数据日期 {currentBusinessDate} · 点击门店查看部门、人员和工作完成状态。</p></div><strong>{hotels.length} 家</strong></header>
        {!hotels.length ? <EmptyState title="当前未解析到负责门店" description="请检查当前任职的组织数据范围；集团总部本身不会被误算为门店。" /> : <div className="workbench-hotel-table">
          <div className="workbench-hotel-row head"><span>门店</span><span>当日完成率</span><span>本月完成率</span><span>今日待提交</span><span>当前逾期</span><span>已补交</span><span>操作</span></div>
          {hotels.map((hotel) => {
            const hotelItems = scopedWork.filter((item) => hotelIdFor(item) === hotel.id)
            const counts = countByStatus(hotelItems)
            const summary = completionData.hotels.find((item) => item.id === hotel.id)
            const hotelWork = work.data.filter((item) => hotelIdFor(item) === hotel.id)
            const today = summary?.today ?? completionFromItems(hotelWork, false, currentBusinessDate)
            const monthToDate = summary?.monthToDate ?? completionFromItems(hotelWork, true, currentBusinessDate)
            return <div className={`workbench-hotel-row ${selectedHotel?.id === hotel.id ? 'selected' : ''}`} key={hotel.id}>
              <span><strong>{hotel.name}</strong><small>{hotel.city || '授权门店'}{hotel.roomCount ? ` · ${hotel.roomCount} 间` : ''}</small></span>
              <span className="workbench-rate-cell"><b>{today.completionRate}%</b><small>{today.onTimeCompleted}/{today.expected}</small></span>
              <span className="workbench-rate-cell"><b>{monthToDate.completionRate}%</b><small>{monthToDate.onTimeCompleted}/{monthToDate.expected}</small></span>
              <span>{today.pending}</span>
              <button type="button" className={counts.OVERDUE ? 'danger-text' : ''} onClick={() => openHotelStatus(hotel.id, 'OVERDUE')}>{counts.OVERDUE}</button>
              <button type="button" className={counts.LATE_SUBMITTED ? 'warning-text' : ''} onClick={() => openHotelStatus(hotel.id, 'LATE_SUBMITTED')}>{counts.LATE_SUBMITTED}</button>
              <span className="workbench-hotel-actions"><button type="button" className="link-button" onClick={() => openHotelStatus(hotel.id)}>进入门店</button><button type="button" className="link-button" onClick={() => openHotelStatus(hotel.id, 'OVERDUE')}>查看逾期</button>{canReadStoreResources && <button type="button" className="link-button" onClick={() => go('store-resources', { hotelId: hotel.id, tab: 'assets' })}>库存与资产</button>}</span>
            </div>
          })}
        </div>}
      </section>}

      {selectedHotel && <section className="workbench-hotel-detail panel">
        <header className="workbench-detail-heading"><div><span className="panel-kicker">HOTEL WORKBENCH</span><h2>{selectedHotel.name}工作台</h2><p>本门店工作、部门和员工执行情况统一收口。</p></div><div className="workbench-entry-actions">
          {canReadTeam && <button type="button" onClick={() => go('team-work', { hotelId: selectedHotel.id })}>团队工作</button>}
          {canReadReports && <button type="button" onClick={() => go('daily-reports-team', { hotelId: selectedHotel.id })}>日报</button>}
          {canReadDailyOperations && <button type="button" onClick={() => go('daily-operations', { hotelId: selectedHotel.id })}>日运营</button>}
          {canReadKpi && <button type="button" onClick={() => go('kpi-center', { hotelId: selectedHotel.id })}>KPI</button>}
          {canReadStoreResources && <button type="button" onClick={() => go('store-resources', { hotelId: selectedHotel.id, tab: 'assets' })}>库存与资产</button>}
        </div></header>
        <div className="workbench-detail-scroll" role="region" tabIndex={0} aria-label="门店工作明细，可左右滚动">
        <div className="workbench-detail-grid">
          <SummaryTable title="部门工作统计" subtitle="本门店各部门工作完成情况" rows={departmentRows} />
          <SummaryTable title="部门员工工作统计" subtitle="管理层查看部门及下级员工" rows={employeeRows} />
          <section className="workbench-task-board" id="workbench-status-detail" tabIndex={-1}>
            <header><div><span className="panel-kicker">WORK ITEMS</span><h3>工作任务</h3></div><button type="button" className="link-button" onClick={() => go('team-work', { hotelId: selectedHotel.id })}>查看全部</button></header>
            <div className="workbench-status-tabs">
              {(['ALL', 'COMPLETED', 'PENDING'] as WorkbenchStatus[]).map((status) => <button type="button" className={statusFilter === status ? 'active' : ''} key={status} onClick={() => openHotelStatus(selectedHotel.id, status)}>{statusLabel(status)} <b>{status === 'ALL' ? selectedHotelItems.length : hotelCounts[status]}</b></button>)}
              <button type="button" className={['OVERDUE', 'LATE_SUBMITTED'].includes(statusFilter) ? 'active danger' : 'danger'} onClick={() => openHotelStatus(selectedHotel.id, 'OVERDUE')}>已逾期 <b>{hotelCounts.OVERDUE + hotelCounts.LATE_SUBMITTED}</b></button>
            </div>
            {['OVERDUE', 'LATE_SUBMITTED'].includes(statusFilter) && <div className="workbench-overdue-tabs"><button type="button" className={statusFilter === 'OVERDUE' ? 'active' : ''} onClick={() => openHotelStatus(selectedHotel.id, 'OVERDUE')}>仍未提交 {hotelCounts.OVERDUE}</button><button type="button" className={statusFilter === 'LATE_SUBMITTED' ? 'active' : ''} onClick={() => openHotelStatus(selectedHotel.id, 'LATE_SUBMITTED')}>已补交 {hotelCounts.LATE_SUBMITTED}</button></div>}
            <WorkRows items={filteredWork} onSelect={setSelected} />
          </section>
        </div></div>
      </section>}

      {!selectedHotel && routeParams.focus === 'work-status' && <section className="workbench-scope-detail panel" id="workbench-status-detail" tabIndex={-1}>
        <header><div><span className="panel-kicker">SCOPE WORK ITEMS</span><h2>权限范围 · {statusLabel(statusFilter)}</h2><p>展示当前岗位有权查看的全部门店、部门及下属工作。</p></div><button type="button" className="link-button" onClick={() => go('workbench', { scope: 'all', status: 'ALL', focus: 'work-status' })}>查看全部状态</button></header>
        <div className="workbench-status-tabs" aria-label="权限范围工作状态">
          {(['ALL', 'COMPLETED', 'PENDING', 'OVERDUE', 'LATE_SUBMITTED'] as WorkbenchStatus[]).map((status) => <button type="button" className={`${statusFilter === status ? 'active' : ''}${status === 'OVERDUE' ? ' danger' : ''}`} key={status} onClick={() => go('workbench', { scope: 'all', status, focus: 'work-status' })}>{statusLabel(status)} <b>{status === 'ALL' ? scopedWork.length : allCounts[status]}</b></button>)}
        </div>
        <WorkRows items={filteredWork} onSelect={setSelected} />
      </section>}

      <section className="workbench-bottom-grid">
        <article className="panel workbench-bottom-panel"><header><div><span className="panel-kicker">TODAY PERSONAL WORK</span><h2>今日个人工作</h2></div><button className="link-button" onClick={() => go('my-work')}>查看全部</button></header>
          {canReadTeam && ownWork.error ? <div className="inline-error" role="alert">{ownWork.error}<button type="button" onClick={() => void ownWork.reload()}>重新读取</button></div>
            : canReadTeam && ownWork.loading ? <div role="status">正在读取个人工作…</div>
              : <WorkRows items={personalWork.slice(0, 6)} onSelect={setSelected} />}
        </article>
        <aside>
          {canReadNotices && <article className="panel workbench-bottom-panel"><header><div><span className="panel-kicker">MANAGEMENT ALERTS</span><h2>管理提醒</h2></div><button className="link-button" onClick={() => go('notifications')}>查看全部</button></header><NoticeList items={unreadNotices} permissions={permissions} go={go} /></article>}
          {canReadTasks && presentationKey !== 'GROUP_CHAIRMAN' && <article className="panel workbench-bottom-panel"><header><div><span className="panel-kicker">TASK PROGRESS</span><h2>执行任务</h2></div><button className="link-button" onClick={() => go('tasks')}>进入任务</button></header><ManagementTaskList items={activeTasks} go={go} /></article>}
        </aside>
      </section>

      {!selectedHotel && canReadTeam && <div className="workbench-scope-grid workbench-team-summaries">
        <SummaryTable title="部门统计" subtitle="当前管理范围内的部门工作" rows={departmentRows} />
        <SummaryTable title="部门员工统计" subtitle="当前部门及下级员工执行情况" rows={employeeRows} />
      </div>}
    </>}
    {selected && <TeamWorkDrawer initial={selected} identity={identity} permissions={permissions} go={go} onClose={() => setSelected(undefined)} onChanged={() => { void work.reload(); if (canReadTeam) { void completion.reload(); void ownWork.reload() } }} />}
    {creatingTask && canManageDispatch && <TaskCreateDialog
      identity={identity}
      permissions={permissions}
      initialHotelId={selectedHotel?.id}
      creationSource="WORKBENCH_QUICK_DISPATCH"
      onClose={() => setCreatingTask(false)}
      onCreated={async () => { await tasks.reload() }}
    />}
  </section>
}
