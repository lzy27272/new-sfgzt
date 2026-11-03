import { useState } from 'react'
import type { RoleContext } from '../../domain'
import { apiRequest } from '../../api/client'
import { useScopedResource } from '../../shared/useScopedResource'
import { EvidencePhoto } from './EvidencePhoto'
import { errorText, failureText, statusText } from './model'
import type { Check, Evidence, ReviewContext } from './model'
import './managerBackfill.css'

type Purpose = 'UNCONFIRMED' | 'SINGLE' | 'MEETING' | 'GROUP' | 'OTHER'
type PhotoMapping = { attachmentId: string; purpose: Purpose; subjectAssignmentId: string | null; confirmed: boolean }
type BackfillCheck = Check & { requester_name?: string; backfill_reason?: string }
type BackfillContext = {
  canBackfill: boolean; blockedReason?: string; providerReady: boolean; dailyRequestLimit: number; remainingCalls: number;
  original?: { ownerName: string; submittedAt: string; businessDate: string }; actor?: { name: string };
  attachments: (Evidence & { checkpoint_code?: string })[]; subjects: ReviewContext['subjects']; backfillChecks?: BackfillCheck[];
}
type Preview = {
  previewToken: string; personCount: number; imageCount: number; excludedCount: number; duplicateCount: number;
  remainingCalls: number; canEnqueue: boolean; blockedReason?: string;
  subjects: { subjectAssignmentId: string; name: string; attachmentIds: string[]; ruleId: string; ruleVersion: number; duplicate: boolean }[];
  excluded: { attachmentId: string; reason: string }[];
}
type Receipt = { checks: BackfillCheck[]; queuedCount: number; duplicateCount: number }
const empty: BackfillContext = { canBackfill: false, providerReady: false, dailyRequestLimit: 0, remainingCalls: 0, attachments: [], subjects: [] }
const purposes: Record<Purpose, string> = { UNCONFIRMED: '尚未确认用途', SINGLE: '单人仪容照', MEETING: '会议记录', GROUP: '多人合照', OTHER: '其他材料' }
const dateTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'

