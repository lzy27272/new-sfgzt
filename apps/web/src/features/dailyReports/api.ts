import { isGenericWorkQuestion, isOccurrenceEntry } from '../../app/workEntryPolicy'
import type { ApiIdentity } from '../../api/client'
import { featureApiMutation, featureApiRequest } from '../shared/featureApi'
import { queryString, requireItems, type PageEnvelope } from '../shared/apiEnvelope'
import type {
  DailyReportDetail,
  DailyReportDraftInput,
  DailyReportEvidence,
  DailyReportItemResult,
  DailyReportRevision,
  DailyReportSectionResult,
  DailyReportStatus,
  DailyReportSummary,
} from './types'

const base = '/daily-reports'
type Row = Record<string, unknown>

export type CurrentBusinessDay = {
  hotelOrgUnitId: string
  orgUnitId: string
  businessDate: string
  timezone: string
  cutoffLocalTime: string
  closingGraceMinutes: number
  resolvedAt: string
  currentBusinessDay: boolean
}

export type DailyReportOrgOption = {
  id: string
  code: string
  name: string
}

const row = (value: unknown): Row => value && typeof value === 'object' && !Array.isArray(value) ? value as Row : {}
const rows = (value: unknown): Row[] => Array.isArray(value) ? value.map(row) : []
const text = (value: unknown, fallback = '') => value === undefined || value === null ? fallback : String(value)
const number = (value: unknown, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback

function jsonRow(value: unknown): Row {
  if (typeof value === 'string') {
    try { return row(JSON.parse(value)) } catch { return {} }
  }
  return row(value)
}

function reportStatus(value: unknown): DailyReportStatus {
  const status = text(value).toUpperCase()
  return ['DRAFT', 'SUBMITTED', 'ARCHIVED'].includes(status) ? status as DailyReportStatus : 'DRAFT'
}

function reviewStatus(value: unknown): DailyReportSummary['reviewStatus'] {
  const status = text(value).toUpperCase()
  return ['NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED'].includes(status)
    ? status as DailyReportSummary['reviewStatus']
    : 'NOT_REQUIRED'
}

function revisionStatus(value: unknown): DailyReportRevision['status'] {
  const status = text(value).toUpperCase()
  return ['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED'].includes(status)
    ? status as DailyReportRevision['status']
    : 'DRAFT'
}

function inputType(value: unknown): DailyReportItemResult['inputType'] {
  const normalized = text(value, 'TEXT').toUpperCase()
  if (['SINGLE_SELECT', 'MULTI_SELECT', 'SELECT'].includes(normalized)) return 'SELECT'
  if (['LONG_TEXT', 'NARRATIVE'].includes(normalized)) return 'NARRATIVE'
  if (['WORK_RECORD_REFERENCE', 'METRIC_REFERENCE', 'EVIDENCE'].includes(normalized)) return 'EVIDENCE'
  if (normalized === 'NUMBER' || normalized === 'BOOLEAN') return normalized
  return 'TEXT'
}

function dataSource(value: unknown): DailyReportItemResult['dataSource'] {
  const normalized = text(value, 'MANUAL').toUpperCase()
  if (['MANUAL', 'EMPLOYEE'].includes(normalized)) return 'EMPLOYEE'
  if (['SYSTEM', 'WORK_RECORD', 'AI_SUMMARY'].includes(normalized)) return 'SYSTEM'
  return 'MIXED'
}

const workRecordFieldLabels: Record<string, string> = {
  roomNumber: '房号',
  serviceContent: '服务内容描述',
  location: '房号',
  complaintContent: '客诉内容',
  initialHandling: '初步处理情况',
  workSummary: '工作结果',
  issueFound: '是否有异常',
  issueSummary: '异常说明',
  checkoutRooms: '退房打扫数',
  stayoverRooms: '续住房打扫数',
  plannedHygieneContent: '计划卫生内容',
  plannedHygieneRoomNumbers: '计划卫生房间号',
  lostPropertyOccurred: '是否发现遗留物',
  inspectionResult: '检查结果',
}

function displayWorkForm(value: unknown): string {
  return Object.entries(jsonRow(value))
    .filter(([key, field]) => workRecordFieldLabels[key] && field !== null && field !== undefined && typeof field !== 'object')
    .map(([key, field]) => `${workRecordFieldLabels[key]}：${typeof field === 'boolean' ? (field ? '是' : '否') : text(field)}`)
    .join('；')
}

const workRecordStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交待审核',
  APPROVED: '已审核',
  REJECTED: '已退回',
}

