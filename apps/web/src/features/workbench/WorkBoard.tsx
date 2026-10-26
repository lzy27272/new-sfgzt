import { useEffect, useState, type ReactNode } from 'react'
import type { NavigationParams, RoleContext, RouteParams, WorkExpectation } from '../../domain'
import { useResource } from '../../useResource'
import { loadWorkBoard } from './workBoardApi'
import {
  boardLabels, boardQuery, dateRange, emptyBoard, isLateSubmitted, localBusinessDate, personalWorkLabel, rate, validDate,
  type BoardMetric, type BoardStatus, type DateMode, type WorkBoard,
} from './workBoardModel'
import './workBoard.css'

export function useWorkBoard(identity: RoleContext, scope: 'my' | 'team', params: RouteParams, enabled = true) {
  const [serverDate, setServerDate] = useState<string>()
  const query = boardQuery(params, serverDate)
  const key = JSON.stringify(query)
  const resource = useResource(identity.key + ':work-board:' + scope + ':' + enabled + ':' + key,
    () => enabled ? loadWorkBoard(identity, scope, params, serverDate)
      : Promise.resolve({ data: emptyBoard, source: 'api' as const }), emptyBoard, 60_000, true)
  useEffect(() => {
    if (resource.data.businessDate) setServerDate(resource.data.businessDate)
  }, [resource.data.businessDate])
  return { ...resource, query }
}

export function WorkDateToolbar({ params, businessDate, onChange }: {
  params: RouteParams; businessDate?: string; onChange: (patch: NavigationParams) => void
}) {
  const today = businessDate || localBusinessDate()
  const query = boardQuery(params, today)
  const [from, setFrom] = useState(query.from)
  const [to, setTo] = useState(query.to)
  const [mode, setMode] = useState(query.mode)
  const [error, setError] = useState('')
  useEffect(() => { setMode(query.mode) }, [query.mode])
  useEffect(() => { setFrom(query.from); setTo(query.to); setError('') }, [query.from, query.to])
  const apply = (nextMode: DateMode, start = from, end = to) => {
    if (!validDate(start) || !validDate(end) || (nextMode === 'custom' && end < start)
      || (nextMode === 'custom' && Date.parse(end) - Date.parse(start) > 365 * 86_400_000)) {
      setError('请选择有效日期，结束日不能早于开始日，最多查询366天。')
      return
    }
    setError('')
    const range = dateRange(nextMode, start, end)
    setMode(nextMode)
    setFrom(range.from)
    setTo(range.to)
    onChange({ dateMode: nextMode, ...range, queue: undefined })
  }
  return <section className="wb-date-bar" aria-label="工作日期范围">
    <div className="wb-date-tabs">{([['day', '日'], ['week', '周'], ['custom', '自定义']] as const).map(([value, label]) =>
      <button type="button" key={value} className={mode === value ? 'active' : ''} aria-pressed={mode === value}
        onClick={() => apply(value)}>{label}</button>)}</div>
    <label><span className="wb-sr-only">开始日期</span><input type="date" value={from}
      onChange={event => { setFrom(event.target.value); if (mode !== 'custom' && validDate(event.target.value)) apply(mode, event.target.value, event.target.value) }} /></label>
    {(mode === 'custom' || mode === 'week') && <><span>至</span><label><span className="wb-sr-only">结束日期</span>
      <input type="date" value={to} readOnly={mode === 'week'} onChange={event => setTo(event.target.value)} /></label></>}
    {mode === 'custom' && <button className="primary" type="button" onClick={() => apply('custom')}>查询</button>}
    <button type="button" className="secondary" onClick={() => apply('day', today, today)}>今天</button>
    <button type="button" className={mode === 'month' ? 'secondary active' : 'secondary'} onClick={() => apply('month', today, today)}>本月累计</button>
    <small>按工作归属日期汇总 · 营业日 {today}</small>
    {error && <p className="inline-error" role="alert">{error}</p>}
  </section>
}

