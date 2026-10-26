import { useEffect, useMemo, useRef, useState } from 'react'
import { demoFallbackEnabled } from './api/client'
import {
  createCorrectiveTask,
  createWorkRecordEvaluation,
  loadAttachmentContent,
  loadAccessibleHotels,
  loadHotelDashboard,
  loadOperationsDashboard,
  loadExpectation,
  loadTeamWorkCase,
  reviewWorkRecord,
} from './api/resources'
import type {
  ApiSource,
  HotelDashboard,
  ManagementTask,
  Navigate,
  RoleContext,
  RouteParams,
  TeamWorkCase,
  WorkExpectation,
  WorkRecordAttachment,
} from './domain'
import { useResource } from './useResource'
import { UsageEvidenceSummary } from './features/storeResources/WorkUsagePanel'
import type { NavigationParams } from './domain'
import { useWorkBoard, WorkDateToolbar, WorkStatusCards, CompletionRates, WorkQueueAlerts, WorkBoardList } from './features/workbench/WorkBoard'
import { withBoardParams } from './features/workbench/workBoardModel'
import { loadWorkBoard } from './features/workbench/workBoardApi'
import { spotCheckImpactFromBoard, spotCheckPreviewParams, type SpotCheckPreview } from './features/workbench/spotCheckPreview'
import './features/workbench/roleDailyWorkflow.css'
import { AiEvidenceRecord, AiEvidenceTeam } from './features/aiEvidence/AiEvidenceReview'

const statusLabels: Record<string, string> = {
  PLANNED: '待开放', AVAILABLE: '可填报', PENDING: '待完成', IN_PROGRESS: '执行中',
  SUBMITTED: '已提交', APPROVED: '复核通过', REJECTED: '已退回', SATISFIED: '已达标',
  FAILED: '未达标', MISSED: '已漏交', COMPLETED: '已完成', OVERDUE: '已逾期',
  PROPOSED: '待派发', PENDING_ACK: '待确认', RESULT_SUBMITTED: '结果已提交',
  AWAITING_REVIEW: '待验收', REWORK: '返工中', CANCELLED: '已取消',
  PASS: '通过', WARN: '预警', WARNING: '预警', FAIL: '不通过', OPEN: '待处理',
  ON_TIME: '正常', DUE_SOON: '即将到期', ESCALATED: '已升级',
  LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急', CLEAN: '安全', PENDING_SCAN: '待扫描',
}

function label(value?: string) {
  return value ? statusLabels[value] ?? value : '—'
}

