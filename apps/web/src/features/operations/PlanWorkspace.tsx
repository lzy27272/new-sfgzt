import { useEffect, useRef, useState } from 'react'
import { apiRequest, asList } from '../../api/client'
import { buildResumableDraftKey, readResumableDraft, removeResumableDraft, writeResumableDraft } from '../../app/resumableDrafts'
import type { RoleContext } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { useResource } from '../../useResource'
import { addDays, chinaToday, emptyPlanDraft, formatTime, localDateTime, planDeadline, planDueAt, planPeriodStart, serializePlanDraft, validatePlanDraft, type OperationsContext, type PlanAction, type PlanDraft, type PlanType, type WorkPlan } from './model'

type Target = { assignmentId: string; employeeName: string; positionName: string; hotelId: string }
type Preview = { planId: string; revisionId: string; approverAssignmentId?: string; canApprove: boolean; newTaskCount: number; linkedCount: number; items: (PlanAction & { mode: string })[] }
type Prefill = { summary: string; suggestions: string[]; mode: string; missingData: string[]; facts: Record<string, unknown> }
type Reference = { id: string; title: string; hotelId: string; assignee: string; dueAt: string; acceptance: string; kind: 'task' | 'work' }
const err = (value: unknown) => value instanceof Error ? value.message : '操作失败，请重试。'
const planNames: Record<string, string> = { DRAFT: '草稿', PENDING_APPROVAL: '待主管审批', APPROVED: '已审批', REJECTED: '已退回' }
const actionStatus: Record<string, string> = { PROPOSED: '待派发', PENDING_ACK: '待执行人确认', IN_PROGRESS: '执行中', RESULT_SUBMITTED: '待人工验收', AWAITING_REVIEW: '待人工验收', REWORK: '退回补证', COMPLETED: '已验收完成', CANCELLED: '已取消' }
function planDraft(plan: WorkPlan): PlanDraft {
  return { summary: plan.summary || '', objective: plan.objective || '', risk: plan.risk || '', hotelSummaries: plan.hotelSummaries || {}, items: plan.items.map(item => ({ ...item, dueAt: localDateTime(item.dueAt) })) }
}

