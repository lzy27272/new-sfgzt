import { lazy, Suspense, useRef, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import type { AppNavigate } from '../../app/routeConfig'
import { errorMessage, financeDownload } from './api'
import { FinanceMutation, FinanceTable } from './FinanceData'
import { money, type Claim, type FinanceContext } from './model'
import { emptyTodos, financeTodoState, financeTodoType, rowText, todoTarget, type FinanceList, type FinanceRow, type FinanceTodos } from './unifiedModel'
import { useFinanceResource } from './useFinanceResource'
import { InvoiceIntakeEditor } from './InvoiceIntakeEditor'
import { RecognitionReview } from './RecognitionReview'
import type { EvidenceExtraction } from './model'
import s from './finance.module.css'

const todoKindLabels: Record<string, string> = { review: '待我审核', payment: '待我付款', returned: '退回待补', receipt: '待补回单', work: '待本人跟进' }
const FinanceInvoiceAutomation = lazy(() => import('./FinanceInvoiceAutomation'))

export function FinanceOverview({ identity, go, permissions }: { identity: ApiIdentity; go: AppNavigate; permissions: string[] }) {
  const [page, setPage] = useState(0)
  const [kind, setKind] = useState('')
  const resource = useFinanceResource<FinanceTodos>(identity, `/todos?page=${page}${kind ? `&kind=${kind}` : ''}`)
  const data = resource.data ?? emptyTodos
  const can = (code: string) => permissions.includes('*') || permissions.includes(code)
  return <section className={s.unifiedPage}><header className={s.hero}><div><small>FINANCE WORKBENCH</small><h2>让每一笔收支都有来源</h2><p>财务待办与岗位工作台同步，按当前账号、任职和组织范围办理。</p></div>{can('finance.submit') && <button onClick={() => go('finance', { tab: 'mine' })}>进入我的报销 ›</button>}</header>
    <div className={s.metricGrid}>{[['review', '待我审核', '当前节点与审核人均已匹配'], ['payment', '待我付款', '审批通过，不等于已经支付'], ['returned', '退回待补', '本人仍需补充的申请'], ['receipt', '待补回单', '已登记付款但原件尚未归档'], ['work', '待本人跟进', '本人建议跟进与待作废重建的过期资金指令']].map(([key, label, note]) => <button className={s.metricCard} aria-pressed={kind === key} key={key} onClick={() => { setPage(0); setKind(key) }}><small>{label}</small><strong>{resource.data ? data.counts[key as keyof typeof data.counts] : '—'}</strong><small>{note}</small></button>)}</div>
    <section className={s.panel}><div className={s.heading}><div><h3>当前财务待办{kind ? ` · ${todoKindLabels[kind]}` : ''}</h3><p className={s.muted}>有效跨日事项持续保留；报销与业务单据按同一类别分页，不跳转到仅报销的旧列表。</p></div><div className={s.actions}>{kind && <button onClick={() => { setKind(''); setPage(0) }}>显示全部类别</button>}<button onClick={resource.reload}>刷新服务端状态</button></div></div>
      {resource.error ? <p role="alert" className={s.error}>{errorMessage(resource.error)}</p> : resource.loading ? <p role="status" className={s.empty}>正在核验本人当前节点…</p> : data.items.length ? data.items.map(item => <button className={s.claimRow} key={`${item.domain}:${item.kind}:${item.id}`} onClick={() => go('finance', todoTarget(item))}><span><strong>{item.title || '未填写事由'}</strong><b>{item.amount == null ? '—' : money(item.amount)}</b></span><small>{financeTodoType(item)} · {item.hotelName} · {item.applicantName}</small><span>{financeTodoState(item)} ›</span></button>) : <p className={s.empty}>当前没有本人可办理事项。未流转至本人的单据不会提前计入。</p>}
      <div className={s.actions}><button disabled={page <= 0 || resource.loading} onClick={() => setPage(value => value - 1)}>上一页</button><span>共 {resource.data ? data.total : '—'} 项</span><button disabled={!resource.data || (page + 1) * 50 >= data.total || resource.loading} onClick={() => setPage(value => value + 1)}>下一页</button></div>
    </section><div className={s.ledgerColumns}><section className={s.panel}><h3>业务与核算分层核对</h3><p>报销和采购等业务先审核；出纳登记已发生的收付款；财务按真实经济事件复核过账。重复票据被拦截，补贴等金额差异进入人工核实。</p>{can('finance.read') && <button onClick={() => go('finance', { tab: 'operations', screen: 'calendar' })}>查看合同、付款与税务日历 ›</button>}</section><section className={s.panel}><h3>配置状态与边界</h3><p>未配置主体或审核人时，员工仍可保存待配置草稿，正式提交会明确指出缺项。外部OCR、发票、银行及财务政策未确认时，不宣称已自动验真、转账或申报。</p>{can('finance.configure') && <button onClick={() => go('finance', { tab: 'config' })}>配置法人、审批人与权限 ›</button>}</section></div>
  </section>
}

export function FinanceInvoicePool({ identity, permissions, go, context }: { identity: ApiIdentity; permissions: string[]; go: AppNavigate; context?: FinanceContext }) {
  const [page, setPage] = useState(0); const [selectedId, setSelectedId] = useState(''); const [error, setError] = useState(''); const [creating, setCreating] = useState(false)
  const resource = useFinanceResource<FinanceList & { notice: string }>(identity, `/invoice-pool?page=${page}`)
  const detail = useFinanceResource<FinanceRow>(identity, selectedId ? `/invoice-pool/${selectedId}` : undefined)
  const canReview = permissions.includes('*') || permissions.includes('finance.review'); const canSubmit = permissions.includes('*') || permissions.includes('finance.submit')
  const claims = useFinanceResource<{ items: Claim[] }>(identity, canSubmit && selectedId ? '/claims?scope=mine&page=0' : undefined)
  const cacheKey = `${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId ?? ''}:${identity.orgScopes.join(',')}:${permissions.join(',')}:${selectedId}`
  const selectedCache = useRef<{ key: string; data: FinanceRow } | undefined>(undefined)
  const denied = !!detail.error && typeof detail.error === 'object' && 'status' in detail.error && [401, 403, 404].includes(Number(detail.error.status))
  if (denied) selectedCache.current = undefined
  if (detail.data) selectedCache.current = { key: cacheKey, data: detail.data }
  // Resource refreshes must not unmount a partially typed editor. Never reuse another identity's cached document.
  const selected = denied ? undefined : detail.data ?? (selectedCache.current?.key === cacheKey ? selectedCache.current.data : undefined)
  const available = (claims.data?.items ?? []).filter(claim => claim.canEdit && claim.hotel_id === selected?.hotelId)
  return <section className={s.panel}><div className={s.heading}><div><small>ORIGINAL DOCUMENTS</small><h2>独立收票与原件资料库</h2></div><div className={s.actions}><button onClick={() => { resource.reload(); detail.reload() }}>刷新原件状态</button>{canSubmit && <button className={s.primary} onClick={() => setCreating(!creating)}>＋ 先收票，后报销</button>}</div></div><p className={s.warning}>{resource.data?.notice || '先归集真实原件，再关联本人同组织报销。人工核对与外部查验分别留状态，人工确认不代表税务验真。'}</p>
    {creating && <FinanceMutation identity={identity} path="/invoice-pool" label="建立独立收票草稿" initial={{ assignmentId: identity.businessActorAssignmentId ?? '' }} fields={[{ key: 'assignmentId', label: '资料归属任职', required: true, type: 'select', options: (context?.assignments ?? []).map(assignment => ({ value: assignment.assignment_id, label: `${assignment.hotel_name} · ${assignment.employee_name}` })) }]} build={(value, requestId) => ({ requestId, assignmentId: value.assignmentId })} onSuccess={value => { setSelectedId(rowText(value, 'id')); setCreating(false) }} />}
    {(resource.error || detail.error || error) && <p role="alert" className={s.error}>{error || errorMessage(resource.error || detail.error)}</p>}
    {resource.loading ? <p role="status" className={s.empty}>读取授权范围内票据…</p> : <FinanceTable rows={resource.data?.items ?? []} columns={[{ key: 'invoiceNumber', label: '完整发票号码' }, { key: 'invoiceCode', label: '传统发票代码' }, { key: 'hotelName', label: '归属组织' }, { key: 'amount', label: '金额' }, { key: 'originalName', label: '原件文件' }, { key: 'status', label: '归集状态' }, { key: 'verificationStatus', label: '核对 / 查验状态' }]} label="独立票据" onOpen={row => setSelectedId(rowText(row, 'id'))} />}
    {selected && <div className={s.detailPanel} key={cacheKey}><h3>{rowText(selected, 'invoiceNumber', '尚未填写票号')}</h3><p>{rowText(selected, 'originalName', '原件待上传')} · {rowText(selected, 'hotelName')}</p><p className={s.muted}>原件 SHA256：{rowText(selected, 'sha256')}</p><div className={s.actions}>{!!selected.claimId && <button onClick={() => go('finance', { tab: canSubmit ? 'mine' : 'all', id: rowText(selected, 'claimId') })}>查看关联报销</button>}{!!selected.originalName && <button onClick={() => void financeDownload(identity, `/invoice-pool/${selected.id}/original`, rowText(selected, 'originalName')).catch(error => setError(errorMessage(error)))}>下载授权原件</button>}</div>
      {selected.canEdit === true && <><InvoiceIntakeEditor identity={identity} intake={selected} onUpdated={() => { resource.reload(); detail.reload() }} />
        <details><summary>关联本人同组织报销</summary><p className={s.muted}>原件仍引用同一份受保护字节；正式提交报销时才占用票号。已关联资料不会再作为新票重复报销。</p><FinanceMutation identity={identity} path={`/invoice-pool/${selected.id}/associate`} label="关联到所选报销草稿" fields={[{ key: 'claimId', label: '选择本人的可编辑报销', required: true, type: 'select', options: available.map(claim => ({ value: claim.id, label: `${claim.title || '未填写事由'} · ${claim.hotel_name}` })) }]} build={value => { const target = available.find(claim => claim.id === value.claimId); if (!target) throw new Error('所选草稿已不可用，请刷新本人报销列表。'); return { claimId: target.id, expectedVersion: target.version } }} onSuccess={claim => go('finance', { tab: 'mine', id: rowText(claim, 'id') })} /></details></>}
      {selected.canEdit !== true && <RecognitionReview extraction={selected.extraction as EvidenceExtraction | undefined} />}
      {canReview && <details><summary>记录人工核对依据（非税务验真）</summary><FinanceMutation identity={identity} path={`/invoice-pool/${selected.id}/verification`} label="保存人工核对记录" fields={[{ key: 'note', label: '核对原件的具体依据', type: 'textarea', required: true }]} build={value => value} /></details>}
      <Suspense fallback={<p role="status">读取OCR与验真服务状态…</p>}><FinanceInvoiceAutomation identity={identity} intakeId={rowText(selected, 'id')} permissions={permissions} /></Suspense>
    </div>}
    <div className={s.actions}><button disabled={page <= 0 || resource.loading} onClick={() => { setSelectedId(''); setPage(value => value - 1) }}>上一页</button><span>共 {resource.data?.total ?? '—'} 张</span><button disabled={!resource.data || (page + 1) * 50 >= resource.data.total || resource.loading} onClick={() => { setSelectedId(''); setPage(value => value + 1) }}>下一页</button></div>
  </section>
}