function formatDate(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function Status({ value }: { value: string }) {
  return <span className={`status-pill ${value.toLowerCase().replaceAll('_', '-')}`}>{label(value)}</span>
}

function SourceFlag({ source }: { source: ApiSource }) {
  return <span className={`source-flag ${source}`}>{source === 'demo' ? '演示回退' : '实时 API'}</span>
}

function LoadingState({ loading, error, empty, retry }: { loading: boolean; error?: string; empty?: boolean; retry: () => void }) {
  if (loading) return <div className="state-card"><div className="spinner" /><strong>正在读取业务数据</strong><span>数据范围由服务端权限决定</span></div>
  if (error) return <div className="state-card error-state"><b>!</b><strong>数据读取失败</strong><span>{error}</span><button className="secondary" onClick={retry}>重新加载</button></div>
  if (empty) return <div className="state-card"><b>◇</b><strong>当前范围暂无数据</strong><span>系统不会用演示数据覆盖真实空结果。</span></div>
  return null
}

function PageHeader({ eyebrow, title, description, source, actions }: { eyebrow: string; title: string; description: string; source: ApiSource; actions?: React.ReactNode }) {
  return <header className="page-title"><div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p></div><div className="page-actions"><SourceFlag source={source} />{actions}</div></header>
}

export function TeamWorkPage({ identity, permissions, routeParams = {}, go }: { identity: RoleContext; permissions: string[]; routeParams?: RouteParams; go: Navigate }) {
  const [aiReviewOpen, setAiReviewOpen] = useState(false)
  const resource = useWorkBoard(identity, 'team', routeParams)
  const [selected, setSelected] = useState<WorkExpectation>()
  const [detailError, setDetailError] = useState<string>()
  const change = (patch: NavigationParams) => go('team-work', withBoardParams(routeParams, patch))
  useEffect(() => {
    let active = true
    setSelected(undefined); setDetailError(undefined)
    if (routeParams.expectationId) {
      loadExpectation(identity, routeParams.expectationId).then(result => { if (active) setSelected(result.data) })
        .catch(reason => { if (active) setDetailError(reason instanceof Error ? reason.message : '详情读取失败') })
    }
    return () => { active = false }
  }, [identity.key, routeParams.expectationId])
  const departmentGroups = resource.data.groups.filter(group => group.dimension === 'department' && group.group_id)
  const employeeGroups = resource.data.groups.filter(group => group.dimension === 'employee' && group.group_id)
  if (aiReviewOpen) return <AiEvidenceTeam key={identity.key} identity={identity} hotelId={routeParams.hotelId} permissions={permissions} onClose={() => setAiReviewOpen(false)} />
  return <section className="wb-page">
    <header className="wb-page-heading"><div><h1>团队工作看板</h1><p>按原工作日期汇总；待审核独立于完成，补交保留完整证据链。</p></div>
      <div className="page-actions"><button className="secondary" onClick={() => setAiReviewOpen(true)}>仪容仪表复核</button><button className="secondary" onClick={() => go('workbench', withBoardParams(routeParams, { status: undefined, queue: undefined, departmentId: undefined, assignmentId: undefined }))}>返回工作台</button></div></header>
    <WorkDateToolbar params={routeParams} businessDate={resource.data.businessDate} onChange={change} />
    <div className="wb-scope-filters">
      <label>部门 <select aria-label="部门筛选" value={routeParams.departmentId || ''} onChange={event => change({ departmentId: event.target.value || undefined, assignmentId: undefined })}>
        <option value="">全部授权部门</option>{departmentGroups.map(group => <option value={group.group_id!} key={group.group_id}>{group.group_name}</option>)}
      </select></label>
      <label>员工 <select aria-label="员工筛选" value={routeParams.assignmentId || ''} onChange={event => change({ assignmentId: event.target.value || undefined })}>
        <option value="">全部授权员工</option>{employeeGroups.map(group => <option value={group.group_id!} key={group.group_id}>{group.group_name}</option>)}
      </select></label>
      {routeParams.hotelId && <button className="text-action" onClick={() => change({ hotelId: undefined, departmentId: undefined, assignmentId: undefined })}>返回全部授权门店</button>}
    </div>
    <nav className="wb-filter-tabs rw-review-filters" aria-label="工作审核分类">
      {([['PERIOD', '全部工作'], ['SPOT_CHECK', '免审待抽查'], ['REVIEW', '待人工审核'], ['REPAIR', '待整改']] as const).map(([queue, title]) =>
        <button type="button" key={queue} aria-pressed={resource.query.queue === queue} className={resource.query.queue === queue ? 'active' : ''}
          onClick={() => change({ queue, status: 'ALL', lateOnly: undefined })}>{title}</button>)}
    </nav>
    <article className="wb-panel"><WorkStatusCards metric={resource.data.period} loading={resource.loading || Boolean(resource.error)}
      selected={resource.query.status} onSelect={status => change({ status, queue: undefined })} />
      {!resource.loading && !resource.error && <CompletionRates metric={resource.data.period} />}</article>
    {!resource.loading && !resource.error && <WorkQueueAlerts data={resource.data} onOpen={change} />}
    {detailError && <p className="inline-error" role="alert">{detailError}</p>}
    <WorkBoardList data={resource.data} loading={resource.loading} error={resource.error} params={routeParams} onChange={change}
      onOpen={item => go('team-work', { ...routeParams, expectationId: item.id, recordId: undefined })} onRetry={resource.reload} />
    {selected && <TeamWorkDrawer key={`${identity.key}:${selected.id}:${routeParams.recordId ?? ''}`} initial={selected} requestedRecordId={routeParams.recordId} identity={identity} permissions={permissions} go={go}
      onClose={() => go('team-work', { ...routeParams, expectationId: undefined, recordId: undefined })} onChanged={resource.reload} />}
  </section>
}

const payloadLabels: Record<string, string> = {
  employeeAssessments: '员工绩效评估', employeeName: '员工姓名', positionName: '岗位', score: '评估分数', focus: '关注重点', periodLabel: '归属周期',
  plannedHygieneContent: '计划卫生内容', plannedHygieneRoomNumbers: '计划卫生房间号',
  expectedAttendance: '应到人数', actualAttendance: '实到人数', appearancePassed: '仪容仪表是否合格',
  meetingTopic: '晨会主题', meetingNotes: '会议记录', issueFound: '是否发现问题', issueSummary: '问题说明',
  inspectionResult: '巡查结果', lobbyStatus: '大堂情况', equipmentRoomStatus: '设备间情况',
  warehouseStatus: '库房情况', correctiveAction: '整改措施', correctiveOwner: '整改负责人',
  correctiveDeadline: '整改期限', dirtyRoomNumbers: '走脏房房号', roomInspectionRoomNumbers: '查房房号',
  complaintOccurred: '是否发生客诉', complaintHandling: '客诉处理情况', followUpOrders: '跟进事项',
  employeeCommunicationOccurred: '是否进行员工沟通', stayoverCommunicationOccurred: '是否进行住客沟通',
  trainingOccurred: '是否开展培训', cooperationAssessmentOccurred: '是否开展协作评估',
  coachingSummary: '辅导情况', specialNotes: '特别说明', notes: '备注', summary: '完成说明', issues: '发现问题数',
}

function unwrapPayloadValue(raw: unknown): unknown {
  let current = raw
  for (let attempt = 0; attempt < 3; attempt += 1) {
    if (typeof current === 'string') {
      try { current = JSON.parse(current); continue } catch { return current }
    }
    if (current && typeof current === 'object' && !Array.isArray(current)) {
      const wrapped = current as Record<string, unknown>
      if ((wrapped.type === 'json' || wrapped.type === 'jsonb') && typeof wrapped.value === 'string') {
        current = wrapped.value
        continue
      }
    }
    break
  }
  return current
}

function readableValue(raw: unknown): string {
  const value = unwrapPayloadValue(raw)
  if (value === null || value === undefined || value === '') return '未填写'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) return value.length ? value.map(readableValue).join('、') : '未填写'
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
    return entries.length ? entries.filter(([key]) => key !== 'employeeId').map(([key, child]) => `${payloadLabels[key] ?? '记录内容'}：${readableValue(child)}`).join('；') : '未填写'
  }
  return String(value)
}

function readablePayload(expectation: WorkExpectation, payload: Record<string, unknown>) {
  const unwrapped = unwrapPayloadValue(payload)
  if (!unwrapped || typeof unwrapped !== 'object' || Array.isArray(unwrapped)) return []
  const properties = expectation.formSchema?.properties ?? {}
  return Object.entries(unwrapped as Record<string, unknown>)
    .filter(([key, raw]) => !['type', 'value', 'null'].includes(key) && !key.startsWith('_')
      && !(key === 'inventoryQuantities' && raw && typeof raw === 'object' && 'schemaVersion' in raw))
    .map(([key, raw], index) => ({
      key,
      label: properties[key]?.title || payloadLabels[key] || `补充记录 ${index + 1}`,
      value: readableValue(raw),
    }))
}

function AttachmentList({ items, disabled, loadingId, onPreview }: {
  items: WorkRecordAttachment[]
  disabled: boolean
  loadingId?: string
  onPreview: (item: WorkRecordAttachment) => void
}) {
  if (!items.length) return <p className="muted">尚未上传现场图片或附件。</p>
  return <div className="attachment-list">{items.map((item) => <div key={item.id}>
    <span className="attachment-icon">▧</span><span><strong>{item.originalName}</strong><small>{formatSize(item.sizeBytes)} · {label(item.scanStatus)} · {formatDate(item.createdAt)}</small></span>
    <button className="link-button" disabled={disabled} onClick={() => onPreview(item)}>{loadingId === item.id ? '读取中…' : '查看'}</button>
  </div>)}</div>
}

function isImageAttachment(item: WorkRecordAttachment) {
  return item.mediaType.startsWith('image/') || /\.(?:jpe?g|png)$/i.test(item.originalName)
}

