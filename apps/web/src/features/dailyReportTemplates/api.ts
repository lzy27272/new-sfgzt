import type { ApiIdentity } from '../../api/client'
import { featureApiMutation, featureApiRequest } from '../shared/featureApi'
import { queryString, requireItems, type PageEnvelope } from '../shared/apiEnvelope'
import type {
  DailyReportDeliveryPolicy,
  DailyReportDeliveryPolicyDraft,
  DailyReportTemplateDetail,
  DailyReportTemplateItem,
  DailyReportTemplateSection,
  DailyReportTemplateSummary,
  DailyReportTemplateVersion,
  TemplateLifecycleStatus,
  TemplateOwnership,
  TemplateVersionDraft,
} from './types'

const base = '/daily-report-templates'
type Row = Record<string, unknown>

const row = (value: unknown): Row => value && typeof value === 'object' && !Array.isArray(value) ? value as Row : {}
const rows = (value: unknown): Row[] => Array.isArray(value) ? value.map(row) : []
const text = (value: unknown, fallback = '') => value === undefined || value === null ? fallback : String(value)
const number = (value: unknown, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback

function jsonRow(value: unknown): Row {
  if (typeof value === 'string') {
    try { return row(JSON.parse(value)) } catch { return {} }
  }
  const source = row(value)
  if (typeof source.value === 'string' && Object.keys(source).every((key) => ['type', 'value'].includes(key))) {
    try { return row(JSON.parse(source.value)) } catch { return {} }
  }
  return source
}

function ownership(value: unknown): TemplateOwnership {
  return ['STORE', 'STORE_SUPPLEMENT'].includes(text(value).toUpperCase()) ? 'STORE_SUPPLEMENT' : 'HEADQUARTERS'
}

function origin(value: TemplateOwnership): 'HQ' | 'STORE' {
  return value === 'STORE_SUPPLEMENT' ? 'STORE' : 'HQ'
}

function lifecycle(value: unknown): TemplateLifecycleStatus {
  const normalized = text(value).toUpperCase()
  return ['DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED'].includes(normalized)
    ? normalized as TemplateLifecycleStatus
    : 'DRAFT'
}

function inputType(value: unknown): DailyReportTemplateItem['inputType'] {
  const normalized = text(value, 'TEXT').toUpperCase()
  if (['SINGLE_SELECT', 'MULTI_SELECT', 'SELECT'].includes(normalized)) return 'SELECT'
  if (['LONG_TEXT', 'NARRATIVE'].includes(normalized)) return 'NARRATIVE'
  if (['WORK_RECORD_REFERENCE', 'METRIC_REFERENCE', 'EVIDENCE'].includes(normalized)) return 'EVIDENCE'
  if (normalized === 'NUMBER' || normalized === 'BOOLEAN') return normalized
  return 'TEXT'
}

function valueType(value: DailyReportTemplateItem['inputType']): string {
  if (value === 'SELECT') return 'SINGLE_SELECT'
  if (value === 'NARRATIVE') return 'LONG_TEXT'
  if (value === 'EVIDENCE') return 'WORK_RECORD_REFERENCE'
  return value
}

function dataSource(value: unknown): DailyReportTemplateItem['dataSource'] {
  const normalized = text(value, 'MANUAL').toUpperCase()
  if (['MANUAL', 'EMPLOYEE'].includes(normalized)) return 'EMPLOYEE'
  if (normalized === 'SYSTEM') return 'SYSTEM'
  return 'MIXED'
}

function dataSourceType(value: DailyReportTemplateItem['dataSource']): string {
  if (value === 'EMPLOYEE') return 'MANUAL'
  return value
}

function versionActions(status: TemplateLifecycleStatus): string[] {
  if (status === 'DRAFT') return ['SUBMIT_REVIEW']
  if (status === 'IN_REVIEW') return ['PUBLISH']
  if (status === 'PUBLISHED') return ['RETIRE']
  return []
}

function normalizeItem(value: unknown, index: number): DailyReportTemplateItem {
  const source = row(value)
  const evidencePolicy = jsonRow(source.evidencePolicy)
  const validationRules = jsonRow(source.validationRules)
  const dataSourceConfig = jsonRow(source.dataSourceConfig)
  const optionValues = Array.isArray(source.optionValues) ? source.optionValues : []
  const code = text(source.itemCode || source.code, `ITEM_${index + 1}`)
  return {
    id: text(source.id, code),
    code,
    label: text(source.label || source.title, code),
    description: text(source.description || source.helpText) || undefined,
    inputType: inputType(source.valueType || source.inputType),
    required: source.required !== false,
    evidenceRequired: source.evidenceRequired === true || evidencePolicy.required === true || number(evidencePolicy.minimumCount) > 0,
    dataSource: dataSource(source.dataSourceType || source.dataSource),
    standardReference: text(source.standardReference || source.standardVersionId) || undefined,
    displayOrder: number(source.sortOrder ?? source.displayOrder, index),
    workPackageItemId: text(source.workPackageItemId) || undefined,
    standardVersionId: text(source.standardVersionId) || undefined,
    metricId: text(source.metricId) || undefined,
    config: { dataSourceConfig, evidencePolicy, validationRules, optionValues,
      originalDataSourceType: text(source.dataSourceType || source.dataSource, 'MANUAL'),
      originalValueType: text(source.valueType || source.inputType, 'TEXT') },
  }
}

function normalizeSection(value: unknown, index: number): DailyReportTemplateSection {
  const source = row(value)
  const applicabilityCondition = jsonRow(source.applicabilityCondition)
  const sectionOrigin = ownership(source.sectionOrigin || source.source)
  const code = text(source.sectionCode || source.code, `SECTION_${index + 1}`)
  const conditional = source.conditional === true || Object.keys(applicabilityCondition).length > 0 || text(source.sectionRole).toUpperCase() === 'CONDITIONAL'
  return {
    id: text(source.id || source.sectionVersionId, code),
    code,
    name: text(source.title || source.name, code),
    description: text(source.description) || undefined,
    conditional,
    conditionDescription: text(source.conditionDescription) || (conditional ? JSON.stringify(applicabilityCondition) : undefined),
    source: sectionOrigin,
    required: source.required !== false,
    displayOrder: number(source.sortOrder ?? source.displayOrder, index),
    config: { applicabilityCondition, sectionRole: text(source.sectionRole) || undefined },
    items: rows(source.items).map(normalizeItem),
  }
}

function normalizeVersion(value: unknown): DailyReportTemplateVersion {
  const source = row(value)
  const configuration = jsonRow(source.configuration)
  const status = lifecycle(source.lifecycleStatus || source.status)
  return {
    ...source,
    id: text(source.id),
    versionNo: number(source.versionNo),
    lifecycleStatus: status,
    title: text(configuration.title || source.title, `模板版本 V${number(source.versionNo)}`),
    description: text(configuration.description || source.description) || undefined,
    workPackageVersionId: text(source.workPackageVersionId) || undefined,
    effectiveFrom: text(source.effectiveFrom) || undefined,
    effectiveTo: text(source.effectiveTo) || undefined,
    rowVersion: number(source.rowVersion),
    sections: rows(configuration.sections ?? source.sections).map(normalizeSection),
    deliveryTiming: configuration.deliveryTiming as DailyReportTemplateVersion['deliveryTiming'],
    publicationTimeZone: text(source.publicationTimeZone) || undefined,
    allowedActions: Array.isArray(source.allowedActions) ? source.allowedActions.map(String) : versionActions(status),
    createdAt: text(source.createdAt) || undefined,
  } as DailyReportTemplateVersion
}

function normalizeSummary(value: unknown): DailyReportTemplateSummary {
  const source = row(value)
  const status = lifecycle(source.latestVersionStatus || source.lifecycleStatus)
  return {
    ...source,
    id: text(source.id),
    code: text(source.code),
    name: text(source.name, '未命名日报模板'),
    ownership: ownership(source.templateOrigin || source.ownership),
    ownerOrgUnitId: text(source.ownerOrgUnitId) || undefined,
    positionId: text(source.positionId) || undefined,
    baseTemplateDefinitionId: text(source.baseTemplateDefinitionId) || undefined,
    ownerOrgName: text(source.ownerOrgName) || undefined,
    positionName: text(source.positionName) || undefined,
    workPackageName: text(source.workPackageName) || undefined,
    currentVersionId: text(source.latestVersionId || source.currentVersionId) || undefined,
    versionNo: source.latestVersionNo === undefined && source.versionNo === undefined ? undefined : number(source.latestVersionNo ?? source.versionNo),
    lifecycleStatus: status,
    effectiveFrom: text(source.effectiveFrom) || undefined,
    effectiveTo: text(source.effectiveTo) || undefined,
    updatedAt: text(source.updatedAt) || undefined,
  } as DailyReportTemplateSummary
}

function normalizeDetail(value: unknown): DailyReportTemplateDetail {
  const source = row(value)
  const versions = rows(source.versions).map(version => normalizeVersion({ ...version,
    publicationTimeZone: version.publicationTimeZone ?? source.publicationTimeZone }))
  const current = versions[0]
  const summary = normalizeSummary({
    ...source,
    latestVersionId: source.latestVersionId || current?.id,
    latestVersionNo: source.latestVersionNo ?? current?.versionNo,
    latestVersionStatus: source.latestVersionStatus || current?.lifecycleStatus,
  })
  return {
    ...summary,
    versions,
    allowedActions: Array.isArray(source.allowedActions) ? source.allowedActions.map(String) : [],
  }
}

function uuidOrUndefined(value: string): string | undefined {
  // Seeded PostgreSQL UUIDs include deterministic hashes, not just RFC version 1–5 identifiers.
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value) ? value : undefined
}

