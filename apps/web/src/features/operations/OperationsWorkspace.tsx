import { lazy, Suspense, useState } from 'react'
import { apiRequest } from '../../api/client'
import { loadExpectation } from '../../api/resources'
import type { RoleContext, RouteParams, WorkExpectation } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { useResource } from '../../useResource'
import { PlanWorkspace } from './PlanWorkspace'
import { chinaToday, percentage, planDeadline, type CompletionReport, type DailyItem, type OperationsContext } from './model'
import './operations.css'

const WorkEntry = lazy(() => import('../../App').then(module => ({ default: module.WorkRecordDialog })))
const emptyContext: OperationsContext = { positionCode: '', positionName: '', hotels: [], dailyTemplateStatus: 'PENDING_CONFIGURATION', canReadPlan: false, canSubmitPlan: false, canReviewPlan: false }
const stateLabels: Record<string, string> = { PLANNED: '待开放', AVAILABLE: '待填写', PENDING: '待提交', OVERDUE: '逾期未提交', REVIEW: '待人工验收', COMPLETED: '已合格完成', PENDING_APPROVAL: '待人工审批', APPROVED: '已审批', DRAFT: '草稿', REJECTED: '已退回' }
export const dateTime = (value?: string) => value && Number.isFinite(Date.parse(value)) ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value)) : '—'
export const messageOf = (error: unknown) => error instanceof Error ? error.message : '操作失败，请重试。'
export const Badge = ({ value }: { value: string }) => <span className={`ops-badge ${value.toLowerCase()}`}>{stateLabels[value] || value}</span>

export function OperationsWorkspace({ identity, permissions, params, go }: { identity: RoleContext; permissions: string[]; params: RouteParams; go: AppNavigate }) {
  const context = useResource(`${identity.key}:operations-context`, async signal => ({ data: await apiRequest<OperationsContext>('/operations/context', identity, { signal }), source: 'api' as const }), emptyContext, 0, false, { cancellable: true })
  const tab = ['daily', 'plans', 'monthly', 'scope'].includes(params.tab) ? params.tab : 'daily'
  return <div className="ops-root">
    <nav className="ops-tabs" aria-label="运营部工作功能">{[['daily', '日清工作'], ['plans', '周 / 月计划'], ['monthly', '月度统计'], ['scope', '负责门店配置']].map(([id, name]) => <button key={id} className={tab === id ? 'active' : ''} onClick={() => go('operations-work', { tab: id })}>{name}</button>)}</nav>
    {context.loading ? <p className="ops-loading" role="status">正在读取当前任职与负责门店…</p> : context.error ? <ErrorNotice error={context.error} retry={context.reload} /> : <>
      <p className="ops-context">{context.data.positionName || identity.label} · {identity.userName}　负责 {context.data.hotels.length} 家门店 <span>组织归属与负责门店分别配置</span></p>
      {context.data.portfolioConfigurationNotice && <p className="ops-note">{context.data.portfolioConfigurationNotice}</p>}
      {tab === 'daily' && <DailyWorkspace key={identity.key} identity={identity} context={context.data} go={go} />}
      {tab === 'plans' && <PlanWorkspace key={identity.key + (params.planId || '')} identity={identity} context={context.data} planId={params.planId} go={go} />}
      {tab === 'monthly' && <MonthlyWorkspace key={identity.key} identity={identity} context={context.data} go={go} />}
      {tab === 'scope' && <section className="panel ops-panel"><header><h1>指定负责门店</h1><p>运营经理、运营助理可以在同一个任职下负责多家门店。</p></header><div className="ops-body"><dl className="ops-scope-list"><div><dt>所属组织</dt><dd>{identity.orgName}</dd></div><div><dt>当前负责门店</dt><dd>{context.data.hotels.length ? context.data.hotels.map(hotel => hotel.name).join('、') : '尚未配置，不默认开放全部门店'}</dd></div></dl><p className="ops-note">在人事中心 → 组织与人员 → 人员与任职 → 修改任职，多选负责门店。岗位功能与门店范围分别生效，助理不会自动获得经理的审核权限。</p>{permissions.includes('*') || permissions.includes('org.manage') ? <button className="primary" onClick={() => go('organization', { tab: 'employee' })}>前往人员与任职配置</button> : <p className="ops-muted">当前账号没有修改任职权限，请由管理员或有权限的人事人员配置。</p>}<p className="ops-muted">运营助理工作清单待补充，本轮不套用经理的7项日清。门店范围调整不覆盖历史工作记录。</p></div></section>}
    </>}
  </div>
}
export function ErrorNotice({ error, retry }: { error?: string; retry?: () => unknown }) {
  return error ? <p className="inline-error" role="alert">{error}{retry && <button type="button" onClick={() => void retry()}>重新加载</button>}</p> : null
}