function evidenceContext(item: WorkRecordAttachment, expectation: WorkExpectation) {
  const requirement = expectation.submissionPolicy?.evidenceRequirements
    .find((candidate) => candidate.checkpointCode === item.checkpointCode)
  const area = requirement?.label ?? (item.checkpointCode ? `检查区域：${item.checkpointCode}` : '补充现场照片')
  const instance = item.evidenceInstanceKey
    ? `${requirement?.instanceLabel ?? '房号'} ${item.evidenceInstanceKey}`
    : undefined
  return { area, instance }
}

type EvidenceImageOrientation = 'LANDSCAPE' | 'PORTRAIT' | 'SQUARE'
type EvidenceImageDimensions = { width: number; height: number }

function dimensionsOrientation({ width, height }: EvidenceImageDimensions): EvidenceImageOrientation {
  if (width > height) return 'LANDSCAPE'
  if (height > width) return 'PORTRAIT'
  return 'SQUARE'
}

function attachmentDimensions(item: WorkRecordAttachment): EvidenceImageDimensions | undefined {
  return item.normalizedWidth && item.normalizedHeight
    ? { width: item.normalizedWidth, height: item.normalizedHeight }
    : undefined
}

function EvidenceGalleryCard({ item, expectation, url, error, onPreview }: {
  item: WorkRecordAttachment
  expectation: WorkExpectation
  url?: string
  error?: string
  onPreview: (item: WorkRecordAttachment) => void
}) {
  const context = evidenceContext(item, expectation)
  const [dimensions, setDimensions] = useState<EvidenceImageDimensions | undefined>(() => attachmentDimensions(item))
  const orientation = dimensions ? dimensionsOrientation(dimensions) : item.displayOrientation
  const orientationClass = orientation ? ` evidence-orientation-${orientation.toLowerCase()}` : ''
  const orientationLabel = orientation === 'PORTRAIT' ? '竖图' : orientation === 'LANDSCAPE' ? '横图' : orientation === 'SQUARE' ? '方图' : '图片'

  return <article className={orientationClass.trim()}>
    <button
      type="button"
      className={`evidence-gallery-image${orientationClass}`}
      style={dimensions ? { aspectRatio: `${dimensions.width} / ${dimensions.height}` } : undefined}
      disabled={!url}
      onClick={() => onPreview(item)}
      aria-label={`放大查看：${context.area}${context.instance ? `，${context.instance}` : ''}`}
    >
      {url ? <img
        src={url}
        alt={`${context.area}${context.instance ? `，${context.instance}` : ''}`}
        loading="lazy"
        onLoad={(event) => {
          const next = { width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight }
          if (next.width > 0 && next.height > 0) {
            setDimensions((current) => current?.width === next.width && current.height === next.height ? current : next)
          }
        }}
      /> : <span>{error ? '图片读取失败' : '图片加载中…'}</span>}
    </button>
    <div className="evidence-gallery-caption">
      <strong>{context.area}</strong>
      <span>{context.instance && <b>{context.instance}</b>}<em>{item.captureSource === 'CAMERA' ? '现场拍摄' : '相册上传'} · {orientationLabel}</em></span>
      <small>{formatDate(item.capturedAtClient ?? item.receivedAt ?? item.createdAt)} · {formatSize(item.sizeBytes)} · {label(item.scanStatus)}</small>
      {error && <small className="evidence-gallery-error">{error}</small>}
    </div>
  </article>
}

function EvidenceGallery({ items, expectation, urls, errors, onPreview }: {
  items: WorkRecordAttachment[]
  expectation: WorkExpectation
  urls: Record<string, string>
  errors: Record<string, string>
  onPreview: (item: WorkRecordAttachment) => void
}) {
  if (!items.length) return null
  return <div className="evidence-gallery">{items.map((item) => <EvidenceGalleryCard
    key={item.id}
    item={item}
    expectation={expectation}
    url={urls[item.id]}
    error={errors[item.id]}
    onPreview={onPreview}
  />)}</div>
}

type DrawerNextAction =
  | { kind: 'section'; targetId: string; label: string }
  | { kind: 'route'; view: 'evaluations' | 'tasks'; params: Record<string, string>; label: string }

type DrawerMessage = { tone: 'ok' | 'error'; text: string; next?: DrawerNextAction }
type AttachmentPreview = { attachmentId?: string; url: string; name: string; mediaType: string; context?: string; ownedUrl: boolean }

function resultId(result: unknown) {
  if (!result || typeof result !== 'object') return ''
  const row = result as Record<string, unknown>
  const nested = row.data && typeof row.data === 'object' ? row.data as Record<string, unknown> : undefined
  return String(row.id ?? row.taskId ?? row.evaluationId ?? nested?.id ?? nested?.taskId ?? nested?.evaluationId ?? '')
}

