import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import type { RouteParams } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { financeRequest, errorMessage } from './api'
import { ClaimEditor } from './ClaimEditor'
import { ReviewDetail } from './ReviewDetail'
import { FinanceLedger } from './FinanceLedger'
import { FinanceConfiguration } from './FinanceConfiguration'
import { FinanceOverview, FinanceInvoicePool } from './FinanceOverview'
import { claimStatusLabel, money, type Claim, type FinanceContext } from './model'
import s from './finance.module.css'
const FinanceAccounting = lazy(() => import('./FinanceAccounting').then(module => ({ default: module.FinanceAccounting })))
const FinanceOperations = lazy(() => import('./FinanceOperations').then(module => ({ default: module.FinanceOperations })))
const FinanceSupply = lazy(() => import('./FinanceSupply'))
const FinanceBusiness = lazy(() => import('./FinanceBusiness'))
const FinanceInsights = lazy(() => import('./FinanceInsights'))
const FinanceMigration = lazy(() => import('./FinanceMigration'))
const FinanceControl = lazy(() => import('./FinanceControl'))
const FinanceAccrual = lazy(() => import('./FinanceAccrual'))
const FinanceArchive = lazy(() => import('./FinanceArchive'))
const FinanceCashCorrections = lazy(() => import('./FinanceCashCorrections'))
const FinanceAuthorizations = lazy(() => import('./FinanceAuthorizations'))
const FinanceBusinessExceptions = lazy(() => import('./FinanceBusinessExceptions'))
const FinanceInsightActions = lazy(() => import('./FinanceInsightActions'))
const FinanceTaxControl = lazy(() => import('./FinanceTaxControl'))
const queueTabs = ['mine', 'review', 'all']