function numberList(value: unknown, fallback: number[]): number[] {
  const supplied = Array.isArray(value) || typeof value === 'string'
  const source = Array.isArray(value)
    ? value
    : typeof value === 'string'
      ? value.split(',')
      : []
  const normalized = source
    .map((item) => typeof item === 'string' ? item.trim() : item)
    .filter((item) => item !== '')
    .map((item) => Number(item))
    .filter((item) => Number.isInteger(item) && item >= 0)
  return supplied ? [...new Set(normalized)].sort((left, right) => left - right) : fallback
}

function normalizeDeliveryPolicy(value: unknown): DailyReportDeliveryPolicy {
  const source = row(value)
  return {
    id: text(source.id) || undefined,
    templateAssignmentId: text(source.templateAssignmentId) || undefined,
    enabled: source.enabled === true,
    openLocalTime: text(source.openLocalTime, '22:00').slice(0, 5),
    dueLocalTime: text(source.dueLocalTime, '23:00').slice(0, 5),
    graceMinutes: Math.max(0, number(source.graceMinutes, 30)),
    preDueReminderMinutes: numberList(source.preDueReminderMinutes, [30]),
    overdueReminderMinutes: numberList(source.overdueReminderMinutes, [0, 30]),
    backfillDays: Math.max(0, number(source.backfillDays, 1)),
    timeZone: text(source.timeZone) || undefined,
    rowVersion: number(source.rowVersion),
    updatedAt: text(source.updatedAt) || undefined,
    configured: Boolean(source.id || source.templateAssignmentId || source.configured === true),
    deliveryTiming: source.deliveryTiming as DailyReportDeliveryPolicy['deliveryTiming'],
  }
}