function displayRepeatableWorkRecords(value: Row): string | undefined {
  const entries = rows(value.entries)
  if (!entries.length || number(value.recordCount) < 1) return undefined
  const details = entries.map((entry, index) => {
    const formData = jsonRow(entry.formData)
    const formFacts = Object.entries(formData)
      .filter(([, fieldValue]) => fieldValue !== undefined && fieldValue !== null && text(fieldValue).trim())
      .map(([field, fieldValue]) => `${workRecordFieldLabels[field] ?? field}：${text(fieldValue)}`)
    const status = workRecordStatusLabels[text(entry.status).toUpperCase()] ?? text(entry.status)
    return `第 ${number(entry.seriesEntryNo, index + 1)} 条（${[status, ...formFacts].filter(Boolean).join('；')}）`
  })
  return `当日共 ${number(value.recordCount, entries.length)} 条记录：${details.join('；')}`
}

function displayValue(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      const usage = displayUsageWorkRecord(row(parsed))
      if (usage) return usage
      const repeatableRecords = displayRepeatableWorkRecords(row(parsed))
      if (repeatableRecords) return repeatableRecords
      const work = displayRoutineWorkRecord(row(parsed))
      if (work) return work
    } catch {
      // Plain employee text is expected here and should remain unchanged.
    }
    return value
  }
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  const usage = displayUsageWorkRecord(row(value))
  if (usage) return usage
  const repeatableRecords = displayRepeatableWorkRecords(row(value))
  if (repeatableRecords) return repeatableRecords
  const work = displayRoutineWorkRecord(row(value))
  if (work) return work
  try { return JSON.stringify(value) } catch { return String(value) }
}

function displayRoutineWorkRecord(value: Row): string | undefined {
  if (!value.workRecordId || !value.completion) return undefined
  return [text(value.completion), displayWorkForm(value.formData),
    value.exception ? `异常：${text(value.exception)}` : '',
    value.nextAction ? `后续：${text(value.nextAction)}` : ''].filter(Boolean).join('；')
}

function displayUsageWorkRecord(value: Row): string | undefined {
  const usage = row(value.inventoryUsage)
  if (!text(usage.summary)) return undefined
  return [text(value.completion), `${text(usage.statusLabel)}：${text(usage.summary)}`,
    usage.reversalReason ? `冲销原因：${text(usage.reversalReason)}` : '',
    value.exception ? `异常：${text(value.exception)}` : '', value.nextAction ? `后续：${text(value.nextAction)}` : ''].filter(Boolean).join('；')
}

function displayFactSummary(value: unknown): string {
  const facts = jsonRow(value)
  if (Object.keys(facts).length === 0) return text(value, '暂无结构化事实')
  const parts = [
    ['应完成', facts.expectedCount],
    ['已完成', facts.completedCount],
    ['待审核', facts.pendingReviewCount],
    ['异常', facts.exceptionCount],
    ['逾期', facts.missedCount],
    ['证据', facts.evidenceCount],
  ].filter(([, factValue]) => factValue !== undefined && factValue !== null)
    .map(([label, factValue]) => `${label} ${number(factValue)} 项`)
  return parts.length ? parts.join('；') : '暂无结构化事实'
}

function normalizeEvidence(value: unknown, index: number): DailyReportEvidence {
  const source = row(value)
  return {
    id: text(source.id, `evidence:${index}`),
    fileName: text(source.originalName || source.fileName || source.evidenceType, '未命名证据'),
    mediaType: text(source.mediaType) || undefined,
    sensitivity: text(source.sensitivity).toUpperCase() === 'NORMAL' ? 'NORMAL' : 'SENSITIVE',
    scanStatus: text(source.scanStatus) || undefined,
    invalidatedAt: text(source.invalidatedAt) || undefined,
  }
}