export function WorkRecordReviewActions({ status, eligibility, reason, busy, isDemo, onReason, onReview, preview, onPrepareSpotCheck, onCancelSpotCheck }: {
  status: string; eligibility: TeamWorkCase['reviewEligibility']; reason: string; busy?: string; isDemo: boolean
  onReason: (reason: string) => void; onReview: (outcome: 'APPROVED' | 'REJECTED') => void
  preview?: SpotCheckPreview; onPrepareSpotCheck?: () => void; onCancelSpotCheck?: () => void
}) {
  const spotCheck = eligibility?.spotCheck === true
  const actionable = status === 'SUBMITTED' || (spotCheck && status === 'APPROVED')
  const disabled = Boolean(busy) || isDemo || !eligibility?.canReview
  return <section className="action-box" id="work-review-action"><h3>{spotCheck ? '免审工作抽查' : '工作记录复核'}</h3>
    <p className="muted">{spotCheck
      ? '抽查不合格须填写原因；按原工作日期撤销一次有效完成，非固定扣分。整改后须人工复验通过才恢复；按需事件不扣必做完成率。'
      : '复核只判断记录是否完整，不替代标准评价和任务验收。'}</p>
    {actionable ? <>
      {!eligibility?.canReview && <div className="inline-error">{eligibility?.reason || '正在确认审核权限'}</div>}
      <label>{spotCheck ? '抽查不合格原因' : '复核意见'}<textarea rows={2} value={reason} disabled={disabled}
        onChange={event => onReason(event.target.value)} placeholder={spotCheck ? '请说明不合格事实及整改要求' : '退回时必须填写原因'} /></label>
      <div><button className="primary" disabled={disabled} onClick={() => onReview('APPROVED')}>{busy === 'review-APPROVED' ? '处理中…' : spotCheck ? '抽查合格' : '复核通过'}</button>
        <button className="danger-button" disabled={disabled || !reason.trim() || Boolean(preview?.loading)} onClick={() => spotCheck ? onPrepareSpotCheck?.() : onReview('REJECTED')}>{busy === 'review-REJECTED' ? '处理中…' : spotCheck ? '抽查不合格' : '退回补充'}</button></div>
      {spotCheck && preview && <div className="rw-spot-confirm" role="group" aria-label="抽查不合格确认">
        <h4>确认不合格前核对影响</h4>
        <p>以下按当前授权范围内原执行任职、原工作日期统计预估，不是固定扣分。提交时服务端会再次核对资格与记录版本。</p>
        {preview.loading ? <p role="status">正在读取原任职原日工作统计…</p> : preview.error ? <p className="inline-error" role="alert">{preview.error}</p> : preview.impact && <dl>
          <div><dt>原工作日期</dt><dd>{preview.impact.businessDate}</dd></div>
          <div><dt>当日应完成</dt><dd>{preview.impact.expected} 项</dd></div>
          <div><dt>有效完成数</dt><dd>{preview.impact.completed} → {preview.impact.afterCompleted} 项</dd></div>
          <div><dt>闭环完成率预估</dt><dd>{preview.impact.beforeRate}% → {preview.impact.afterRate}%</dd></div>
        </dl>}
        <p>不合格原因：{reason}</p><p>撤销该项一次有效完成；整改提交后须人工复验通过才恢复。实际统计以提交后刷新结果为准。</p>
        <div className="rw-actions"><button type="button" className="secondary" disabled={Boolean(busy)} onClick={onCancelSpotCheck}>取消，不提交</button>
          {preview.error && <button type="button" className="secondary" disabled={disabled} onClick={onPrepareSpotCheck}>重新读取预估</button>}
          <button type="button" className="danger-button" disabled={disabled || !reason.trim() || Boolean(preview.loading) || !preview.impact}
            onClick={() => onReview('REJECTED')}>{busy === 'review-REJECTED' ? '正在提交…' : '确认不合格并通知整改'}</button></div>
      </div>}
    </> : <p className="muted">当前记录状态不可复核。{eligibility?.reason || '如有整改，请等待员工重新提交后人工复验。'}</p>}
  </section>
}