export function PlanWorkspace({ identity, context, planId, go }: { identity: RoleContext; context: OperationsContext; planId?: string; go: AppNavigate }) {
  if (!context.canReadPlan && !context.canSubmitPlan && !context.canReviewPlan) return <section className="panel ops-empty"><h1>当前岗位工作计划尚未配置</h1><p>运营助理清单待补，本轮不套用经理的周/月计划。</p></section>
  return <LinkedPlanWorkspace identity={identity} context={context} planId={planId} go={go} />
}
function LinkedPlanWorkspace({ identity, context, planId, go }: { identity: RoleContext; context: OperationsContext; planId?: string; go: AppNavigate }) {
  const linked = useResource(`${identity.key}:linked-plan:${planId || ''}`, async signal => ({ data: planId ? await apiRequest<WorkPlan>(`/operations/plans/${encodeURIComponent(planId)}`, identity, { signal }) : undefined, source: 'api' as const }), undefined as WorkPlan | undefined, 0, false, { cancellable: true })
  if (planId && linked.loading) return <p className="ops-loading" role="status">正在读取通知对应的工作计划…</p>
  if (planId && linked.error) return <p className="inline-error" role="alert">{linked.error}<button onClick={() => void linked.reload()}>重试</button></p>
  return <PeriodWorkspace key={identity.key + (planId || '')} identity={identity} context={context} initialPlan={linked.data} go={go} />
}
function PeriodWorkspace({ identity, context, initialPlan, go }: { identity: RoleContext; context: OperationsContext; initialPlan?: WorkPlan; go: AppNavigate }) {
  const [type, setType] = useState<PlanType>(initialPlan?.type || 'WEEKLY')
  const [date, setDate] = useState(initialPlan?.periodStart || chinaToday())
  const [scope, setScope] = useState(initialPlan && initialPlan.ownerAssignmentId !== context.assignmentId ? 'REVIEW' : context.canSubmitPlan ? 'OWN' : 'REVIEW')
  const [selectedId, setSelectedId] = useState(initialPlan?.id || '')
  const [updatedPlan, setUpdatedPlan] = useState<WorkPlan>()
  const start = planPeriodStart(type, date)
  const query = new URLSearchParams({ type, periodStart: start, scope }).toString()
  const list = useResource(`${identity.key}:plans:${query}`, async signal => ({ data: await apiRequest<{ items: WorkPlan[] }>(`/operations/plans?${query}`, identity, { signal }), source: 'api' as const }), { items: [] }, 0, false, { cancellable: true })
  const plans = list.data.items.map(plan => updatedPlan?.id === plan.id && updatedPlan.type === type && updatedPlan.periodStart === start ? updatedPlan : plan)
  const selected = plans.find(plan => plan.id === selectedId) || (updatedPlan?.id === selectedId && updatedPlan.type === type && updatedPlan.periodStart === start ? updatedPlan : undefined) || (initialPlan?.id === selectedId ? initialPlan : undefined) || plans[0]
  return <>
    <header className="ops-title"><div><h1>工作计划</h1><p>周总结与下周计划同页提交，审批后自动生成执行任务。计划审批不代表工作完成。</p></div><button className="secondary" onClick={() => go('workbench')}>返回工作台</button></header>
    <div className="ops-toolbar"><div className="ops-tabs compact">{(['WEEKLY', 'MONTHLY'] as const).map(value => <button key={value} className={type === value ? 'active' : ''} onClick={() => { setType(value); setSelectedId('') }}>{value === 'WEEKLY' ? '周总结与下周计划' : '月运营计划'}</button>)}</div><label>所属周期<input type={type === 'WEEKLY' ? 'date' : 'month'} value={type === 'WEEKLY' ? start : start.slice(0, 7)} onChange={e => { if (e.target.value) { setDate(type === 'MONTHLY' ? `${e.target.value}-01` : e.target.value); setSelectedId('') } }} /></label>{context.canReviewPlan && <label>查看范围<select aria-label="查看范围" value={scope} onChange={e => { setScope(e.target.value); setSelectedId('') }}>{context.canSubmitPlan && <option value="OWN">我的计划</option>}<option value="REVIEW">直属下级待审批计划</option></select></label>}<button className="secondary" onClick={() => void list.reload()}>刷新</button></div>
    <p className="ops-note">{type === 'WEEKLY' ? `总结 ${start} 至 ${addDays(start, 6)}，计划 ${addDays(start, 7)} 至 ${addDays(start, 13)}；每周日24:00前提交。` : `${start.slice(0, 7)}月运营计划，每月5日24:00前提交；可以提前准备下月草稿。`} 每份计划覆盖该任职负责门店，两岗分别执行。</p>
    {list.error && <p className="inline-error" role="alert">{list.error}<button onClick={() => void list.reload()}>重试</button></p>}
    {list.loading ? <p className="ops-loading" role="status">正在读取工作计划…</p> : !list.error && (scope === 'REVIEW' ? <>
      {plans.length ? <div className="ops-plan-selector">{plans.map(plan => <button className={selected?.id === plan.id ? 'active' : ''} key={plan.id} onClick={() => setSelectedId(plan.id)}><strong>{plan.ownerName}</strong><span>{planNames[plan.status] || plan.status}{plan.late ? ' · 已补交' : ''}</span></button>)}</div> : <section className="panel ops-empty"><h2>当前周期没有待审批计划</h2><p>这里只显示当前任职有权审批的直属下级记录。</p></section>}
      {selected && <SelectedPlanEditor key={identity.key + selected.id + selected.revisionId} identity={identity} context={context} type={type} start={start} selected={selected} reviewing go={go} onChanged={setUpdatedPlan} />}
    </> : context.canSubmitPlan && (selected ? <SelectedPlanEditor key={identity.key + type + start + selected.id} identity={identity} context={context} type={type} start={start} selected={selected} reviewing={false} go={go} onChanged={setUpdatedPlan} /> : <PlanEditor key={identity.key + type + start} identity={identity} context={context} type={type} start={start} reviewing={false} go={go} onChanged={setUpdatedPlan} />))}
  </>
}