export function AiManagerBackfill({ identity, recordId, checks, onChanged }: {
  identity: RoleContext; recordId: string; checks: Check[]; onChanged: () => void;
}) {
  const resource = useScopedResource(`ai-manager-backfill:${identity.key}:${recordId}`, signal =>
    apiRequest<BackfillContext>(`/ai-evidence/records/${recordId}/backfill`, identity, { signal }), empty)
  const [step, setStep] = useState<'entry' | 'mapping' | 'preview' | 'queued'>('entry')
  const [photos, setPhotos] = useState<PhotoMapping[]>([])
  const [reason, setReason] = useState('')
  const [query, setQuery] = useState('')
  const [preview, setPreview] = useState<Preview>()
  const [receipt, setReceipt] = useState<Receipt>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const context = resource.data
  const savedChecks = context.backfillChecks ?? []
  const activeReceipt = receipt ?? (savedChecks.length ? { checks: savedChecks, queuedCount: savedChecks.length, duplicateCount: 0 } : undefined)
  const savedRequester = [...new Set(savedChecks.map(check => check.requester_name).filter(Boolean))].join('、')
  const selected = photos.filter(photo => photo.purpose === 'SINGLE')
  const people = new Set(selected.filter(photo => photo.subjectAssignmentId && photo.confirmed).map(photo => photo.subjectAssignmentId))
  const validation = !reason.trim() ? '请填写补查原因。' : !selected.length ? '请至少选择一张单人仪容照，并确认对应员工。'
    : selected.some(photo => !photo.subjectAssignmentId || !photo.confirmed) ? '请为每张单人仪容照选择员工，并确认照片对应关系。'
    : [...people].some(id => selected.filter(photo => photo.subjectAssignmentId === id).length > 4) ? '每位员工最多使用 4 张照片。请调整照片用途。' : ''
  const updatePhoto = (id: string, patch: Partial<PhotoMapping>) => {
    setPhotos(rows => rows.map(photo => photo.attachmentId === id ? { ...photo, ...patch } : photo))
    setPreview(undefined); setError('')
  }
  const begin = () => {
    setPhotos(context.attachments.map(photo => ({ attachmentId: photo.id, purpose: 'UNCONFIRMED', subjectAssignmentId: null, confirmed: false })))
    setReason(''); setPreview(undefined); setError(''); setStep('mapping')
  }
  const preflight = async () => {
    if (validation) { setError(validation); return }
    setBusy(true); setError('')
    try {
      const result = await apiRequest<Preview>(`/ai-evidence/records/${recordId}/backfill/preview`, identity,
        { method: 'POST', body: JSON.stringify({ reason: reason.trim(), photos }) })
      setPreview(result); setStep('preview')
    } catch (failure) { setError(errorText(failure)); setPreview(undefined) }
    finally { setBusy(false) }
  }
  const enqueue = async () => {
    if (!preview?.canEnqueue || busy) return
    setBusy(true); setError('')
    try {
      const result = await apiRequest<Receipt>(`/ai-evidence/records/${recordId}/backfill`, identity,
        { method: 'POST', body: JSON.stringify({ reason: reason.trim(), photos, previewToken: preview.previewToken }) })
      setReceipt(result); setStep('queued'); setPreview(undefined); onChanged()
    } catch (failure) {
      setError(errorText(failure)); setPreview(previous => previous ? { ...previous, canEnqueue: false } : previous)
    } finally { setBusy(false) }
  }
  if (resource.loading) return <p role="status" className="ai-muted">正在核对授权补查范围…</p>
  if (resource.error) return <div className="ai-error" role="alert">补查入口读取失败：{errorText(resource.error)}<button type="button" className="secondary" onClick={() => void resource.reload()}>重试补查入口</button></div>
  if (!context.canBackfill && !receipt) return context.blockedReason ? <p className="ai-muted">授权补查：{context.blockedReason}</p> : null

  const audit = <dl className="ai-backfill-audit">
    <div><dt>原提交人</dt><dd>{context.original?.ownerName ?? '—'}</dd></div>
    <div><dt>原提交时间</dt><dd>{dateTime(context.original?.submittedAt)}</dd></div>
    <div><dt>{!receipt && savedChecks.length ? '补查发起人' : '本次发起人'}</dt><dd>{!receipt && savedRequester ? savedRequester : context.actor?.name ?? identity.userName}</dd></div>
    <div><dt>工作日期</dt><dd>{context.original?.businessDate ?? '2026-10-27'}</dd></div>
  </dl>
  return <section className="ai-backfill" aria-label="授权管理人员补查">
    <header className="ai-heading"><h3>授权管理人员补查</h3><span className="ai-pill">仅生成 AI 建议</span></header>
    {error && <p className="ai-error" role="alert">{error}</p>}
    {step === 'entry' && <>
      <p>原提交人暂时无法操作时，授权管理人员可确认现有照片对应员工，填写原因后发起补查。当前仅开放两家试点店 2026 年 10 月 27 日这批记录。</p>
      {audit}
      <div className="ai-actions"><button type="button" className="primary" onClick={activeReceipt ? () => setStep('queued') : begin}>{activeReceipt ? '查看本次补查状态' : '发起 AI 补查'}</button></div>
    </>}
    {step === 'mapping' && <>
      <p className="ai-banner">先确认照片用途和对应人员。系统不会自动认人；会议记录、多人合照及未确认照片不用于个人仪容检查。</p>
      <div className="ai-backfill-layout"><div>
        <label>搜索可选员工<input aria-label="搜索补查员工" value={query} onChange={event => setQuery(event.target.value)} placeholder="输入姓名或岗位" disabled={busy} /></label>
        <div className="ai-backfill-photos">{photos.map((mapping, index) => {
          const attachment = context.attachments.find(photo => photo.id === mapping.attachmentId)!
          const meeting = attachment.checkpoint_code?.toLowerCase().includes('meeting')
          const persons = context.subjects.filter(person => person.configured && (person.id === mapping.subjectAssignmentId || `${person.name} ${person.position_name}`.includes(query.trim())))
          return <article className="ai-backfill-photo" key={mapping.attachmentId} aria-label={`补查照片 ${index + 1}`}>
            <EvidencePhoto identity={identity} item={attachment} />
            <div><h4>照片 {index + 1}</h4>
              <label>照片用途<select aria-label={`照片 ${index + 1} 用途`} disabled={busy} value={mapping.purpose} onChange={event => updatePhoto(mapping.attachmentId, { purpose: event.target.value as Purpose, subjectAssignmentId: null, confirmed: false })}>
                {(Object.entries(purposes) as [Purpose, string][]).map(([value, label]) => <option value={value} key={value} disabled={value === 'SINGLE' && meeting}>{label}</option>)}
              </select></label>
              {mapping.purpose === 'SINGLE' ? <>
                <label>对应员工<select aria-label={`照片 ${index + 1} 对应员工`} value={mapping.subjectAssignmentId ?? ''} disabled={busy} onChange={event => updatePhoto(mapping.attachmentId, { subjectAssignmentId: event.target.value || null, confirmed: false })}>
                  <option value="">请选择本店员工</option>{persons.map(person => <option key={person.id} value={person.id}>{person.name} · {person.position_name}</option>)}
                </select></label>
                {!persons.length && <p className="ai-muted">未找到匹配员工，请调整搜索词。</p>}
                <label className="ai-checkbox"><input type="checkbox" aria-label={`确认照片 ${index + 1} 对应员工`} checked={mapping.confirmed} disabled={busy || !mapping.subjectAssignmentId} onChange={event => updatePhoto(mapping.attachmentId, { confirmed: event.target.checked })} />我已核对，这是所选员工的单人照片</label>
              </> : <p className="ai-muted">{meeting ? '晨会记录不参与个人仪容检查。' : '本张暂不进入个人仪容检查。'}</p>}
            </div>
          </article>
        })}</div>
        {!photos.length && <p className="ai-warning">没有可用照片。已清理的图片无法用于本次历史补查，请转人工核对。</p>}
        <label>补查原因（必填）<textarea aria-label="补查原因" value={reason} maxLength={1000} disabled={busy} onChange={event => { setReason(event.target.value); setError(''); setPreview(undefined) }} placeholder="说明原提交人无法操作和本次补查的原因" /></label>
      </div><aside><h4>本次处理摘要</h4><p>已确认员工 <strong>{people.size} 人</strong></p><p>个人仪容照片 <strong>{selected.length} 张</strong></p><p>排除 / 未确认 <strong>{photos.length - selected.length} 张</strong></p><p className="ai-muted">同员工多张照片合并检查；预览时核对重复记录和剩余额度。</p>{audit}</aside></div>
      <div className="ai-backfill-footer"><p aria-live="polite">{validation || '可以预览；尚未发起 AI 检查。'}</p><div className="ai-actions"><button type="button" className="secondary" disabled={busy} onClick={() => { setStep('entry'); setError('') }}>暂不补查</button><button type="button" className="primary" disabled={busy || !!validation} onClick={() => void preflight()}>{busy ? '正在核对…' : '预览本次补查'}</button></div></div>
    </>}
    {step === 'preview' && preview && <>
      <h4>确认本次 AI 补查</h4><p className="ai-muted">确认后加入检查队列，尚未调用模型；排队和实际执行时都会重新核对权限、照片及额度。</p>
      <dl className="ai-backfill-counts"><div><dt>新增检查员工</dt><dd>{preview.personCount} 人</dd></div><div><dt>新增检查照片</dt><dd>{preview.imageCount} 张</dd></div><div><dt>排除</dt><dd>{preview.excludedCount} 张</dd></div><div><dt>跳过重复</dt><dd>{preview.duplicateCount} 人</dd></div></dl>
      <p>预计新增调用 <strong>{preview.subjects.filter(person => !person.duplicate).length} 次</strong> · 今日预估剩余 <strong>{preview.remainingCalls} 次</strong></p>
      <ul className="ai-backfill-persons">{preview.subjects.map(person => <li key={person.subjectAssignmentId}><span><strong>{person.name}</strong><small>{person.attachmentIds.length} 张照片 · 规则 V{person.ruleVersion}</small></span><span className="ai-pill">{person.duplicate ? '已有检查，跳过' : '本次新检查'}</span></li>)}</ul>
      {!!preview.excluded.length && <details><summary>查看排除的 {preview.excludedCount} 张照片</summary><ul>{preview.excluded.map(photo => <li key={photo.attachmentId}>{context.attachments.find(item => item.id === photo.attachmentId)?.original_name ?? '工作照片'}：{photo.reason}</li>)}</ul></details>}
      {audit}<p className="ai-backfill-reason"><strong>补查原因：</strong>{reason.trim()}</p>
      {preview.blockedReason && <p className="ai-warning" role="status">{preview.blockedReason}</p>}
      <div className="ai-actions"><button type="button" className="secondary" disabled={busy} onClick={() => { setStep('mapping'); setPreview(undefined); setError('') }}>返回修改</button><button type="button" className="secondary" disabled={busy} onClick={() => void preflight()}>重新核对预览</button><button type="button" className="primary" disabled={busy || !preview.canEnqueue} onClick={() => void enqueue()}>{busy ? '正在登记…' : '确认并加入 AI 检查队列'}</button></div>
    </>}
    {step === 'queued' && activeReceipt && <>
      <p className="ai-success" role="status">{receipt ? `已登记 ${receipt.queuedCount} 项补查，跳过 ${receipt.duplicateCount} 项已有检查。登记成功不代表检查已完成。` : `已有 ${savedChecks.length} 项补查记录，请查看逐人状态。`}</p>
      <ul className="ai-backfill-persons">{activeReceipt.checks.map(initial => {
        const check = checks.find(item => item.id === initial.id) ?? initial
        const needsManual = ['FAILED', 'UNAVAILABLE', 'STALE'].includes(check.state) || check.result?.overall === 'UNKNOWN'
        return <li key={check.id}><span><strong>{check.subject_name ?? '员工'}</strong><small>{needsManual ? failureText[check.failure_code ?? ''] ?? '本次无法判断，请人工核对；不认定违规。' : check.state === 'COMPLETE' ? 'AI 建议已生成，等待人工核对。' : '等待队列执行；完成后显示检查建议。'}</small>{initial.requester_name && <small>发起人：{initial.requester_name} · {dateTime(initial.created_at)}</small>}{initial.backfill_reason && <small className="ai-backfill-reason">原因：{initial.backfill_reason}</small>}</span><span className="ai-pill">{needsManual ? '转人工核对' : statusText[check.state] ?? '待核对'}</span></li>
      })}</ul>{audit}{receipt && <p className="ai-backfill-reason"><strong>补查原因：</strong>{reason.trim()}</p>}
      <div className="ai-actions"><button type="button" className="secondary" onClick={onChanged}>刷新检查状态</button><button type="button" className="secondary" onClick={() => setStep('entry')}>返回原工作详情</button></div>
    </>}
    <p className="ai-muted">实际发起人、原因、员工、照片和规则版本均留痕。原提交人和提交时间保持不变；不自动修改工作审批、完成率或 KPI。</p>
  </section>
}