export default function FinanceRoutes({ identity, permissions, params, go }: { identity: ApiIdentity; permissions: string[]; params: RouteParams; go: AppNavigate }) {
  const allowed = (permission: string) => permissions.includes('*') || permissions.includes(permission)
  const baseFinance = ['finance.submit', 'finance.review', 'finance.read', 'finance.pay', 'finance.configure'].some(allowed)
  const hasAccounting = ['finance.read', 'finance.configure', 'finance.accounting.read', 'finance.accounting.write', 'finance.accounting.review', 'finance.accounting.post', 'finance.accounting.close'].some(allowed)
  const managedFinance = ['finance.read', 'finance.review', 'finance.pay', 'finance.configure'].some(allowed)
  const hasArchive = ['finance.archive.read', 'finance.archive.grant'].some(allowed)
  const tabs = [(baseFinance || hasAccounting || hasArchive) && ['overview', '财务工作台'], allowed('finance.submit') && ['mine', '我的报销'], allowed('finance.review') && ['review', '报销审核'], (allowed('finance.read') || allowed('finance.pay')) && ['all', '单据与付款'], baseFinance && ['operations', '资金与往来'], (managedFinance || hasAccounting) && ['supply', '采购与资产'], managedFinance && ['business', '收入与专项'], baseFinance && ['control', allowed('finance.submit') && !managedFinance ? '差旅与本人资料' : '财务控制'], (allowed('finance.submit') || allowed('finance.read') || allowed('finance.review')) && ['invoices', '票据原件'], hasAccounting && ['accounting', '核算与报表'], hasAccounting && ['accrual', '租赁与待摊'], hasAccounting && ['insights', '经营分析与AI'], allowed('finance.configure') && ['migration', '财务迁移'], allowed('finance.read') && ['ledger', '费用台账'], allowed('finance.configure') && ['config', '财务配置']].filter(Boolean) as string[][]
  if (hasArchive) tabs.push(['archive', '电子档案'])
  if (allowed('finance.tax')) tabs.push(['tax-control', '进项税与税费实缴'])
  if (managedFinance) tabs.push(['cash-errors', '资金差错更正'])
  if (baseFinance) tabs.push(['authorizations', '代理与预算例外'])
  if (managedFinance || allowed('finance.tax')) tabs.push(['business-exceptions', '应收差异与税历'])
  if (baseFinance || hasAccounting) tabs.push(['insight-actions', '分析建议跟进'])
  const tab = tabs.some(t => t[0] === params.tab) ? params.tab : tabs[0]?.[0]
  const [context, setContext] = useState<FinanceContext>(); const [items, setItems] = useState<Claim[]>([]); const [claim, setClaim] = useState<Claim>(); const [error, setError] = useState(''); const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(0); const [more, setMore] = useState(false); const [revision, setRevision] = useState(0); const [assignment, setAssignment] = useState(identity.businessActorAssignmentId ?? '')
  const creating = useRef(false); const creation = useRef({ assignment: '', id: '' }); const [createBusy, setCreateBusy] = useState(false)
  const changed = useCallback(() => setRevision(x => x + 1), [])
  const open = (id: string) => go('finance', { tab, id })
  useEffect(() => { if (!baseFinance) return; let alive = true; void financeRequest<FinanceContext>(identity, '/context').then(c => { if (alive) { setContext(c); setAssignment(a => c.assignments.some(x => x.assignment_id === a) ? a : c.assignments[0]?.assignment_id ?? '') } }).catch(e => { if (alive) setError(errorMessage(e)) }); return () => { alive = false } }, [identity, revision, baseFinance])
  useEffect(() => { if (tab === 'mine') return; const refresh = () => { if (!document.hidden) changed() }; window.addEventListener('focus', refresh); window.addEventListener('finance:changed', refresh); document.addEventListener('visibilitychange', refresh); return () => { window.removeEventListener('focus', refresh); window.removeEventListener('finance:changed', refresh); document.removeEventListener('visibilitychange', refresh) } }, [tab, changed])
  useEffect(() => { setPage(0) }, [tab])
  useEffect(() => {
    let alive = true; setError(''); setClaim(undefined)
    if (!tab || !queueTabs.includes(tab)) { setLoading(false); return }
    setLoading(true)
    const list = financeRequest<{ items: Claim[]; hasMore: boolean }>(identity, `/claims?scope=${tab}&page=${page}`)
    const detail = params.id ? financeRequest<Claim>(identity, `/claims/${params.id}`) : Promise.resolve(undefined)
    void Promise.all([list, detail]).then(([rows, selected]) => { if (alive) { setItems(rows.items); setMore(rows.hasMore); setClaim(selected) } }).catch(e => { if (alive) setError(errorMessage(e)) }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [identity, tab, page, params.id, revision])
  const create = async () => {
    if (creating.current || !assignment) return
    if (!navigator.onLine) { setError('当前离线，无法新建报销。请联网后重试；已打开的草稿可继续填写。'); return }
    creating.current = true; setCreateBusy(true); setError('')
    if (creation.current.assignment !== assignment) creation.current = { assignment, id: crypto.randomUUID() }
    try { const c = await financeRequest<Claim>(identity, '/claims', 'POST', { requestId: creation.current.id, assignmentId: assignment }); creation.current = { assignment: '', id: '' }; open(c.id) }
    catch (e) { setError(errorMessage(e)) } finally { creating.current = false; setCreateBusy(false) }
  }
  const reload = async () => {
    if (!tab || !queueTabs.includes(tab)) { setError(''); changed(); return }
    setError(''); setLoading(true)
    try { const rows = await financeRequest<{ items: Claim[]; hasMore: boolean }>(identity, `/claims?scope=${tab}&page=${page}`); setItems(rows.items); setMore(rows.hasMore); if (params.id) setClaim(await financeRequest<Claim>(identity, `/claims/${params.id}`)) }
    catch (e) { setError(errorMessage(e)) } finally { setLoading(false) }
  }
  if (!tabs.length) return <p className={s.error}>当前岗位未开放财务操作权限，请联系管理员。</p>
  return <div className={s.finance}>
    <nav className={s.tabs} aria-label="财务中心">{tabs.map(([key, label]) => <button key={key} className={tab === key ? s.selected : ''} aria-current={tab === key ? 'page' : undefined} onClick={() => go('finance', { tab: key })}>{label}</button>)}</nav>
    {tab === 'overview' && <FinanceOverview identity={identity} permissions={permissions} go={go} />}
    {tab === 'invoices' && <FinanceInvoicePool identity={identity} permissions={permissions} go={go} context={context} />}
    <Suspense fallback={<p role="status" className={s.info}>正在打开财务模块…</p>}>
      {tab === 'accounting' && <FinanceAccounting key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'operations' && <FinanceOperations key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'supply' && <FinanceSupply key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={new Set(permissions)} params={params} go={go} />}
      {tab === 'business' && <FinanceBusiness key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'insights' && <FinanceInsights key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'migration' && <FinanceMigration key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'control' && <FinanceControl key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'accrual' && <FinanceAccrual key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={new Set(permissions)} params={params} go={go} />}
      {tab === 'archive' && <FinanceArchive key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'cash-errors' && <FinanceCashCorrections key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'authorizations' && <FinanceAuthorizations key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'business-exceptions' && <FinanceBusinessExceptions key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'insight-actions' && <FinanceInsightActions key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
      {tab === 'tax-control' && <FinanceTaxControl key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}`} identity={identity} permissions={permissions} params={params} go={go} />}
    </Suspense>
    {error && <p role="alert" className={s.error}>{error}<button type="button" onClick={() => { changed(); void reload() }}>重试读取</button></p>}
    {baseFinance && !context && !error && <p className={s.empty}>正在读取财务权限与门店信息…</p>}
    {context && tab === 'config' && <FinanceConfiguration identity={identity} context={context} onChange={changed} />}
    {context && tab === 'ledger' && <FinanceLedger identity={identity} context={context} openClaim={id => go('finance', { tab: 'all', id })} />}
    {context && tab === 'mine' && params.id && claim && <ClaimEditor key={`${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId}:${claim.id}`} initial={claim} context={context} identity={identity} onChange={changed} onBack={() => go('finance', { tab: 'mine' })} />}
    {context && (tab === 'mine' && !params.id || tab === 'review' || tab === 'all') && <>
      {tab === 'mine' && <div className={s.heading}><div><h2>我的报销</h2><p className={s.muted}>配置未齐也能先建草稿；正式提交时校验。断网时可继续填写已经打开的草稿。</p></div><div className={s.filters}><label>报销任职<select value={assignment} onChange={e => setAssignment(e.target.value)}>{context.assignments.map(a => <option key={a.assignment_id} value={a.assignment_id}>{a.hotel_name} · {a.employee_name}{a.enabled ? '' : '（可保存待配置草稿）'}</option>)}</select></label><button className={s.primary} disabled={createBusy || !assignment} onClick={() => void create()}>{createBusy ? '创建中…' : '＋ 新建报销'}</button></div></div>}
      <div className={tab !== 'mine' && params.id ? s.reviewLayout : s.listLayout}><aside className={s.queue}><div className={s.heading}><h3>{tab === 'review' ? '报销审核' : tab === 'all' ? '单据与付款' : '申请记录'}</h3><button onClick={() => void reload()} disabled={loading}>刷新</button></div><p className={s.muted}>{tab === 'review' ? '待处理 · 仅配置到本人的审核链' : '仅显示当前账号允许查看的记录'}</p>{items.map(c => <button className={`${s.claimRow} ${params.id === c.id ? s.activeRow : ''}`} key={c.id} onClick={() => open(c.id)}><span><strong>{c.title || '未填写事由'}</strong><b>{money(c.requested_amount)}</b></span><small>{c.hotel_name} · {c.applicant_name}</small><span><em>{claimStatusLabel(c)}</em>{c.mismatch && <i>差异待核实</i>}</span></button>)}{!loading && !items.length && <p className={s.empty}>暂无符合条件的报销记录</p>}<div className={s.actions}><button disabled={page === 0 || loading} onClick={() => setPage(p => p - 1)}>上一页</button><small>第 {page + 1} 页</small><button disabled={!more || loading} onClick={() => setPage(p => p + 1)}>下一页</button></div></aside>
        {tab !== 'mine' && claim && <ReviewDetail key={`${claim.id}:${claim.version}`} claim={claim} context={context} identity={identity} onUpdated={c => { setClaim(c); setItems(rows => tab === 'review' && !c.canReview ? rows.filter(r => r.id !== c.id) : rows.map(r => r.id === c.id ? { ...r, ...c } : r)); changed() }} />}
      </div>
    </>}
    {loading && <p className={s.info} role="status">正在读取报销记录…</p>}
  </div>
}
