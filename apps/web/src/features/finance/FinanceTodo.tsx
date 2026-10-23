import type { ApiIdentity } from '../../api/client'
import type { AppNavigate } from '../../app/routeConfig'
import { errorMessage } from './api'
import { money } from './model'
import { emptyTodos, financeState, financeTodoType, financeWaiting, todoTarget, type FinanceTodos } from './unifiedModel'
import { useFinanceResource } from './useFinanceResource'
import s from './finance.module.css'

export function FinanceTodo({ identity, go }: { identity: ApiIdentity; go: AppNavigate }) {
  const resource = useFinanceResource<FinanceTodos>(identity, '/todos')
  const data = resource.data ?? emptyTodos
  return <article className={`wb-panel ${s.todoPanel}`} aria-label="财务待办"><header><div><h2>财务待办{resource.data ? ` · ${data.total}` : ''}</h2><p>仅本人当前审核节点与出纳任务；跨日保留，不计入日常工作完成率</p></div><button className="text-action" onClick={() => go('finance', { tab: 'overview' })}>进入财务中心 ›</button></header>
    {resource.loading ? <p className="wb-empty" role="status">正在核对本人财务待办…</p> : resource.error ? <p className="inline-error" role="alert">{errorMessage(resource.error)}<button onClick={resource.reload}>重新读取</button></p> : <>
      <p className="finance-todo-counts"><span>待我审核 <strong>{data.counts.review}</strong></span><span>待我付款 <strong>{data.counts.payment}</strong></span><span>待补回单 <strong>{data.counts.receipt ?? 0}</strong></span><span>退回待补 <strong>{data.counts.returned}</strong></span></p>
      {data.items.length ? data.items.slice(0, 6).map(item => <button className="wb-compact-row" key={`${item.domain}:${item.kind}:${item.id}`} onClick={() => go('finance', todoTarget(item))}><span><strong>{item.title || '未填写事由'}</strong><small>{financeTodoType(item)} · {item.hotelName} · {item.applicantName} · {money(item.amount)}</small><small>{financeWaiting(item.waitingSince)}{item.mismatch ? ' · 金额或资料差异待核实' : ''}</small></span><b>{item.kind === 'receipt' ? '待补回单' : financeState(item.status)} ›</b></button>) : <p className="wb-empty">暂无本人当前可办理的财务事项。尚未流转至本人的审批不计入待办。</p>}
      {data.total > 6 && <button className="text-action" onClick={() => go('finance', { tab: 'overview' })}>查看全部 {data.total} 项财务待办 ›</button>}
    </>}
  </article>
}