function DailyWorkspace({ identity, context, go }: { identity: RoleContext; context: OperationsContext; go: AppNavigate }) {
  const [date, setDate] = useState(chinaToday)
  const [hotelId, setHotelId] = useState('')
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<WorkExpectation>()
  const [opening, setOpening] = useState<string>()
  const [error, setError] = useState('')
  const board = useResource(`${identity.key}:operations-daily:${date}`, async signal => ({ data: await apiRequest<{ businessDate: string; items: DailyItem[] }>(`/operations/daily?date=${date}`, identity, { signal }), source: 'api' as const }), { businessDate: date, items: [] }, 0, false, { cancellable: true })
  const rows = board.data.items.filter(item => (!hotelId || hotelId === item.hotelId) && item.name.includes(search.trim()))
  const hotels = Array.from(new Map([...context.hotels, ...board.data.items.map(item => ({ id: item.hotelId, name: item.hotelName }))].map(hotel => [hotel.id, hotel])).values())
  async function open(id: string) {
    setOpening(id); setError('')
    try { setSelected((await loadExpectation(identity, id)).data) } catch (reason) { setError(messageOf(reason)) } finally { setOpening(undefined) }
  }
  return <>
    <header className="ops-title"><div><h1>每日运营工作</h1><p>总监与经理按本人负责门店分别执行，四轮检查均包含预订、流量和竞价。</p></div><Badge value="人工审核" /></header>
    <div className="ops-toolbar"><label>工作日期<input type="date" value={date} onChange={e => e.target.value && setDate(e.target.value)} /></label><label>工作门店<select aria-label="工作门店" value={hotelId} onChange={e => setHotelId(e.target.value)}><option value="">全部工作门店</option>{hotels.map(hotel => <option key={hotel.id} value={hotel.id}>{hotel.name}{context.hotels.some(current => current.id === hotel.id) ? '' : '（原职责）'}</option>)}</select></label><label className="ops-search">搜索工作<input type="search" value={search} onChange={e => setSearch(e.target.value)} placeholder="输入工作名称或关键词" /></label><button className="secondary" onClick={() => void board.reload()}>刷新</button></div>
    <ErrorNotice error={board.error || error} retry={board.reload} />
    {board.loading ? <p className="ops-loading" role="status">正在读取本人日清工作…</p> : !hotels.length ? <section className="panel ops-empty"><h2>尚未配置负责门店</h2><p>配置本人负责门店后再生成对应工作。</p><button className="secondary" onClick={() => go('operations-work', { tab: 'scope' })}>查看配置方式</button></section> : !board.error && !rows.length ? <section className="panel ops-empty"><h2>{context.dailyTemplateStatus === 'READY' ? '所选日期或筛选条件暂无工作' : '工作清单待配置'}</h2><p>{context.dailyTemplateStatus === 'READY' ? '请调整日期或搜索条件；未生成的工作不会被当作员工漏交。' : '当前岗位的工作清单尚未配置，不会套用其他岗位的工作。'}</p></section> : !board.error && hotels.filter(hotel => rows.some(item => item.hotelId === hotel.id)).map(hotel => <section className="panel ops-panel" key={hotel.id}><header><div><h2>{hotel.name}{!context.hotels.some(current => current.id === hotel.id) && <small> · 原职责</small>}</h2><p>{rows.filter(item => item.hotelId === hotel.id).length}项工作 · 本人独立提交</p></div></header><p className="ops-scroll-hint">表格可左右滑动，查看截止时间和填报操作。</p><div className="ops-table-scroll"><table className="ops-daily-table"><thead><tr><th>工作全名</th><th>计划完成</th><th>本人提醒</th><th>完成截止</th><th>上级提醒</th><th>状态</th><th>操作</th></tr></thead><tbody>{rows.filter(item => item.hotelId === hotel.id).map(item => <tr key={item.expectationId}><td><strong>{item.name}</strong></td><td>{item.targetTime || '—'}</td><td>{dateTime(item.reminderAt)}</td><td>{dateTime(item.dueAt)}</td><td>{dateTime(item.escalationAt)}</td><td><Badge value={item.boardStatus} /></td><td><button className="secondary" disabled={Boolean(opening)} onClick={() => void open(item.expectationId)}>{opening === item.expectationId ? '读取中…' : ['COMPLETED', 'REVIEW'].includes(item.boardStatus) ? '查看填报' : '填写结果'}</button></td></tr>)}</tbody></table></div></section>)}
    {selected && <Suspense fallback={<p role="status">正在打开工作填报…</p>}><WorkEntry item={selected} identity={identity} onClose={() => setSelected(undefined)} onSaved={async () => { setSelected(undefined); await board.reload() }} onDelegated={setError} /></Suspense>}
  </>
}

