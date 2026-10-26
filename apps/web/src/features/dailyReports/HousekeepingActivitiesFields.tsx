import { useEffect, useRef, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { featureApiRequest } from '../shared/featureApi'
import { ActivityPhotos } from './ActivityPhotos'
import type { HousekeepingActivities, ServiceCase, LostPropertyEntry } from './housekeepingActivities'
import { loadReportLostProperties, submitLostPropertyEntry, type RoutedLostProperty } from './dailyActivityApi'
import type { DailyReportDetail } from './types'
import styles from './activities.module.css'
type Recipient = { assignmentId: string; employeeName: string; positionName: string }
export function HousekeepingActivitiesFields({ identity, reportId, reportVersion, value, editable, disabled, onChange, onPendingChange, onImmediateSubmitted }: {
  identity: ApiIdentity; reportId: string; value: HousekeepingActivities; editable: boolean; disabled?: boolean;
  reportVersion: number; onImmediateSubmitted: (detail: DailyReportDetail) => void;
  onChange: (update: (current: HousekeepingActivities) => HousekeepingActivities) => void;
  onPendingChange: (id: string, pending: boolean) => void
}) {
  const [recipients, setRecipients] = useState<Recipient[]>([])
  const [error, setError] = useState<string>()
  const [attempt, setAttempt] = useState(0)
  const [routed, setRouted] = useState<RoutedLostProperty[]>([])
  const [statesReady, setStatesReady] = useState(false)
  const [sending, setSending] = useState<string>()
  const [entryError, setEntryError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const requests = useRef(new Map<string, { signature: string; key: string }>())
  useEffect(() => {
    if (!editable) return
    const controller = new AbortController()
    setError(undefined)
    setStatesReady(false)
    void Promise.all([
      featureApiRequest<Recipient[]>('/daily-reports/' + reportId + '/activity-recipients', identity, { signal: controller.signal }),
      loadReportLostProperties(identity, reportId, controller.signal),
    ]).then(([people, entries]) => {
      if (controller.signal.aborted) return
      setRecipients(people); setRouted(entries); setStatesReady(true)
      if (entries.length) onChange(current => ({ ...current, lostPropertyOccurred: true, lostProperties: [
        ...current.lostProperties.map(item => {
          const saved = entries.find(entry => entry.entryId === item.id)
          return saved && saved.state !== 'RETURNED' ? saved.payload : item
        }),
        ...entries.filter(entry => !current.lostProperties.some(item => item.id === entry.entryId)).map(entry => entry.payload),
      ] }))
    }).catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '接收人与送审状态读取失败') })
    return () => controller.abort()
  }, [identity.tenantId, identity.actorId, identity.businessActorAssignmentId, reportId, editable, attempt])
  const updateService = (id: string, patch: Partial<ServiceCase>) => onChange(current => ({ ...current, serviceCases: current.serviceCases.map(item => item.id === id ? { ...item, ...patch } : item) }))
  const updateLost = (id: string, patch: Partial<LostPropertyEntry>) => onChange(current => ({ ...current, lostProperties: current.lostProperties.map(item => item.id === id ? { ...item, ...patch } : item) }))
  const photos = (kind: 'serviceCases' | 'lostProperties', id: string, photoId: string, remove = false) => onChange(current => ({
    ...current, [kind]: current[kind].map(item => item.id === id ? { ...item, photoIds: remove ? item.photoIds.filter(photo => photo !== photoId) : [...new Set([...item.photoIds, photoId])] } : item),
  }))
  const addLost = () => onChange(current => ({ ...current, lostPropertyOccurred: true, lostProperties: [...current.lostProperties, {
    id: crypto.randomUUID(), itemName: '', quantity: 1, roomNumber: '', foundLocation: '', foundAt: new Date().toISOString(), recipientAssignmentId: '', notes: '', photoIds: [],
  }] }))
  const send = async (item: LostPropertyEntry) => {
    if (sending || disabled || !statesReady) return
    if (!item.itemName.trim() || !item.roomNumber.trim() || !item.foundLocation.trim() || !item.foundAt || !item.recipientAssignmentId
      || !Number.isSafeInteger(item.quantity) || item.quantity! < 1 || item.quantity! > 10000 || !item.photoIds.length) {
      setEntryError('请先补全本条物品、数量、房号/区域、发现位置与时间、照片及接收人。其他未填完的日报内容不影响此条送审。'); return
    }
    const signature = JSON.stringify({ expectedVersion: reportVersion, entry: item })
    let request = requests.current.get(item.id)
    if (!request || request.signature !== signature) {
      request = { signature, key: crypto.randomUUID() }; requests.current.set(item.id, request)
    }
    setSending(item.id); setEntryError(undefined); setNotice(undefined); onPendingChange('lost-submit:' + item.id, true)
    try {
      const detail = await submitLostPropertyEntry(identity, reportId, item, reportVersion, request.key)
      const saved = detail.housekeepingActivities?.lostProperties.find(entry => entry.id === item.id) ?? item
      onChange(current => ({ ...current, lostPropertyOccurred: true, lostProperties: current.lostProperties.map(entry => entry.id === item.id ? saved : entry) }))
      setRouted(current => [...current.filter(entry => entry.entryId !== item.id), { id: '', entryId: item.id, state: 'PENDING', rowVersion: 0, payload: saved }])
      onImmediateSubmitted(detail)
      setNotice('本条已保存并通知接收人，等待接收确认；尚未入库，日报其他内容可继续填写。')
    } catch (cause) { setEntryError(cause instanceof Error ? cause.message : '送审未完成，请重试；已填内容保留。') }
    finally { setSending(undefined); onPendingChange('lost-submit:' + item.id, false) }
  }
  return <div className={styles.sections}>
    <section className={styles.card}><header><h2>服务案例</h2><span>随日报提交 · 无案例可不填</span></header>
      {value.serviceCases.map((item, index) => <article className={styles.entry} key={item.id}>
        <h3>服务案例 {index + 1}</h3><fieldset disabled={!editable || disabled} className={styles.fields}>
          <label>房号 / 区域<input value={item.roomNumber} maxLength={80} onChange={event => updateService(item.id, { roomNumber: event.target.value })} /></label>
          <label className={styles.wide}>服务内容<textarea value={item.serviceContent} maxLength={2000} rows={3} onChange={event => updateService(item.id, { serviceContent: event.target.value })} /></label>
        </fieldset>
        <ActivityPhotos identity={identity} base={'/daily-reports/' + reportId + '/activity-photos'} entryId={item.id} ids={item.photoIds} editable={editable} disabled={disabled} onAdd={id => photos('serviceCases', item.id, id)} onRemove={id => photos('serviceCases', item.id, id, true)} onPendingChange={onPendingChange} />
        {editable && <button className="link-button" type="button" disabled={disabled} onClick={() => onChange(current => ({ ...current, serviceCases: current.serviceCases.filter(row => row.id !== item.id) }))}>移除此案例</button>}
      </article>)}
      {editable && <button className="secondary" type="button" disabled={disabled || value.serviceCases.length >= 30} onClick={() => onChange(current => ({ ...current, serviceCases: [...current.serviceCases, { id: crypto.randomUUID(), roomNumber: '', serviceContent: '', photoIds: [] }] }))}>＋ 添加服务案例</button>}
      {!editable && !value.serviceCases.length && <p>本次日报未填写服务案例</p>}
    </section>
    <section className={styles.card}><header><h2>遗留物品</h2><span>与日报同时提醒、同页填报 · 发现即上报</span>
      {editable && <button type="button" className="link-button" disabled={disabled || Boolean(sending)} onClick={() => setAttempt(current => current + 1)}>刷新接收状态</button>}</header>
      <p className={styles.hint}>发现后可逐条立即通知接收人，不必等15:30或整份日报完成。接收人核对实物、照片和数量后才入库；日报引用同一记录，前台办理客人领取。</p>
      <label className={styles.question}>今日是否发现遗留物品<select disabled={!editable || disabled} value={value.lostPropertyOccurred == null ? '' : String(value.lostPropertyOccurred)} onChange={event => onChange(current => ({ ...current, lostPropertyOccurred: event.target.value === '' ? null : event.target.value === 'true' }))}>
        <option value="">请选择</option><option value="false" disabled={value.lostProperties.length > 0}>无</option><option value="true">有</option>
      </select></label>
      {error && <div role="alert" className="inline-error">{error}<button type="button" onClick={() => setAttempt(current => current + 1)}>重新加载接收人</button></div>}
      {entryError && <p role="alert" className="inline-error">{entryError}</p>}
      {notice && <p role="status" className={styles.sentNotice}>{notice}</p>}
      {value.lostProperties.map((item, index) => {
        const state = routed.find(entry => entry.entryId === item.id)?.state
        const locked = Boolean(state && state !== 'RETURNED')
        return <article className={styles.entry} key={item.id}><h3>遗留物品 {index + 1}</h3>
        {state && <p className={styles.routedState}>{state === 'PENDING' ? '待接收确认 · 尚未入库' : state === 'IN_CUSTODY' ? '已接收保管 · 日报仅引用' : state === 'COLLECTED' ? '已领取出库 · 保留历史记录' : '已退回 · 补充本条后重新通知接收人'}</p>}
        <fieldset disabled={!editable || disabled || locked || !statesReady || Boolean(sending)} className={styles.fields}>
          <label>物品名称<input value={item.itemName} maxLength={160} onChange={event => updateLost(item.id, { itemName: event.target.value })} /></label>
          <label>数量<input type="number" min="1" max="10000" step="1" inputMode="numeric" value={item.quantity ?? ''} onChange={event => updateLost(item.id, { quantity: event.target.value === '' ? null : Number(event.target.value) })} /></label>
          <label>房号 / 区域<input value={item.roomNumber} maxLength={80} onChange={event => updateLost(item.id, { roomNumber: event.target.value })} /></label>
          <label>发现位置<input value={item.foundLocation} maxLength={240} onChange={event => updateLost(item.id, { foundLocation: event.target.value })} placeholder="例如：床头柜抽屉" /></label>
          <label>发现时间<input type="datetime-local" value={localTime(item.foundAt)} onChange={event => updateLost(item.id, { foundAt: event.target.value ? new Date(event.target.value).toISOString() : '' })} /></label>
          <label>接收人<select value={item.recipientAssignmentId} onChange={event => updateLost(item.id, { recipientAssignmentId: event.target.value })}><option value="">请选择本店接收人</option>
            {item.recipientAssignmentId && !recipients.some(person => person.assignmentId === item.recipientAssignmentId) && <option value={item.recipientAssignmentId}>{item.recipientLabel || '提交时指定的接收人'}{editable ? '（已流转记录保留原接收人）' : ''}</option>}
            {recipients.map(person => <option key={person.assignmentId} value={person.assignmentId}>{person.employeeName} · {person.positionName}</option>)}</select></label>
          <label className={styles.wide}>物品特征 / 补充说明<textarea rows={2} maxLength={1000} value={item.notes} onChange={event => updateLost(item.id, { notes: event.target.value })} placeholder="请勿填写完整证件号等敏感信息" /></label>
        </fieldset>
        {editable && !error && !recipients.length && <p className="inline-warning">本店暂无可用接收人，请管理员在岗位功能配置中启用“遗留物品保管”入口和“遗留物接收审核”权限。可先保存日报草稿。</p>}
        <ActivityPhotos identity={identity} base={'/daily-reports/' + reportId + '/activity-photos'} entryId={item.id} ids={item.photoIds} editable={editable && !locked && statesReady} disabled={disabled || Boolean(sending)} onAdd={id => photos('lostProperties', item.id, id)} onRemove={id => photos('lostProperties', item.id, id, true)} onPendingChange={onPendingChange} />
        {editable && <div className={styles.actions}>{!locked && <button type="button" className="primary" disabled={disabled || Boolean(sending) || !statesReady} onClick={() => void send(item)}>{sending === item.id ? '正在保存并通知…' : state === 'RETURNED' ? '重新通知接收人' : '立即通知接收人'}</button>}
          {!state && <button type="button" className="link-button" disabled={disabled || Boolean(sending) || !statesReady} onClick={() => onChange(current => ({ ...current, lostProperties: current.lostProperties.filter(row => row.id !== item.id) }))}>移除此条</button>}
          {locked && <p className={styles.hint}>已流转记录不能删除或改写；如需更正，请接收人退回。</p>}
        </div>}
      </article>})}
      {editable && value.lostPropertyOccurred && <button type="button" className="secondary" disabled={disabled || value.lostProperties.length >= 30} onClick={addLost}>＋ 添加遗留物品</button>}
    </section>
  </div>
}
function localTime(value: string) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0,16)
}