export function TeamWorkDrawer({ initial, requestedRecordId, identity, permissions, go, onClose, onChanged }: {
  initial: WorkExpectation
  requestedRecordId?: string
  identity: RoleContext
  permissions: string[]
  go: Navigate
  onClose: () => void
  onChanged: () => void
}) {
  const fallback: TeamWorkCase = useMemo(() => ({ expectation: initial }), [initial])
  const [selectedRecordId, setSelectedRecordId] = useState<string | undefined>(requestedRecordId)
  const resource = useResource(`${identity.key}:team-case:${initial.id}:${selectedRecordId ?? 'default'}`, () => loadTeamWorkCase(identity, initial, selectedRecordId), fallback)
  const { expectation, record, reviewEligibility } = resource.data
  const [reviewReason, setReviewReason] = useState('')
  const [spotPreview, setSpotPreview] = useState<SpotCheckPreview>()
  const previewRequest = useRef(0)
  const [taskTitle, setTaskTitle] = useState(`整改：${initial.title}`)
  const [taskDescription, setTaskDescription] = useState('请根据工作标准完成整改，补充结果说明和现场证据。')
  const [taskPriority, setTaskPriority] = useState('NORMAL')
  const [taskDueAt, setTaskDueAt] = useState(() => new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 16))
  const [selectedStandard, setSelectedStandard] = useState('')
  const [busy, setBusy] = useState<string>()
  const [message, setMessage] = useState<DrawerMessage>()
  const [attachmentPreview, setAttachmentPreview] = useState<AttachmentPreview>()
  const [lightboxMeasurement, setLightboxMeasurement] = useState<{ url: string; orientation: 'landscape' | 'portrait' | 'square' }>()
  const [attachmentUrls, setAttachmentUrls] = useState<Record<string, string>>({})
  const [attachmentLoadErrors, setAttachmentLoadErrors] = useState<Record<string, string>>({})
  const standards = expectation.standards ?? []
  const payloadFields = readablePayload(expectation, record?.payload ?? {})
  const isDemo = resource.source === 'demo'
  const allows = (permission: string) => permissions.includes(permission)
    || (permission === 'work-record.review' && expectation.executionPolicy?.managerCycle === true && permissions.includes('manager-cycle.review'))
    || (demoFallbackEnabled && isDemo)
  const imageAttachments = (record?.attachments ?? []).filter(isImageAttachment)
  const documentAttachments = (record?.attachments ?? []).filter((item) => !isImageAttachment(item))
  const attachmentLoadKey = imageAttachments.map((item) => item.id).join(':')
  useEffect(() => {
    previewRequest.current += 1
    setSpotPreview(undefined)
    return () => { previewRequest.current += 1 }
  }, [identity.key, record?.id, record?.rowVersion])

  useEffect(() => {
    if (!selectedStandard && standards.length) setSelectedStandard(standards[0].standardVersionId)
  }, [selectedStandard, standards])
  useEffect(() => () => { if (attachmentPreview?.ownedUrl) URL.revokeObjectURL(attachmentPreview.url) }, [attachmentPreview])
  useEffect(() => {
    setAttachmentUrls({})
    setAttachmentLoadErrors({})
    if (isDemo || !imageAttachments.length) return
    let active = true
    let cursor = 0
    const createdUrls: string[] = []
    const loadNext = async () => {
      while (active) {
        const item = imageAttachments[cursor++]
        if (!item) return
        try {
          const blob = await loadAttachmentContent(identity, item.id)
          const url = URL.createObjectURL(blob)
          if (!active) { URL.revokeObjectURL(url); return }
          createdUrls.push(url)
          setAttachmentUrls((current) => ({ ...current, [item.id]: url }))
        } catch (error) {
          if (active) setAttachmentLoadErrors((current) => ({
            ...current,
            [item.id]: error instanceof Error ? error.message : '附件读取失败',
          }))
        }
      }
    }
    void Promise.all(Array.from({ length: Math.min(4, imageAttachments.length) }, loadNext))
    return () => {
      active = false
      createdUrls.forEach((url) => URL.revokeObjectURL(url))
    }
  }, [attachmentLoadKey, identity.key, isDemo])

  const mutation = async <T,>(name: string, action: () => Promise<T>, success: string | DrawerMessage | ((result: T) => DrawerMessage)) => {
    if (isDemo) { setMessage({ tone: 'error', text: '演示回退仅用于页面走查，不会向业务系统写入数据。' }); return }
    setBusy(name); setMessage(undefined)
    try {
      const result = await action()
      setMessage(typeof success === 'function' ? success(result) : typeof success === 'string' ? { tone: 'ok', text: success } : success)
      await Promise.all([resource.reload(), onChanged()])
    } catch (error) { setMessage({ tone: 'error', text: error instanceof Error ? error.message : '操作失败' }) }
    finally { setBusy(undefined) }
  }

  const runNext = (next: DrawerNextAction) => {
    if (next.kind === 'section') {
      document.getElementById(next.targetId)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      return
    }
    onClose()
    go(next.view, next.params)
  }

  const review = (outcome: 'APPROVED' | 'REJECTED') => {
    if (!record || !reviewEligibility?.canReview || isDemo) return
    const spotCheck = reviewEligibility.spotCheck === true
    if (record.status !== 'SUBMITTED' && !(spotCheck && record.status === 'APPROVED')) return
    if (spotCheck && outcome === 'REJECTED' && (!spotPreview?.impact || spotPreview.loading)) return
    if (outcome === 'REJECTED' && !reviewReason.trim()) {
      setMessage({ tone: 'error', text: spotCheck ? '请填写抽查不合格原因。' : '退回时必须填写原因。' }); return
    }
    void mutation(`review-${outcome}`, () => reviewWorkRecord(identity, record.id, outcome, reviewReason, record.rowVersion), outcome === 'APPROVED'
      ? spotCheck ? { tone: 'ok', text: '抽查合格已留痕，原工作状态、提交事实和完成率保持不变。' } : { tone: 'ok', text: '工作记录已复核通过。下一步可按工作标准创建评价。', next: { kind: 'section', targetId: 'standard-evaluation-action', label: '继续创建标准评价' } }
      : { tone: 'ok', text: spotCheck ? '抽查不合格已登记，已按原工作日期撤销本次有效完成。员工整改提交后须人工复验通过才恢复，不会重复扣减。' : '工作记录已退回补充，员工可在“我的工作”中补齐说明和证据。' })
  }

  const prepareSpotCheck = async () => {
    if (!record || !reviewEligibility?.canReview || !reviewEligibility.spotCheck || isDemo || busy || !reviewReason.trim()) return
    const request = ++previewRequest.current
    setSpotPreview({ loading: true })
    try {
      const params = spotCheckPreviewParams(record, expectation)
      const result = await loadWorkBoard(identity, 'team', params)
      if (request !== previewRequest.current) return
      if (result.source !== 'api') throw new Error('未读取到实际统计，无法预估。请刷新后重试。')
      setSpotPreview({ impact: spotCheckImpactFromBoard(result.data, params) })
    } catch (error) {
      if (request === previewRequest.current) setSpotPreview({ error: error instanceof Error ? error.message : '无法读取完成率影响，请重试。' })
    }
  }

  const createTask = () => {
    if (!record?.targetOrgUnitId || !record.positionAssignmentId) {
      setMessage({ tone: 'error', text: '记录缺少目标组织或执行任职，无法创建整改任务。' }); return
    }
    const reviewerAssignmentId = identity.businessActorAssignmentId && record.positionAssignmentId !== identity.businessActorAssignmentId
      ? identity.businessActorAssignmentId
      : undefined
    void mutation('task', () => createCorrectiveTask(identity, {
      orgUnitId: record.targetOrgUnitId!, assigneeAssignmentId: record.positionAssignmentId!, reviewerAssignmentId,
      creatorAssignmentId: identity.businessActorAssignmentId,
      standardVersionId: selectedStandard || undefined, workRecordId: record.id, title: taskTitle,
      description: taskDescription, priority: taskPriority, dueAt: taskDueAt ? new Date(taskDueAt).toISOString() : undefined,
    }), (result) => {
      const taskId = resultId(result)
      return {
        tone: 'ok',
        text: '整改任务已创建并派发，接收人可立即在任务与通知中心查看。',
        next: { kind: 'route', view: 'tasks', params: { view: 'team', ...(taskId ? { taskId } : {}) }, label: '进入任务中心' },
      }
    })
  }

  const preview = async (attachment: WorkRecordAttachment) => {
    if (isDemo) { setMessage({ tone: 'error', text: '演示附件没有真实文件内容。' }); return }
    const galleryUrl = attachmentUrls[attachment.id]
    const context = evidenceContext(attachment, expectation)
    if (galleryUrl) {
      setAttachmentPreview({
        attachmentId: attachment.id,
        url: galleryUrl,
        name: attachment.originalName,
        mediaType: attachment.mediaType || 'image/jpeg',
        context: `${context.area}${context.instance ? ` · ${context.instance}` : ''}`,
        ownedUrl: false,
      })
      return
    }
    setBusy(`preview-${attachment.id}`)
    setMessage(undefined)
    try {
      const blob = await loadAttachmentContent(identity, attachment.id)
      setAttachmentPreview({ url: URL.createObjectURL(blob), name: attachment.originalName, mediaType: attachment.mediaType || blob.type, ownedUrl: true })
    } catch (error) { setMessage({ tone: 'error', text: error instanceof Error ? error.message : '附件读取失败' }) }
    finally { setBusy(undefined) }
  }

  const movePreview = (direction: -1 | 1) => {
    if (!attachmentPreview?.attachmentId) return
    const currentIndex = imageAttachments.findIndex((item) => item.id === attachmentPreview.attachmentId)
    if (currentIndex < 0) return
    const next = imageAttachments[(currentIndex + direction + imageAttachments.length) % imageAttachments.length]
    if (next && attachmentUrls[next.id]) void preview(next)
  }

  const lightboxOrientation = lightboxMeasurement && lightboxMeasurement.url === attachmentPreview?.url
    ? lightboxMeasurement.orientation
    : undefined

  const evaluate = () => {
    if (!record || !selectedStandard) { setMessage({ tone: 'error', text: '必须选择工作包绑定的已发布标准版本。' }); return }
    void mutation('evaluation', () => createWorkRecordEvaluation(identity, { record, standardVersionId: selectedStandard }), (result) => {
      const evaluationId = resultId(result)
      const params: Record<string, string> = evaluationId ? { evaluationId } : {}
      return {
        tone: 'ok',
        text: '标准评价已创建，可进入评价中心查看逐项结果。',
        next: { kind: 'route', view: 'evaluations', params, label: '进入评价中心' },
      }
    })
  }

  return <div className="drawer-backdrop"><aside className="drawer p0-drawer" role="dialog" aria-modal="true">
    <header><div><span className="panel-kicker">团队工作详情</span><h2>{expectation.title}</h2><small>{expectation.hotelName && expectation.hotelName !== expectation.targetOrgName ? `${expectation.hotelName} · ` : ''}{expectation.targetOrgName} · {expectation.assigneeName}</small></div><button className="close" onClick={onClose} aria-label="关闭详情">×</button></header>
    <div className="drawer-body">
      <LoadingState loading={resource.loading} error={resource.error} retry={resource.reload} />
      {!resource.loading && !resource.error && <>
        <div className="task-summary"><span><small>工作状态</small><Status value={expectation.status} /></span><span><small>评价结果</small>{expectation.evaluationOutcome ? <Status value={expectation.evaluationOutcome} /> : '—'}</span><span><small>截止时间</small><strong>{formatDate(expectation.dueAt)}</strong></span></div>
        {message && <div role="status" className={`team-action-feedback ${message.tone}`}><span><strong>{message.tone === 'ok' ? '操作成功' : '操作未完成'}</strong><small>{message.text}</small></span>{message.next ? <button type="button" className="secondary" onClick={() => runNext(message.next!)}>{message.next.label} →</button> : null}</div>}
        {(expectation.records?.length ?? 0) > 1 && <section className="detail-section"><label>选择提交记录（共 {expectation.records!.length} 条）<select aria-label="选择提交记录" value={record?.id ?? ''} disabled={!!busy} onChange={(event) => { setSelectedRecordId(event.target.value); setReviewReason(''); setMessage(undefined); setAttachmentPreview(undefined) }}>{expectation.records!.map((entry, index) => <option key={entry.id} value={entry.id}>{entry.status === 'SUBMITTED' ? '待审核' : label(entry.status)} · {formatDate(entry.submittedAt)} · 记录 {expectation.records!.length - index}</option>)}</select></label><p className="muted">每条提交保留独立的内容、照片和审核结果；可切换查看，重复提交工作优先展示待审核记录。</p></section>}
        {!record ? <div className="inline-warning">该工作期望尚未形成可复核的工作记录。</div> : <>
          {record.inventoryUsage && <UsageEvidenceSummary usage={record.inventoryUsage} />}<section className="detail-section"><h3>记录事实</h3><dl><div><dt>提交员工</dt><dd>{record.employeeName}</dd></div><div><dt>执行岗位</dt><dd>{record.positionName}</dd></div><div><dt>记录状态</dt><dd>{label(record.status)}</dd></div><div><dt>提交时间</dt><dd>{formatDate(record.submittedAt)}</dd></div></dl>{payloadFields.length ? <div className="payload-grid">{payloadFields.map((field) => <span key={field.key}><small>{field.label}</small><strong>{field.value}</strong></span>)}</div> : <p className="muted">本次提交没有额外的表单记录。</p>}{record.reviewReason && <div className="inline-warning">上次复核意见：{record.reviewReason}</div>}</section>

          <AiEvidenceRecord key={record.id} identity={identity} recordId={record.id} review canDecide={allows('work-record.review')} />
          <section className="detail-section"><h3>现场图片与附件</h3>
            <p className="muted">图片按检查区域直接展示；竖拍照片以竖向画框陈列，横拍照片以横向画框陈列。附件补充和删除仍由记录所属员工完成。</p>
            {!record.attachments.length && <p className="muted">尚未上传现场图片或附件。</p>}
            <EvidenceGallery items={imageAttachments} expectation={expectation} urls={attachmentUrls} errors={attachmentLoadErrors} onPreview={preview} />
            {!!documentAttachments.length && <><h4 className="evidence-document-title">文档附件</h4><AttachmentList items={documentAttachments} disabled={!!busy || isDemo} loadingId={busy?.startsWith('preview-') ? busy.slice('preview-'.length) : undefined} onPreview={preview} /></>}
          </section>

          {(allows('work-record.review') || reviewEligibility?.canReview) && <WorkRecordReviewActions status={record.status}
            eligibility={reviewEligibility} reason={reviewReason} busy={busy} isDemo={isDemo} onReason={setReviewReason} onReview={review}
            preview={spotPreview} onPrepareSpotCheck={() => void prepareSpotCheck()} onCancelSpotCheck={() => { previewRequest.current += 1; setSpotPreview(undefined) }} />}

          {allows('evaluation.manual-review') && <section className="action-box" id="standard-evaluation-action"><h3>创建标准评价</h3><label>评价依据<select value={selectedStandard} onChange={(event) => setSelectedStandard(event.target.value)}><option value="">请选择已发布标准</option>{standards.map((standard) => <option value={standard.standardVersionId} key={standard.standardVersionId}>{standard.title}（第{standard.versionNo}版）</option>)}</select></label>{!standards.length && <div className="inline-warning">工作包条目尚未返回绑定标准，不能由页面猜测评价依据。</div>}<div><button className="primary" disabled={!!busy || !selectedStandard || isDemo} onClick={evaluate}>{busy === 'evaluation' ? '创建中…' : '按标准创建评价'}</button></div></section>}

          {allows('task.create') && <section className="action-box" id="corrective-task-action"><h3>创建整改任务</h3><div className="form-grid"><label>任务标题<input value={taskTitle} onChange={(event) => setTaskTitle(event.target.value)} /></label><label>优先级<select value={taskPriority} onChange={(event) => setTaskPriority(event.target.value)}><option value="LOW">低</option><option value="NORMAL">普通</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label><label>完成时限<input type="datetime-local" value={taskDueAt} onChange={(event) => setTaskDueAt(event.target.value)} /></label></div><label>整改要求<textarea rows={3} value={taskDescription} onChange={(event) => setTaskDescription(event.target.value)} /></label><div><button className="primary" disabled={!!busy || !taskTitle.trim() || !taskDescription.trim() || isDemo} onClick={createTask}>{busy === 'task' ? '创建中…' : '创建整改任务'}</button></div></section>}
        </>}
        {isDemo && <div className="inline-warning">当前为演示回退：所有P0操作入口可见，但写操作被保护性禁用。</div>}
      </>}
    </div>
    {attachmentPreview && <div className={`attachment-lightbox${lightboxOrientation ? ` attachment-lightbox-${lightboxOrientation}` : ''}`} role="dialog" aria-modal="true" aria-label={`查看附件：${attachmentPreview.name}`}><div><header><span><strong>{attachmentPreview.context ?? attachmentPreview.name}</strong><small>{attachmentPreview.context ? `${attachmentPreview.name} · 现场证据预览` : '现场证据预览'}</small></span><button type="button" className="close" onClick={() => setAttachmentPreview(undefined)} aria-label="关闭附件预览">×</button></header>{attachmentPreview.mediaType.startsWith('image/') ? <div className="attachment-lightbox-image">{imageAttachments.length > 1 && <button type="button" className="lightbox-nav previous" onClick={() => movePreview(-1)} aria-label="上一张图片">‹</button>}<img src={attachmentPreview.url} alt={attachmentPreview.context ?? attachmentPreview.name} onLoad={(event) => { const { naturalWidth: width, naturalHeight: height } = event.currentTarget; setLightboxMeasurement({ url: attachmentPreview.url, orientation: width > height ? 'landscape' : height > width ? 'portrait' : 'square' }) }} />{imageAttachments.length > 1 && <button type="button" className="lightbox-nav next" onClick={() => movePreview(1)} aria-label="下一张图片">›</button>}</div> : attachmentPreview.mediaType === 'application/pdf' ? <iframe src={attachmentPreview.url} title={attachmentPreview.name} /> : <div className="attachment-download"><p>该格式不能在浏览器内直接预览。</p><a className="primary" href={attachmentPreview.url} download={attachmentPreview.name}>下载附件</a></div>}</div></div>}
  </aside></div>
}