const statusOrder: Exclude<BoardStatus, 'ALL'>[] = ['PENDING', 'OVERDUE', 'REVIEW', 'COMPLETED']
const metricField = { PENDING: 'pending', OVERDUE: 'overdue', REVIEW: 'review', COMPLETED: 'completed' } as const
export function WorkStatusCards({ metric, loading, onSelect, selected }: {
  metric: BoardMetric; loading?: boolean; onSelect: (status: BoardStatus) => void; selected?: BoardStatus
}) {
  return <div className="wb-status-cards">{statusOrder.map(status =>
    <button type="button" key={status} className={'wb-status-card ' + status.toLowerCase() + (selected === status ? ' selected' : '')}
      onClick={() => onSelect(status)} aria-label={boardLabels[status] + '，' + (loading ? '加载中' : metric[metricField[status]] + '项')}>
      <span>{boardLabels[status]}</span><strong>{loading ? '—' : metric[metricField[status]]}<small>项</small></strong><em>查看工作 ›</em>
    </button>)}</div>
}
export function CompletionRates({ metric }: { metric: BoardMetric }) {
  return <div className="wb-rates">
    <span>闭环完成率 <strong>{rate(metric.completed_rate)}</strong><small>{metric.completed}/{metric.expected} 项</small></span>
    <span>提交率 <strong>{rate(metric.submitted_rate)}</strong><small>含待审核</small></span>
    <span>按时提交率 <strong>{rate(metric.on_time_submitted_rate)}</strong><small>不受审核等待影响</small></span>
  </div>
}
export function WorkBoardPanel({ title, subtitle, data, loading, error, onSelect, onAll, onRetry, id, children }: {
  title: string; subtitle: string; data: WorkBoard; loading: boolean; error?: string; id?: string
  onSelect: (status: BoardStatus) => void; onAll: () => void; onRetry: () => void
  children?: ReactNode
}) {
  return <article id={id} className="wb-panel">
    <header><div><h2>{title}</h2><p>{subtitle}</p></div><button className="text-action" onClick={onAll}>查看全部 ›</button></header>
    {error ? <div className="inline-error" role="alert">{error}<button onClick={onRetry}>重新加载</button></div> : <>
      <WorkStatusCards metric={data.period} loading={loading} onSelect={onSelect} />
      {!loading && <><CompletionRates metric={data.period} />
        <div className="wb-completion-track" aria-label="所选期间闭环完成进度"><span style={{ width: Math.max(0, Math.min(100, data.period.completed_rate ?? 0)) + '%' }} /></div>
        <div className="wb-board-periods"><span>当日完成率 <strong>{rate(data.today.completed_rate)}</strong></span>
          <span>截至当日月完成率 <strong>{rate(data.month.completed_rate)}</strong></span></div>
        <p className="wb-metric-note">已补交 {data.period.late} 项（附加标记） · 按需记录 {data.period.optional_entries} 条 / {data.period.optional_records} 个工作项，不计必做完成率</p>{children}</>}
    </>}
  </article>
}
export function WorkQueueAlerts({ data, onOpen }: { data: WorkBoard; onOpen: (patch: NavigationParams) => void }) {
  return <div className="wb-queue-alerts">
    {data.reviewQueue.item_count > 0 && <button className="wb-review-alert" type="button" onClick={() => onOpen({ queue: 'REVIEW', status: 'ALL', lateOnly: undefined })}>
      <span><strong>{data.reviewQueue.pending_review_records} 条记录待审核 · {data.reviewQueue.item_count} 项工作</strong><small>不受所选日期限制，含历史补交；点击工作查看所属待审核记录，仅可操作授权范围</small></span><b>立即查看 →</b></button>}
    {data.carryover.item_count > 0 && <button className="wb-carry-alert" type="button" onClick={() => onOpen({ queue: 'CARRYOVER', status: 'ALL', lateOnly: undefined })}>
      <span><strong>跨期待办 {data.carryover.item_count} 项</strong><small>仅非日清持续任务，不混入所选日期完成率</small></span><b>查看 →</b></button>}
  </div>
}
function dateTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}
export function WorkBoardList({ data, loading, error, params, onChange, onOpen, onRetry, own = false, assignmentId }: {
  data: WorkBoard; loading: boolean; error?: string; params: RouteParams; own?: boolean; assignmentId?: string
  onChange: (patch: NavigationParams) => void; onOpen: (item: WorkExpectation) => void; onRetry: () => void
}) {
  const query = boardQuery(params, data.businessDate || undefined)
  return <article className="wb-panel wb-list">
    <header><div><h2>{query.queue === 'SPOT_CHECK' ? '免审待抽查（含历史）' : query.queue === 'REPAIR' ? '待整改（含历史）' : query.queue === 'REVIEW' ? '待人工审核（含历史）' : query.queue === 'CARRYOVER' ? '非日清跨期待办' : '工作明细'}</h2>
      <p>{query.queue === 'PERIOD' ? query.from + ' 至 ' + query.to + ' · 原工作日期口径' : '跨期队列独立显示，不改变所选期间完成率'}</p></div>
      {query.queue !== 'PERIOD' && <button className="secondary" onClick={() => onChange({ queue: undefined, status: undefined })}>返回所选日期</button>}
    </header>
    <div className="wb-list-toolbar">
      <div className="wb-filter-tabs">{(['ALL', ...statusOrder] as BoardStatus[]).map(status =>
        <button type="button" key={status} className={query.status === status ? 'active' : ''} onClick={() => onChange({ status })}>{boardLabels[status]}</button>)}</div>
      <label><input type="checkbox" checked={query.lateOnly} onChange={event => onChange({ lateOnly: event.target.checked ? 'true' : undefined,
        status: event.target.checked && query.status === 'OVERDUE' ? 'ALL' : query.status })} /> 仅看已补交</label>
    </div>
    {(query.status === 'OVERDUE' || query.lateOnly) && <div className="wb-filter-tabs wb-overdue-tabs" aria-label="逾期工作处理情况">
      <button className={!query.lateOnly ? 'active' : ''} onClick={() => onChange({ status: 'OVERDUE', lateOnly: undefined })}>仍未提交</button>
      <button className={query.lateOnly ? 'active' : ''} onClick={() => onChange({ status: 'ALL', lateOnly: 'true' })}>已补交</button>
    </div>}
    {loading ? <p className="wb-empty" role="status">正在读取工作与统计…</p>
      : error ? <p className="inline-error" role="alert">{error}<button onClick={onRetry}>重新加载</button></p>
      : !data.items.length ? <p className="wb-empty">当前日期和筛选范围内没有工作记录。</p>
      : <div className="wb-table-scroll" role="region" tabIndex={0} aria-label="数据表，可左右滚动"><table className="wb-work-table">
        <thead><tr><th>工作名称</th><th>负责人 / 岗位</th><th>工作归属日 / 截止</th><th>提交时间</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{data.items.map(item => <tr key={item.id}>
          <td><strong>{item.title}</strong><small>{item.hotelName || item.targetOrgName} · {item.departmentName || '直属岗位'}</small></td>
          <td>{own && assignmentId ? personalWorkLabel(item, assignmentId) : item.assigneeName}<small>{item.positionName}</small></td>
          <td>{item.businessDate}<small>截止 {dateTime(item.dueAt)}</small></td>
          <td>{dateTime(item.latestSubmittedAt)}</td>
          <td><span className={'wb-badge ' + (item.boardStatus || 'PENDING').toLowerCase()}>{boardLabels[item.boardStatus || 'PENDING']}</span>
            {isLateSubmitted(item) && <span className="wb-badge late">已补交</span>}
            {['REJECTED', 'RETURNED'].includes(item.latestRecordStatus ?? '') && <span className="wb-badge overdue">已退回</span>}
            {item.executionPolicy?.repeatableDailyRecords && <span className="wb-badge optional">可重复记录</span>}
          </td>
          <td><button className="text-action" onClick={() => onOpen(item)}>{own ? '填写 / 查看' : '详情与复核'} ›</button></td>
        </tr>)}</tbody>
      </table></div>}
    {!loading && !error && <footer className="wb-pagination"><span>共 {data.filtered.item_count} 项 · 第 {data.page + 1} 页</span>
      <button className="secondary" disabled={data.page <= 0} onClick={() => onChange({ page: String(data.page - 1) })}>上一页</button>
      <button className="secondary" disabled={(data.page + 1) * data.size >= data.filtered.item_count}
        onClick={() => onChange({ page: String(data.page + 1) })}>下一页</button></footer>}
  </article>
}