function sourceLabelsFor(itemResultId: string, sourceRows: Row[], sourceSummary: unknown): string[] {
  const labels = sourceRows
    .filter((source) => text(source.itemResultId) === itemResultId)
    .map((source) => text(source.sourceExternalKey || source.sourceType))
    .filter(Boolean)
  const summary = jsonRow(sourceSummary)
  if (['WORK_RECORD', 'WORK_RECORD_COLLECTION'].includes(text(summary.sourceType))) {
    return ['当日工作记录', ...(number(summary.evidenceCount) > 0 ? [`${number(summary.evidenceCount)} 份证据`] : [])]
  }
  return [...new Set(labels)]
}

function normalizeItem(
  value: unknown,
  index: number,
  resultRows: Row[],
  sourceRows: Row[],
  evidenceRows: Row[],
): DailyReportItemResult {
  const template = row(value)
  const templateItemId = text(template.templateItemId || template.id)
  const result = resultRows.find((candidate) => text(candidate.templateItemId) === templateItemId) || {}
  const itemResultId = text(result.id)
  const evidencePolicy = jsonRow(template.evidencePolicy)
  const resolvedValue = result.value
  const systemPrefilled = result.systemPrefilled === true
  const normalizedResultStatus = text(result.resultStatus).toUpperCase()
  const resultStatus = ['PENDING', 'COMPLETED', 'EXCEPTION', 'NOT_APPLICABLE'].includes(normalizedResultStatus)
    ? normalizedResultStatus as DailyReportItemResult['resultStatus']
    : undefined
  return {
    id: itemResultId || templateItemId || `item:${index}`,
    templateItemId: templateItemId || text(result.templateItemId),
    code: text(template.itemCode || template.code || result.itemCode, `ITEM_${index + 1}`),
    label: text((systemPrefilled && result.label) || template.label || result.label, `日报字段 ${index + 1}`),
    description: text(template.description || template.helpText) || undefined,
    inputType: inputType(template.valueType || template.inputType),
    required: template.required !== false,
    evidenceRequired: template.evidenceRequired === true || evidencePolicy.required === true || number(evidencePolicy.minimumCount) > 0,
    dataSource: dataSource(template.dataSourceType || template.dataSource),
    resultStatus,
    systemValue: systemPrefilled ? displayValue(resolvedValue) : undefined,
    employeeValue: systemPrefilled ? undefined : displayValue(resolvedValue),
    exception: result.exceptionFlag === true || result.resultStatus === 'EXCEPTION',
    sourceLabels: sourceLabelsFor(itemResultId, sourceRows, result.sourceSummary),
    evidence: evidenceRows
      .filter((evidence) => text(evidence.itemResultId) === itemResultId)
      .map(normalizeEvidence),
  }
}

function normalizeSections(configuration: Row, resultRows: Row[], sourceRows: Row[], evidenceRows: Row[]): DailyReportSectionResult[] {
  const configuredItemIds = new Set<string>()
  const sections = rows(configuration.sections).map((value, sectionIndex) => {
    const section = row(value)
    const condition = jsonRow(section.applicabilityCondition)
    const items = rows(section.items).map((item, itemIndex) => {
      const normalized = normalizeItem(item, itemIndex, resultRows, sourceRows, evidenceRows)
      if (normalized.templateItemId) configuredItemIds.add(normalized.templateItemId)
      return normalized
    })
    return {
      id: text(section.id || section.sectionVersionId || section.sectionCode, `section:${sectionIndex}`),
      name: text(section.title || section.name, `日报模块 ${sectionIndex + 1}`),
      conditional: Object.keys(condition).length > 0 || text(section.sectionRole).toUpperCase() === 'CONDITIONAL',
      applicable: section.applicable !== false,
      items,
    }
  })
  const unconfiguredResults = resultRows.filter((result) => !configuredItemIds.has(text(result.templateItemId)))
  if (unconfiguredResults.length) {
    sections.push({
      id: 'unconfigured-results',
      name: '历史填报字段',
      conditional: false,
      applicable: true,
      items: unconfiguredResults.map((result, index) => normalizeItem({
        id: result.templateItemId,
        itemCode: result.itemCode,
        label: result.label,
        required: false,
      }, index, resultRows, sourceRows, evidenceRows)),
    })
  }
  return sections
}