function TaskList({ tasks, onSelect }: { tasks: ManagementTask[]; onSelect: (task: ManagementTask) => void }) {
  if (!tasks.length) return <p className="muted">当前门店没有未完成任务。</p>
  return <div className="dashboard-task-list">{tasks.map((task) => <button type="button" className="dashboard-task-row" key={task.id} onClick={() => onSelect(task)}><span><strong>{task.title}</strong><small>{task.assigneeName} · 截止 {formatDate(task.dueAt)}</small></span><span><Status value={task.slaStatus} /><Status value={task.status} /></span></button>)}</div>
}

export function HotelDashboardPage({ identity, routeParams, go }: { identity: RoleContext; routeParams: RouteParams; go: Navigate }) {
  const hotelsResource = useResource(`${identity.key}:dashboard-hotels`, () => loadAccessibleHotels(identity), { hotels: [] })
  const hotels = hotelsResource.data.hotels
  const requestedHotel = hotels.find((hotel) => hotel.id === routeParams.hotelId)
  const assignedHotel = hotels.find((hotel) => hotel.id === identity.assignmentOrgUnitId)
  const selectedHotel = requestedHotel ?? assignedHotel ?? hotels[0]
  const hotelId = selectedHotel?.id ?? ''
  const emptyDashboard: HotelDashboard = {
    hotel: { id: hotelId, name: selectedHotel?.name ?? '门店' }, activeEmployeeCount: 0, todayWorkSubmissionCount: 0, latestMetrics: [], risks: [], incompleteTasks: [],
  }
  const resource = useResource(`${identity.key}:hotel-dashboard:${hotelId}`, () => hotelId
    ? loadHotelDashboard(identity, hotelId)
    : Promise.resolve({ data: emptyDashboard, source: 'api' as const }), emptyDashboard)
  const dashboard = resource.data
  const sections = new Set(dashboard.templateSections?.length ? dashboard.templateSections : ['OPERATING_METRICS', 'RISKS', 'INCOMPLETE_TASKS', 'WORK_COMPLETION'])
  const overdue = dashboard.incompleteTasks.filter((task) => ['OVERDUE', 'ESCALATED'].includes(task.slaStatus)).length
  const highRisks = dashboard.risks.filter((risk) => ['HIGH', 'URGENT'].includes(risk.severity)).length
  const loading = hotelsResource.loading || (!!hotelId && resource.loading)
  const error = hotelsResource.error || resource.error || (!hotelsResource.loading && !hotelId ? '当前账号没有可访问的门店工作台。' : undefined)
  const openTask = (task: ManagementTask) => go('tasks', { view: 'team', status: 'ACTIVE', hotelId, taskId: task.id })
  const openRisk = (risk: typeof dashboard.risks[number]) => {
    const common = { hotelId }
    if (risk.type === 'STANDARD_EVALUATION') return go('evaluations', { ...common, evaluationId: risk.sourceId, outcome: 'FAIL' })
    if (risk.type === 'OVERDUE_TASK') return go('tasks', { ...common, view: 'team', status: 'OVERDUE', taskId: risk.sourceId })
    if (risk.type === 'MISSED_WORK') return go('team-work', { ...common, status: 'MISSED', expectationId: risk.sourceId })
    return go('team-work', { ...common, status: 'EXCEPTION' })
  }

  return <section className="page-section">
    <PageHeader eyebrow="HOTEL WORKBENCH" title={`${dashboard.hotel.name}工作台`} description="店总视角聚合经营指标、风险事项与未完成任务；所有明细仍回到原始记录和任务。" source={resource.source} actions={hotels.length > 1 ? <label className="dashboard-hotel-select"><span>查看门店</span><select value={hotelId} onChange={(event) => go('hotel-dashboard', { hotelId: event.target.value })}>{hotels.map((hotel) => <option value={hotel.id} key={hotel.id}>{hotel.name}</option>)}</select></label> : undefined} />
    <LoadingState loading={loading} error={error} retry={() => void Promise.all([hotelsResource.reload(), resource.reload()])} />
    {!loading && !error && !!hotelId && <>
      <section className="metrics-grid p0-dashboard-metrics"><button type="button" className="metric metric-action blue" onClick={() => go('team-work', { hotelId })}><div>员</div><span>在岗员工<strong>{dashboard.activeEmployeeCount}</strong><small>{dashboard.hotel.city ?? '当前门店'} · {dashboard.hotel.roomCount ?? '—'}间客房</small></span></button>{sections.has('WORK_COMPLETION') && <button type="button" className="metric metric-action teal" onClick={() => go('team-work', { hotelId, status: 'SUBMITTED' })}><div>工</div><span>今日工作提交<strong>{dashboard.todayWorkSubmissionCount}</strong><small>岗位工作记录</small></span></button>}{sections.has('RISKS') && <button type="button" className="metric metric-action gold" onClick={() => go('team-work', { hotelId, status: 'EXCEPTION' })}><div>险</div><span>开放风险<strong>{dashboard.risks.length}</strong><small>{highRisks}项高风险</small></span></button>}{sections.has('INCOMPLETE_TASKS') && <button type="button" className="metric metric-action violet" onClick={() => go('tasks', { view: 'team', status: 'ACTIVE', hotelId })}><div>任</div><span>未完成任务<strong>{dashboard.incompleteTasks.length}</strong><small>{overdue}项已逾期/升级</small></span></button>}</section>
      <section className="dashboard-grid">
        {sections.has('OPERATING_METRICS') && <article className="panel span-3"><header><div><span className="panel-kicker">LATEST OPERATING METRICS</span><h2>门店经营快照</h2></div></header><div className="operation-metric-grid">{dashboard.latestMetrics.map((metric) => <div key={metric.code}><span>{metric.name}</span><strong>{metric.value.toLocaleString('zh-CN')}{metric.unit === 'PERCENT' ? '%' : ''}</strong><small>{metric.code} · {metric.businessDate ?? '最新'}</small></div>)}</div>{!dashboard.latestMetrics.length && <p className="muted">暂无经营指标。</p>}</article>}
        {sections.has('RISKS') && <article className="panel"><header><div><span className="panel-kicker">RISK ITEMS</span><h2>风险事项</h2></div></header><div className="risk-list">{dashboard.risks.map((risk) => <button type="button" className="risk-row" key={risk.id} onClick={() => openRisk(risk)}><i /><span><strong>{risk.title}</strong><small>{risk.ownerName ?? risk.source ?? label(risk.type)} · {formatDate(risk.occurredAt)}</small></span><Status value={risk.severity} /></button>)}</div>{!dashboard.risks.length && <p className="muted">当前没有开放风险。</p>}</article>}
        {sections.has('INCOMPLETE_TASKS') && <article className="panel span-2"><header><div><span className="panel-kicker">INCOMPLETE TASKS</span><h2>未完成任务汇总</h2></div><button className="link-button" onClick={() => go('tasks', { view: 'team', status: 'ACTIVE', hotelId })}>进入任务中心</button></header><TaskList tasks={dashboard.incompleteTasks} onSelect={openTask} /></article>}
      </section>
    </>}
  </section>
}

