import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { loadExpectation } from '../../api/resources'
import type { AppNavigate } from '../../app/routeConfig'
import type { RoleContext, RouteParams, WorkExpectation } from '../../domain'
import { useResource } from '../../useResource'
import { OwnDailyReportShortcut } from './DailyReportShortcuts'
import { loadWorkBoard } from './workBoardApi'
import { boardLabels, personalWorkLabel } from './workBoardModel'
import { breakfastWorkflowSchedule, dailyWorkflowGroup, dailyWorkflowGroups, isImmediateWorkflowItem, loadDailyWorkflowItems, workflowTime, workflowTimeOrder, type DailyWorkflowGroup, type DailyWorkflowRole } from './roleDailyWorkflow'
import './roleDailyWorkflow.css'

const DailyWorkflowRecordDialog = lazy(() => import('../../App').then(module => ({ default: module.WorkRecordDialog })))

export function RoleDailyWorkflow({ identity, role, params, permissions, dailyReportsAvailable, go, onSaved }: {
  identity: RoleContext; role: DailyWorkflowRole; params: RouteParams; permissions: string[]
  dailyReportsAvailable: boolean; go: AppNavigate; onSaved: () => Promise<void>
}) {
  const queryKey = JSON.stringify(params)
  const resource = useResource(identity.key + ':daily-workflow:' + queryKey, () => loadDailyWorkflowItems(page =>
    loadWorkBoard(identity, 'my', { ...params, page: String(page), status: 'ALL', queue: 'PERIOD' })), [] as WorkExpectation[], 60_000, true)
  const [selected, setSelected] = useState<DailyWorkflowGroup>('start')
  const [editing, setEditing] = useState<WorkExpectation>()
  const [opening, setOpening] = useState<string>()
  const [message, setMessage] = useState('')
  const request = useRef(0)
  useEffect(() => () => { request.current += 1 }, [])
  const groups = dailyWorkflowGroups[role]
  const activeGroup = groups.find(group => group.id === selected)!
  const items = resource.data.filter(item => dailyWorkflowGroup(role, item) === selected).sort(workflowTimeOrder)
  const immediate = resource.data.filter(isImmediateWorkflowItem)
  const canOpenReports = dailyReportsAvailable && (permissions.includes('*') || permissions.includes('daily-report.read'))

  async function open(item: WorkExpectation) {
    const version = ++request.current
    setMessage(''); setOpening(item.id)
    try {
      const result = await loadExpectation(identity, item.id)
      if (version !== request.current) return
      if (result.source !== 'api') throw new Error('未读取到实际工作详情，请刷新重试。')
      if (result.data.itemCode === 'HKA_DAILY_REPORT'
        || (['HKA_GUEST_SERVICE_EVENT', 'HKA_LOST_PROPERTY'].includes(result.data.itemCode ?? '') && !result.data.recordId)) {
        if (!canOpenReports) throw new Error('当前任职尚未开放日报入口，请联系管理员核对岗位功能配置。')
        go('daily-reports-my', { businessDate: result.data.businessDate, open: 'today' })
      } else setEditing(result.data)
    } catch (error) { if (version === request.current) setMessage(error instanceof Error ? error.message : '工作详情读取失败，请重试。') }
    finally { if (version === request.current) setOpening(undefined) }
  }

  return <section className="rw-workflow" aria-labelledby="role-workflow-heading">
    <header className="rw-heading"><div><h2 id="role-workflow-heading">我的当班工作</h2><p>仅当前任职的实际工作；页面分组不减少考核项目，也不改变完成率。</p></div>
      <button type="button" className="secondary" disabled={resource.loading} onClick={resource.reload}>{resource.loading ? '读取中…' : '刷新工作'}</button></header>
    {resource.loading ? <p className="rw-notice" role="status">正在读取当前任职的完整工作清单…</p>
      : resource.error ? <div className="inline-error" role="alert">{resource.error}<button className="text-action" onClick={resource.reload}>重新加载</button></div>
      : <>
        <aside className="rw-immediate" aria-label="即时事件入口"><div><strong>即时记录与上报</strong><p>客诉、安全与遗留物等异常及时处理，不等日报截止。</p></div>
          <div className="rw-actions">{immediate.length ? immediate.map(item => <button type="button" className="secondary" key={item.id}
            disabled={Boolean(opening)} onClick={() => void open(item)}>{item.title}{params.from !== params.to ? ' · ' + item.businessDate : ''}</button>)
            : <span className="rw-muted">当前工作清单未配置即时事项。紧急情况请先联系当班负责人。</span>}</div></aside>
        <nav className="rw-groups" aria-label="当班工作分组">{groups.map(group => <button type="button" key={group.id}
          aria-pressed={selected === group.id} className={selected === group.id ? 'active' : ''} onClick={() => setSelected(group.id)}>{group.label}</button>)}</nav>
        <div className="rw-group-content"><header><h3>{activeGroup.label}</h3><p>{activeGroup.description}</p></header>
          {!resource.data.length ? <p className="rw-notice">所选日期暂无当前任职工作。若应有工作，请刷新或联系管理员检查任职、工作包和排班配置。</p>
            : !items.length ? <p className="rw-notice">该分组暂无已生成工作，可切换其他分组查看；系统不会用示例记录填充。</p>
            : <ul className="rw-items">{items.map(item => { const breakfast = breakfastWorkflowSchedule(item); return <li key={item.id}>
              <div className="rw-item-main"><strong>{item.title}</strong><span>{personalWorkLabel(item, identity.businessActorAssignmentId)} · 工作归属日 {item.businessDate}</span>
                <span>可开始 {workflowTime(item.availableAt)}　截止 {workflowTime(item.dueAt)}</span>
                {breakfast ? <><ol className="rw-breakfast-stages" aria-label="本店早餐工作阶段">{breakfast.stages.map(stage => <li key={stage.key}><strong>{stage.label}</strong><span>{stage.start}–{stage.end}</span></li>)}</ol>
                  {!breakfast.confirmed && <span>本店早餐营业时段尚未确认，请联系管理员核对；以上配置不表示已执行。</span>}
                  {breakfast.recheck && <span>{breakfast.recheck}仅复查尚未解决的问题，不另增一项必做工作。</span>}
                  <span>阶段实际结果在原工作表单中保存、查看，未填写不显示为已完成。</span></>
                  : (item.itemCode ?? '').includes('BREAKFAST') && <span>早餐三阶段时段待配置，请联系管理员核对已发布工作包。</span>}
                {role === 'NIGHT_SERVICE_ATTENDANT' && item.itemCode === 'NSA_DAILY_HANDOVER' && <span>以上为实际工作时间；未绑定有效班次时请联系管理员配置，不默认按02:00交班。</span>}
                {item.executionPolicy?.repeatableDailyRecords && <span>按需重复记录，不因未发生事件扣完成率。</span>}</div>
              <div className="rw-item-action"><span className={'rw-status ' + (item.boardStatus ?? '').toLowerCase()}>{item.boardStatus ? boardLabels[item.boardStatus] : '状态待核对'}</span>
                <button type="button" className="text-action" disabled={Boolean(opening)} onClick={() => void open(item)}>{opening === item.id ? '正在打开…' : '填写 / 查看'}</button></div>
            </li> })}</ul>}
          {selected === 'close' && <footer className="rw-report-entry"><div><strong>打开本人日报</strong><p>引用当天记录，补充实际差异和其他工作。库存、遗留物接收与交班仍须各自确认。</p></div>
            <OwnDailyReportShortcut permissions={permissions} available={dailyReportsAvailable} hasAssignment={Boolean(identity.businessActorAssignmentId)} go={go} />
            {!canOpenReports && <span className="rw-muted">日报入口待管理员配置。</span>}</footer>}
        </div>
      </>}
    {opening && <p className="rw-notice" role="status">正在读取工作详情，请稍候…</p>}
    {message && <p className="inline-warning" role="status">{message}</p>}
    {editing && <Suspense fallback={<p role="status">正在打开原工作表单…</p>}><DailyWorkflowRecordDialog
      key={identity.key + ':' + editing.id} item={editing} identity={identity} onClose={() => setEditing(undefined)}
      onSaved={async () => { await Promise.all([resource.reload(), onSaved()]) }} onDelegated={setMessage} /></Suspense>}
  </section>
}
