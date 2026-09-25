import { WorkUsageFields } from '../storeResources/WorkUsageFields'
import { usageValidation, usagePayload, loadUsageCatalogue, type UsageCatalogue } from '../storeResources/workUsage'
import { dailyUsagePayload, dailyUsageValidation } from '../storeResources/dailyUsage'
import { HousekeepingReportForm } from './HousekeepingReportForm'
import { emptyActivities, readActivities, validateActivities, type HousekeepingActivities } from './housekeepingActivities'
import housekeepingStyles from './housekeepingReport.module.css'
import { DailyReportAutoSummary } from './DailyReportAutoSummary'
import { PlannedHygieneFields } from './PlannedHygieneFields'
import editorStyles from './editor.module.css'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { hasPermission, permissions } from '../../app/permissions'
import { buildResumableDraftKey, readResumableDraft, removeResumableDraft, writeResumableDraft } from '../../app/resumableDrafts'
import type { AppNavigate, DailyFeatureRouteId } from '../../app/routeConfig'
import type { RoleContext, RouteParams } from '../../domain'
import { AsyncState } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { useStableCommand } from '../../shared/useStableCommand'
import { FeatureHeader, StatusBadge, featureStyles as styles, formatLocalDateTime } from '../shared/FeatureUI'
import { loadCurrentBusinessDay, loadDailyReport, loadDailyReportOrgOptions, loadMyDailyReports, loadTeamDailyReports, requestDailyReportCorrection, reviewDailyReport, reviewDailyReportRevision, saveDailyReportDraft, submitDailyReport, type DailyReportOrgOption } from './api'
import type { DailyReportDetail, DailyReportDraftInput, DailyReportSummary } from './types'

const dailyReportStatusLabels: Record<string, string> = { DRAFT: '草稿', SUBMITTED: '已提交', ARCHIVED: '已归档', NOT_REQUIRED: '无需审核', PENDING: '待审核', APPROVED: '已通过', REJECTED: '已退回' }

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
  useEffect(() => {
    if (mode === 'my' && params.open === 'today' && !resource.loading && !resource.error && resource.data.length === 1)
      go('daily-report-detail', { reportId: resource.data[0].id })
  }, [mode, params.open, resource.loading, resource.error, resource.data, go])
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
  const noticeScope = `${identity.key}:${reportId}`
  const [savedNotice, setSavedNotice] = useState<{ scope: string; text: string }>()
  const onChanged = async (notice?: string) => {
    // Reload unmounts the editor. Keep the acknowledged save result at the page level.
    setSavedNotice(notice ? { scope: noticeScope, text: notice } : undefined)
    await resource.reload()
  }
  const housekeeping = resource.data?.plannedHygieneSupported || Boolean(resource.data?.inventoryContext)
  const back = <button className="link-button" onClick={() => go(hasPermission(grantedPermissions, permissions.dailyReport.readTeam) ? 'daily-reports-team' : 'daily-reports-my')}>返回列表</button>
  return <section className={styles.page}>{housekeeping
    ? <header className={housekeepingStyles.reportHeader}><div><h1>{resource.data.positionName}日报</h1><p>{resource.data.hotelName || identity.orgName || ''} · {resource.data.businessDate}</p></div>{back}</header>
    : <FeatureHeader eyebrow="POSITION DAILY REPORT" title={resource.data ? `${resource.data.businessDate} · ${resource.data.positionName}日报` : '日报详情'} description="系统预填与员工补充明确区分；提交后原版本和证据锁定。" actions={back} />}
    {savedNotice?.scope === noticeScope && <div className="inline-success" role="status">{savedNotice.text}</div>}
    <AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />{resource.data && <ReportEditor key={`${resource.data.id}:${resource.data.currentRevision.id}`} report={resource.data} identity={identity} grantedPermissions={grantedPermissions} correctionRevisionId={correctionRevisionId} onChanged={onChanged} go={go} />}</section>
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