function versionUpdateBody(draft: TemplateVersionDraft) {
  return {
    title: draft.title,
    description: draft.description,
    deliveryTiming: draft.deliveryTiming,
    sections: draft.sections.map((section, sectionIndex) => ({
      id: uuidOrUndefined(section.id),
      sectionCode: section.code,
      title: section.name,
      description: section.description,
      sectionOrigin: origin(section.source),
      applicabilityCondition: section.config?.applicabilityCondition || {},
      sectionRole: section.config?.sectionRole || (section.source === 'STORE_SUPPLEMENT' ? 'SUPPLEMENT' : section.conditional ? 'CONDITIONAL' : 'BASE'),
      required: section.required !== false,
      sortOrder: section.displayOrder ?? sectionIndex,
      items: section.items.map((item, itemIndex) => ({
        id: uuidOrUndefined(item.id),
        itemCode: item.code,
        label: item.label,
        description: item.description,
        valueType: item.config?.originalValueType || valueType(item.inputType),
        required: item.required,
        workPackageItemId: item.workPackageItemId,
        standardVersionId: item.standardVersionId,
        metricId: item.metricId,
        dataSourceType: item.config?.originalDataSourceType || dataSourceType(item.dataSource),
        dataSourceConfig: item.config?.dataSourceConfig || {},
        evidencePolicy: { ...(item.config?.evidencePolicy || {}), ...(item.evidenceRequired ? { required: true } : {}) },
        validationRules: item.config?.validationRules || {},
        optionValues: item.config?.optionValues || [],
        sortOrder: item.displayOrder ?? itemIndex,
      })),
    })),
  }
}

