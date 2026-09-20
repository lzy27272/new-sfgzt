import { useEffect, useMemo, useState } from 'react'
import type { RoleContext } from '../../domain'
import {
  approveDirectoryCandidate,
  loadDirectoryCandidateReviewOptions,
  loadDirectoryCandidates,
  loadDirectoryEvents,
  regenerateDirectoryInvitation,
  rejectDirectoryCandidate,
  retryDirectoryEvent,
  retryDirectoryCandidate,
  type DirectoryCandidate,
  type DirectoryEventRow,
  type DirectoryOnboardingStatus,
  type DirectoryReviewOptions,
} from './directoryOnboardingApi'

const labels: Record<DirectoryOnboardingStatus, string> = {
  WAITING_PROFILE: '待员工填写', PENDING_APPROVAL: '待中台审核', CONFLICT: '异常绑定',
  APPROVED: '已启用', REJECTED: '已拒绝', CANCELLED: '已取消', EXPIRED: '已过期',
}

function displayTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

export function WecomDirectoryOnboardingAdministration({
  identity,
  canApprove,
  canManage,
  candidateId,
  directoryEventId,
  onClearTarget,
}: {
  identity: RoleContext
  canApprove: boolean
  canManage: boolean
  candidateId?: string
  directoryEventId?: string
  onClearTarget?: () => void
}) {
  const [items, setItems] = useState<DirectoryCandidate[]>([])
  const [directoryEvents, setDirectoryEvents] = useState<DirectoryEventRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [directoryEventError, setDirectoryEventError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const [status, setStatus] = useState('')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<DirectoryCandidate>()
  const [busy, setBusy] = useState(false)
  const [reviewOptions, setReviewOptions] = useState<DirectoryReviewOptions>()
  const [reviewAssignmentKey, setReviewAssignmentKey] = useState('')
  const [reviewOptionsLoading, setReviewOptionsLoading] = useState(false)
  const [reviewOptionsError, setReviewOptionsError] = useState<string>()

  const reload = async () => {
    setLoading(true); setError(undefined); setDirectoryEventError(undefined)
    try {
      const [candidates, events] = await Promise.allSettled([
        loadDirectoryCandidates(identity, status || undefined),
        canManage ? loadDirectoryEvents(identity) : Promise.resolve([]),
      ])
      if (candidates.status === 'rejected') throw candidates.reason
      setItems(candidates.value)
      if (events.status === 'fulfilled') setDirectoryEvents(events.value)
      else {
        setDirectoryEvents([])
        setDirectoryEventError(events.reason instanceof Error ? events.reason.message : '人员同步异常列表加载失败')
      }
    }
    catch (reason) { setError(reason instanceof Error ? reason.message : '企微入职申请加载失败') }
    finally { setLoading(false) }
  }
  useEffect(() => { void reload() }, [identity.key, status])
  useEffect(() => {
    if (candidateId && !loading) {
      const target = items.find((item) => item.id === candidateId)
      if (target) setSelected(target)
    }
  }, [candidateId, items, loading])
  useEffect(() => {
    if (!directoryEventId || loading) return
    document.getElementById(`directory-event-${directoryEventId}`)?.scrollIntoView({ block: 'center' })
  }, [directoryEventId, directoryEvents, loading])
  useEffect(() => {
    setReviewOptions(undefined)
    setReviewAssignmentKey('')
    setReviewOptionsError(undefined)
    setReviewOptionsLoading(false)
    if (!selected || selected.requestedPositionId || !canApprove
      || !['PENDING_APPROVAL', 'CONFLICT'].includes(selected.status)) return
    let active = true
    setReviewOptionsLoading(true)
    void loadDirectoryCandidateReviewOptions(identity, selected)
      .then((options) => { if (active) setReviewOptions(options) })
      .catch((reason) => {
        if (active) setReviewOptionsError(reason instanceof Error ? reason.message : '可分配岗位加载失败')
      })
      .finally(() => { if (active) setReviewOptionsLoading(false) })
    return () => { active = false }
  }, [selected?.id, selected?.requestedPositionId, selected?.status, canApprove, identity.key])

  const visible = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    return items.filter((item) => !normalized || `${item.displayName} ${item.requestedLoginName ?? ''} ${item.requestedHotelName ?? ''} ${item.requestedPositionName ?? ''}`.toLowerCase().includes(normalized))
  }, [items, query])
  const count = (value: DirectoryOnboardingStatus) => items.filter((item) => item.status === value).length
  const reviewAssignment = reviewOptions?.positions.find(
    (position) => `${position.orgUnitId}:${position.positionId}` === reviewAssignmentKey,
  )
  const needsReviewerAssignment = Boolean(selected && !selected.requestedPositionId
    && ['PENDING_APPROVAL', 'CONFLICT'].includes(selected.status))

  const approve = async () => {
    if (!selected || !['PENDING_APPROVAL', 'CONFLICT'].includes(selected.status)) return
    if (needsReviewerAssignment && !reviewAssignment) {
      setReviewOptionsError('请先为员工分配具体岗位再确认启用')
      return
    }
    const transfer = selected.status === 'CONFLICT'
    let reason: string | undefined
    if (transfer) {
      if (!window.confirm(`“${selected.displayName}”的企业微信身份已关联其他中台账号。确认进入高风险转移流程？`)) return
      const input = window.prompt('请输入转移原因。确认后原账号会话将失效，并保留完整审计记录：')
      if (!input?.trim()) return
      reason = input.trim()
      if (!window.confirm('最后确认：将现有企业微信绑定原子转移到本次新账号，并立即启用新任职？')) return
    } else if (!window.confirm(`确认启用“${selected.displayName}”的中台账号、${reviewAssignment ? `“${reviewAssignment.name}”岗位、` : ''}任职与企业微信绑定？`)) {
      return
    }
    setBusy(true); setError(undefined)
    try {
      const result = await approveDirectoryCandidate(identity, selected, reason, transfer,
        reviewAssignment ? {
          orgUnitId: reviewAssignment.orgUnitId,
          positionId: reviewAssignment.positionId,
        } : undefined)
      setNotice(result.status === 'APPROVED'
        ? transfer ? '身份冲突已完成审核转移并启用，群推送开关未改变' : '入职申请已确认启用，群推送开关未改变'
        : result.message)
      setSelected(undefined); onClearTarget?.(); await reload()
    }
    catch (reason) { setError(reason instanceof Error ? reason.message : '确认启用失败') }
    finally { setBusy(false) }
  }
  const reject = async () => {
    if (!selected) return
    const reason = window.prompt('请输入拒绝原因，员工本人会收到该处理结果：')
    if (!reason?.trim()) return
    setBusy(true); setError(undefined)
    try { await rejectDirectoryCandidate(identity, selected, reason.trim()); setNotice('入职申请已拒绝'); setSelected(undefined); onClearTarget?.(); await reload() }
    catch (failure) { setError(failure instanceof Error ? failure.message : '拒绝申请失败') }
    finally { setBusy(false) }
  }
  const retry = async (mode: 'retry' | 'regenerate') => {
    if (!selected) return
    const regenerating = mode === 'regenerate'
    const message = regenerating
      ? '确认重新生成一条120分钟有效的新邀请？旧链接保持失效，不能恢复。'
      : '确认重试本次技术故障？系统将旋转旧凭据并发送一条新的120分钟邀请。'
    if (!window.confirm(message)) return
    const reason = window.prompt('可填写本次处理备注（选填）：')?.trim()
    setBusy(true); setError(undefined)
    try {
      const result = regenerating
        ? await regenerateDirectoryInvitation(identity, selected, reason)
        : await retryDirectoryCandidate(identity, selected, reason)
      setNotice(result.message)
      setSelected(undefined); onClearTarget?.(); await reload()
    } catch (failure) { setError(failure instanceof Error ? failure.message : regenerating ? '重新生成邀请失败' : '技术重试失败') }
    finally { setBusy(false) }
  }
  const retryEvent = async (event: DirectoryEventRow) => {
    if (!canApprove || !window.confirm('确认将该企业微信人员同步技术异常重新排队？系统不会公开人员身份信息。')) return
    const reason = window.prompt('可填写本次处理备注（选填）：')?.trim()
    setBusy(true); setError(undefined)
    try {
      const result = await retryDirectoryEvent(identity, event, reason)
      setNotice(result.message)
      onClearTarget?.()
      await reload()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '同步技术异常重试失败')
    } finally { setBusy(false) }
  }

  return <section className="page-section directory-onboarding-admin">
    <header className="page-title"><div><span className="eyebrow">WECOM ONBOARDING</span><h1>企业微信入职审核</h1><p>企业微信新成员或管理员邀请统一进入这里；员工自行注册账号并填写任职后，由行政人事或行政人事主管审核。</p></div><div className="page-actions"><span className="source-flag api">UserID 仅显示脱敏指纹</span><button className="secondary" onClick={() => void reload()}>刷新</button></div></header>
    <div className="inline-warning page-error">入职审核只创建人员账号、任职和个人企微绑定，不会自动开启企业微信群推送。</div>
    {notice && <div className="inline-success page-error">{notice}</div>}{error && <div className="inline-error page-error">{error}</div>}
    <div className="directory-onboarding-metrics">{[
      ['待员工填写', count('WAITING_PROFILE'), 'waiting'], ['待中台审核', count('PENDING_APPROVAL'), 'approval'],
      ['已启用', count('APPROVED'), 'approved'], ['异常', count('CONFLICT'), 'conflict'],
    ].map(([label, value, tone]) => <article key={String(label)}><span>{label}</span><strong className={String(tone)}>{value}</strong></article>)}</div>
    <article className="panel table-panel"><header><div><span className="panel-kicker">NEW EMPLOYEE REVIEW</span><h2>入职申请</h2></div></header>
      <div className="directory-onboarding-filters"><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">全部状态</option>{Object.entries(labels).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="员工、申请组织或岗位" /></div>
      {loading ? <div className="state-card"><div className="spinner"/><strong>正在读取入职申请</strong></div> : !visible.length ? <div className="state-card"><b>◇</b><strong>当前没有入职申请</strong><span>企业微信新增成员或员工打开一键邀请后会出现在这里。</span></div> : <div className="directory-onboarding-table">
        <div className="directory-onboarding-head"><span>员工</span><span>申请组织</span><span>申请岗位</span><span>提交时间</span><span>状态</span><span>操作</span></div>
        {visible.map((item) => <div key={item.id}><span><strong>{item.displayName}</strong><small>{item.requestedLoginName ? `账号：${item.requestedLoginName}` : item.invitationSource === 'MANUAL_LINK' ? '管理员一键邀请' : item.maskedFingerprint}</small></span><span>{item.requestedHotelName ?? '待员工选择'}</span><span>{[item.requestedDepartmentName,item.requestedPositionName].filter(Boolean).join(' · ') || (item.requestedOrgUnitId ? '岗位待分配' : '待员工选择')}</span><span>{displayTime(item.updatedAt)}{item.invitationExpiresAt && ['WAITING_PROFILE', 'EXPIRED'].includes(item.status) && <small>{item.status === 'EXPIRED' ? '邀请已失效' : `有效期至 ${displayTime(item.invitationExpiresAt)}`}</small>}</span><span><b className={`status-pill ${item.status.toLowerCase().replaceAll('_','-')}`}>{item.expiringSoon ? '即将过期' : labels[item.status]}</b>{item.suggestedAction && <small>{item.suggestedAction}</small>}</span><span><button className="link-button" onClick={() => setSelected(item)}>{['PENDING_APPROVAL', 'CONFLICT'].includes(item.status) ? '审核' : item.retryable ? '处理故障' : item.canRegenerate ? '重新生成' : '查看'}</button></span></div>)}
      </div>}
    </article>
    {directoryEventError && <div className="inline-warning page-error">入职申请已正常加载；{directoryEventError}</div>}
    <article className="panel table-panel directory-event-panel"><header><div><span className="panel-kicker">DIRECTORY SYNC EXCEPTIONS</span><h2>人员同步技术异常</h2><p>仅显示脱敏原因和建议动作；身份冲突仍须走审核流程，不能在这里重试。</p></div><span className={`status-pill ${directoryEvents.length ? 'conflict' : 'approved'}`}>{directoryEvents.length ? `${directoryEvents.length} 项待处理` : '运行正常'}</span></header>
      {!directoryEvents.length ? <div className="directory-event-empty">当前没有需要管理员处理的企业微信人员同步异常。</div> : <div className="directory-event-list">
        {directoryEvents.map((event) => <div id={`directory-event-${event.id}`} className={directoryEventId === event.id ? 'notification-target' : undefined} key={event.id}><span><strong>{event.changeType || '成员变更'}</strong><small>{displayTime(event.receivedAt)}</small></span><span><strong>{event.errorMessage}</strong><small>{event.lastErrorCode || 'TECHNICAL_FAILURE'}</small></span><span>{event.suggestedAction}</span><span>{canApprove ? <button className="secondary" disabled={busy} onClick={() => void retryEvent(event)}>{busy ? '处理中…' : '重试'}</button> : <small>请由有审核权限的管理员处理</small>}</span></div>)}
      </div>}
    </article>
    {selected && <div className="drawer-backdrop"><aside className="drawer onboarding-review-drawer"><header><div><span className="panel-kicker">EMPLOYEE REVIEW</span><h2>确认员工任职</h2></div><button className="close" onClick={() => { setSelected(undefined); onClearTarget?.() }}>×</button></header><div className="drawer-body">
      <div className="onboarding-review-person"><strong>{selected.displayName}</strong><span>企业微信身份已验证</span><code>{selected.maskedFingerprint}</code></div>
      <dl><div><dt>注册账号</dt><dd>{selected.requestedLoginName ?? '既有账号任职变更'}</dd></div><div><dt>申请组织</dt><dd>{selected.requestedHotelName ?? '未选择'}</dd></div><div><dt>所属部门</dt><dd>{selected.requestedDepartmentName ?? '未选择'}</dd></div><div><dt>申请岗位</dt><dd>{selected.requestedPositionName ?? (selected.requestedOrgUnitId ? '岗位待分配' : '未选择')}</dd></div><div><dt>当前状态</dt><dd>{labels[selected.status]}</dd></div></dl>
      {needsReviewerAssignment && canApprove && <div className="onboarding-review-assignment"><label>审核分配岗位<select value={reviewAssignmentKey} disabled={reviewOptionsLoading} onChange={(event) => { setReviewAssignmentKey(event.target.value); setReviewOptionsError(undefined) }}><option value="">{reviewOptionsLoading ? '正在加载可分配岗位…' : '请选择具体岗位'}</option>{reviewOptions?.positions.map((position) => <option key={position.positionId} value={`${position.orgUnitId}:${position.positionId}`}>{position.name}</option>)}</select></label><small>仅显示当前账号在申请组织中可分配的已发布岗位。总部受保护岗位须由具备任职管理权限的管理员分配；普通审核员仍受原有权限限制。</small></div>}
      {reviewOptionsError && <div className="inline-error">{reviewOptionsError}</div>}
      {selected.status === 'CONFLICT' && <div className="inline-error">该企业微信身份已绑定其他中台账号。仅可在填写原因并完成两次确认后转移；原账号会话会立即失效。</div>}
      {selected.suggestedAction && <div className={selected.retryable || selected.status === 'EXPIRED' ? 'inline-warning' : 'inline-error'}>{selected.suggestedAction}</div>}
      {!canApprove && ['PENDING_APPROVAL', 'CONFLICT'].includes(selected.status) && <div className="inline-warning">当前账号只有查看权限，请由行政人事或行政人事主管完成审核。</div>}
      <div className="inline-warning">确认后将原子创建或启用员工档案、账号、任职、岗位权限和企业微信绑定；不会开启群推送。</div>
    </div><footer><button className="secondary" onClick={() => { setSelected(undefined); onClearTarget?.() }}>取消</button>{canManage && selected.retryable && <button className="primary" disabled={busy} onClick={() => void retry('retry')}>{busy ? '处理中…' : '技术重试'}</button>}{canManage && selected.canRegenerate && <button className="primary" disabled={busy} onClick={() => void retry('regenerate')}>{busy ? '处理中…' : '重新生成邀请'}</button>}{canApprove && ['PENDING_APPROVAL','CONFLICT'].includes(selected.status) && <button className="secondary" disabled={busy} onClick={() => void reject()}>拒绝</button>}{canApprove && ['PENDING_APPROVAL','CONFLICT'].includes(selected.status) && <button className="primary" disabled={busy || reviewOptionsLoading || (needsReviewerAssignment && !reviewAssignment)} onClick={() => void approve()}>{busy ? '处理中…' : selected.status === 'CONFLICT' ? '确认转移并启用' : needsReviewerAssignment ? '分配并启用' : '确认启用'}</button>}</footer></aside></div>}
  </section>
}
