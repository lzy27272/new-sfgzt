import { useEffect, useState } from 'react'
import type { RoleContext } from '../../domain'
import { apiRequest } from '../../api/client'
import { useScopedResource } from '../../shared/useScopedResource'
import { criterionNames, errorText, failureText, latestChecks, statusText } from './model'
import type { Check, ReviewContext } from './model'
import { EvidencePhoto } from './EvidencePhoto'
import { AiRuleReference } from './AiRuleReference'
export { EvidencePhoto } from './EvidencePhoto'
import './aiEvidence.css'

const emptyContext: ReviewContext = { subjects: [], checks: [], providerReady: false, attachments: [], canSubmit: false, defaultSubjectId: '' }
function time(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
type RecordProps = {
  identity: RoleContext; recordId: string; review?: boolean; canDecide?: boolean; selectedId?: string; onChanged?: () => void;
}
export function AiEvidenceRecord(props: RecordProps) {
  return <EvidenceRecord key={`${props.identity.key}:${props.recordId}`} {...props} />
}
function EvidenceRecord({ identity, recordId, review = false, canDecide = true, selectedId, onChanged }: RecordProps) {
  const resource = useScopedResource(`ai-evidence:${identity.key}:${recordId}`, signal => apiRequest<ReviewContext>(`/ai-evidence/records/${recordId}`, identity, { signal }), emptyContext, 8000)
  const [subject, setSubject] = useState('')
  const [chosen, setChosen] = useState<string[]>([])
  const [selected, setSelected] = useState<string>()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [history, setHistory] = useState<{ action: string; reason?: string; created_at: string }[]>()
  const [photoPage, setPhotoPage] = useState(0)
  const [editing, setEditing] = useState(false)
  const [peopleQuery, setPeopleQuery] = useState('')
  useEffect(() => { setSelected(undefined); setReason(''); setHistory(undefined) }, [selectedId])
  const checks = latestChecks(resource.data.checks)
  const configuredSubjects = resource.data.subjects.filter(item => item.configured)
  const effectiveSubject = subject || resource.data.defaultSubjectId
  const current = resource.data.checks.find(item => item.id === (selected ?? selectedId))
    ?? checks.find(item => item.subject_assignment_id === effectiveSubject) ?? (review ? checks[0] : undefined)
  const photos = resource.data.attachments.filter(item => ['image/jpeg', 'image/png'].includes(item.media_type))
  const canReview = review && canDecide && current && current.requested_by !== identity.actorId && !['QUEUED', 'RUNNING'].includes(current.state)
  const mutate = async (run: () => Promise<unknown>, success: string) => {
    setBusy(true); setMessage(''); setError(''); setHistory(undefined)
    try { await run(); await resource.reload(); onChanged?.(); setMessage(success) }
    catch (failure) { setError(errorText(failure)) } finally { setBusy(false) }
  }
  const request = (previous?: Check) => void mutate(async () => {
    const check = await apiRequest<Check>(`/ai-evidence/records/${recordId}/checks`, identity, { method: 'POST', body: JSON.stringify({ subjectAssignmentId: previous?.subject_assignment_id ?? effectiveSubject, attachmentIds: chosen, supersedesId: previous?.id ?? null }) })
    setSelected(check.id); setChosen([]); setEditing(false); setReason('')
  }, '检查申请已登记。原工作及提交时间不变；未接通模型时可申请人工复核。')
  const decide = (action: string) => {
    if (!current || !reason.trim()) { setError('请填写处理意见或复核原因'); return }
    void mutate(() => apiRequest(`/ai-evidence/checks/${current.id}/decisions`, identity, { method: 'POST', body: JSON.stringify({ action, expectedVersion: current.row_version, reason: reason.trim() }) }), action === 'APPEAL' ? '已申请人工复核，可查看处理记录。' : action === 'RETRY' ? '已重新排队检查，不重复生成工作任务。' : '人工结论已留痕；影子期不改变正式工作和绩效。')
  }
  const upload = (file?: File, camera = false) => {
    if (!file) return
    void mutate(async () => {
      const body = new FormData(); body.append('file', file); body.append('captureSource', camera ? 'CAMERA' : 'FILE_PICKER')
      if (camera) body.append('capturedAtClient', new Date().toISOString())
      const attachment = await apiRequest<{ id: string }>(`/ai-evidence/records/${recordId}/photos`, identity, { method: 'POST', body })
      setChosen(ids => [...ids, attachment.id].slice(-4))
    }, '照片已关联原工作，请选择同一员工的照片后提交检查。')
  }
  if (!resource.loading && !resource.error && !configuredSubjects.length && !checks.length) return null
  return <section className="ai-card ai-record" aria-label="仪容仪表AI检查">
    <header className="ai-heading"><h2>{review ? '仪容仪表复核' : resource.data.participantScope ? '全员仪容仪表检查' : '仪容仪表 · AI 检查'}</h2><AiRuleReference identity={identity} recordId={recordId} /><span className="ai-pill">AI 建议 · 人工复核</span></header>
    <p className="ai-muted">按已启用标准检查，包括规则中启用的妆容要求；不自动扣绩效，不改变原工作提交时间。</p>
    {resource.data.pilotEnabled === false && <p className="ai-warning">本门店已配置执行标准，但不在已批准的 AI 照片外发试点范围；请按标准人工检查。</p>}
    {resource.loading && <p role="status">正在读取检查记录…</p>}
    {!!resource.error && <p className="ai-error" role="alert">{errorText(resource.error)}<button type="button" className="secondary" onClick={() => void resource.reload()}>重试</button></p>}
    {error && <p className="ai-error" role="alert">{error}</p>}{message && <p className="ai-success" role="status">{message}</p>}
    <div className={resource.data.participantScope && !review ? 'ai-participant-layout' : ''}>
    {resource.data.participantScope && !review && <aside className="ai-participant-list"><h3>本店可参与员工</h3><p className="ai-muted">选择本次参与人员，逐人关联照片；不重复生成个人工作任务。</p>
      <label>搜索姓名或岗位<input aria-label="搜索检查人员" value={peopleQuery} onChange={event => setPeopleQuery(event.target.value)} placeholder="搜索姓名或岗位" /></label>
      <p className="ai-muted">可选 {configuredSubjects.length} 个任职 · 已登记检查 {checks.length} 项</p>
      <div className="ai-people-scroll">{configuredSubjects.filter(person => `${person.name} ${person.position_name}`.includes(peopleQuery.trim())).map(person => {
        const checked = checks.find(check => check.subject_assignment_id === person.id)
        return <button type="button" className={`ai-person-row${effectiveSubject === person.id ? ' selected' : ''}`} key={person.id} disabled={busy} onClick={() => {
          setSubject(person.id); setChosen([]); setSelected(checked?.id); setEditing(false); setReason(''); setHistory(undefined); setPhotoPage(0); setMessage(''); setError('')
        }}><span><strong>{person.name}</strong><small>{person.position_name}</small></span><span className="ai-pill">{checked ? ['QUEUED', 'RUNNING'].includes(checked.state) ? '检查中' : checked.human_outcome === 'PENDING' ? '待复核' : '已复核' : '选择照片'}</span></button>
      })}</div>
      {!configuredSubjects.some(person => `${person.name} ${person.position_name}`.includes(peopleQuery.trim())) && <p className="ai-empty">未找到匹配人员</p>}
    </aside>}
    <div className="ai-participant-content">
    {!review && resource.data.participantScope && <h3>{resource.data.subjects.find(person => person.id === effectiveSubject)?.name ?? '请选择参与员工'}</h3>}
    {!review && resource.data.canSubmit && current && <button type="button" className="primary" disabled={busy} onClick={() => { setEditing(value => !value); setSubject(current.subject_assignment_id); setChosen([]) }}>{editing ? '返回检查结果' : '补拍 / 新增人员检查'}</button>}
    {!review && resource.data.canSubmit && (!current || editing) && <div className="ai-submission">
      <label>被检查员工<select aria-label="被检查员工" value={effectiveSubject} disabled={busy} onChange={e => {
        setSubject(e.target.value); setChosen([]); setSelected(undefined); setEditing(true); setReason(''); setHistory(undefined); setMessage(''); setError('')
      }}><option value="">请选择本店员工</option>{resource.data.subjects.map(person => <option value={person.id} key={person.id} disabled={!person.configured}>{person.name} · {person.position_name}{person.configured ? '' : '（未配置规则）'}</option>)}</select></label>
      <p>由提交人确认照片对应人员。一人一组照片，按执行标准提供全身照或清晰近照；无需工牌、无规定装备的项目不额外要求。多人合照无法据此判断每个人是否合格。</p>
      <div className="ai-upload-actions"><label className="ai-file-button">拍照 / 补拍<input aria-label="拍照补拍" type="file" accept="image/jpeg,image/png" capture="environment" disabled={busy} onChange={e => { upload(e.target.files?.[0], true); e.currentTarget.value = '' }} /></label><label className="ai-file-button">选择照片<input aria-label="选择检查照片" type="file" accept="image/jpeg,image/png" disabled={busy} onChange={e => { upload(e.target.files?.[0]); e.currentTarget.value = '' }} /></label></div>
      <div className="ai-evidence-grid">{photos.slice(photoPage * 6, photoPage * 6 + 6).map(photo => <div className="ai-evidence-choice" key={photo.id}><EvidencePhoto identity={identity} item={photo} /><label className="ai-checkbox"><input type="checkbox" checked={chosen.includes(photo.id)} disabled={busy || (chosen.length >= 4 && !chosen.includes(photo.id))} onChange={() => setChosen(ids => ids.includes(photo.id) ? ids.filter(id => id !== photo.id) : [...ids, photo.id])} />用于本次检查</label></div>)}</div>
      {photos.length > 6 && <div className="ai-actions"><button className="secondary" disabled={photoPage === 0} onClick={() => setPhotoPage(page => page - 1)}>上一组照片</button><span>{photoPage + 1}/{Math.ceil(photos.length / 6)}</span><button className="secondary" disabled={(photoPage + 1) * 6 >= photos.length} onClick={() => setPhotoPage(page => page + 1)}>下一组照片</button></div>}
      <small>本次选择 {chosen.length}/4 张；原工作全部照片仍保留在证据列表中。</small>
      <div className="ai-actions"><button type="button" className="primary" disabled={busy || !chosen.length || !configuredSubjects.some(person => person.id === effectiveSubject)} onClick={() => request(checks.find(check => check.subject_assignment_id === effectiveSubject))}>{checks.some(check => check.subject_assignment_id === effectiveSubject) ? '补拍并重新提交' : '开始 AI 检查'}</button></div>
    </div>}
    {!!checks.length && <label>检查记录<select aria-label="选择检查记录" value={current?.id ?? ''} disabled={busy} onChange={e => {
      setSelected(e.target.value); setSubject(checks.find(check => check.id === e.target.value)?.subject_assignment_id ?? ''); setChosen([]); setEditing(false); setReason(''); setHistory(undefined); setMessage(''); setError('')
    }}><option value="" disabled>请选择检查记录</option>{checks.map(check => <option value={check.id} key={check.id}>{check.subject_name ?? '员工'} · {time(check.created_at)}</option>)}</select></label>}
    {current ? <div className="ai-result"><div className={`ai-result-banner ${(current.result.overall ?? 'UNKNOWN').toLowerCase()}`}><strong>{current.result.overall === 'UNKNOWN' ? '需要补拍或人工核对，不认定违规' : current.result.overall === 'CONCERN' ? '发现疑似问题，等待人工核实' : current.result.overall === 'PASS' ? '证据建议符合，等待人工核对' : statusText[current.state] ?? '等待人工核对'}</strong><p>{current.failure_code ? failureText[current.failure_code] ?? '检查暂未完成，请人工核对。' : 'AI 观察不是最终考核结论。'}</p></div>
      <div className="ai-evidence-grid">{(current.evidence ?? []).map(photo => <EvidencePhoto identity={identity} key={photo.id} item={photo} box={current.result.checks?.find(finding => finding.evidenceId === photo.id && finding.box)?.box} />)}</div>
      <div className="ai-findings">{current.result.checks?.map(finding => <article key={finding.code}><div><strong>{criterionNames[finding.code]}</strong><span className={`ai-pill ${finding.status.toLowerCase()}`}>{statusText[finding.status] ?? '待核对'}</span></div><p>{finding.observation}</p></article>)}</div>
      <p>人工处理：{current.human_outcome === 'PASS' ? '人工确认符合' : statusText[current.human_outcome] ?? '待复核'}{current.human_reason ? ` · ${current.human_reason}` : ''}</p>
      {current.appeal_reason && <p>人工复核申请：{current.appeal_reason}</p>}
      <small>规则 V{current.rule_version ?? '—'} · 输入 {current.input_tokens ?? 0} / 输出 {current.output_tokens ?? 0} Token</small>
      <AiRuleReference identity={identity} recordId={recordId} preferredRuleId={current.rule_id} label="查看本次检查标准" />
      <p className="ai-muted">仅累计接口已返回的用量；超时或失败调用也可能产生费用。</p>
      {(resource.data.canSubmit || canReview) && <label>{canReview ? '复核意见' : '申请说明'}<textarea aria-label="检查处理意见" placeholder="说明观察事实、补拍要求或申请人工复核的原因" value={reason} maxLength={2000} onChange={e => setReason(e.target.value)} disabled={busy} /></label>}
      <div className="ai-actions">{canReview && <><button className="secondary" disabled={busy || !reason.trim()} onClick={() => decide('RETAKE')}>要求补拍</button><button className="primary" disabled={busy || !reason.trim()} onClick={() => decide('PASS')}>确认通过</button><button className="secondary" disabled={busy || !reason.trim()} onClick={() => decide('OVERTURNED')}>撤销误判</button></>}{!review && resource.data.canSubmit && <><button className="secondary" disabled={busy || !reason.trim()} onClick={() => decide('APPEAL')}>申请人工复核</button>{['UNAVAILABLE', 'FAILED'].includes(current.state) && resource.data.providerReady && <button className="secondary" disabled={busy || !reason.trim()} onClick={() => decide('RETRY')}>重新检查</button>}</>}
        <button className="secondary" disabled={busy} onClick={() => void mutate(async () => { setHistory(await apiRequest(`/ai-evidence/checks/${current.id}/history`, identity)) }, '')}>查看处理记录</button></div>
      {history && <ol className="ai-history">{history.map((event, index) => <li key={`${event.created_at}:${index}`}><time>{time(event.created_at)}</time><span>{event.reason || '处理已留痕'}</span></li>)}</ol>}
    </div> : !resource.loading && <p className="ai-empty">{configuredSubjects.length ? review ? '尚未发起 AI 检查。由原提交人在本人工作详情选择参与员工和对应照片，再点击“开始 AI 检查”。' : '此员工暂无 AI 检查记录。请选择对应照片后发起检查；系统不会自动扫描历史照片。' : '暂无适用规则，请联系管理员配置此门店和工作项。'}</p>}
    </div></div>
    {review && <p className="ai-warning">此处仅复核 AI 影子建议。正式工作复核仍在原工作详情中完成，不替代绩效或薪酬审批。</p>}
  </section>
}

export function AiEvidenceTeam({ identity, hotelId, permissions, onClose }: { identity: RoleContext; hotelId?: string; permissions: string[]; onClose: () => void }) {
  const [filter, setFilter] = useState('PENDING')
  const [selected, setSelected] = useState<string>()
  const resource = useScopedResource<Check[]>(`ai-team:${identity.key}:${hotelId ?? ''}:${filter}`, signal => apiRequest(`/ai-evidence/team?${new URLSearchParams({ ...(hotelId ? { hotelId } : {}), ...(filter ? { state: filter } : {}) })}`, identity, { signal }), [], 15000)
  const current = resource.data.find(row => row.id === selected) ?? resource.data[0]
  return <section className="ai-page"><header className="ai-heading"><div><h1>仪容仪表复核</h1><p className="ai-muted">仅查看当前任职授权的门店与人员；影子结果不改变正式考核。</p></div><button className="secondary" onClick={onClose}>返回团队工作</button></header>
    <label className="ai-filter">处理状态<select value={filter} aria-label="AI复核状态筛选" onChange={e => { setFilter(e.target.value); setSelected(undefined) }}><option value="PENDING">待人工复核</option><option value="">全部处理记录</option><option value="RETAKE">需要补拍</option><option value="PASS">人工确认符合</option><option value="OVERTURNED">误判已撤销</option></select></label>
    {!!resource.error && <p className="ai-error" role="alert">{errorText(resource.error)}<button className="secondary" onClick={() => void resource.reload()}>重新加载</button></p>}
    <div className="ai-review-layout"><section className="ai-card ai-queue"><h2>待复核记录</h2>{resource.loading && <p role="status">正在读取…</p>}{!resource.loading && !resource.data.length && <p className="ai-empty">当前范围没有对应检查记录。</p>}{resource.data.map(row => <button type="button" className={`ai-queue-row ${current?.id === row.id ? 'selected' : ''}`} key={row.id} onClick={() => setSelected(row.id)}><span><strong>{row.subject_name}</strong><small>{row.position_name} · {row.hotel_name}</small></span><span className="ai-pill">{statusText[row.result.overall ?? row.state] ?? '待核对'}</span><small>{time(row.created_at)}</small></button>)}</section>
      {current ? <AiEvidenceRecord key={current.work_record_id} identity={identity} recordId={current.work_record_id} selectedId={current.id} review canDecide={permissions.includes('work-record.review') || permissions.includes('*')} onChanged={() => void resource.reload()} /> : <section className="ai-card ai-empty">选择记录，对照证据查看AI观察和人工结论。</section>}
    </div>
  </section>
}