export async function loadDailyReportTemplates(identity: ApiIdentity, signal: AbortSignal, filters: { status?: string; ownership?: string } = {}) {
  const payload = await featureApiRequest<unknown[] | PageEnvelope<unknown>>(`${base}${queryString({ status: filters.status })}`, identity, { signal })
  const templates = requireItems(payload, base).map(normalizeSummary)
  return filters.ownership ? templates.filter((template) => template.ownership === filters.ownership) : templates
}

export async function loadDailyReportTemplate(identity: ApiIdentity, templateId: string, signal?: AbortSignal) {
  return normalizeDetail(await featureApiRequest<unknown>(`${base}/${encodeURIComponent(templateId)}`, identity, { signal }))
}

export async function createDailyReportTemplate(identity: ApiIdentity, input: {
  code: string
  name: string
  description?: string
  positionId: string
  templateOrigin: 'HQ' | 'STORE'
  ownerOrgUnitId: string
  baseTemplateDefinitionId?: string
}, idempotencyKey: string) {
  return normalizeDetail(await featureApiMutation<unknown>(base, identity, { body: input, idempotencyKey }))
}

export async function createDailyReportTemplateVersion(identity: ApiIdentity, templateId: string, draft: TemplateVersionDraft & { workPackageVersionId: string }, idempotencyKey: string) {
  return normalizeVersion(await featureApiMutation<unknown>(`${base}/${encodeURIComponent(templateId)}/versions`, identity, {
    body: { title: draft.title, description: draft.description, workPackageVersionId: draft.workPackageVersionId },
    idempotencyKey,
  }))
}

export async function saveDailyReportTemplateVersion(identity: ApiIdentity, templateId: string, version: DailyReportTemplateVersion, draft: TemplateVersionDraft, idempotencyKey: string) {
  return normalizeVersion(await featureApiMutation<unknown>(`${base}/${encodeURIComponent(templateId)}/versions/${encodeURIComponent(version.id)}`, identity, {
    method: 'PUT', body: versionUpdateBody(draft), expectedVersion: version.rowVersion, idempotencyKey,
  }))
}

export async function transitionDailyReportTemplateVersion(
  identity: ApiIdentity,
  templateId: string,
  version: DailyReportTemplateVersion,
  action: 'submit-review' | 'publish' | 'retire',
  idempotencyKey: string,
  effectiveFrom?: string,
) {
  return normalizeVersion(await featureApiMutation<unknown>(`${base}/${encodeURIComponent(templateId)}/versions/${encodeURIComponent(version.id)}/actions/${action}`, identity, {
    body: { effectiveFrom: effectiveFrom ?? version.effectiveFrom, effectiveTo: version.effectiveTo },
    expectedVersion: version.rowVersion,
    idempotencyKey,
  }))
}

export async function loadDailyReportDeliveryPolicy(identity: ApiIdentity, templateId: string, versionId: string, signal?: AbortSignal) {
  const endpoint = `${base}/${encodeURIComponent(templateId)}/versions/${encodeURIComponent(versionId)}/delivery-policy`
  return normalizeDeliveryPolicy(await featureApiRequest<unknown>(endpoint, identity, { signal }))
}

export async function saveDailyReportDeliveryPolicy(
  identity: ApiIdentity,
  templateId: string,
  versionId: string,
  policy: DailyReportDeliveryPolicy,
  draft: DailyReportDeliveryPolicyDraft,
  idempotencyKey: string,
) {
  const endpoint = `${base}/${encodeURIComponent(templateId)}/versions/${encodeURIComponent(versionId)}/delivery-policy`
  return normalizeDeliveryPolicy(await featureApiMutation<unknown>(endpoint, identity, {
    method: 'PUT',
    body: { ...draft, expectedVersion: policy.rowVersion },
    expectedVersion: policy.rowVersion,
    idempotencyKey,
  }))
}
