import { useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { hasPermission, permissions } from '../../app/permissions'
import { buildResumableDraftKey, readResumableDraft, removeResumableDraft, writeResumableDraft } from '../../app/resumableDrafts'
import type { AppNavigate, DailyFeatureRouteId } from '../../app/routeConfig'
import type { RoleContext, RouteParams } from '../../domain'
import { AsyncState } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { useStableCommand } from '../../shared/useStableCommand'
import { AiRecommendationCard, FeatureHeader, StatusBadge, featureStyles as styles, formatLocalDateTime } from '../shared/FeatureUI'
import { loadCurrentBusinessDay, loadDailyReport, loadDailyReportOrgOptions, loadMyDailyReports, loadTeamDailyReports, requestDailyReportCorrection, reviewDailyReport, reviewDailyReportRevision, saveDailyReportDraft, submitDailyReport, type DailyReportOrgOption } from './api'
import type { DailyReportDetail, DailyReportDraftInput, DailyReportSummary } from './types'

export function DailyReportRoutes({ view, params, identity, grantedPermissions, go }: { view: DailyFeatureRouteId; params: RouteParams; identity: RoleContext; grantedPermissions: string[]; go: AppNavigate }) {
  if (view === 'daily-reports-my') return <ReportList mode="my" identity={identity} params={params} go={go} />
  if (view === 'daily-reports-team') return <ReportList mode="team" identity={identity} params={params} go={go} />
  if (!params.reportId) return <AsyncState loading={false} error={new Error('路由缺少日报编号')} />
  return <ReportDetailPage identity={identity} grantedPermissions={grantedPermissions} reportId={params.reportId} correctionRevisionId={view === 'daily-report-correction' ? params.revisionId : undefined} go={go} />
}

function ReportList({ mode, identity, params, go }: { mode: 'my' | 'team'; identity: RoleContext; params: RouteParams; go: AppNavigate }) {
  if (mode === 'team') return <TeamReportList identity={identity} params={params} go={go} />
  const orgUnitId = params.orgUnitId || identity.assignmentOrgUnitId || identity.orgScopes[0] || ''
  return <ScopedReportList mode="my" identity={identity} params={params} go={go} orgUnitId={orgUnitId} orgOptions={[]} />
}

function TeamReportList({ identity, params, go }: { identity: RoleContext; params: RouteParams; go: AppNavigate }) {
  const options = useScopedResource(
    `${identity.key}:daily-report-org-options`,
    (signal) => loadDailyReportOrgOptions(identity, signal),
    [] as DailyReportOrgOption[],
    30_000,
  )
  const requestedOrgUnitId = params.orgUnitId || identity.assignmentOrgUnitId || ''
  const selected = options.data.find((option) => option.id === requestedOrgUnitId) ?? options.data[0]
  if (options.loading) {
    return <section className={styles.page}><FeatureHeader eyebrow="TEAM DAILY REPORTS" title="团队日报" description="正在读取可管理的门店范围。" /><AsyncState loading /></section>
  }
  if (options.error || !selected) {
    return <section className={styles.page}><FeatureHeader eyebrow="TEAM DAILY REPORTS" title="团队日报" description="团队日报需要先选择一个有效门店。" /><AsyncState loading={false} error={options.error || new Error('当前权限范围内没有已启用门店')} onRetry={options.reload} /></section>
  }
  return <ScopedReportList mode="team" identity={identity} params={params} go={go} orgUnitId={selected.id} orgOptions={options.data} />
}

function ScopedReportList({ mode, identity, params, go, orgUnitId, orgOptions }: { mode: 'my' | 'team'; identity: RoleContext; params: RouteParams; go: AppNavigate; orgUnitId: string; orgOptions: DailyReportOrgOption[] }) {
  const currentDay = useScopedResource(
    `${identity.key}:current-business-day:${orgUnitId}:${params.businessDate ?? ''}`,
    (signal) => params.businessDate
      ? Promise.resolve({ businessDate: params.businessDate })
      : loadCurrentBusinessDay(identity, orgUnitId, signal),
    undefined as { businessDate: string } | undefined,
  )
  if (currentDay.loading) {
    return <section className={styles.page}><FeatureHeader eyebrow={mode === 'my' ? 'MY DAILY REPORTS' : 'TEAM DAILY REPORTS'} title={mode === 'my' ? '我的日报' : '团队日报'} description="正在按门店时区和营业日截止点解析当前营业日。" /><AsyncState loading /></section>
  }
  if (currentDay.error || !currentDay.data?.businessDate) {
    return <section className={styles.page}><FeatureHeader eyebrow={mode === 'my' ? 'MY DAILY REPORTS' : 'TEAM DAILY REPORTS'} title={mode === 'my' ? '我的日报' : '团队日报'} description="营业日由服务端统一解析，避免凌晨跨日时查询错误日期。" /><AsyncState loading={false} error={currentDay.error || new Error('当前营业日不可用')} onRetry={currentDay.reload} /></section>
  }
  return <ResolvedReportList key={`${identity.key}:${mode}:${orgUnitId}:${currentDay.data.businessDate}`} mode={mode} identity={identity} params={params} go={go} initialBusinessDate={currentDay.data.businessDate} orgUnitId={orgUnitId} orgOptions={orgOptions} />
}

function ResolvedReportList({ mode, identity, params, go, initialBusinessDate, orgUnitId, orgOptions }: { mode: 'my' | 'team'; identity: RoleContext; params: RouteParams; go: AppNavigate; initialBusinessDate: string; orgUnitId: string; orgOptions: DailyReportOrgOption[] }) {
  const [businessDate, setBusinessDate] = useState(initialBusinessDate)
  const [status, setStatus] = useState(params.status || '')
  const key = `${identity.key}:daily-reports:${mode}:${businessDate}:${status}:${orgUnitId}:${identity.businessActorAssignmentId ?? ''}`
  const resource = useScopedResource(key, (signal) => mode === 'my'
    ? loadMyDailyReports(identity, signal, { businessDate, status, positionAssignmentId: identity.businessActorAssignmentId })
    : loadTeamDailyReports(identity, signal, { businessDate, status, orgUnitId }), [], 30_000)
  const updateFilters = (nextDate: string, nextStatus: string) => {
    setBusinessDate(nextDate); setStatus(nextStatus)
    go(mode === 'my' ? 'daily-reports-my' : 'daily-reports-team', { ...params, businessDate: nextDate, status: nextStatus || undefined, orgUnitId: mode === 'team' ? orgUnitId : undefined })
  }
  return <section className={styles.page}>
    <FeatureHeader eyebrow={mode === 'my' ? 'MY DAILY REPORTS' : 'TEAM DAILY REPORTS'} title={mode === 'my' ? '我的日报' : '团队日报'} description={mode === 'my' ? '由当前岗位工作包驱动，按模块填报，不使用统一长表。' : '集中处理未提交、异常日报、待补充和修订审核。'} />
    <div className={styles.toolbar}>{mode === 'team' && <label>门店<select value={orgUnitId} onChange={(event) => go('daily-reports-team', { ...params, orgUnitId: event.target.value, businessDate: undefined })}>{orgOptions.map((option) => <option key={option.id} value={option.id}>{option.code ? `${option.code} · ` : ''}{option.name}</option>)}</select></label>}<label>营业日<input type="date" value={businessDate} onChange={(event) => updateFilters(event.target.value, status)} /></label><label>状态<select value={status} onChange={(event) => updateFilters(businessDate, event.target.value)}><option value="">全部</option><option value="DRAFT">草稿</option><option value="SUBMITTED">已提交</option><option value="ARCHIVED">已归档</option></select></label><button className="secondary" onClick={() => void resource.reload()}>刷新</button></div>
    <AsyncState loading={resource.loading} error={resource.error} empty={!resource.data.length} onRetry={resource.reload} emptyTitle="该营业日暂无日报" emptyDescription="这是有效空结果；若接口不可用，页面会显示单独的错误状态。" />
    {!resource.loading && !resource.error && <div className={styles.cardGrid}>{resource.data.map((report) => <ReportCard key={report.id} report={report} go={go} />)}</div>}
  </section>
}

function ReportCard({ report, go }: { report: DailyReportSummary; go: AppNavigate }) {
  const overdue = report.status === 'DRAFT' && Boolean(report.dueAt) && new Date(report.dueAt as string).getTime() < Date.now()
  const displayStatus = report.status === 'DRAFT' ? (overdue ? '已逾期' : '待填报') : '已提交'
  return <article className={styles.card}><header><div className={styles.meta}><span>{report.businessDate}</span><span>{report.positionName}</span></div><StatusBadge value={displayStatus} /></header><h2>{report.employeeName || '我的日报'}</h2><p>{report.templateName} · {report.templateVersionNo ? `V${report.templateVersionNo}` : '版本待解析'}</p><div className={styles.progress}><i style={{ width: `${Math.max(0, Math.min(100, report.completionRate ?? 0))}%` }} /></div><div className={styles.meta}><span>截止 {formatLocalDateTime(report.dueAt)}</span><span>完成 {report.completionRate ?? '—'}%</span><span>缺失 {report.missingRequiredCount ?? '—'}</span><span>异常 {report.exceptionCount ?? '—'}</span><span>证据 {report.evidenceCount ?? '—'}</span></div><footer><button className="primary" onClick={() => go('daily-report-detail', { reportId: report.id })}>{report.status === 'DRAFT' ? '立即填报' : '查看日报'}</button></footer></article>
}

function ReportDetailPage({ identity, grantedPermissions, reportId, correctionRevisionId, go }: { identity: RoleContext; grantedPermissions: string[]; reportId: string; correctionRevisionId?: string; go: AppNavigate }) {
  const resource = useScopedResource(`${identity.key}:daily-report:${reportId}`, (signal) => loadDailyReport(identity, reportId, signal), undefined as never)
  return <section className={styles.page}><FeatureHeader eyebrow="POSITION DAILY REPORT" title={resource.data ? `${resource.data.businessDate} · ${resource.data.positionName}日报` : '日报详情'} description="系统预填与员工补充明确区分；提交后原版本和证据锁定。" actions={<button className="secondary" onClick={() => go(hasPermission(grantedPermissions, permissions.dailyReport.readTeam) ? 'daily-reports-team' : 'daily-reports-my')}>返回列表</button>} /><AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />{resource.data && <ReportEditor key={`${resource.data.id}:${resource.data.currentRevision.id}`} report={resource.data} identity={identity} grantedPermissions={grantedPermissions} correctionRevisionId={correctionRevisionId} onChanged={resource.reload} go={go} />}</section>
}

type DailyReportLocalDraft = {
  schemaVersion: 1
  revisionId: string
  baseRowVersion: number
  values: Record<string, string>
  exceptions: string[]
}

function hasDailyReportDraftChanges(draft: DailyReportLocalDraft, initialValues: Record<string, string>, initialExceptions: Set<string>) {
  const valueKeys = new Set([...Object.keys(initialValues), ...Object.keys(draft.values)])
  if ([...valueKeys].some((key) => (draft.values[key] ?? '') !== (initialValues[key] ?? ''))) return true
  if (draft.exceptions.length !== initialExceptions.size) return true
  return draft.exceptions.some((id) => !initialExceptions.has(id))
}

function ReportEditor({ report, identity, grantedPermissions, correctionRevisionId, onChanged, go }: { report: DailyReportDetail; identity: RoleContext; grantedPermissions: string[]; correctionRevisionId?: string; onChanged: () => Promise<void>; go: AppNavigate }) {
  const initialValues = useMemo(() => Object.fromEntries(report.currentRevision.sections.flatMap((section) => section.items.map((item) => [item.id, item.employeeValue ?? '']))), [report])
  const initialExceptions = useMemo(() => new Set(report.currentRevision.sections.flatMap((section) => section.items.filter((item) => item.exception).map((item) => item.id))), [report])
  const [values, setValues] = useState<Record<string, string>>(initialValues)
  const [exceptions, setExceptions] = useState<Set<string>>(initialExceptions)
  const [message, setMessage] = useState<string>()
  const [draftNotice, setDraftNotice] = useState<string>()
  const [draftHydrated, setDraftHydrated] = useState(false)
  const [draftHydrationSettled, setDraftHydrationSettled] = useState(false)
  const [conflictingLocalDraft, setConflictingLocalDraft] = useState<DailyReportLocalDraft>()
  const [conflict, setConflict] = useState(false)
  const [reason, setReason] = useState('')
  const command = useStableCommand(`daily-report-${report.id}`)
  const editable = report.status === 'DRAFT' && report.currentRevision.status === 'DRAFT' && report.allowedActions.includes('EDIT') && hasPermission(grantedPermissions, permissions.dailyReport.submit)
  const localDraftKey = buildResumableDraftKey('daily-report-form', [identity.tenantId, identity.actorId, identity.businessActorAssignmentId, report.id, report.currentRevision.id])
  const localDraftRef = useRef<DailyReportLocalDraft>({
    schemaVersion: 1,
    revisionId: report.currentRevision.id,
    baseRowVersion: report.rowVersion,
    values: initialValues,
    exceptions: [...initialExceptions],
  })
  useEffect(() => {
    let active = true
    setDraftHydrated(false)
    setDraftHydrationSettled(false)
    setConflictingLocalDraft(undefined)
    setDraftNotice(undefined)
    void readResumableDraft<DailyReportLocalDraft>(localDraftKey).then((stored) => {
      if (!active) return
      if (!editable) {
        void removeResumableDraft(localDraftKey)
        return
      }
      if (stored?.schemaVersion === 1 && stored.revisionId === report.currentRevision.id && stored.baseRowVersion !== report.rowVersion) {
        setValues(initialValues)
        setExceptions(initialExceptions)
        localDraftRef.current = stored
        setConflictingLocalDraft(stored)
      } else if (stored?.schemaVersion === 1 && stored.revisionId === report.currentRevision.id) {
        setValues(stored.values)
        setExceptions(new Set(stored.exceptions))
        localDraftRef.current = stored
        setDraftNotice('已恢复上次未提交的日报内容。')
      } else {
        localDraftRef.current = {
          schemaVersion: 1,
          revisionId: report.currentRevision.id,
          baseRowVersion: report.rowVersion,
          values: initialValues,
          exceptions: [...initialExceptions],
        }
      }
      setDraftHydrated(true)
    }).catch((error) => {
      if (active) setMessage(`设备草稿暂时无法读取。为避免覆盖已保存内容，本页不会自动改写设备草稿。${error instanceof Error ? `（${error.message}）` : ''}`)
    }).finally(() => { if (active) setDraftHydrationSettled(true) })
    return () => { active = false }
  }, [editable, initialExceptions, initialValues, localDraftKey, report.currentRevision.id, report.rowVersion])
  const chooseServerDraft = () => {
    const snapshot: DailyReportLocalDraft = {
      schemaVersion: 1,
      revisionId: report.currentRevision.id,
      baseRowVersion: report.rowVersion,
      values: initialValues,
      exceptions: [...initialExceptions],
    }
    setValues(initialValues)
    setExceptions(initialExceptions)
    localDraftRef.current = snapshot
    setConflictingLocalDraft(undefined)
    setDraftNotice('已使用服务器上的最新日报草稿。')
    void removeResumableDraft(localDraftKey).catch((error) =>
      setMessage(error instanceof Error ? error.message : '本机日报草稿清除失败'))
  }
  const chooseLocalDraft = () => {
    if (!conflictingLocalDraft) return
    const restored: DailyReportLocalDraft = {
      ...conflictingLocalDraft,
      baseRowVersion: report.rowVersion,
    }
    setValues(restored.values)
    setExceptions(new Set(restored.exceptions))
    localDraftRef.current = restored
    setConflictingLocalDraft(undefined)
    setDraftNotice('已恢复本机未提交内容；保存时会基于服务器最新版本更新。')
    void writeResumableDraft(localDraftKey, restored).catch((error) =>
      setMessage(error instanceof Error ? error.message : '本机日报草稿暂存失败'))
  }
  useEffect(() => {
    if (!editable || !draftHydrated || conflictingLocalDraft) return
    const snapshot: DailyReportLocalDraft = {
      schemaVersion: 1,
      revisionId: report.currentRevision.id,
      baseRowVersion: report.rowVersion,
      values,
      exceptions: [...exceptions],
    }
    localDraftRef.current = snapshot
    const timeout = window.setTimeout(() => {
      const operation = hasDailyReportDraftChanges(snapshot, initialValues, initialExceptions)
        ? writeResumableDraft(localDraftKey, snapshot)
        : removeResumableDraft(localDraftKey)
      void operation.catch((error) =>
        setMessage(error instanceof Error ? error.message : '日报内容暂存失败'))
    }, 250)
    return () => window.clearTimeout(timeout)
  }, [conflictingLocalDraft, draftHydrated, editable, exceptions, initialExceptions, initialValues, localDraftKey, report.currentRevision.id, report.rowVersion, values])
  useEffect(() => {
    if (!editable || !draftHydrated || conflictingLocalDraft) return
    const flushDraft = () => {
      const snapshot = localDraftRef.current
      void (hasDailyReportDraftChanges(snapshot, initialValues, initialExceptions)
        ? writeResumableDraft(localDraftKey, snapshot)
        : removeResumableDraft(localDraftKey))
    }
    const flushWhenHidden = () => { if (document.visibilityState === 'hidden') flushDraft() }
    window.addEventListener('pagehide', flushDraft)
    document.addEventListener('visibilitychange', flushWhenHidden)
    return () => {
      window.removeEventListener('pagehide', flushDraft)
      document.removeEventListener('visibilitychange', flushWhenHidden)
      flushDraft()
    }
  }, [conflictingLocalDraft, draftHydrated, editable, initialExceptions, initialValues, localDraftKey])
  const input: DailyReportDraftInput = { itemValues: report.currentRevision.sections.flatMap((section) => section.items.map((item) => ({ templateItemId: item.templateItemId, value: values[item.id], exception: exceptions.has(item.id) }))) }
  const currentMissingRequiredCount = report.currentRevision.sections.flatMap((section) => section.items)
    .filter((item) => item.required && item.dataSource !== 'SYSTEM' && !exceptions.has(item.id)
      && !((values[item.id] ?? '').trim() || (item.systemValue ?? '').trim())).length
  const reviewingHistoricalRevision = Boolean(correctionRevisionId && correctionRevisionId !== report.currentRevision.id)
  const reviewPermission = report.currentRevision.revisionType === 'CORRECTION' ? permissions.dailyReport.reviewCorrection : permissions.dailyReport.reviewException
  const canReviewCurrentRevision = !reviewingHistoricalRevision && Boolean(identity.businessActorAssignmentId) && hasPermission(grantedPermissions, reviewPermission)
  const run = async (action: 'save' | 'submit' | 'correction' | 'approve' | 'reject') => {
    if (conflictingLocalDraft && (action === 'save' || action === 'submit')) {
      setMessage('服务器日报草稿与本机内容不一致，请先选择要继续使用的版本。')
      return
    }
    setMessage(undefined); setConflict(false)
    try {
      await command.run((key) => {
        if (action === 'save') return saveDailyReportDraft(identity, report, input, key)
        if (action === 'submit') return saveDailyReportDraft(identity, report, input, `${key}-save`)
          .then((saved) => submitDailyReport(identity, saved, `${key}-submit`))
        if (action === 'correction') return requestDailyReportCorrection(identity, report, reason, key)
        if (correctionRevisionId) return reviewDailyReportRevision(identity, report, correctionRevisionId, action === 'approve' ? 'APPROVED' : 'REJECTED', reason, key)
        return reviewDailyReport(identity, report, action === 'approve' ? 'APPROVED' : 'REJECTED', reason, key)
      })
      if (action === 'save' || action === 'submit') await removeResumableDraft(localDraftKey)
      await onChanged()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) { setConflict(true); setMessage('服务器已有新版本；你的本地填写内容仍保留。请查看最新版本后重新应用修改。') }
      else setMessage(error instanceof Error ? error.message : '操作失败')
    }
  }
  const toggleException = (id: string) => setExceptions((current) => { const next = new Set(current); if (next.has(id)) next.delete(id); else next.add(id); return next })
  if (editable && !draftHydrationSettled) {
    return <section className={styles.section}><div className="state-card"><div className="spinner" /><strong>正在恢复未提交的日报内容</strong><small>恢复完成前暂不开放编辑，避免覆盖本机草稿。</small></div></section>
  }
  if (conflictingLocalDraft) {
    return <section className={styles.section}><div className="inline-warning"><strong>发现两个版本的未提交日报</strong><p>服务器日报已在其他页面或设备更新，本机仍保留一份未提交内容。请选择一个版本后再继续，系统不会自动覆盖。</p><div className={styles.actions}><button className="secondary" onClick={chooseServerDraft}>使用服务器最新草稿</button><button className="secondary" onClick={chooseLocalDraft}>恢复本机未提交内容</button></div></div></section>
  }
  return <div className={styles.split}><main className={styles.stack}><section className={styles.section}><header><h2>填报内容</h2><div className={styles.actions}><StatusBadge value={report.status} /><StatusBadge value={report.reviewStatus} /></div></header>{draftNotice && <div className="inline-success">{draftNotice}</div>}{!editable && <div className={styles.locked}>日报已提交或当前任职无编辑权限，原始填报内容只读。需要修改时发起修订，不覆盖历史版本。</div>}{report.currentRevision.sections.filter((section) => section.applicable).map((section) => <section className={styles.reportGroup} key={section.id}><header><strong>{section.name}</strong>{section.conditional && <span className={styles.badge}>条件模块</span>}</header>{section.items.map((item) => <div className={styles.reportItem} key={item.id}><label>{item.label}{item.required ? ' *' : ''}{item.resultStatus === 'PENDING' && item.systemValue !== undefined && <span className={styles.badge}>已提交待审核</span>}{item.systemValue !== undefined && <span className={styles.locked}>系统预填：{item.systemValue}</span>}<textarea rows={item.inputType === 'NARRATIVE' ? 4 : 2} disabled={!editable || item.dataSource === 'SYSTEM'} value={values[item.id] ?? ''} onChange={(event) => setValues({ ...values, [item.id]: event.target.value })} /></label><div className={styles.actions}><label><input type="checkbox" disabled={!editable} checked={exceptions.has(item.id)} onChange={() => toggleException(item.id)} /> 标记异常</label><span className={styles.meta}>{item.evidenceRequired ? '需要证据' : '证据可选'} · 来源 {item.sourceLabels.join('、') || '员工填报'}</span></div>{item.evidence.map((evidence) => <div className={styles.meta} key={evidence.id}><span>{evidence.fileName}</span><span>{evidence.sensitivity === 'SENSITIVE' ? '敏感证据' : '普通证据'}</span><span>{evidence.invalidatedAt ? '已失效，历史保留' : evidence.scanStatus || '已上传'}</span></div>)}</div>)}</section>)}</section>{conflict && <div className={styles.conflict}>{message}</div>}{message && !conflict && <div className="inline-error">{message}</div>}<section className={styles.section}><div className={styles.actions}>{editable && <button className="secondary" disabled={command.busy} onClick={() => void run('save')}>保存草稿</button>}{editable && report.allowedActions.includes('SUBMIT') && <button className="primary" disabled={command.busy || currentMissingRequiredCount > 0} onClick={() => void run('submit')}>提交日报</button>}{!editable && report.allowedActions.includes('REQUEST_CORRECTION') && <button className="secondary" disabled={command.busy || !reason.trim()} onClick={() => void run('correction')}>发起修订</button>}</div>{!editable && report.allowedActions.includes('REQUEST_CORRECTION') && <label className={styles.formGrid}>修订原因<textarea rows={2} value={reason} onChange={(event) => setReason(event.target.value)} /></label>}{report.blockedActionReasons.SUBMIT && <p className={styles.locked}>{report.blockedActionReasons.SUBMIT}</p>}</section></main><aside className={styles.stack}><section className={styles.section}><h2>日报上下文</h2><div className={styles.meta}><span>模板 {report.templateVersionNo ? `V${report.templateVersionNo}` : '版本待解析'}</span><span>完成 {report.completionRate ?? '—'}%</span><span>异常 {report.exceptionCount ?? '—'}</span><span>证据 {report.evidenceCount ?? '—'}</span></div><p>截止：{formatLocalDateTime(report.dueAt)}</p><div className={styles.timeline}>{report.revisions.map((revision) => <article key={revision.id}><strong>修订 V{revision.revisionNo} · {revision.status}</strong><small>{formatLocalDateTime(revision.submittedAt)} {revision.correctionReason || ''}</small>{revision.id !== report.currentRevision.id && <button className="link-button" onClick={() => go('daily-report-correction', { reportId: report.id, revisionId: revision.id })}>查看修订</button>}</article>)}</div></section>{reviewingHistoricalRevision && <div className={styles.locked}>历史修订只读；后端仅允许审核当前修订。</div>}{canReviewCurrentRevision && (report.allowedActions.includes('REVIEW_APPROVE') || report.allowedActions.includes('REVIEW_REJECT')) && <section className={styles.section}><h2>主管审核</h2><label className={styles.formGrid}>审核意见<textarea rows={3} value={reason} onChange={(event) => setReason(event.target.value)} /></label><div className={styles.actions}>{report.allowedActions.includes('REVIEW_APPROVE') && <button className="primary" disabled={command.busy || !reason.trim()} onClick={() => void run('approve')}>确认通过</button>}{report.allowedActions.includes('REVIEW_REJECT') && <button className="danger-button" disabled={command.busy || !reason.trim()} onClick={() => void run('reject')}>退回补充</button>}</div><p className={styles.meta}>是否允许审核由服务端依据任职、组织范围和禁止自审规则返回。</p></section>}{report.aiRecommendations.map((item) => <AiRecommendationCard key={item.id} facts={item.facts} analysis={item.analysis} recommendation={item.recommendation} sources={item.sourceLabels} actions={<button className="secondary" disabled>采纳后生成可编辑草稿</button>} />)}</aside></div>
}
