import { useMemo, useState, type ReactNode } from 'react'
import { loadAccessibleHotels, loadOperationsDashboard, loadNotifications, loadTasks } from '../../api/resources'
import { resolveNotificationNavigation } from '../../app/notificationNavigation'
import { permissions as permissionCodes } from '../../app/permissions'
import type { AppNavigate } from '../../app/routeConfig'
import type { RolePresentationKey } from '../../app/rolePresentationPolicy'
import type { AccessibleHotel, RoleContext, RouteParams, NavigationParams } from '../../domain'
import { TaskCreateDialog } from '../../Pilot6Pages'
import { useResource } from '../../useResource'
import { resolveTaskDispatchMode } from '../tasks/taskDispatchAccess'
import { OwnDailyReportShortcut, TeamDailyReportShortcut } from './DailyReportShortcuts'
import { DailyReportTodo } from './DailyReportTodo'
import { AcademyTodo } from '../academy/AcademyTodo'
import { ProcurementTodo } from '../procurement/ProcurementTodo'
import { canProcure } from '../procurement/model'
import { ManagerCycles, cycleType } from './ManagerCycles'
import { WorkBoardPanel, WorkDateToolbar, WorkQueueAlerts, useWorkBoard } from './WorkBoard'
import { boardLabels, emptyMetric, personalWorkLabel, rate, withBoardParams, type BoardStatus, type BoardGroup } from './workBoardModel'
export { isLateSubmitted } from './workBoardModel'

function allows(granted: string[], permission: string) { return granted.includes('*') || granted.includes(permission) }
const executiveKeys = new Set<RolePresentationKey>(['GROUP_CHAIRMAN', 'GROUP_VICE_PRESIDENT', 'CEO', 'REGIONAL_OPERATIONS', 'PLATFORM_ADMIN'])

function GroupTable({ title, rows, onOpen }: { title: string; rows: BoardGroup[]; onOpen: (row: BoardGroup) => void }) {
  return <article className="wb-panel">
    <header><h2>{title}</h2></header>
    {!rows.length ? <p className="wb-empty">所选日期暂无工作统计</p> : <div className="wb-table-scroll" role="region" tabIndex={0} aria-label="数据表，可左右滚动"><table className="wb-summary-table">
      <thead><tr><th>名称</th><th>待提交</th><th>逾期</th><th>待审核</th><th>已完成</th><th>完成率</th><th>操作</th></tr></thead>
      <tbody>{rows.map(row => <tr key={row.group_id ?? 'direct'}><td>{row.group_name || '直属岗位'}</td>
        <td data-label="待提交">{row.pending}</td><td data-label="逾期" className="wb-red">{row.overdue}</td><td data-label="待审核" className="wb-amber">{row.review}</td><td data-label="已完成" className="wb-green">{row.completed}</td>
        <td data-label="完成率">{rate(row.completed_rate)}</td><td>{row.group_id ? <button className="text-action" onClick={() => onOpen(row)}>查看工作 ›</button> : '直属工作请从团队看板查看'}</td></tr>)}</tbody>
    </table></div>}
  </article>
}