function normalizeSummary(value: unknown): DailyReportSummary {
  const source = row(value)
  return {
    ...source,
    id: text(source.id),
    businessDate: text(source.businessDate),
    hotelName: text(source.hotelName) || undefined,
    departmentName: text(source.departmentName) || undefined,
    positionName: text(source.positionName, '未解析岗位'),
    employeeName: text(source.employeeName) || undefined,
    templateName: text(source.templateName, '岗位日报模板'),
    templateVersionNo: number(source.templateVersionNo),
    status: reportStatus(source.reportStatus || source.status),
    reviewStatus: reviewStatus(source.reviewStatus),
    completionRate: source.completionRate === undefined ? undefined : number(source.completionRate),
    missingRequiredCount: source.missingRequiredCount === undefined ? undefined : number(source.missingRequiredCount),
    exceptionCount: source.exceptionCount === undefined ? undefined : number(source.exceptionCount),
    evidenceCount: source.evidenceCount === undefined ? undefined : number(source.evidenceCount),
    dueAt: text(source.reportDeadlineAt || source.dueAt) || undefined,
    updatedAt: text(source.updatedAt) || undefined,
    rowVersion: number(source.rowVersion),
  } as DailyReportSummary
}

function normalizeRevision(value: unknown): DailyReportRevision {
  const source = row(value)
  const snapshot = jsonRow(source.payloadSnapshot)
  const type = text(source.revisionType).toUpperCase()
  return {
    id: text(source.id),
    revisionNo: number(source.revisionNo),
    revisionType: ['ORIGINAL', 'CORRECTION'].includes(type) ? type as DailyReportRevision['revisionType'] : undefined,
    status: revisionStatus(source.revisionStatus || source.status),
    correctionReason: text(snapshot.correctionReason || source.correctionReason || (type === 'CORRECTION' ? source.narrative : undefined)) || undefined,
    rowVersion: number(source.rowVersion),
    sections: [],
    submittedAt: text(source.submittedAt) || undefined,
    submittedByAccountId: text(source.submittedByAccountId) || undefined,
  }
}

function derivedActions(identity: ApiIdentity, summary: DailyReportSummary, currentRevision: DailyReportRevision): string[] {
  const source = summary as DailyReportSummary & { positionAssignmentId?: string }
  const owner = Boolean(identity.businessActorAssignmentId && source.positionAssignmentId === identity.businessActorAssignmentId)
  const actions: string[] = []
  if (owner && summary.status === 'DRAFT' && currentRevision.status === 'DRAFT') actions.push('EDIT', 'SUBMIT')
  if (owner && ['SUBMITTED', 'ARCHIVED'].includes(summary.status)) actions.push('REQUEST_CORRECTION')
  const independentReviewer = Boolean(identity.businessActorAssignmentId && currentRevision.status === 'SUBMITTED'
    && summary.status === 'SUBMITTED' && summary.reviewStatus === 'PENDING'
    && currentRevision.submittedByAccountId !== identity.actorId)
  if (independentReviewer) actions.push('REVIEW_APPROVE', 'REVIEW_REJECT')
  return actions
}