function SelectedPlanEditor({ selected, ...props }: { selected: WorkPlan; identity: RoleContext; context: OperationsContext; type: PlanType; start: string; reviewing: boolean; go: AppNavigate; onChanged: (plan: WorkPlan) => void }) {
  const detail = useResource(`${props.identity.key}:plan-detail:${selected.id}:${selected.revisionId}:${selected.draftVersion ?? 0}:${selected.status}`, async signal => ({ data: await apiRequest<WorkPlan>(`/operations/plans/${encodeURIComponent(selected.id)}`, props.identity, { signal }), source: 'api' as const }), undefined as WorkPlan | undefined, 0, false, { cancellable: true })
  if (detail.loading) return <p className="ops-loading" role="status">正在读取计划行动项与审批详情…</p>
  if (detail.error) return <p className="inline-error" role="alert">{detail.error}<button onClick={() => void detail.reload()}>重试</button></p>
  return detail.data ? <PlanEditor {...props} original={detail.data} /> : null
}

function PlanEditor({ identity, context, type, start, original, reviewing, go, onChanged }: { identity: RoleContext; context: OperationsContext; type: PlanType; start: string; original?: WorkPlan; reviewing: boolean; go: AppNavigate; onChanged: (plan: WorkPlan) => void }) {
  const [plan, setPlan] = useState(original)
  const [draft, setDraft] = useState<PlanDraft>(() => original ? planDraft(original) : emptyPlanDraft(context.hotels.map(hotel => hotel.id)))
  const [conflictingDraft, setConflictingDraft] = useState<PlanDraft>()
  const [hydrated, setHydrated] = useState(reviewing)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [preview, setPreview] = useState<Preview>()
  const [comment, setComment] = useState('')
  const [prefill, setPrefill] = useState<Prefill>()
  const [references, setReferences] = useState<Reference[]>([])
  const [referenceError, setReferenceError] = useState('')
  const [referencesLoading, setReferencesLoading] = useState(false)
  const latestDraft = useRef(draft)
  const localKey = buildResumableDraftKey('work-plan-form', [identity.tenantId, identity.actorId, context.assignmentId, type, start])
  const locked = reviewing || plan?.status === 'APPROVED' || plan?.status === 'PENDING_APPROVAL'
  const displayHotels = locked && plan?.hotels?.length ? plan.hotels : context.hotels
  const materialDeadline = plan?.dueAt || planDueAt(type, start)
  const overdueDraft = !locked && Date.now() >= Date.parse(materialDeadline)
  const targets = useResource(`${identity.key}:plan-targets:${context.hotels.map(hotel => hotel.id).join(',')}:${reviewing}`, async signal => ({ data: reviewing ? [] : (await Promise.all(context.hotels.map(hotel => apiRequest<Target[]>(`/operations/plans/targets?hotelId=${hotel.id}`, identity, { signal })))).flat(), source: 'api' as const }), [] as Target[], 0, false, { cancellable: true })
  useEffect(() => {
    if (reviewing) return
    let active = true
    void readResumableDraft<{ baseRevisionId?: string; baseDraftVersion?: number; draft: PlanDraft }>(localKey).then(cached => {
      if (!active) return
      if (cached && !locked) {
        if (cached.baseRevisionId === original?.revisionId && (cached.baseDraftVersion ?? 0) === (original?.draftVersion ?? 0)) { setDraft(cached.draft); latestDraft.current = cached.draft; setNotice('已恢复本机未完成草稿。') }
        else { setConflictingDraft(cached.draft); setNotice('服务端计划已更新。本机旧草稿保留，当前显示服务端版本；请先对比后再决定是否恢复。') }
      }
      setHydrated(true)
    }).catch(reason => { if (active) { setError(err(reason)); setHydrated(true) } })
    return () => { active = false }
  }, [localKey])
  function change(next: PlanDraft) {
    latestDraft.current = next; setDraft(next); setError('')
    void writeResumableDraft(localKey, { baseRevisionId: plan?.revisionId, baseDraftVersion: plan?.draftVersion, draft: next }).then(() => setNotice('已自动保存到本机，切后台或刷新后可继续。')).catch(reason => setError(err(reason)))
  }
  function editAction(id: string, patch: Partial<PlanAction>) { change({ ...latestDraft.current, items: latestDraft.current.items.map(item => item.actionId === id ? { ...item, ...patch } : item) }) }
  async function save(): Promise<WorkPlan> {
    const saved = await apiRequest<WorkPlan>(`/operations/plans/${type}/${start}/draft`, identity, { method: 'PUT', body: JSON.stringify({ ...serializePlanDraft(latestDraft.current), expectedRevisionId: plan?.revisionId, expectedDraftVersion: plan?.draftVersion }) })
    setPlan(saved); const nextDraft = planDraft(saved); latestDraft.current = nextDraft; setDraft(nextDraft); await removeResumableDraft(localKey); setNotice('草稿已保存到服务端。'); return saved
  }
  async function run(action: () => Promise<void>) {
    if (busy) return
    setBusy(true); setError('')
    try { await action() } catch (reason) { setError(err(reason)) } finally { setBusy(false) }
  }
  function submit() {
    const validation = validatePlanDraft(latestDraft.current, context.hotels.map(hotel => hotel.id))
    if (validation) { setError(validation); return }
    void run(async () => {
      const saved = await save()
      const submitted = await apiRequest<WorkPlan>(`/operations/plans/${saved.id}/submit`, identity, { method: 'POST' })
      setPlan(submitted); const nextDraft = planDraft(submitted); latestDraft.current = nextDraft; setDraft(nextDraft); setPreview(await apiRequest<Preview>(`/operations/plans/${saved.id}/approval-preview`, identity)); setNotice('计划已提交，等待有效上级人工审批。')
    })
  }
  async function showPreview() { if (plan) await run(async () => setPreview(await apiRequest<Preview>(`/operations/plans/${plan.id}/approval-preview`, identity))) }
  async function decide(decision: 'APPROVE' | 'RETURN') {
    if (!plan || !preview?.canApprove) return
    if (decision === 'RETURN' && !comment.trim()) { setError('退回时请填写原因，便于员工修改。'); return }
    await run(async () => {
      const result = await apiRequest<WorkPlan>(`/operations/plans/${plan.id}/review`, identity, { method: 'POST', body: JSON.stringify({ decision, comment, expectedRevisionId: preview.revisionId }) })
      setPlan(result); const nextDraft = planDraft(result); latestDraft.current = nextDraft; setDraft(nextDraft); setPreview(undefined); setNotice(decision === 'APPROVE' ? '审批通过。执行任务已生成或关联，仍需完成后人工验收。' : '已退回修改，审批记录已保留。'); onChanged(result)
    })
  }
  async function loadReferences() {
    if (referencesLoading) return
    setReferencesLoading(true); setReferenceError('')
    const rowText = (row: Record<string, unknown>, ...keys: string[]) => String(keys.map(key => row[key]).find(value => value != null) || '')
    try {
      const [taskData, workData] = await Promise.all([apiRequest<unknown>('/tasks?view=mine&page=0&size=100', identity), apiRequest<unknown>(`/my/work-expectations?businessDate=${chinaToday()}&page=0&size=200`, identity)])
      const map = (value: unknown, kind: 'task' | 'work') => asList<Record<string, unknown>>(value).map(row => ({ id: rowText(row, 'id'), title: rowText(row, 'title', 'name', 'item_name', 'itemName'), hotelId: rowText(row, 'hotelId', 'hotel_org_unit_id', 'targetOrgUnitId', 'target_org_unit_id', 'org_unit_id', 'orgUnitId'), assignee: rowText(row, 'assigneeAssignmentId', 'assignee_assignment_id', 'positionAssignmentId', 'position_assignment_id', 'assignmentId'), dueAt: rowText(row, 'dueAt', 'due_at'), acceptance: rowText(row, 'acceptanceCriteria', 'acceptance_criteria', 'description') || '沿用原任务或工作的验收标准与凭证。', kind })).filter(row => row.id && context.hotels.some(hotel => hotel.id === row.hotelId))
      setReferences([...map(taskData, 'task'), ...map(workData, 'work')])
    } catch (reason) { setReferenceError(err(reason)) } finally { setReferencesLoading(false) }
  }
  function linkReference(actionId: string, value: string) {
    const ref = references.find(item => `${item.kind}:${item.id}` === value)
    if (!ref) { editAction(actionId, { linkedTaskId: undefined, linkedExpectationId: undefined }); return }
    editAction(actionId, { hotelId: ref.hotelId, assigneeAssignmentId: ref.assignee || context.assignmentId || '', title: ref.title, dueAt: localDateTime(ref.dueAt), acceptanceCriteria: ref.acceptance, linkedTaskId: ref.kind === 'task' ? ref.id : undefined, linkedExpectationId: ref.kind === 'work' ? ref.id : undefined })
  }
  if (!hydrated) return <p className="ops-loading" role="status">正在恢复工作计划草稿…</p>
  return <div className="ops-plan-editor">{overdueDraft && <p className="ops-note">已超过本周期提交截止时间，可以补交；系统保留真实提交时间，后续审批不会消除逾期记录。</p>}
    {conflictingDraft && !locked && <section className="panel ops-panel"><header><h2>保留的本机草稿</h2><p>下方填报区显示服务器最新内容，展开对比后可自主恢复本机版本。</p></header><div className="ops-body"><details><summary>查看本机未提交内容</summary><label>本机总结<textarea readOnly rows={4} aria-label="本机总结" value={conflictingDraft.summary} /></label><label>本机目标<textarea readOnly rows={4} aria-label="本机目标" value={conflictingDraft.objective} /></label><label>本机风险与待确认事项<textarea readOnly rows={3} aria-label="本机风险与待确认事项" value={conflictingDraft.risk} /></label>{Object.entries(conflictingDraft.hotelSummaries).map(([id, value]) => <p key={id}>{context.hotels.find(hotel => hotel.id === id)?.name || '原负责门店'}：{value || '未填写'}</p>)}{conflictingDraft.items.map(item => <div className="ops-preview-item" key={item.actionId}><strong>{item.title || '未填写工作内容'}</strong><p>{item.description}</p><p>截止 {formatTime(item.dueAt)} · 验收：{item.acceptanceCriteria || '未填写'}</p></div>)}</details><button className="secondary" disabled={busy} onClick={() => { change(conflictingDraft); setConflictingDraft(undefined) }}>恢复本机内容到编辑草稿</button><p className="ops-muted">恢复后仍需重新核对门店、负责人和版本，再保存或提交。</p></div></section>}
    <section className="panel ops-panel"><header><div><h2>{reviewing ? `${plan?.ownerName || ''} · ` : ''}{type === 'WEEKLY' ? '本周总结与下周工作计划' : '上月简评与当月运营计划'}</h2><p>版本 {plan ? `V${plan.revisionNo}` : '待创建'} · {planNames[plan?.status || 'DRAFT'] || plan?.status}{plan?.late ? ' · 逾期补交' : ''}{` · 截止 ${planDeadline(materialDeadline)}`}</p></div><button className="secondary" disabled={busy || locked} onClick={() => void run(async () => setPrefill(await apiRequest<Prefill>(`/operations/plans/prefill?type=${type}&periodStart=${start}`, identity)))}>读取工作汇总与建议</button></header>
      <fieldset className="ops-plan-fields" disabled={busy || locked}><div className="ops-two-columns"><div><label>{type === 'WEEKLY' ? '本周总结' : '上月简评'}<textarea aria-label={type === 'WEEKLY' ? '本周总结' : '上月简评'} value={draft.summary} onChange={e => change({ ...draft, summary: e.target.value })} rows={4} placeholder="依据已核实的工作记录填写，未结算部分说明数据截至时间" /></label>{displayHotels.map(hotel => <label key={hotel.id}>{hotel.name} · 门店分区总结<textarea aria-label={`${hotel.name} · 门店分区总结`} rows={3} value={draft.hotelSummaries[hotel.id] || ''} onChange={e => change({ ...draft, hotelSummaries: { ...draft.hotelSummaries, [hotel.id]: e.target.value } })} /></label>)}</div><div><label>工作目标与重点<textarea aria-label="工作目标与重点" value={draft.objective} onChange={e => change({ ...draft, objective: e.target.value })} rows={5} /></label><label>风险、缺失数据与待确认事项<textarea aria-label="风险、缺失数据与待确认事项" value={draft.risk} onChange={e => change({ ...draft, risk: e.target.value })} rows={4} /></label>{prefill && <div className="ops-note"><strong>{prefill.mode === 'RULE_BASED' ? '规则分析建议 · 未调用大模型' : '工作汇总建议'}</strong>{prefill.summary && <p>{prefill.summary}</p>}{prefill.suggestions.map((suggestion, index) => <p key={index}>{suggestion}</p>)}{prefill.missingData.map((missing, index) => <p key={index}>待补充：{missing}</p>)}<button type="button" className="secondary" onClick={() => change({ ...draft, summary: draft.summary || prefill.summary, objective: [draft.objective, ...prefill.suggestions].filter(Boolean).join('\n') })}>采用到可编辑草稿</button></div>}</div></div></fieldset>
    </section>
    <section className="panel ops-panel"><header><div><h2>执行行动项</h2><p>审批后生成任务，关联已有任务或日清时只计一次；经营目标本身不直接转为任务。</p></div>{!locked && <button className="secondary" disabled={busy || !context.hotels.length} onClick={() => change({ ...draft, items: [...draft.items, { actionId: crypto.randomUUID(), hotelId: context.hotels[0]?.id || '', assigneeAssignmentId: context.assignmentId || '', title: '', description: '', dueAt: '', acceptanceCriteria: '' }] })}>添加行动项</button>}</header>
      {targets.error && <p className="inline-error" role="alert">执行人列表读取失败：{targets.error}<button onClick={() => void targets.reload()}>重试</button></p>}
      {!draft.items.length && <p className="ops-empty">尚未添加行动项。可以先保存总结草稿，再补充具体执行安排。</p>}
      {draft.items.map((item, index) => <article className="ops-action" key={item.actionId}><div className="ops-action-heading"><h3>行动 {index + 1}{item.linkedTaskId || item.linkedExpectationId ? ' · 关联已有工作' : ''}</h3>{!locked && <button className="text-action" disabled={busy} onClick={() => change({ ...draft, items: draft.items.filter(row => row.actionId !== item.actionId) })}>删除此行动</button>}</div><fieldset disabled={busy || locked}><div className="ops-two-columns"><div><label>门店{locked ? <input readOnly value={item.hotelName || displayHotels.find(hotel => hotel.id === item.hotelId)?.name || "原工作门店"} /> : <select aria-label="门店" value={item.hotelId} onChange={e => editAction(item.actionId, { hotelId: e.target.value, assigneeAssignmentId: '', linkedTaskId: undefined, linkedExpectationId: undefined })}><option value="">请选择门店</option>{context.hotels.map(hotel => <option value={hotel.id} key={hotel.id}>{hotel.name}</option>)}</select>}</label><label>工作内容<textarea aria-label="工作内容" rows={3} value={item.title} onChange={e => editAction(item.actionId, { title: e.target.value })} /></label><label>补充说明<textarea aria-label="补充说明" rows={2} value={item.description || ''} onChange={e => editAction(item.actionId, { description: e.target.value })} /></label></div><div><label>负责人{locked ? <input readOnly value={item.assigneeName || (item.assigneeAssignmentId === plan?.ownerAssignmentId ? plan?.ownerName : "负责人名称待核对")} /> : <select aria-label="负责人" value={item.assigneeAssignmentId} onChange={e => editAction(item.actionId, { assigneeAssignmentId: e.target.value })}><option value="">请选择有效任职</option>{targets.data.filter(target => target.hotelId === item.hotelId).map(target => <option key={target.assignmentId} value={target.assignmentId}>{target.employeeName} · {target.positionName}</option>)}</select>}</label><label>截止时间（北京时间）<input type="datetime-local" value={localDateTime(item.dueAt) || item.dueAt} onChange={e => editAction(item.actionId, { dueAt: e.target.value })} /></label><label>验收标准 / 完成凭证<textarea aria-label="验收标准 / 完成凭证" rows={3} value={item.acceptanceCriteria} onChange={e => editAction(item.actionId, { acceptanceCriteria: e.target.value })} /></label></div></div>
        {!locked && <div className="ops-link-work"><button type="button" className="secondary" disabled={referencesLoading} onClick={() => void loadReferences()}>{referencesLoading ? '读取中…' : '读取可关联的本人任务 / 今日日清'}</button>{references.length > 0 && <label>关联原工作<select aria-label="关联原工作" value={item.linkedTaskId ? `task:${item.linkedTaskId}` : item.linkedExpectationId ? `work:${item.linkedExpectationId}` : ''} onChange={e => linkReference(item.actionId, e.target.value)}><option value="">创建新任务</option>{references.filter(ref => ref.hotelId === item.hotelId).map(ref => <option key={`${ref.kind}:${ref.id}`} value={`${ref.kind}:${ref.id}`}>{ref.kind === 'task' ? '任务' : '日清'} · {ref.title}</option>)}</select></label>}<p className="ops-muted">显示最多100项本人任务和200项今日日清；关联后沿用原工作，审批不会重复下达。</p>{referenceError && <p className="inline-error">{referenceError}</p>}</div>}
      </fieldset>{item.taskId && <div className="ops-compliance"><span className="ops-badge">{actionStatus[item.taskStatus || ''] || item.taskStatus || '已生成 · 待执行'}</span><button className="text-action" onClick={() => go('tasks', { taskId: item.taskId })}>查看执行任务</button></div>}{item.linkedExpectationId && <button className="text-action" onClick={() => go('my-work', { expectationId: item.linkedExpectationId })}>查看关联日清</button>}</article>)}
    </section>
    {preview && <section className="panel ops-panel ops-preview"><header><h2>审批预览</h2><span>{preview.newTaskCount}项新任务 · {preview.linkedCount}项关联</span></header><div className="ops-body"><p className="ops-note">审批人必须具备下达权限与有效上下级关系。审批通过只建立执行任务，完成后仍由下达人工作台人工验收。</p>{preview.items.map(item => <article className="ops-preview-item" key={item.actionId}><strong>{item.hotelName || displayHotels.find(hotel => hotel.id === item.hotelId)?.name || '负责门店'} · {item.title}</strong><p>{item.mode === 'CREATE' ? '生成新任务' : '关联已有工作，不重复生成'} · 截止 {formatTime(item.dueAt)}</p><p>负责人：{item.assigneeName || "有效执行任职"}</p><p>验收：{item.acceptanceCriteria}</p></article>)}{preview.canApprove ? <><label>审批意见 / 退回原因<textarea aria-label="审批意见 / 退回原因" value={comment} disabled={busy} onChange={e => setComment(e.target.value)} rows={3} /></label><div className="ops-footer"><button className="secondary" disabled={busy} onClick={() => void decide('RETURN')}>退回修改</button><button className="primary" disabled={busy} onClick={() => void decide('APPROVE')}>{busy ? '处理中…' : '审批并生成 / 关联任务'}</button></div></> : <p className="ops-muted">当前账号仅查看审批预览，等待有权限的直属上级审批。</p>}</div></section>}
    {error && <p className="inline-error" role="alert">{error}</p>}{notice && <p className="ops-note" role="status">{notice}</p>}
    <div className="ops-footer"><span className="ops-muted">材料提交及时性与执行任务完成率分别记录。</span>{!locked && <><button className="secondary" disabled={busy || !context.hotels.length} onClick={() => void run(async () => { await save() })}>保存草稿到服务端</button><button className="primary" disabled={busy || !context.hotels.length} onClick={submit}>{busy ? '处理中…' : '提交主管审批'}</button></>}{plan && (reviewing || locked) && plan.status !== 'APPROVED' && <button className="primary" disabled={busy} onClick={() => void showPreview()}>查看审批预览</button>}{plan?.status === 'APPROVED' && <button className="secondary" onClick={() => go('workbench')}>前往工作台查看执行与待审核</button>}</div>
  </div>
}