export function RoleWorkbench({ identity, permissions, presentationKey, executiveTasksEnabled,
  storeResourcesAvailable = false, dailyReportsAvailable = false, navigation, routeParams = {}, go }: {
  identity: RoleContext; permissions: string[]; presentationKey: RolePresentationKey
  executiveTasksEnabled: boolean; storeResourcesAvailable?: boolean; dailyReportsAvailable?: boolean; navigation?: ReactNode
  routeParams?: RouteParams; go: AppNavigate
}) {
  const canReadTeam = allows(permissions, permissionCodes.workRecord.readTeam) || allows(permissions, 'work-record.review')
  const canReadOwn = Boolean(identity.businessActorAssignmentId) && (allows(permissions, 'work-record.read') || allows(permissions, 'work-record.submit') || allows(permissions, 'work.submit'))
  const canReadTasks = ['task.read', 'task.act', 'task.review', permissionCodes.executiveTask.read].some(code => allows(permissions, code))
  const canReadNotices = allows(permissions, 'notification.read')
  const canReadOperations = allows(permissions, 'dashboard.operations')
  const canReadHotels = allows(permissions, 'dashboard.hotel')
  const canReadDailyOperations = [permissionCodes.dailyOperations.readHotel, permissionCodes.dailyOperations.readCrossHotel].some(code => allows(permissions, code))
  const canReadKpi = [permissionCodes.kpi.scorecardReadOwn, permissionCodes.kpi.scorecardReadTeam, permissionCodes.kpi.scorecardReadAll, permissionCodes.kpi.templateRead].some(code => allows(permissions, code))
  const canReadStoreResources = storeResourcesAvailable && allows(permissions, permissionCodes.storeResource.read)
  const dispatchMode = resolveTaskDispatchMode(presentationKey, executiveTasksEnabled, permissions)
  const executive = executiveKeys.has(presentationKey)
  const [creatingTask, setCreatingTask] = useState(false)
  const dateParams = useMemo(() => Object.fromEntries(Object.entries(routeParams).filter(([key]) => ['dateMode', 'from', 'to'].includes(key))), [routeParams])
  const allTeam = useWorkBoard(identity, 'team', dateParams, canReadTeam)
  const directory = useResource(identity.key + ':workbench-hotels:' + canReadOperations + ':' + canReadHotels, async () => {
    if (canReadOperations) { const result = await loadOperationsDashboard(identity); return { data: result.data.hotels as AccessibleHotel[], source: result.source } }
    if (canReadHotels) { const result = await loadAccessibleHotels(identity); return { data: result.data.hotels, source: result.source } }
    return { data: [] as AccessibleHotel[], source: 'api' as const }
  }, [])
  const hotels = useMemo(() => {
    const map = new Map(directory.data.map(hotel => [hotel.id, hotel]))
    allTeam.data.groups.filter(group => group.dimension === 'hotel' && group.group_id).forEach(group => {
      if (!map.has(group.group_id!)) map.set(group.group_id!, { id: group.group_id!, name: group.group_name || '门店' })
    })
    return [...map.values()]
  }, [directory.data, allTeam.data.groups])
  const selectedHotel = hotels.find(hotel => hotel.id === routeParams.hotelId)
    ?? (!executive && hotels.length === 1 ? hotels[0] : undefined)
  const selectedParams = { ...dateParams, ...(selectedHotel ? { hotelId: selectedHotel.id } : {}) }
  const hotelTeam = useWorkBoard(identity, 'team', selectedParams, canReadTeam && Boolean(selectedHotel))
  const team = selectedHotel ? hotelTeam : allTeam
  const own = useWorkBoard(identity, 'my', dateParams, canReadOwn)
  const tasks = useResource(identity.key + ':workbench-tasks:' + canReadTasks,
    () => canReadTasks ? loadTasks(identity, { view: identity.businessActorAssignmentId ? 'mine' : 'team' })
      : Promise.resolve({ data: [], source: 'api' as const }), [])
  const notices = useResource(identity.key + ':workbench-notices:' + canReadNotices,
    () => canReadNotices ? loadNotifications(identity) : Promise.resolve({ data: [], source: 'api' as const }), [])
  const change = (patch: NavigationParams) => go('workbench', withBoardParams(routeParams, patch))
  const openTeam = (patch: NavigationParams = {}) => go('team-work', { ...selectedParams, ...patch, returnTo: 'workbench' })
  const openOwn = (status: BoardStatus = 'ALL') => go('my-work', { ...dateParams, status, returnTo: 'workbench' })
  const openHotel = (hotelId?: string) => change({ hotelId, departmentId: undefined, assignmentId: undefined, status: undefined })
  const headline = canReadTeam ? team : own
  const activeTasks = tasks.data.filter(task => !['COMPLETED', 'CANCELLED'].includes(task.status))
  const unreadNotices = notices.data.filter(notice => !notice.readAt)
  const managerCycles = presentationKey === 'HOTEL_MANAGER' && canReadOwn
  const cycleOnly = managerCycles && cycleType(routeParams.cycle) !== 'DAY' && routeParams.cycleTeam !== 'true'

  return <section className="wb-page">
    <header className="wb-page-heading"><div><span className="eyebrow">当前岗位工作</span><h1>{identity.label}工作台</h1><p>{identity.orgName} · 所选日期的工作进展与待办</p></div>
      <div className="wb-actions">
        <OwnDailyReportShortcut permissions={permissions} available={dailyReportsAvailable} hasAssignment={Boolean(identity.businessActorAssignmentId)} go={go} />
        {dispatchMode && <button className="primary" aria-label="一键下达任务" onClick={() => dispatchMode === 'EXECUTIVE'
        ? go('tasks', { create: 'true' }) : setCreatingTask(true)}>工作下达</button>}
        {canReadNotices && <button className="secondary" onClick={() => go('notifications')}>消息提醒{unreadNotices.length ? ' ' + unreadNotices.length : ''}</button>}
      </div></header>
    {navigation}
    {allows(permissions, 'academy.learn') && allows(permissions, 'ui.module.academy') && <AcademyTodo identity={identity} go={go} />}
    {canProcure(permissions) && <ProcurementTodo key={identity.key} identity={identity} go={go} />}
    {managerCycles && <ManagerCycles key={identity.key} identity={identity} routeParams={routeParams} go={go} />}
    {!cycleOnly && <>
    {dailyReportsAvailable && Boolean(identity.businessActorAssignmentId) && allows(permissions, 'daily-report.read') && <DailyReportTodo key={identity.key} identity={identity} go={go} />}
    <WorkDateToolbar params={routeParams} businessDate={headline.data.businessDate} onChange={change} />
    <nav className="wb-mobile-anchors" aria-label="工作台快捷定位">{canReadTeam && <a href="#team-board" onClick={event => { event.preventDefault(); document.getElementById('team-board')?.scrollIntoView({ behavior: 'smooth' }) }}>团队看板</a>}
      {canReadOwn && <a href="#personal-board" onClick={event => { event.preventDefault(); document.getElementById('personal-board')?.scrollIntoView({ behavior: 'smooth' }) }}>个人看板</a>}</nav>
    {!headline.error && !headline.loading && (canReadTeam || canReadOwn) && <WorkQueueAlerts data={headline.data}
      onOpen={patch => canReadTeam ? openTeam(patch) : go('my-work', { ...dateParams, ...patch })} />}
    <div className={'wb-dual-boards' + (!canReadTeam || !canReadOwn ? ' single' : '')}>
      {canReadTeam && <WorkBoardPanel id="team-board" title="团队工作看板" subtitle={selectedHotel ? selectedHotel.name + ' · 含本人与授权下属工作' : '当前任职授权范围 · 含本人，不与个人看板相加'}
        data={team.data} loading={team.loading} error={team.error} onSelect={status => openTeam({ status })} onAll={() => openTeam()} onRetry={team.reload} />}
      {canReadOwn && <WorkBoardPanel id="personal-board" title="个人工作看板" subtitle="只统计当前任职，不混入其他兼职"
        data={own.data} loading={own.loading} error={own.error} onSelect={openOwn} onAll={() => openOwn()} onRetry={own.reload}>
        {own.data.upcomingItems.length > 0 && <div className="wb-upcoming"><h3>最近到期的个人工作</h3>{own.data.upcomingItems.map(item =>
          <button className="wb-compact-row" key={item.id} onClick={() => go('my-work', { ...dateParams, expectationId: item.id })}>
            <span>{item.title}<small>{personalWorkLabel(item, identity.businessActorAssignmentId)}</small><small>截止 {item.dueAt ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(item.dueAt)) : '—'}</small></span><b>去完成 ›</b>
          </button>)}</div>}
      </WorkBoardPanel>}
    </div>
    {!canReadTeam && !canReadOwn && <p className="wb-empty">当前账号没有业务工作看板权限。请选择业务任职或使用配置管理入口。</p>}
    {(canReadTeam || canReadOwn) && <p className="wb-metric-note">当日闭环完成率、截至当日月工作完成率均按工作归属日核算；未来日期不计入完成率，已结算 KPI 不随页面查询改写。</p>}
    {selectedHotel && <div className="wb-scope-heading"><strong>{selectedHotel.name}工作台</strong>{executive && <button className="text-action" onClick={() => openHotel()}>返回门店列表</button>}
      <div className="wb-actions">
        <TeamDailyReportShortcut permissions={permissions} available={dailyReportsAvailable} hotelId={selectedHotel.id} go={go} />
        {canReadDailyOperations && <button className="text-action" onClick={() => go('daily-operations', { hotelId: selectedHotel.id })}>日运营</button>}
        {canReadKpi && <button className="text-action" onClick={() => go('kpi-center', { hotelId: selectedHotel.id })}>KPI</button>}
        {canReadStoreResources && <button className="text-action" onClick={() => go('store-resources', { hotelId: selectedHotel.id, tab: 'assets' })}>库存与资产</button>}
      </div></div>}
    {executive && canReadTeam && <article className="wb-panel">
      <header><h2>负责门店 <small>共 {hotels.length} 家</small></h2><p>选择门店，查看部门、员工和工作明细</p></header>
      {directory.error && <p className="inline-error">门店目录读取失败：{directory.error}<button onClick={directory.reload}>重试</button></p>}
      {allTeam.error ? <p className="inline-error">{allTeam.error}</p> : <div className="wb-table-scroll" role="region" tabIndex={0} aria-label="数据表，可左右滚动"><table className="wb-summary-table">
        <thead><tr><th>门店</th><th>待提交</th><th>逾期</th><th>待审核</th><th>已完成</th><th>完成率</th><th>操作</th></tr></thead>
        <tbody>{hotels.map(hotel => { const count = allTeam.data.groups.find(group => group.dimension === 'hotel' && group.group_id === hotel.id) ?? emptyMetric
          return <tr key={hotel.id} className={selectedHotel?.id === hotel.id ? 'selected' : ''}><td><strong>{hotel.name}</strong></td>
            <td data-label="待提交">{count.pending}</td><td data-label="逾期"><button className="text-action wb-red" onClick={() => go('team-work', { ...dateParams, hotelId: hotel.id, status: 'OVERDUE' })}>{count.overdue}</button></td>
            <td data-label="待审核">{count.review}</td><td data-label="已完成" className="wb-green">{count.completed}</td><td data-label="完成率">{rate(count.completed_rate)}</td>
            <td><button className="text-action" onClick={() => openHotel(hotel.id)}>进入门店 ›</button>
              {canReadStoreResources && <button className="text-action" onClick={() => go('store-resources', { hotelId: hotel.id, tab: 'assets' })}>库存与资产</button>}</td></tr> })}</tbody>
      </table></div>}
      {!hotels.length && !directory.loading && <p className="wb-empty">授权范围内暂无门店；集团总部不作为门店计数。</p>}
    </article>}
    {canReadTeam && !team.error && !team.loading && <div className="wb-group-grid">
      <GroupTable title="部门工作统计" rows={team.data.groups.filter(group => group.dimension === 'department')}
        onOpen={row => openTeam({ departmentId: row.group_id! })} />
      <GroupTable title="员工工作统计" rows={team.data.groups.filter(group => group.dimension === 'employee')}
        onOpen={row => openTeam({ assignmentId: row.group_id! })} />
    </div>}
    {canReadTeam && !team.error && !team.loading && <article className="wb-panel">
      <header><div><h2>最近提交</h2><p>近 7 天实际提交，含历史补交；不受工作日期筛选限制</p></div><button className="text-action" onClick={() => openTeam({ queue: 'REVIEW' })}>查看待审核 ›</button></header>
      {team.data.recentSubmissions.length ? team.data.recentSubmissions.map(item => <button className="wb-compact-row" key={item.id}
        onClick={() => openTeam({ expectationId: item.id })}>
        <span><strong>{item.title}</strong><small>{item.assigneeName} · {item.hotelName || item.targetOrgName} · {item.businessDate}</small></span>
        <b>{boardLabels[item.boardStatus || 'REVIEW']} · 查看详情 ›</b>
      </button>) : <p className="wb-empty">当前团队近 7 天暂无提交记录</p>}
    </article>}
    <div className="wb-group-grid">
      {canReadTasks && <article className="wb-panel"><header><h2>执行任务</h2><button className="text-action" onClick={() => go('tasks')}>进入任务 ›</button></header>
        <p className="wb-metric-note">临时下达、整改任务沿用任务验收流程，与日清工作分开统计。</p>
        {tasks.error ? <p className="inline-error">{tasks.error}</p> : activeTasks.length ? activeTasks.slice(0, 5).map(task => <button className="wb-compact-row" key={task.id} onClick={() => go('tasks', { taskId: task.id })}>
          <span><strong>{task.title}</strong><small>{task.targetOrgName}</small></span><b>{task.slaStatus === 'OVERDUE' ? '已逾期' : '去处理'} ›</b></button>) : <p className="wb-empty">暂无执行中任务</p>}
      </article>}
      {canReadNotices && <article className="wb-panel"><header><h2>管理提醒</h2><button className="text-action" onClick={() => go('notifications')}>查看全部 ›</button></header>
        {notices.error ? <p className="inline-error">{notices.error}</p> : unreadNotices.length ? unreadNotices.slice(0, 5).map(notice => <button className="wb-compact-row" key={notice.id}
          onClick={() => { const target = resolveNotificationNavigation(notice, permissions); target ? go(target.view, target.params) : go('notifications') }}>
          <span><strong>{notice.title}</strong><small>{notice.content}</small></span><b>查看 ›</b></button>) : <p className="wb-empty">暂无未读提醒</p>}
      </article>}
    </div>
    </>}
    {creatingTask && dispatchMode === 'MANAGEMENT' && <TaskCreateDialog identity={identity} permissions={permissions}
      initialHotelId={selectedHotel?.id} creationSource="WORKBENCH_QUICK_DISPATCH" onClose={() => setCreatingTask(false)}
      onCreated={async () => { await tasks.reload() }} />}
  </section>
}