export function normalizeDetail(value: unknown, identity: ApiIdentity): DailyReportDetail {
  const source = row(value)
  const rawRevisions = rows(source.revisions)
  const revisions = rawRevisions.map(normalizeRevision)
  const currentRevisionId = text(source.currentRevisionId)
  const currentIndex = rawRevisions.findIndex((revision) => text(revision.id) === currentRevisionId)
  const rawCurrent = rawRevisions[currentIndex >= 0 ? currentIndex : 0] || {}
  const currentRevision = revisions[currentIndex >= 0 ? currentIndex : 0] || normalizeRevision({
    id: currentRevisionId,
    revisionNo: source.currentRevisionNo,
    revisionStatus: source.reportStatus,
    rowVersion: 0,
  })
  const snapshot = jsonRow(rawCurrent.payloadSnapshot)
  const configuration = jsonRow(snapshot.resolvedTemplate)
  const autoSummary = jsonRow(snapshot.dailySummary)
  const resolution = jsonRow(snapshot.templateResolution)
  const baseTemplate = jsonRow(resolution.baseTemplate)
  const currentItemRows = rows(source.itemResults).filter((item) => text(item.revisionId) === currentRevision.id)
  const currentSourceRows = rows(source.sources).filter((item) => text(item.revisionId) === currentRevision.id)
  const currentEvidenceRows = rows(source.evidence).filter((item) => text(item.revisionId) === currentRevision.id)
  currentRevision.sections = normalizeSections(configuration, currentItemRows, currentSourceRows, currentEvidenceRows)
  // Historical submitted revisions remain visible exactly as recorded.
  if (currentRevision.status === 'DRAFT') {
    currentRevision.sections = currentRevision.sections.map((section) => ({
      ...section,
      items: section.items.filter((item) => !isGenericWorkQuestion(item.code, item.label)).map((item) =>
        isOccurrenceEntry(item.code) ? { ...item, required: false, evidenceRequired: false,
          description: '发生时登记，未发生无需填报；记录会自动汇总到日报。',
          systemValue: item.systemValue || '未登记发生记录' } : item),
    }))
  }
  const allItems = currentRevision.sections.flatMap((section) => section.items)
  const completedItems = allItems.filter((item) => item.exception
    || item.resultStatus === 'COMPLETED'
    || item.resultStatus === 'NOT_APPLICABLE'
    || Boolean(item.employeeValue?.trim()))
  const missingRequiredCount = allItems.filter((item) => item.required
    && !item.exception
    && item.resultStatus !== 'COMPLETED'
    && item.resultStatus !== 'NOT_APPLICABLE'
    && !item.employeeValue?.trim()).length
  const summary = normalizeSummary({
    ...source,
    templateName: source.templateName || configuration.title || baseTemplate.name,
    templateVersionNo: source.templateVersionNo ?? baseTemplate.versionNo,
    completionRate: autoSummary.expectedCount !== undefined
      ? (number(autoSummary.expectedCount) > 0 ? Math.min(100, Math.round(number(autoSummary.completedCount) / number(autoSummary.expectedCount) * 100)) : 0)
      : (allItems.length ? Math.round((completedItems.length / allItems.length) * 100) : 0),
    missingRequiredCount,
    exceptionCount: autoSummary.exceptionCount === undefined ? allItems.filter((item) => item.exception).length : number(autoSummary.exceptionCount),
    evidenceCount: currentEvidenceRows.length,
  })
  const allowedActions = Array.isArray(source.allowedActions)
    ? source.allowedActions.map(String)
    : derivedActions(identity, summary, currentRevision)
  const blockedActionReasons = row(source.blockedActionReasons) as Record<string, string>
  if (missingRequiredCount > 0 && !blockedActionReasons.SUBMIT) {
    blockedActionReasons.SUBMIT = `仍有 ${missingRequiredCount} 个必填项未完成`
  }
  return {
    ...summary,
    inventoryContext: source.inventoryContext as DailyReportDetail['inventoryContext'],
    inventoryQuantities: snapshot.inventoryQuantities,
    roomCounts: snapshot.roomCounts as DailyReportDetail['roomCounts'],
    plannedHygieneSupported: source.plannedHygieneSupported === true,
    plannedHygiene: snapshot.plannedHygiene as DailyReportDetail['plannedHygiene'],
    currentRevision,
    revisions,
    autoSummary: Object.keys(autoSummary).length ? {
      mode: text(autoSummary.mode),
      workItems: rows(autoSummary.workItems).map((item) => ({
        label: text(item.label), status: text(item.status), completion: text(item.completion),
        details: displayWorkForm(item.formData), exception: text(item.exception), nextAction: text(item.nextAction),
      })),
      usageItems: rows(autoSummary.usageItems).map((item) => ({
        itemName: text(item.itemName), category: text(item.category), unit: text(item.unit),
        approvedQuantity: text(item.approvedQuantity, '0'), pendingQuantity: text(item.pendingQuantity, '0'),
      })),
      usageRecordCount: number(autoSummary.usageRecordCount),
      excludedUsageRecordCount: number(autoSummary.excludedUsageRecordCount),
    } : undefined,
    aiRecommendations: rows(source.aiRecommendations).map((recommendation, index) => ({
      id: text(recommendation.id, `ai:${index}`),
      facts: displayFactSummary(recommendation.facts),
      analysis: text(recommendation.analysis, '暂无补充分析'),
      recommendation: text(recommendation.recommendation, '暂无建议内容'),
      sourceLabels: Array.isArray(recommendation.sourceLabels)
        ? [...new Set(recommendation.sourceLabels.map((label) => text(label).startsWith('DAILY_REPORT:') ? '本日报当日工作与耗用记录' : text(label)))] : [],
      createdAt: text(recommendation.createdAt),
    })),
    allowedActions,
    blockedActionReasons,
  }
}

