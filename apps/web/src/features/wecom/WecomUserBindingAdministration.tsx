import { useEffect, useMemo, useState } from 'react'
import type { RoleContext } from '../../domain'
import {
  bulkInviteBindings, bulkSuspendBindings, decideBinding, inviteBinding, loadBindingDashboard,
  loadBindingHistory, rebind, selectPreferredAssignment, updateBinding,
  type AuditEntry, type BindingDashboard, type BindingPerson, type Invitation,
} from './bindingAdminApi'
import { createDirectoryOnboardingInvitation } from './directoryOnboardingApi'
import { WecomInvitationDialog } from './WecomInvitationDialog'
import styles from './wecomBinding.module.css'

const statusLabel: Record<string, string> = {
  WAITING_SCAN: '待员工扫描', WAITING_APPROVAL: '待管理员确认', ACTIVE: '已启用',
  SUSPENDED: '已暂停', ABNORMAL: '异常绑定', EXPIRED: '已过期', UNBOUND: '未绑定', REVOKED: '已解除',
}

function displayTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

function defaultAssignment(person: BindingPerson) {
  return person.assignments.find((item) => item.id === person.preferredAssignmentId)
    ?? person.assignments.find((item) => item.primary) ?? person.assignments[0]
}

export function WecomUserBindingAdministration({ identity, requestId, onClearRequest }: { identity: RoleContext; requestId?: string; onClearRequest?: () => void }) {
  const [dashboard, setDashboard] = useState<BindingDashboard>()
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState<string>()
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const [hotel, setHotel] = useState('')
  const [department, setDepartment] = useState('')
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [invitation, setInvitation] = useState<Invitation>()
  const [history, setHistory] = useState<{ person: BindingPerson; entries: AuditEntry[] }>()
  const [invitationMode, setInvitationMode] = useState<'binding' | 'onboarding'>('binding')

  const reload = async () => {
    setLoading(true); setError(undefined)
    try {
      setDashboard(await loadBindingDashboard(identity))
      setSelected(new Set())
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '企业微信人员绑定加载失败')
    } finally { setLoading(false) }
  }

  useEffect(() => { void reload() }, [identity.key])

  const hotels = useMemo(() => [...new Set((dashboard?.people ?? []).map((item) => item.hotelName).filter(Boolean))].sort(), [dashboard])
  const departments = useMemo(() => [...new Set((dashboard?.people ?? []).map((item) => item.departmentName).filter(Boolean))].sort(), [dashboard])
  const people = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    return (dashboard?.people ?? []).filter((person) =>
      (!requestId || person.requestId === requestId)
      && (!hotel || person.hotelName === hotel) && (!department || person.departmentName === department)
      && (!status || person.bindingStatus === status)
      && (!normalized || `${person.employeeName} ${person.loginName}`.toLowerCase().includes(normalized)))
  }, [dashboard, hotel, department, query, requestId, status])
  const command = async (key: string, operation: () => Promise<unknown>, success: string) => {
    setBusy(key); setError(undefined); setNotice(undefined)
    try { await operation(); setNotice(success); await reload() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败') }
    finally { setBusy(undefined) }
  }

  const showInvitation = async (promise: Promise<Invitation>, mode: 'binding' | 'onboarding' = 'binding') => {
    setBusy('invite'); setError(undefined); setNotice(undefined)
    try { setInvitationMode(mode); setInvitation(await promise); await reload() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '绑定邀请生成失败') }
    finally { setBusy(undefined) }
  }

  const inviteEmployee = () => {
    if (busy) return
    void showInvitation(createDirectoryOnboardingInvitation(identity).then((result) => ({
      requestId: result.candidateId,
      enrollmentUrl: result.enrollmentUrl,
      expiresAt: result.expiresAt,
      status: result.status,
    })), 'onboarding')
  }

  const invite = (person: BindingPerson) => {
    const assignment = defaultAssignment(person)
    if (!assignment) { setError('该员工没有有效任职，不能发起绑定'); return }
    void showInvitation(inviteBinding(identity, person.accountId, assignment.id))
  }

  const approve = (person: BindingPerson) => {
    if (!person.requestId) return
    const conflict = person.requestStatus === 'CONFLICT'
    const text = conflict
      ? '该身份已绑定其他账号。确认后将原子转移：原账号立即失效，新账号启用。是否继续？'
      : '确认员工身份与中台账号一致并启用绑定？群推送开关不会改变。'
    if (!window.confirm(text)) return
    void command(`approve:${person.accountId}`, () => decideBinding(identity, person.requestId!, 'approve', {
      expectedVersion: person.rowVersion, transferExistingBinding: conflict, reason: conflict ? 'IDENTITY_TRANSFER_APPROVED' : 'IDENTITY_CONFIRMED',
    }), '绑定已确认启用；群推送仍保持独立关闭状态')
  }

  const reject = (person: BindingPerson) => {
    if (!person.requestId) return
    const reason = window.prompt('请输入拒绝原因（员工本人将收到处理结果）：')
    if (reason === null) return
    void command(`reject:${person.accountId}`, () => decideBinding(identity, person.requestId!, 'reject', {
      expectedVersion: person.rowVersion, reason,
    }), '绑定申请已拒绝')
  }

  const cancel = (person: BindingPerson) => {
    if (!person.requestId || !window.confirm('取消后当前邀请立即失效，确认取消？')) return
    void command(`cancel:${person.accountId}`, () => decideBinding(identity, person.requestId!, 'cancel', {
      expectedVersion: person.rowVersion, reason: 'INVITATION_CANCELLED_BY_OPERATOR',
    }), '邀请已取消')
  }

  const retry = (person: BindingPerson) => {
    if (!person.requestId || !window.confirm('技术故障重试会生成新的120分钟邀请，旧邀请继续失效。确认继续？')) return
    void showInvitation(decideBinding(identity, person.requestId, 'retry', {
      expectedVersion: person.rowVersion, reason: 'TECHNICAL_RETRY',
    }) as Promise<Invitation>)
  }

  const changeState = (person: BindingPerson, action: 'suspend' | 'resume' | 'revoke') => {
    const prompts = { suspend: '确认暂停该员工的企业微信绑定？', resume: '确认账号和任职有效并恢复绑定？', revoke: '解除后原身份立即失效且必须重新绑定。确认解除？' }
    if (!window.confirm(prompts[action])) return
    void command(`${action}:${person.accountId}`, () => updateBinding(identity, person.accountId, action, person.rowVersion, `MANUAL_${action.toUpperCase()}`), {
      suspend: '绑定已暂停', resume: '绑定已恢复', revoke: '绑定已解除',
    }[action])
  }

  const startRebind = (person: BindingPerson) => {
    const assignment = defaultAssignment(person)
    if (!assignment || !window.confirm('重新绑定会立即使原企业微信身份失效并生成新邀请。确认继续？')) return
    void showInvitation(rebind(identity, person, assignment.id, 'MANUAL_REBIND'))
  }

  const setPreferred = (person: BindingPerson, assignmentId: string) => {
    if (assignmentId === person.preferredAssignmentId) return
    void command(`assignment:${person.accountId}`, () => selectPreferredAssignment(identity, person, assignmentId), '默认企微任职已更新')
  }

  const viewHistory = async (person: BindingPerson) => {
    setBusy(`history:${person.accountId}`); setError(undefined)
    try { setHistory({ person, entries: await loadBindingHistory(identity, person.accountId) }) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '操作记录读取失败') }
    finally { setBusy(undefined) }
  }

  const selectedPeople = (dashboard?.people ?? []).filter((person) => selected.has(person.accountId))
  const canBulkInvite = selectedPeople.length > 0 && selectedPeople.every((person) => ['UNBOUND', 'EXPIRED', 'REVOKED'].includes(person.bindingStatus) && defaultAssignment(person))
  const canBulkSuspend = selectedPeople.length > 0 && selectedPeople.every((person) => person.bindingStatus === 'ACTIVE')
  const bulkInvite = () => {
    if (!canBulkInvite || !window.confirm(`确认批量生成 ${selectedPeople.length} 个120分钟绑定邀请？`)) return
    const payload = selectedPeople.map((person) => ({ accountId: person.accountId, preferredAssignmentId: defaultAssignment(person)!.id }))
    setBusy('bulk-invite'); setError(undefined)
    void bulkInviteBindings(identity, payload).then(async (result) => {
      setNotice(`已生成 ${result.invitations.length} 个邀请。为防止令牌泄漏，批量邀请请逐一安全发送，不会在刷新后回显。`)
      await reload()
    }).catch((reason) => setError(reason instanceof Error ? reason.message : '批量邀请失败')).finally(() => setBusy(undefined))
  }
  const bulkSuspend = () => {
    if (!canBulkSuspend || !window.confirm(`确认批量暂停 ${selectedPeople.length} 个绑定？该操作已进入二次确认。`)) return
    void command('bulk-suspend', () => bulkSuspendBindings(identity, selectedPeople, 'BULK_SUSPENDED'), '所选绑定已批量暂停')
  }

  const capabilities = dashboard?.capabilities
  return <section className="page-section configuration-page">
    <header className="page-title"><div><span className="eyebrow">WECOM IDENTITY GOVERNANCE</span><h1>企业微信人员绑定</h1><p>统一生成员工邀请；员工验证企业微信身份并填写手机号，系统判重后提交资料审核。</p></div><div className="page-actions">{capabilities?.canManage && <button className="primary" disabled={Boolean(busy) || loading} onClick={inviteEmployee}>{busy === 'invite' ? '正在生成…' : '一键邀请'}</button>}<span className="source-flag api">UserID 仅显示脱敏指纹</span><button className="secondary" disabled={loading} onClick={() => void reload()}>刷新</button></div></header>
    <div className="inline-warning page-error">人员绑定与企业微信群推送完全独立。绑定启用不会打开任何群机器人、应用消息或日报自动推送开关。</div>
    {requestId && <div className="inline-success page-error">已定位到通知对应的绑定申请。<button className="link-button" onClick={onClearRequest}>查看全部人员绑定</button></div>}
    {notice && <div className="inline-success page-error">{notice}</div>}{error && <div className="inline-error page-error">{error}</div>}
    <div className={styles.metrics}>{[
      ['待员工扫描', dashboard?.counters.waitingScan ?? 0, 'scan'], ['待管理员确认', dashboard?.counters.waitingApproval ?? 0, 'approval'],
      ['已启用', dashboard?.counters.active ?? 0, 'active'], ['已暂停', dashboard?.counters.suspended ?? 0, 'suspended'],
      ['异常绑定', dashboard?.counters.abnormal ?? 0, 'abnormal'],
    ].map(([label, value, tone]) => <article key={String(label)} className={styles.metric}><span>{label}</span><strong className={styles[String(tone)]}>{value}</strong></article>)}</div>
    <article className="panel table-panel config-panel"><header><div><span className="panel-kicker">EMPLOYEE BINDINGS</span><h2>人员绑定状态</h2></div><div className="panel-actions">{capabilities?.canManage && <><button className="secondary" disabled={!canBulkInvite || Boolean(busy)} onClick={bulkInvite}>批量生成邀请</button><button className="secondary" disabled={!canBulkSuspend || Boolean(busy)} onClick={bulkSuspend}>批量暂停</button></>}</div></header>
      <div className={styles.filters}><select value={hotel} onChange={(event) => setHotel(event.target.value)}><option value="">全部门店</option>{hotels.map((value) => <option key={value} value={value}>{value}</option>)}</select><select value={department} onChange={(event) => setDepartment(event.target.value)}><option value="">全部部门/岗位</option>{departments.map((value) => <option key={value} value={value}>{value}</option>)}</select><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="员工姓名或中台账号"/><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">全部状态</option>{Object.entries(statusLabel).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select></div>
      {loading ? <div className="state-card"><div className="spinner"/><strong>正在读取人员绑定状态</strong></div> : !people.length ? <div className="state-card"><b>◇</b><strong>{requestId ? '通知对应的绑定申请已处理或不在当前权限范围' : '没有符合条件的人员'}</strong><span>{requestId ? '可以返回全部人员绑定继续查看。' : '请调整筛选条件或先维护员工有效任职。'}</span></div> : <div className={styles.table}>
        <div className={styles.head}><span>选择</span><span>门店 / 员工</span><span>账号 / 岗位</span><span>企微状态</span><span>默认企微任职</span><span>验证 / 更新</span><span>UserID 指纹</span><span>操作</span></div>
        {people.map((person) => <div className={styles.row} key={person.accountId}><span><input type="checkbox" checked={selected.has(person.accountId)} onChange={(event) => setSelected((current) => { const next = new Set(current); event.target.checked ? next.add(person.accountId) : next.delete(person.accountId); return next })}/></span><span><strong>{person.hotelCode ? `${person.hotelCode} · ` : ''}{person.hotelName ?? '未归属门店'}</strong><small>{person.employeeName}</small></span><span><strong>{person.loginName}</strong><small>{person.departmentName ? `${person.departmentName} · ` : ''}{person.positionName ?? '无有效任职'}</small></span><span><b className={`${styles.status} ${styles[person.bindingStatus.toLowerCase()]}`}>{statusLabel[person.bindingStatus] ?? person.bindingStatus}</b>{person.expiringSoon && <em>即将过期</em>}<small>{person.reason || person.recommendedAction}</small></span><span>{person.assignments.length > 1 && capabilities?.canManage ? <select value={person.preferredAssignmentId ?? defaultAssignment(person)?.id ?? ''} disabled={busy === `assignment:${person.accountId}`} onChange={(event) => setPreferred(person, event.target.value)}>{person.assignments.map((assignment) => <option key={assignment.id} value={assignment.id}>{[assignment.hotelName, assignment.departmentName, assignment.positionName].filter(Boolean).join(' · ')}</option>)}</select> : <small>{person.defaultAssignment ?? '—'}</small>}</span><span><small>验证：{displayTime(person.lastVerifiedAt)}</small><small>更新：{displayTime(person.updatedAt)}{person.updatedBy ? ` · ${person.updatedBy}` : ' · 系统'}</small></span><span><code>{person.userIdFingerprint ?? '—'}</code></span><span className={styles.actions}>{capabilities?.canManage && ['UNBOUND','EXPIRED','REVOKED'].includes(person.bindingStatus) && <button onClick={() => invite(person)}>发起绑定</button>}{person.bindingStatus === 'WAITING_SCAN' && capabilities?.canManage && <button onClick={() => cancel(person)}>取消邀请</button>}{person.bindingStatus === 'WAITING_APPROVAL' && person.requestStatus === 'AUTHORIZING' && capabilities?.canManage && <button onClick={() => cancel(person)}>取消本次授权</button>}{person.bindingStatus === 'WAITING_APPROVAL' && person.requestStatus === 'PENDING_APPROVAL' && capabilities?.canApprove && <><button onClick={() => approve(person)}>确认启用</button><button className={styles.danger} onClick={() => reject(person)}>拒绝</button></>}{person.bindingStatus === 'ABNORMAL' && person.requestStatus === 'FAILED' && capabilities?.canApprove && <button onClick={() => retry(person)}>技术重试</button>}{person.bindingStatus === 'ABNORMAL' && person.requestStatus === 'CONFLICT' && capabilities?.canApprove && <><button onClick={() => approve(person)}>审批转移</button><button className={styles.danger} onClick={() => reject(person)}>拒绝</button></>}{person.bindingStatus === 'ACTIVE' && capabilities?.canManage && <button onClick={() => changeState(person, 'suspend')}>暂停</button>}{person.bindingStatus === 'SUSPENDED' && capabilities?.canApprove && <button onClick={() => changeState(person, 'resume')}>恢复</button>}{['ACTIVE','SUSPENDED'].includes(person.bindingStatus) && capabilities?.canApprove && <><button onClick={() => startRebind(person)}>重新绑定</button><button className={styles.danger} onClick={() => changeState(person, 'revoke')}>解除</button></>}<button onClick={() => void viewHistory(person)}>操作记录</button></span></div>)}
      </div>}
    </article>
    {invitation && <WecomInvitationDialog key={invitation.requestId} invitation={invitation} mode={invitationMode} regenerating={busy === 'invite'} error={error} onClose={() => setInvitation(undefined)} onRegenerate={invitationMode === 'onboarding' && capabilities?.canManage ? inviteEmployee : undefined}/>}
    {history && <div className="modal-backdrop"><section className={`modal ${styles.history}`} role="dialog" aria-modal="true"><header><div><span className="panel-kicker">APPEND-ONLY AUDIT</span><h2>{history.person.employeeName} · 操作记录</h2></div><button className="close" onClick={() => setHistory(undefined)}>×</button></header><div className={styles.historyList}>{history.entries.length ? history.entries.map((entry, index) => <div key={`${entry.createdAt}-${index}`}><strong>{entry.action}</strong><span>{entry.actorName ?? '系统'} · {displayTime(entry.createdAt)}</span><small>{entry.summary || '已写入不可覆盖的审计记录'}</small></div>) : <div className="state-card"><strong>暂无操作记录</strong></div>}</div></section></div>}
  </section>
}