function wasDraftSaved(draft: DailyReportLocalDraft, saved?: DailyReportLocalDraft) {
  return Boolean(saved && draft.revisionId === saved.revisionId && draft.baseRowVersion === saved.baseRowVersion
    && !hasDailyReportDraftChanges(draft, saved.values, new Set(saved.exceptions)))
}

function ReportEditor({ report, identity, grantedPermissions, correctionRevisionId, onChanged, go }: { report: DailyReportDetail; identity: RoleContext; grantedPermissions: string[]; correctionRevisionId?: string; onChanged: (notice?: string) => Promise<void>; go: AppNavigate }) {
  const initialValues = useMemo(() => ({ ...Object.fromEntries(report.currentRevision.sections.flatMap((section) => section.items.map((item) => [item.id, item.employeeValue ?? '']))), _inventory: JSON.stringify(report.inventoryQuantities ?? null), _checkoutRooms: String(report.roomCounts?.checkoutRooms ?? ''), _stayoverRooms: String(report.roomCounts?.stayoverRooms ?? ''), _plannedHygieneContent: report.plannedHygiene?.content ?? '', _plannedHygieneRoomNumbers: report.plannedHygiene?.roomNumbers ?? '', _activities: JSON.stringify(report.housekeepingActivities ?? emptyActivities()) }), [report])
  const initialExceptions = useMemo(() => new Set(report.currentRevision.sections.flatMap((section) => section.items.filter((item) => item.exception).map((item) => item.id))), [report])
  const [values, setValues] = useState<Record<string, string>>(initialValues)
  const [pendingPhotoEntries, setPendingPhotoEntries] = useState<Set<string>>(new Set())
  const onPhotosPending = useCallback((id: string, pending: boolean) => setPendingPhotoEntries(current => {
    if (current.has(id) === pending) return current
    const next = new Set(current); if (pending) next.add(id); else next.delete(id); return next
  }), [])
  const [exceptions, setExceptions] = useState<Set<string>>(initialExceptions)
  const [message, setMessage] = useState<string>()
  const [draftNotice, setDraftNotice] = useState<string>()
  const [refreshedCatalogue, setRefreshedCatalogue] = useState<{ reportVersion: number; catalogue: UsageCatalogue }>()
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
  const savedDraftRef = useRef<DailyReportLocalDraft | undefined>(undefined)
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
        setValues(initialValues)
        setExceptions(initialExceptions)
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
      if (wasDraftSaved(snapshot, savedDraftRef.current)) return
      const operation = hasDailyReportDraftChanges(snapshot, initialValues, initialExceptions)
        ? writeResumableDraft(localDraftKey, snapshot)
        : removeResumableDraft(localDraftKey)
      void operation.then(() => {
        if (localDraftRef.current === snapshot && hasDailyReportDraftChanges(snapshot, initialValues, initialExceptions)) setDraftNotice('草稿已暂存（本机）')
      }).catch((error) => {
        setDraftNotice(undefined)
        setMessage(error instanceof Error ? error.message : '日报内容暂存失败')
      })
    }, 250)
    return () => window.clearTimeout(timeout)
  }, [conflictingLocalDraft, draftHydrated, editable, exceptions, initialExceptions, initialValues, localDraftKey, report.currentRevision.id, report.rowVersion, values])
  useEffect(() => {
    if (!editable || !draftHydrated || conflictingLocalDraft) return
    const flushDraft = () => {
      const snapshot = localDraftRef.current
      // A refresh/unmount after a successful save must not resurrect the old local version.
      if (wasDraftSaved(snapshot, savedDraftRef.current)) return
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
  let inventoryValue: unknown
  try { inventoryValue = JSON.parse(values._inventory || 'null') } catch { inventoryValue = undefined }
  const housekeeping = report.plannedHygieneSupported || Boolean(report.inventoryContext)
  let activities: HousekeepingActivities | undefined
  let activitiesError: string | undefined
  try { activities = readActivities(values._activities) } catch (error) { activitiesError = error instanceof Error ? error.message : '遗留物草稿读取失败' }
  const updateActivities = (update: (current: HousekeepingActivities) => HousekeepingActivities) => setValues(current => ({
    ...current, _activities: JSON.stringify(update(readActivities(current._activities))),
  }))
  const usageCatalogue = refreshedCatalogue?.reportVersion === report.rowVersion ? refreshedCatalogue.catalogue : report.inventoryContext?.catalogue
  const refreshCatalogue = async () => {
    if (!usageCatalogue) return
    try {
      const catalogue = await loadUsageCatalogue(identity, usageCatalogue.hotelOrgUnitId, undefined, { businessDate: report.businessDate, itemCode: 'HKA_DAILY_REPORT' })
      setRefreshedCatalogue({ reportVersion: report.rowVersion, catalogue })
      setMessage('物品清单已刷新，已填写的数量和补充内容仍保留；请核对配置有变化的物品。')
    } catch (error) { setMessage(error instanceof Error ? error.message : '清单刷新失败，已填内容仍保留。') }
  }
  const roomCount = (key: string) => values[key]?.trim() ? Number(values[key]) : undefined
  const input: DailyReportDraftInput = {
    housekeepingActivities: housekeeping ? activities : undefined,
    inventoryQuantities: usageCatalogue ? (housekeeping ? dailyUsagePayload(inventoryValue, usageCatalogue) : usagePayload(inventoryValue, usageCatalogue)) : undefined,
    roomCounts: usageCatalogue ? { checkoutRooms: roomCount('_checkoutRooms'), stayoverRooms: roomCount('_stayoverRooms') } : undefined,
    plannedHygiene: report.plannedHygieneSupported ? { content: values._plannedHygieneContent ?? '', roomNumbers: values._plannedHygieneRoomNumbers ?? '' } : undefined,
    itemValues: report.currentRevision.sections.flatMap((section) => section.items.filter((item) => item.dataSource !== 'SYSTEM').map((item) => ({ templateItemId: item.templateItemId, value: values[item.id], exception: false }))) }
  const currentMissingRequiredCount = report.currentRevision.sections.flatMap((section) => section.items)
    .filter((item) => item.required && item.dataSource !== 'SYSTEM'
      && !((values[item.id] ?? '').trim() || (item.systemValue ?? '').trim())).length
  const reviewingHistoricalRevision = Boolean(correctionRevisionId && correctionRevisionId !== report.currentRevision.id)
  const reviewPermission = report.currentRevision.revisionType === 'CORRECTION' ? permissions.dailyReport.reviewCorrection : permissions.dailyReport.reviewException
  const canReviewCurrentRevision = !reviewingHistoricalRevision && Boolean(identity.businessActorAssignmentId) && hasPermission(grantedPermissions, reviewPermission)
  const run = async (action: 'save' | 'submit' | 'correction' | 'approve' | 'reject') => {
    if ((action === 'save' || action === 'submit') && housekeeping) {
      if (pendingPhotoEntries.size) { setMessage('仍有照片等待上传，请完成上传或移除后再保存、提交。'); return }
      if (activitiesError) { setMessage(activitiesError); return }
      if (action === 'submit' && activities) { const error = validateActivities(activities); if (error) { setMessage(error); return } }
    }
    if (conflictingLocalDraft && (action === 'save' || action === 'submit')) {
      setMessage('服务器日报草稿与本机内容不一致，请先选择要继续使用的版本。')
      return
    }
    if (action === 'submit' && usageCatalogue) {
      const error = usageCatalogue.enabled ? (housekeeping ? dailyUsageValidation(inventoryValue, usageCatalogue) : usageValidation(inventoryValue, usageCatalogue)) : '门店尚未配置耗用审核人，请联系管理员配置后再提交。'
      if (error) { setMessage(error); return }
      if (['_checkoutRooms', '_stayoverRooms'].some(key => roomCount(key) === undefined || !Number.isSafeInteger(roomCount(key)) || roomCount(key)! < 0)) {
        setMessage('请填写退房打扫数、续住房打扫数，数量为非负整数。'); return
      }
    }
    setMessage(undefined); setConflict(false)
    try {
      const submittedDraft: DailyReportLocalDraft = { ...localDraftRef.current, values: { ...values }, exceptions: [...exceptions] }
      const result = await command.run((key) => {
        if (action === 'save') return saveDailyReportDraft(identity, report, input, key)
        if (action === 'submit') return saveDailyReportDraft(identity, report, input, `${key}-save`)
          .then((saved) => submitDailyReport(identity, saved, `${key}-submit`))
        if (action === 'correction') return requestDailyReportCorrection(identity, report, reason, key)
        if (correctionRevisionId) return reviewDailyReportRevision(identity, report, correctionRevisionId, action === 'approve' ? 'APPROVED' : 'REJECTED', reason, key)
        return reviewDailyReport(identity, report, action === 'approve' ? 'APPROVED' : 'REJECTED', reason, key)
      })
      if (!result) return
      if (action === 'save' || action === 'submit') {
        savedDraftRef.current = submittedDraft
        await removeResumableDraft(localDraftKey)
      }
      await onChanged(action === 'save' ? '草稿已保存到服务器' : undefined)
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) { setConflict(true); setMessage('服务器已有新版本；你的本地填写内容仍保留。请查看最新版本后重新应用修改。') }
      else setMessage(error instanceof Error ? error.message : '操作失败')
    }
  }
  if (editable && !draftHydrationSettled) {
    return <section className={styles.section}><div className="state-card"><div className="spinner" /><strong>正在恢复未提交的日报内容</strong><small>恢复完成前暂不开放编辑，避免覆盖本机草稿。</small></div></section>
  }
  if (conflictingLocalDraft) {
    return <section className={styles.section}><div className="inline-warning"><strong>发现两个版本的未提交日报</strong><p>服务器日报已在其他页面或设备更新，本机仍保留一份未提交内容。请选择一个版本后再继续，系统不会自动覆盖。</p><div className={styles.actions}><button className="secondary" onClick={chooseServerDraft}>使用服务器最新草稿</button><button className="secondary" onClick={chooseLocalDraft}>恢复本机未提交内容</button></div></div></section>
  }
  if (housekeeping) return <HousekeepingReportForm report={report} values={values} editable={editable} busy={command.busy}
    identity={identity} activities={activities} activitiesError={activitiesError} onActivitiesChange={updateActivities} onPhotosPending={onPhotosPending} photosPending={pendingPhotoEntries.size > 0}
    catalogue={usageCatalogue} inventoryValue={inventoryValue} notice={draftNotice} message={message} conflict={conflict}
    missingRequiredCount={currentMissingRequiredCount} setValue={(key,value) => setValues(current => ({ ...current, [key]: value }))}
    onRefreshCatalogue={() => void refreshCatalogue()} onLegacyDraft={() => go('my-work', { expectationId: report.inventoryContext!.expectationId, entry: 'legacy' })}
    onSave={() => void run('save')} onSubmit={() => void run('submit')}>
    {!editable && report.allowedActions.includes('REQUEST_CORRECTION') && <section className={styles.section}><label className={styles.formGrid}>修订原因<textarea rows={2} value={reason} onChange={event => setReason(event.target.value)} /></label><button className="secondary" disabled={command.busy || !reason.trim()} onClick={() => void run('correction')}>发起修订</button></section>}
    {report.revisions.filter(revision => revision.id !== report.currentRevision.id).map(revision => <button key={revision.id} className="link-button" onClick={() => go('daily-report-correction', { reportId: report.id, revisionId: revision.id })}>查看修订 {revision.revisionNo}</button>)}
    {reviewingHistoricalRevision && <p>历史修订只读；后端仅允许审核当前修订。</p>}
    {canReviewCurrentRevision && (report.allowedActions.includes('REVIEW_APPROVE') || report.allowedActions.includes('REVIEW_REJECT')) && <section className={styles.section}><h2>主管审核</h2><label className={styles.formGrid}>审核意见<textarea rows={3} value={reason} onChange={event => setReason(event.target.value)} /></label><div className={styles.actions}>{report.allowedActions.includes('REVIEW_APPROVE') && <button className="primary" disabled={command.busy || !reason.trim()} onClick={() => void run('approve')}>确认通过</button>}{report.allowedActions.includes('REVIEW_REJECT') && <button className="danger-button" disabled={command.busy || !reason.trim()} onClick={() => void run('reject')}>退回补充</button>}</div></section>}
  </HousekeepingReportForm>
  return <div className={`${styles.split} ${editorStyles.editor}`}><main className={styles.stack}><DailyReportAutoSummary report={report} section="work" />
    {(editable ? report.plannedHygieneSupported : report.plannedHygiene != null) && <PlannedHygieneFields
      value={editable ? input.plannedHygiene : report.plannedHygiene} disabled={command.busy}
      onChange={editable ? value => setValues(current => ({ ...current,
        _plannedHygieneContent: value.content ?? '', _plannedHygieneRoomNumbers: value.roomNumbers ?? '' })) : undefined} />}
    {editable && usageCatalogue ? <section className={styles.section}>
      {report.inventoryContext?.legacyDraft && <div className="inline-warning">原日清入口还有未提交草稿。请先完成原草稿，再返回日报引用同一份用量。<button className="secondary" onClick={() => go('my-work', { expectationId: report.inventoryContext!.expectationId, entry: 'legacy' })}>继续原草稿</button></div>}
      <WorkUsageFields catalogue={usageCatalogue} value={inventoryValue} disabled={command.busy || report.inventoryContext?.legacyDraft} onChange={value => setValues(current => ({ ...current, _inventory: JSON.stringify(value) }))} onRefresh={() => void onChanged()} />
      <fieldset disabled={command.busy} className={styles.formGrid}><label>退房打扫数量 *<input type="number" inputMode="numeric" min="0" step="1" value={values._checkoutRooms ?? ''} onChange={event => setValues(current => ({ ...current, _checkoutRooms: event.target.value }))} /></label><label>续住房打扫数量 *<input type="number" inputMode="numeric" min="0" step="1" value={values._stayoverRooms ?? ''} onChange={event => setValues(current => ({ ...current, _stayoverRooms: event.target.value }))} /></label></fieldset></section>
      : <><DailyReportAutoSummary report={report} section="usage" />{report.roomCounts && <section className={styles.section}><h2>做房数量</h2><p>退房打扫 {report.roomCounts.checkoutRooms ?? '未登记'} 间 · 续住打扫 {report.roomCounts.stayoverRooms ?? '未登记'} 间</p></section>}</>}<section className={styles.section}><header><h2>补充填报内容</h2><div className={styles.actions}><StatusBadge value={dailyReportStatusLabels[report.status] || report.status} /><StatusBadge value={dailyReportStatusLabels[report.reviewStatus] || report.reviewStatus} /></div></header>{draftNotice && <div className="inline-success">{draftNotice}</div>}{!editable && <div className={styles.locked}>日报已提交或当前任职无编辑权限，原始填报内容只读。需要修改时发起修订，不覆盖历史版本。</div>}{report.currentRevision.sections.filter((section) => section.applicable && section.items.some((item) => item.dataSource !== 'SYSTEM')).map((section) => <section className={styles.reportGroup} key={section.id}><header><strong>{section.name}</strong>{section.conditional && <span className={styles.badge}>条件模块</span>}</header>{section.items.filter((item) => item.dataSource !== 'SYSTEM').map((item) => <div className={styles.reportItem} key={item.id}><label>{item.label}{item.required ? ' *' : ''}{item.resultStatus === 'PENDING' && item.systemValue !== undefined && <span className={styles.badge}>已提交待审核</span>}{item.systemValue !== undefined && <span className={styles.locked}>系统预填：{item.systemValue}</span>}<textarea rows={item.inputType === 'NARRATIVE' ? 4 : 2} disabled={!editable || command.busy || item.dataSource === 'SYSTEM'} value={values[item.id] ?? ''} onChange={(event) => setValues({ ...values, [item.id]: event.target.value })} /></label><div className={styles.actions}>{!editable && exceptions.has(item.id) && <span className={styles.warning}>历史记录含异常标记</span>}<span className={styles.meta}>{item.evidenceRequired ? '需要证据' : '证据可选'} · 来源 {item.sourceLabels.join('、') || '员工填报'}</span></div>{item.evidence.map((evidence) => <div className={styles.meta} key={evidence.id}><span>{evidence.fileName}</span><span>{evidence.sensitivity === 'SENSITIVE' ? '敏感证据' : '普通证据'}</span><span>{evidence.invalidatedAt ? '已失效，历史保留' : evidence.scanStatus || '已上传'}</span></div>)}</div>)}</section>)}</section><DailyReportAutoSummary report={report} section="ai" />{conflict && <div className={styles.conflict}>{message}</div>}{message && !conflict && <div className="inline-error">{message}</div>}<section className={styles.section}><div className={styles.actions}>{editable && <button className="secondary" disabled={command.busy} onClick={() => void run('save')}>保存草稿</button>}{editable && report.allowedActions.includes('SUBMIT') && <button className="primary" disabled={command.busy || currentMissingRequiredCount > 0} onClick={() => void run('submit')}>提交日报</button>}{!editable && report.allowedActions.includes('REQUEST_CORRECTION') && <button className="secondary" disabled={command.busy || !reason.trim()} onClick={() => void run('correction')}>发起修订</button>}</div>{!editable && report.allowedActions.includes('REQUEST_CORRECTION') && <label className={styles.formGrid}>修订原因<textarea rows={2} value={reason} onChange={(event) => setReason(event.target.value)} /></label>}{report.blockedActionReasons.SUBMIT && <p className={styles.locked}>{report.blockedActionReasons.SUBMIT}</p>}</section></main><aside className={styles.stack}><section className={styles.section}><h2>日报上下文</h2><div className={styles.meta}><span>模板 {report.templateVersionNo ? `V${report.templateVersionNo}` : '版本待解析'}</span><span>完成 {report.completionRate ?? '—'}%</span><span>异常 {report.exceptionCount ?? '—'}</span><span>证据 {report.evidenceCount ?? '—'}</span></div><p>截止：{formatLocalDateTime(report.dueAt)}</p><div className={styles.timeline}>{report.revisions.map((revision) => <article key={revision.id}><strong>修订 V{revision.revisionNo} · {dailyReportStatusLabels[revision.status] || revision.status}</strong><small>{formatLocalDateTime(revision.submittedAt)} {revision.correctionReason || ''}</small>{revision.id !== report.currentRevision.id && <button className="link-button" onClick={() => go('daily-report-correction', { reportId: report.id, revisionId: revision.id })}>查看修订</button>}</article>)}</div></section>{reviewingHistoricalRevision && <div className={styles.locked}>历史修订只读；后端仅允许审核当前修订。</div>}{canReviewCurrentRevision && (report.allowedActions.includes('REVIEW_APPROVE') || report.allowedActions.includes('REVIEW_REJECT')) && <section className={styles.section}><h2>主管审核</h2><label className={styles.formGrid}>审核意见<textarea rows={3} value={reason} onChange={(event) => setReason(event.target.value)} /></label><div className={styles.actions}>{report.allowedActions.includes('REVIEW_APPROVE') && <button className="primary" disabled={command.busy || !reason.trim()} onClick={() => void run('approve')}>确认通过</button>}{report.allowedActions.includes('REVIEW_REJECT') && <button className="danger-button" disabled={command.busy || !reason.trim()} onClick={() => void run('reject')}>退回补充</button>}</div><p className={styles.meta}>是否允许审核由服务端依据任职、组织范围和禁止自审规则返回。</p></section>}</aside></div>
}
