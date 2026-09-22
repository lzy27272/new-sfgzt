import { lazy, Suspense, useState } from 'react'
import { apiRequest } from '../../api/client'
import { loadExpectation } from '../../api/resources'
import type { RoleContext, RouteParams, WorkExpectation } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { useResource } from '../../useResource'
import { WeeklyAssessment } from './WeeklyAssessment'
import './managerCycles.css'

const CycleWorkRecordDialog = lazy(() => import('../../App').then(module => ({ default: module.WorkRecordDialog })))

export type CycleType = 'WEEK' | 'MONTH' | 'QUARTER'
type CycleItem = { id: string; item_code: string; name: string; description: string; due_at: string; status: string
  board_status: string; hotel_name: string; late: boolean; evidence_count: number; record_status?: string; payload?: Record<string, unknown>
  form_schema?: { properties?: Record<string, { title?: string }> } }
type CycleBoard = { period: CycleType; start: string; end: string; today: string; items: CycleItem[]; counts: Record<string, number>; carryover: number }
const labels: Record<string, string> = { PENDING: '待提交', REVIEW: '待复核', COMPLETED: '已完成', OVERDUE: '逾期' }
const periods = [{ id: 'DAY', name: '日清' }, { id: 'WEEK', name: '周清', count: 8 }, { id: 'MONTH', name: '月清', count: 9 }, { id: 'QUARTER', name: '季清', count: 6 }]
export const cycleType = (raw?: string): CycleType | 'DAY' => raw === 'WEEK' || raw === 'MONTH' || raw === 'QUARTER' ? raw : 'DAY'
export function shiftCycle(date: string, period: CycleType, direction: number) {
  const value = new Date(date + 'T12:00:00Z')
  if (period === 'WEEK') value.setUTCDate(value.getUTCDate() + direction * 7)
  else { value.setUTCDate(1); value.setUTCMonth(value.getUTCMonth() + direction * (period === 'MONTH' ? 1 : 3)) }
  return value.toISOString().slice(0, 10)
}
const due = (value: string) => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())

export function ManagerCycles({ identity, routeParams, go }: { identity: RoleContext; routeParams: RouteParams; go: AppNavigate }) {
  const selected = cycleType(routeParams.cycle)
  const team = routeParams.cycleTeam === 'true'
  const change = (patch: Record<string, string | undefined>) => go('workbench', { ...routeParams, ...patch })
  return <section className="mc-root" aria-label="店长周期工作">
    <nav className="mc-primary-tabs" aria-label="个人与团队工作">
      <button className={!team ? 'active' : ''} onClick={() => change({ cycleTeam: undefined })}>我的工作</button>
      <button className={team ? 'active' : ''} onClick={() => change({ cycleTeam: 'true', assessmentId: undefined })}>团队工作</button>
    </nav>
    {!team && <><nav className="mc-period-tabs" aria-label="工作周期">{periods.map(period => <button key={period.id}
      className={selected === period.id ? 'active' : ''} onClick={() => change({ cycle: period.id, cycleDate: undefined, assessmentId: undefined })}>
      {period.name}{period.count && <small>{period.count}</small>}</button>)}</nav>
      {selected !== 'DAY' && (routeParams.assessmentId
        ? <WeeklyAssessment key={identity.key + routeParams.assessmentId} identity={identity} id={routeParams.assessmentId} go={go}
          onBack={() => change({ assessmentId: undefined })} />
        : <CyclePanel key={identity.key + selected + (routeParams.cycleDate || '')} identity={identity} period={selected}
          date={routeParams.cycleDate || today()} go={go} onDate={date => change({ cycleDate: date })}
          onPeriod={period => change({ cycle: period, cycleDate: undefined })}
          onAssessment={id => change({ assessmentId: id })} />)}
    </>}
  </section>
}