function MonthlyWorkspace({ identity, context, go }: { identity: RoleContext; context: OperationsContext; go: AppNavigate }) {
  const [month, setMonth] = useState(() => chinaToday().slice(0, 7))
  const [hotel, setHotel] = useState('')
  const [page, setPage] = useState(0)
  const [metric, setMetric] = useState<'completed' | 'ontime'>('completed')
  const [historyOpen, setHistoryOpen] = useState(false)
  const query = new URLSearchParams({ month, page: String(page), size: '50', ...(hotel ? { hotelId: hotel } : {}) }).toString()
  const report = useResource(`${identity.key}:completion:${query}`, async signal => ({ data: await apiRequest<CompletionReport>(`/operations/completion?${query}`, identity, { signal }), source: 'api' as const }), undefined as CompletionReport | undefined, 0, false, { cancellable: true })
  return <>
    <header className="ops-title"><div><h1>月工作完成率</h1><p>日清、计划执行任务和临时下达任务统一统计；同一事项只计一次。</p></div></header>
    <div className="ops-toolbar"><label>统计月份<input type="month" value={month} onChange={e => { if (e.target.value) { setMonth(e.target.value); setPage(0); setHistoryOpen(false) } }} /></label><label>负责门店<select aria-label="负责门店" value={hotel} onChange={e => { setHotel(e.target.value); setPage(0) }}><option value="">全部负责门店</option>{context.hotels.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><button className="secondary" onClick={() => void report.reload()}>刷新统计</button></div>
    <ErrorNotice error={report.error} retry={report.reload} />
    {report.loading ? <p className="ops-loading" role="status">正在汇总当月工作…</p> : report.data && !report.error && <>
      <section className="panel ops-panel"><header><div><h2>统计截至 {dateTime(report.data.asOf)}</h2><p>当前进行月份只统计已到期工作，尚未到期另列。</p></div></header><div className="ops-body"><div className="ops-tabs compact"><button className={metric === 'completed' ? 'active' : ''} onClick={() => setMetric('completed')}>月工作完成率</button><button className={metric === 'ontime' ? 'active' : ''} onClick={() => setMetric('ontime')}>月按时完成率</button></div><div className="ops-completion"><div><strong className="ops-percentage">{percentage(metric === 'completed' ? report.data.completionRate : report.data.onTimeRate)}</strong><p>{report.data.denominator ? `${metric === 'completed' ? report.data.qualifiedCompleted : report.data.onTimeCompleted} 项 / 应完成 ${report.data.denominator} 项` : '暂无应完成工作'}</p></div><dl><div><dt>待人工验收</dt><dd>{report.data.pendingReview}项</dd></div><div><dt>合格但逾期提交</dt><dd>{report.data.lateQualified}项</dd></div><div><dt>尚未到期</dt><dd>{report.data.futurePlanned}项</dd></div></dl></div><p className="ops-note">下达任务必须人工验收。领导晚审核不改变合格版本的提交时间；逾期整改可恢复最终完成数，不恢复原按时记录。跨月补交仍归属原截止月份。</p><button className="text-action" onClick={() => setHistoryOpen(value => !value)}>{historyOpen ? '收起统计变更记录' : '查看统计变更记录'}</button></div></section>
      {historyOpen && <CompletionHistory key={identity.key + month + hotel} identity={identity} query={new URLSearchParams({ month, ...(hotel ? { hotelId: hotel } : {}) }).toString()} />}
      {!!report.data.undatedTasks && <p className="ops-note">另有{report.data.undatedTasks}项任务缺少截止日期，无法确定归属月份，未擅自计入本月。</p>}
      <section className="panel ops-panel"><header><h2>按工作来源查看</h2></header><p className="ops-scroll-hint">左右滑动查看按时完成率和待验收数量。</p><div className="ops-table-scroll"><table><thead><tr><th>工作来源</th><th>应完成</th><th>合格完成</th><th>完成率</th><th>按时完成率</th><th>待验收</th></tr></thead><tbody>{report.data.bySource.map(item => <tr key={item.source}><td>{item.label}</td><td>{item.due}</td><td>{item.completed}</td><td>{percentage(item.completionRate)}</td><td>{percentage(item.onTimeRate)}</td><td>{item.pendingReview}</td></tr>)}</tbody></table></div></section>
      <section className="panel ops-panel"><header><h2>材料填报与经营目标</h2><p>周/月材料的提交与审批独立记录，不把提交计划当成经营目标达成。</p></header><div className="ops-body">{report.data.planCompliance.length ? report.data.planCompliance.map(plan => <div className="ops-compliance" key={plan.id}><span>{plan.type === 'WEEKLY' ? '周总结与下周计划' : '月运营计划'} · {plan.periodStart}</span><Badge value={plan.status} /><span>{plan.submittedAt ? `${plan.late ? '逾期补交' : '按时提交'} · ${dateTime(plan.submittedAt)}` : `截止 ${planDeadline(plan.dueAt)}`}</span></div>) : <p className="ops-muted">暂无已配置的周/月材料记录。</p>}<p className="ops-muted">营业额、转化率等经营目标须依据已核实数据评估，不由工作完成率推定。</p></div></section>
      <section className="panel ops-panel"><header><h2>工作明细</h2><p>共{report.data.totalItems}项 · 按原截止日期归属</p></header>{report.data.items.length ? <div className="ops-table-scroll"><table><thead><tr><th>工作</th><th>门店</th><th>截止 / 提交</th><th>状态</th><th>操作</th></tr></thead><tbody>{report.data.items.map(item => <tr key={item.id}><td>{item.title}</td><td>{item.hotelName}</td><td>{dateTime(item.dueAt)}<small>提交 {dateTime(item.submittedAt)}</small></td><td><Badge value={item.dueAt && Date.parse(item.dueAt) > Date.parse(report.data!.asOf) ? "尚未到期" : item.status} /></td><td>{item.expectationId ? <button className="text-action" onClick={() => go('my-work', { expectationId: item.expectationId })}>查看工作</button> : item.taskId ? <button className="text-action" onClick={() => go('tasks', { taskId: item.taskId })}>查看任务</button> : '—'}</td></tr>)}</tbody></table></div> : <p className="ops-empty">本月暂无可列出的工作。</p>}<div className="ops-footer"><button className="secondary" disabled={!page} onClick={() => setPage(value => value - 1)}>上一页</button><span>第{page + 1}页</span><button className="secondary" disabled={(page + 1) * report.data.size >= report.data.totalItems} onClick={() => setPage(value => value + 1)}>下一页</button></div></section>
      {report.data.historyCorrectionNote && <p className="ops-muted">{report.data.historyCorrectionNote}</p>}
    </>}
  </>
}
function CompletionHistory({ identity, query }: { identity: RoleContext; query: string }) {
  const history = useResource(`${identity.key}:completion-history:${query}`, async signal => ({ data: await apiRequest<{ items: { id: string; revisionNo: number; capturedAt: string; qualifiedCompleted: number; onTimeCompleted: number; denominator: number; changeReason: string }[] }>(`/operations/completion/history?${query}`, identity, { signal }), source: 'api' as const }), { items: [] }, 0, false, { cancellable: true })
  return <section className="panel ops-panel"><header><h2>统计变更记录</h2><p>记录从功能启用后开始生成，不伪造此前快照。</p></header><ErrorNotice error={history.error} retry={history.reload} />{history.loading ? <p className="ops-loading">正在读取变更记录…</p> : <div className="ops-table-scroll"><table><thead><tr><th>版本 / 时间</th><th>合格完成</th><th>按时完成</th><th>应完成</th><th>变更原因</th></tr></thead><tbody>{history.data.items.map(item => <tr key={item.id}><td>V{item.revisionNo} · {dateTime(item.capturedAt)}</td><td>{item.qualifiedCompleted}</td><td>{item.onTimeCompleted}</td><td>{item.denominator}</td><td>{item.changeReason}</td></tr>)}</tbody></table></div>}</section>
}
