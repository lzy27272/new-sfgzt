import { useCallback, useEffect, useState } from 'react'
import type { RoleContext, RouteParams } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { useScopedResource } from '../../shared/useScopedResource'
import { useStableCommand } from '../../shared/useStableCommand'
import { featureApiRequest, featureApiMutation } from '../shared/featureApi'
import { formatLocalDateTime } from '../shared/FeatureUI'
import { buildResumableDraftKey, readResumableDraft, removeResumableDraft, writeResumableDraft } from '../../app/resumableDrafts'
import { ActivityPhotos } from '../dailyReports/ActivityPhotos'
import type { LostPropertyEntry } from '../dailyReports/housekeepingActivities'
import s from '../dailyReports/activities.module.css'
type CaseRow = { id: string; itemName: string; quantity: number; remainingQuantity: number; state: string; rowVersion: number;
  hotelName: string; reporterName: string; recipientName: string; storageLocation?: string; createdAt: string }
type CaseDetail = CaseRow & { reportId: string; reviewNote?: string; payload: LostPropertyEntry; canReceive: boolean; canRelease: boolean; isReporter: boolean;
  history: Array<{ id: string; kind: string; actorName: string; createdAt: string; payload: Record<string, unknown> }> }
const labels: Record<string, string> = { PENDING: '待接收审核', RETURNED: '已退回补充', IN_CUSTODY: '保管中', COLLECTED: '已领取' }
const eventLabels: Record<string, string> = { SUBMITTED: '随日报送审', RESUBMITTED: '修订后重新送审', RECEIVED: '核对入库', RETURNED: '退回补充', RELEASED: '领取出库' }
export default function LostPropertyRoutes({ identity, params, go }: { identity: RoleContext; params: RouteParams; go: AppNavigate }) {
  const [query, setQuery] = useState(params.search ?? '')
  const state = Object.hasOwn(labels, params.state ?? '') ? params.state! : ''
  const page = Math.max(0, Number.parseInt(params.page || '0', 10) || 0)
  const list = useScopedResource<CaseRow[]>(identity.key + ':lost-list:' + state + ':' + (params.search ?? '') + ':' + page,
    signal => featureApiRequest('/lost-properties?' + new URLSearchParams({ state, search: params.search ?? '', page: String(page) }), identity, { signal }), [])
  const detail = useScopedResource<CaseDetail | undefined>(identity.key + ':lost-detail:' + (params.id ?? ''),
    signal => params.id ? featureApiRequest('/lost-properties/' + params.id, identity, { signal }) : Promise.resolve(undefined), undefined)
  const error = list.error || detail.error
  const updated = (value: CaseDetail) => { detail.setData(value); void list.reload() }
  return <section className={s.ledger}>
    <header><span className="eyebrow">GUEST PROPERTY CUSTODY</span><h1>遗留物品保管</h1><p className={s.hint}>审核接收、实物保管、客人领取全程留痕。与酒店耗品库存、成本账分开记录。</p></header>
    <form className={s.toolbar} onSubmit={event => { event.preventDefault(); go('lost-properties', { state, search: query }) }}>
      <label>状态 <select value={state} onChange={event => go('lost-properties', { state: event.target.value, search: params.search })}><option value="">全部</option>{Object.entries(labels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      <input aria-label="搜索遗留物品" placeholder="搜索物品名称或房号" value={query} onChange={event => setQuery(event.target.value)} maxLength={80} />
      <button type="submit" className="secondary">搜索</button><button type="button" className="secondary" onClick={() => void Promise.all([list.reload(), detail.reload()])}>刷新</button>
    </form>
    {Boolean(error) && <div className="inline-error" role="alert">{error instanceof Error ? error.message : '读取失败，请刷新重试'}</div>}
    <div className={s.split}><section className={s.card} aria-label="遗留物品清单">
      <h2>保管清单</h2>{list.loading ? <p role="status">正在读取…</p> : !list.data.length && !list.error ? <p className={s.hint}>当前权限及筛选范围内暂无记录。</p> : null}
      <div className={s.list}>{list.data.slice(0,50).map(row => <button key={row.id} aria-current={params.id === row.id} onClick={() => go('lost-properties', { ...params, id: row.id })}>
        <strong>{row.itemName} <span className={s.badge}>{labels[row.state]}</span></strong>
        <div className={s.meta}><span>{row.hotelName}</span><span>上报：{row.reporterName}</span><span>接收：{row.recipientName}</span></div>
        <p>上报 {row.quantity} · 在库 {row.remainingQuantity}{row.storageLocation ? ' · ' + row.storageLocation : ''}</p>
        <small>{formatLocalDateTime(row.createdAt)}</small>
      </button>)}</div>
      <div className={s.actions}><button className="secondary" disabled={page === 0 || list.loading} onClick={() => go('lost-properties', { state, search: params.search, page: String(page - 1) })}>上一页</button><span>第 {page + 1} 页</span><button className="secondary" disabled={list.data.length <= 50 || list.loading} onClick={() => go('lost-properties', { state, search: params.search, page: String(page + 1) })}>下一页</button></div>
    </section>
      {params.id ? detail.loading ? <section className={s.card + ' ' + s.caseDetail} role="status">正在读取物品详情…</section> : detail.data && !detail.error ? <CaseEditor key={identity.key + ':' + detail.data.id + ':' + detail.data.rowVersion} identity={identity} record={detail.data} onUpdated={updated} go={go} /> : null
        : <section className={s.card}><h2>接收与领取办理</h2><p className={s.hint}>从保管清单选择物品，查看照片与流转记录。</p></section>}
    </div>
  </section>
}
type EditorDraft = { schemaVersion: 1; storageLocation: string; note: string; quantity: string; recipientName: string; verification: string; photoIds: string[] }
function CaseEditor({ identity, record, onUpdated, go }: { identity: RoleContext; record: CaseDetail; onUpdated: (value: CaseDetail) => void; go: AppNavigate }) {
  const [draft, setDraft] = useState<EditorDraft>({ schemaVersion: 1, storageLocation: '', note: '', quantity: String(record.remainingQuantity), recipientName: '', verification: '', photoIds: [] })
  const [ready, setReady] = useState(false)
  const [pending, setPending] = useState(false)
  const onPending = useCallback((_id: string, value: boolean) => setPending(value), [])
  const [error, setError] = useState<string>()
  const command = useStableCommand('lost-' + record.id)
  const key = buildResumableDraftKey('lost-property-action', [identity.tenantId, identity.actorId, identity.businessActorAssignmentId, record.id, String(record.rowVersion)])
  useEffect(() => {
    let active = true
    void readResumableDraft<EditorDraft>(key).then(value => { if (active) { if (value?.schemaVersion === 1) setDraft(value); setReady(true) } })
      .catch(() => { if (active) setError('本机办理草稿无法恢复，请稍后重试。') })
    return () => { active = false }
  }, [key])
  useEffect(() => {
    if (!ready) return
    void writeResumableDraft(key, draft).catch(() => setError('本机暂存失败，请保留填写内容；可在网络正常时完成办理。'))
  }, [key, ready, draft])
  const set = (field: keyof EditorDraft, value: string) => setDraft(current => ({ ...current, [field]: value }))
  const act = async (action: 'RECEIVED' | 'RETURNED' | 'RELEASED') => {
    if (pending) { setError('请先完成领取凭证照片上传。'); return }
    if (action === 'RECEIVED' && !draft.storageLocation.trim()) { setError('请填写实际保管位置。'); return }
    if (action === 'RETURNED' && !draft.note.trim()) { setError('请填写退回原因。'); return }
    if (action === 'RELEASED' && (!draft.recipientName.trim() || !draft.verification.trim() || !draft.photoIds.length || !Number.isSafeInteger(Number(draft.quantity)) || Number(draft.quantity) < 1 || Number(draft.quantity) > record.remainingQuantity)) { setError('请核对领取数量，填写领取人、核验说明，并上传交接凭证照片。'); return }
    setError(undefined)
    const body = action === 'RELEASED' ? { quantity: Number(draft.quantity), recipientName: draft.recipientName, verification: draft.verification, photoIds: draft.photoIds }
      : { outcome: action, storageLocation: draft.storageLocation, note: draft.note }
    try {
      const result = await command.run(key => featureApiMutation<CaseDetail>('/lost-properties/' + record.id + (action === 'RELEASED' ? '/release' : '/review'), identity, { body, expectedVersion: record.rowVersion, idempotencyKey: key }))
      if (result) { onUpdated(result); void removeResumableDraft(key).catch(() => {}) }
    } catch (cause) { setError(cause instanceof Error ? cause.message : '办理失败，草稿仍保留。') }
  }
  return <section className={s.card + ' ' + s.caseDetail}><header><h2>{record.itemName}</h2><span className={s.badge}>{labels[record.state]}</span></header>
    <p>上报 {record.quantity} · 在库 {record.remainingQuantity}</p>
    <div className={s.meta}><span>房号 / 区域：{record.payload.roomNumber}</span><span>位置：{record.payload.foundLocation}</span><span>发现：{formatLocalDateTime(record.payload.foundAt)}</span></div>
    {record.payload.notes && <p>{record.payload.notes}</p>}
    <ActivityPhotos identity={identity} base={'/lost-properties/' + record.id + '/photos'} entryId={'original-' + record.id} ids={record.payload.photoIds} editable={false} />
    {record.storageLocation && <p className={s.badge}>保管位置：{record.storageLocation}</p>}
    {record.reviewNote && <p className="inline-warning">审核说明：{record.reviewNote}</p>}
    {record.isReporter && record.state === 'RETURNED' && <button className="secondary" onClick={() => go('daily-report-detail', { reportId: record.reportId })}>打开原日报，发起修订补充</button>}
    {record.canReceive && <fieldset className={s.fields} disabled={!ready || command.busy}>
      <label className={s.wide}>核实后的保管位置<input value={draft.storageLocation} maxLength={240} placeholder="例如：前台遗留物柜 A-03" onChange={event => set('storageLocation', event.target.value)} /></label>
      <label className={s.wide}>审核说明 / 退回原因<textarea value={draft.note} maxLength={1000} onChange={event => set('note', event.target.value)} /></label>
    </fieldset>}
    {record.canRelease && <><h3>客人领取出库</h3><p className={s.hint}>核验物品特征与领取人后办理。支持分次领取，不填写完整身份证号码。</p><fieldset className={s.fields} disabled={!ready || command.busy}>
      <label>领取数量<input type="number" min="1" max={record.remainingQuantity} step="1" value={draft.quantity} onChange={event => set('quantity', event.target.value)} /></label>
      <label>领取人称呼<input maxLength={80} value={draft.recipientName} onChange={event => set('recipientName', event.target.value)} /></label>
      <label className={s.wide}>核验与交接说明<textarea rows={3} maxLength={1000} value={draft.verification} onChange={event => set('verification', event.target.value)} placeholder="已核对房号、物品特征，并由客人确认领取" /></label>
    </fieldset>
      <ActivityPhotos identity={identity} base={'/lost-properties/' + record.id + '/photos'} entryId={'release-' + record.rowVersion} ids={draft.photoIds} editable disabled={!ready || command.busy} onPendingChange={onPending} onAdd={id => setDraft(current => ({ ...current, photoIds: [...new Set([...current.photoIds, id])] }))} onRemove={id => setDraft(current => ({ ...current, photoIds: current.photoIds.filter(photo => photo !== id) }))} />
    </>}
    {error && <p className="inline-error" role="alert">{error}</p>}
    <div className={s.actions}>{record.canReceive && <><button className="secondary" disabled={!ready || command.busy} onClick={() => void act('RETURNED')}>退回补充</button><button className="primary" disabled={!ready || command.busy} onClick={() => void act('RECEIVED')}>{command.busy ? '处理中…' : '核对无误，确认入库'}</button></>}
      {record.canRelease && <button className="primary" disabled={!ready || command.busy || pending} onClick={() => void act('RELEASED')}>{command.busy ? '处理中…' : '确认客人领取并出库'}</button>}
    </div>
    <h3>流转记录</h3>{record.history.map(event => <article className={s.history} key={event.id}><strong>{eventLabels[event.kind] || event.kind}</strong><p>{event.actorName} · {formatLocalDateTime(event.createdAt)}</p>
      {event.kind === 'RELEASED' && <p>领取 {String(event.payload.quantity)} · {String(event.payload.recipientName)} · {String(event.payload.verification)}</p>}
      {event.payload.note ? <p>{String(event.payload.note)}</p> : null}
      {event.kind === 'RELEASED' && <ActivityPhotos identity={identity} base={'/lost-properties/' + record.id + '/photos'} entryId={event.id} ids={Array.isArray(event.payload.photoIds) ? event.payload.photoIds.map(String) : []} editable={false} />}
    </article>)}
  </section>
}