function requiredAssignmentId(identity: ApiIdentity): string {
  if (!identity.businessActorAssignmentId) throw new Error('请先选择当前任职后再执行该操作')
  return identity.businessActorAssignmentId
}

export async function loadMyDailyReports(identity: ApiIdentity, signal: AbortSignal, filters: { businessDate?: string; status?: string; positionAssignmentId?: string } = {}) {
  const endpoint = `${base}/my${queryString(filters)}`
  const payload = await featureApiRequest<unknown[] | PageEnvelope<unknown>>(endpoint, identity, { signal })
  return requireItems(payload, endpoint).map(normalizeSummary)
}

export async function loadCurrentBusinessDay(identity: ApiIdentity, orgUnitId: string, signal: AbortSignal): Promise<CurrentBusinessDay> {
  if (!orgUnitId) throw new Error('当前岗位缺少可解析营业日的组织范围')
  const endpoint = `/business-days/current${queryString({ orgUnitId })}`
  const source = row(await featureApiRequest<unknown>(endpoint, identity, { signal }))
  const businessDate = text(source.businessDate)
  if (!businessDate) throw new Error('服务端未返回当前营业日')
  return {
    hotelOrgUnitId: text(source.hotelOrgUnitId),
    orgUnitId: text(source.orgUnitId, orgUnitId),
    businessDate,
    timezone: text(source.timezone),
    cutoffLocalTime: text(source.cutoffLocalTime),
    closingGraceMinutes: number(source.closingGraceMinutes),
    resolvedAt: text(source.resolvedAt),
    currentBusinessDay: source.currentBusinessDay !== false,
  }
}

export async function loadDailyReportOrgOptions(identity: ApiIdentity, signal: AbortSignal): Promise<DailyReportOrgOption[]> {
  const endpoint = '/org/units?unitType=HOTEL'
  const payload = await featureApiRequest<unknown[] | PageEnvelope<unknown>>(endpoint, identity, { signal })
  return requireItems(payload, endpoint)
    .map(row)
    .filter((source) => text(source.status).toUpperCase() === 'ACTIVE' && text(source.unit_type ?? source.unitType).toUpperCase() === 'HOTEL')
    .map((source) => ({
      id: text(source.id),
      code: text(source.property_code ?? source.propertyCode ?? source.code),
      name: text(source.name),
    }))
    .filter((source) => Boolean(source.id && source.name))
}