function CyclePanel({ identity, period, date, go, onDate, onPeriod, onAssessment }: { identity: RoleContext; period: CycleType; date: string; go: AppNavigate
  onDate: (date: string) => void; onPeriod: (period: CycleType) => void; onAssessment: (id: string) => void }) {
  const board = useResource(identity.key + period + date, async () => ({ data: await apiRequest<CycleBoard>(`/manager-cycles?period=${period}&date=${date}`, identity), source: 'api' as const }),
    { period, start: date, end: date, today: date, items: [], counts: {}, carryover: 0 })
  const [status, setStatus] = useState('ALL')
  const [selected, setSelected] = useState<WorkExpectation>()
  const [message, setMessage] = useState('')
  const [opening, setOpening] = useState<string>()
  const [report, setReport] = useState(false)
  const name = periods.find(p => p.id === period)!.name
  const rows = status === 'ALL' ? board.data.items : board.data.items.filter(item => item.board_status === status)
  async function open(item: CycleItem) {
    if (item.item_code === 'GM_W_EMPLOYEE') { onAssessment(item.id); return }
    setOpening(item.id); setMessage('')
    try { const result = await loadExpectation(identity, item.id); setSelected(result.data) }
    catch (error) { setMessage(error instanceof Error ? error.message : '工作读取失败，请重试') }
    finally { setOpening(undefined) }
  }
  return <>
    <div className="mc-toolbar"><div><strong>{board.data.start} — {board.data.end}</strong><label>选择归属日期<input type="date" value={date} onChange={e => e.target.value && onDate(e.target.value)} /></label></div>
      <div className="mc-actions"><button className="secondary" onClick={() => onDate(shiftCycle(board.data.start, period, -1))}>上一{name[0]}</button>
        <button className="secondary" onClick={() => onDate(shiftCycle(board.data.start, period, 1))}>下一{name[0]}</button>
        <button className="secondary" onClick={() => setReport(true)}>查看{name[0]}报</button></div></div>
    {(board.error || message) && <p className="inline-error" role="alert">{board.error || message}<button onClick={board.reload}>重新加载</button></p>}
    <div className="mc-counters">{Object.entries(labels).map(([key, text]) => <button key={key} className={'mc-counter ' + key.toLowerCase() + (status === key ? ' selected' : '')}
      onClick={() => setStatus(status === key ? 'ALL' : key)}><b>{board.loading ? '—' : board.data.counts[key] ?? 0}</b><span>{text}</span>
      <small>{key === 'REVIEW' ? '已提交，等待上级复核' : key === 'COMPLETED' ? '已通过复核' : key === 'OVERDUE' ? '尚未有效提交，请及时补交' : '请按时完成并提交'}</small></button>)}</div>
    {board.data.carryover > 0 && <p className="mc-notice">此前周期还有 {board.data.carryover} 项未提交工作。<button className="text-action" onClick={() => go('my-work', { queue: 'CARRYOVER' })}>查看跨期未完成工作 ›</button></p>}
    <article className="mc-panel"><header><h2>店长{name}工作 · {board.data.items.length} 项</h2>{status !== 'ALL' && <button className="text-action" onClick={() => setStatus('ALL')}>显示全部</button>}</header>
      {board.loading ? <p className="mc-empty">正在读取周期工作…</p> : !rows.length ? <p className="mc-empty">此周期暂无符合条件的工作。首次启用前已过截止日的事项不补记逾期；未来周期由系统按期生成。</p> :
        <div className="mc-task-list"><div className="mc-task-head"><span>工作事项</span><span>截止时间</span><span>状态</span><span>操作</span></div>{rows.map(item => <div key={item.id} className={'mc-task-row' + (item.item_code === 'GM_W_EMPLOYEE' ? ' featured' : '')}>
          <div><strong>{item.name}</strong><small>{item.description}</small></div><time>{due(item.due_at)}</time>
          <span><b className={'mc-badge ' + item.board_status.toLowerCase()}>{labels[item.board_status]}</b>{item.late && <small className="mc-late">逾期后补交</small>}</span>
          <button disabled={Boolean(opening)} className={['PENDING','OVERDUE'].includes(item.board_status) ? 'primary' : 'secondary'} onClick={() => void open(item)}>
            {opening === item.id ? '读取中…' : item.item_code === 'GM_W_EMPLOYEE' ? '进入评估' : item.board_status === 'PENDING' ? '去填写' : item.board_status === 'OVERDUE' ? '去补交' : '查看'}</button></div>)}</div>}
    </article>
    <div className="mc-entry-cards">{periods.filter(p => p.id !== 'DAY' && p.id !== period).map(p => <button className="mc-panel" key={p.id} onClick={() => onPeriod(p.id as CycleType)}>
      <strong>{p.name} · {p.count} 项</strong><span>{p.id === 'MONTH' ? '空气能净水设备检查 · 当月28日前' : p.id === 'WEEK' ? '经营跟进、工作部署与员工评估' : '经营复盘、资产核查与人员评估'} ›</span></button>)}</div>
    <p className="mc-note">按工作归属周期统计，跨月提交不改归属；提交后由分管副总复核。人工周评分与工作完成率分别记录。</p>
    {selected && <Suspense fallback={<p role="status">正在打开工作表单…</p>}><CycleWorkRecordDialog item={selected} identity={identity} onClose={() => setSelected(undefined)} onSaved={async () => { setSelected(undefined); await board.reload(); setMessage('') }} onDelegated={setMessage} /></Suspense>}
    {report && <div className="modal-backdrop" role="presentation"><section className="mc-report" role="dialog" aria-modal="true" aria-label={`店长${name[0]}报`}>
      <header><div><h2>店长{name[0]}报</h2><p>{board.data.start} — {board.data.end} · 自动汇总本周期工作，不另计任务数量</p></div><button className="secondary" onClick={() => setReport(false)}>关闭</button></header>
      {board.data.items.map(item => <article key={item.id}><h3>{item.name} <span className={'mc-badge ' + item.board_status.toLowerCase()}>{labels[item.board_status]}</span></h3>
        {item.payload && ['SUBMITTED','APPROVED','REJECTED'].includes(item.record_status || '') ? Object.entries(item.payload).filter(([key]) => !key.startsWith('_')).map(([key, value]) =>
          <p key={key}><b>{item.form_schema?.properties?.[key]?.title || (key === 'employeeAssessments' ? '员工绩效评估' : key === 'periodLabel' ? '归属周期' : '记录内容')}：</b>{readableCycleValue(value)}</p>) : <p>尚未提交有效记录</p>}
        <p>证据 {item.evidence_count} 份{item.late ? ' · 已补交' : ''} <button className="text-action" onClick={() => { setReport(false); void open(item) }}>查看工作与证据 ›</button></p></article>)}
    </section></div>}
  </>
}
export function readableCycleValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '未填写'
  if (Array.isArray(value)) return value.map(readableCycleValue).join('；')
  if (typeof value === 'object') {
    const row = value as Record<string, unknown>
    if ('employeeName' in row) return `${row.employeeName}（${row.positionName}）：${row.score}分${row.focus ? '，关注重点：' + row.focus : ''}`
    return Object.values(row).map(readableCycleValue).join('，')
  }
  return String(value)
}