export function OperationsDashboardPage({ identity }: { identity: RoleContext }) {
  const resource = useResource(`${identity.key}:operations-dashboard`, () => loadOperationsDashboard(identity), { hotels: [] })
  const hotels = resource.data.hotels
  const totals = hotels.reduce((sum, hotel) => ({
    open: sum.open + hotel.openTaskCount,
    overdue: sum.overdue + hotel.overdueTaskCount,
    failed: sum.failed + hotel.failedEvaluationCount,
    missed: sum.missed + hotel.missedWorkCount,
    submitted: sum.submitted + hotel.todaySubmissionCount,
  }), { open: 0, overdue: 0, failed: 0, missed: 0, submitted: 0 })

  return <section className="page-section">
    <PageHeader eyebrow="REGIONAL OPERATIONS" title="区域多门店运营视图" description="区域/运营角色在授权组织树内对比各门店任务、逾期、评价失败和岗位漏交，不扩大账号数据范围。" source={resource.source} />
    <section className="mini-metrics regional-summary"><span><strong>{hotels.length}</strong>授权门店</span><span><strong>{totals.submitted}</strong>今日提交</span><span className="danger"><strong>{totals.overdue}</strong>逾期任务</span><span><strong>{totals.failed}</strong>评价失败</span><span><strong>{totals.missed}</strong>岗位漏交</span></section>
    <article className="panel table-panel"><LoadingState loading={resource.loading} error={resource.error} empty={!hotels.length} retry={resource.reload} />
      {!resource.loading && !resource.error && !!hotels.length && <div className="data-table operations-table"><div className="table-row table-head"><span>门店</span><span>今日提交</span><span>开放任务</span><span>逾期</span><span>评价失败</span><span>岗位漏交</span><span>运营状态</span></div>{hotels.map((hotel) => {
        const risk = hotel.overdueTaskCount + hotel.failedEvaluationCount + hotel.missedWorkCount
        return <div className="table-row" key={hotel.id}><span><strong>{hotel.name}</strong><small>{hotel.city ?? '—'} · {hotel.roomCount ?? '—'}间</small></span><span className="score">{hotel.todaySubmissionCount}</span><span>{hotel.openTaskCount}</span><span className={hotel.overdueTaskCount ? 'danger-text' : ''}>{hotel.overdueTaskCount}</span><span className={hotel.failedEvaluationCount ? 'danger-text' : ''}>{hotel.failedEvaluationCount}</span><span className={hotel.missedWorkCount ? 'danger-text' : ''}>{hotel.missedWorkCount}</span><span><Status value={risk >= 5 ? 'HIGH' : risk ? 'MEDIUM' : 'ON_TIME'} /></span></div>
      })}</div>}
    </article>
  </section>
}