export async function loadTeamDailyReports(identity: ApiIdentity, signal: AbortSignal, filters: { businessDate?: string; status?: string; orgUnitId?: string } = {}) {
  if (!filters.orgUnitId) throw new Error('团队日报需要先选择组织范围')
  const endpoint = `${base}/team${queryString(filters)}`
  const payload = await featureApiRequest<unknown[] | PageEnvelope<unknown>>(endpoint, identity, { signal })
  return requireItems(payload, endpoint).map(normalizeSummary)
}

export async function loadDailyReport(identity: ApiIdentity, reportId: string, signal?: AbortSignal) {
  return normalizeDetail(await featureApiRequest<unknown>(`${base}/${encodeURIComponent(reportId)}`, identity, { signal }), identity)
}

export async function createDailyReport(identity: ApiIdentity, input: { businessDate: string; orgUnitId: string; positionAssignmentId: string; templateVersionId?: string }, idempotencyKey: string) {
  return normalizeDetail(await featureApiMutation<unknown>(base, identity, { body: input, idempotencyKey }), identity)
}

function commandValue(value: string | undefined, type: DailyReportItemResult['inputType']): unknown {
  if (value === undefined || value.trim() === '') return null
  if (type === 'NUMBER') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : value
  }
  if (type === 'BOOLEAN') return value.toLowerCase() === 'true'
  return value
}

export async function saveDailyReportDraft(identity: ApiIdentity, report: DailyReportDetail, input: DailyReportDraftInput, idempotencyKey: string) {
  const itemTypes = new Map(report.currentRevision.sections.flatMap((section) => section.items.map((item) => [item.templateItemId, item.inputType] as const)))
  const body = {
    revisionId: report.currentRevision.id,
    narrative: input.narrative,
    inventoryQuantities: input.inventoryQuantities,
    roomCounts: input.roomCounts,
    plannedHygiene: input.plannedHygiene,
    items: input.itemValues.map((item) => ({
      templateItemId: item.templateItemId,
      value: commandValue(item.value, itemTypes.get(item.templateItemId) || 'TEXT'),
      confirmed: Boolean(item.value?.trim()),
      exception: item.exception,
      comment: item.comment,
    })),
  }
  return normalizeDetail(await featureApiMutation<unknown>(`${base}/${encodeURIComponent(report.id)}/draft`, identity, {
    method: 'PUT', body, expectedVersion: report.rowVersion, idempotencyKey,
  }), identity)
}

export async function submitDailyReport(identity: ApiIdentity, report: DailyReportDetail, idempotencyKey: string) {
  return normalizeDetail(await featureApiMutation<unknown>(`${base}/${encodeURIComponent(report.id)}/actions/submit`, identity, {
    body: { revisionId: report.currentRevision.id }, expectedVersion: report.rowVersion, idempotencyKey,
  }), identity)
}

export async function requestDailyReportCorrection(identity: ApiIdentity, report: DailyReportDetail, reason: string, idempotencyKey: string) {
  return normalizeDetail(await featureApiMutation<unknown>(`${base}/${encodeURIComponent(report.id)}/corrections`, identity, {
    body: { reason }, expectedVersion: report.rowVersion, idempotencyKey,
  }), identity)
}

export async function reviewDailyReport(identity: ApiIdentity, report: DailyReportDetail, outcome: 'APPROVED' | 'REJECTED', reason: string, idempotencyKey: string) {
  return normalizeDetail(await featureApiMutation<unknown>(`${base}/${encodeURIComponent(report.id)}/reviews`, identity, {
    body: { outcome, comment: reason, reviewerAssignmentId: requiredAssignmentId(identity) },
    expectedVersion: report.rowVersion,
    idempotencyKey,
  }), identity)
}

export function reviewDailyReportRevision(identity: ApiIdentity, report: DailyReportDetail, revisionId: string, outcome: 'APPROVED' | 'REJECTED', reason: string, idempotencyKey: string) {
  if (revisionId !== report.currentRevision.id) {
    return Promise.reject(new Error('后端仅允许审核当前修订；历史修订保持只读'))
  }
  return reviewDailyReport(identity, report, outcome, reason, idempotencyKey)
}
