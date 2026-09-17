export type DataRetentionPolicy = {
  imageRetentionDays: number
  documentRetentionDays: number
  employeeMetricRetentionMonths: number
  pmsRetentionMonths: number
  otaRetentionMonths: number
  kpiRetentionMonths: number
  rawPayloadRetentionDays: number
  imageCleanupEnabled: boolean
  version: number
  updatedAt: string | null
  updatedBy: string | null
}

export type DataRetentionPreview = {
  candidateCount: number
  totalSizeBytes: number
  oldestRetentionUntil: string | null
  newestRetentionUntil: string | null
  generatedAt: string | null
  cleanupEnabled: boolean
}

export const retentionNumericFields = [
  'imageRetentionDays',
  'documentRetentionDays',
  'employeeMetricRetentionMonths',
  'pmsRetentionMonths',
  'otaRetentionMonths',
  'kpiRetentionMonths',
  'rawPayloadRetentionDays',
] as const

export type RetentionNumericField = typeof retentionNumericFields[number]

export type DataRetentionDraft = Record<RetentionNumericField, string> & {
  imageCleanupEnabled: boolean
}

export type DataRetentionUpdateRequest = Record<RetentionNumericField, number> & {
  imageCleanupEnabled: boolean
  expectedVersion: number
}

export const retentionLimits: Readonly<Record<RetentionNumericField, Readonly<{ min: number; max: number }>>> = Object.freeze({
  imageRetentionDays: Object.freeze({ min: 1, max: 365 }),
  documentRetentionDays: Object.freeze({ min: 1, max: 3650 }),
  employeeMetricRetentionMonths: Object.freeze({ min: 1, max: 120 }),
  pmsRetentionMonths: Object.freeze({ min: 1, max: 120 }),
  otaRetentionMonths: Object.freeze({ min: 1, max: 120 }),
  kpiRetentionMonths: Object.freeze({ min: 1, max: 120 }),
  rawPayloadRetentionDays: Object.freeze({ min: 1, max: 3650 }),
})

const administratorRoles = new Set(['PLATFORM_ADMIN', 'GROUP_ADMIN'])

function record(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}_INVALID`)
  return value as Record<string, unknown>
}

function integer(source: Record<string, unknown>, key: string, min = 0): number {
  const value = source[key]
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min) {
    throw new Error(`DATA_RETENTION_${key.toUpperCase()}_INVALID`)
  }
  return value
}

function nonNegativeNumber(source: Record<string, unknown>, key: string): number {
  const value = source[key]
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new Error(`DATA_RETENTION_${key.toUpperCase()}_INVALID`)
  }
  return value
}

function bool(source: Record<string, unknown>, key: string): boolean {
  const value = source[key]
  if (typeof value !== 'boolean') throw new Error(`DATA_RETENTION_${key.toUpperCase()}_INVALID`)
  return value
}

function nullableText(source: Record<string, unknown>, key: string): string | null {
  const value = source[key]
  if (value === undefined || value === null || value === '') return null
  if (typeof value !== 'string') throw new Error(`DATA_RETENTION_${key.toUpperCase()}_INVALID`)
  return value
}

export function hasDataRetentionAdministrationRole(roleCodes: readonly string[]): boolean {
  return roleCodes.some((roleCode) => administratorRoles.has(roleCode))
}

export function parseDataRetentionPolicy(value: unknown): DataRetentionPolicy {
  const source = record(value, 'DATA_RETENTION_POLICY')
  const policy = {
    imageRetentionDays: integer(source, 'imageRetentionDays', 1),
    documentRetentionDays: integer(source, 'documentRetentionDays', 1),
    employeeMetricRetentionMonths: integer(source, 'employeeMetricRetentionMonths', 1),
    pmsRetentionMonths: integer(source, 'pmsRetentionMonths', 1),
    otaRetentionMonths: integer(source, 'otaRetentionMonths', 1),
    kpiRetentionMonths: integer(source, 'kpiRetentionMonths', 1),
    rawPayloadRetentionDays: integer(source, 'rawPayloadRetentionDays', 1),
    imageCleanupEnabled: bool(source, 'imageCleanupEnabled'),
    version: integer(source, 'version'),
    updatedAt: nullableText(source, 'updatedAt'),
    updatedBy: nullableText(source, 'updatedBy'),
  }
  const outsideLimits = retentionNumericFields.some((key) => policy[key] > retentionLimits[key].max)
  if (outsideLimits) throw new Error('DATA_RETENTION_POLICY_INVALID')
  return policy
}

export function parseDataRetentionPreview(value: unknown): DataRetentionPreview {
  const source = record(value, 'DATA_RETENTION_PREVIEW')
  return {
    candidateCount: integer(source, 'candidateCount'),
    totalSizeBytes: nonNegativeNumber(source, 'totalSizeBytes'),
    oldestRetentionUntil: nullableText(source, 'oldestRetentionUntil'),
    newestRetentionUntil: nullableText(source, 'newestRetentionUntil'),
    generatedAt: nullableText(source, 'generatedAt'),
    cleanupEnabled: bool(source, 'cleanupEnabled'),
  }
}

export function dataRetentionDraftFrom(policy: DataRetentionPolicy): DataRetentionDraft {
  return {
    imageRetentionDays: String(policy.imageRetentionDays),
    documentRetentionDays: String(policy.documentRetentionDays),
    employeeMetricRetentionMonths: String(policy.employeeMetricRetentionMonths),
    pmsRetentionMonths: String(policy.pmsRetentionMonths),
    otaRetentionMonths: String(policy.otaRetentionMonths),
    kpiRetentionMonths: String(policy.kpiRetentionMonths),
    rawPayloadRetentionDays: String(policy.rawPayloadRetentionDays),
    imageCleanupEnabled: policy.imageCleanupEnabled,
  }
}

export function validateDataRetentionDraft(draft: DataRetentionDraft): Partial<Record<RetentionNumericField, string>> {
  const errors: Partial<Record<RetentionNumericField, string>> = {}
  retentionNumericFields.forEach((key) => {
    const raw = draft[key].trim()
    const value = Number(raw)
    const { min, max } = retentionLimits[key]
    if (!raw || !Number.isSafeInteger(value) || value < min || value > max) {
      errors[key] = `请输入 ${min} 至 ${max} 的整数`
    }
  })
  return errors
}

export function buildDataRetentionUpdateRequest(
  draft: DataRetentionDraft,
  expectedVersion: number,
): DataRetentionUpdateRequest {
  if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 0) throw new Error('DATA_RETENTION_VERSION_INVALID')
  if (Object.keys(validateDataRetentionDraft(draft)).length) throw new Error('DATA_RETENTION_DRAFT_INVALID')
  return {
    imageRetentionDays: Number(draft.imageRetentionDays),
    documentRetentionDays: Number(draft.documentRetentionDays),
    employeeMetricRetentionMonths: Number(draft.employeeMetricRetentionMonths),
    pmsRetentionMonths: Number(draft.pmsRetentionMonths),
    otaRetentionMonths: Number(draft.otaRetentionMonths),
    kpiRetentionMonths: Number(draft.kpiRetentionMonths),
    rawPayloadRetentionDays: Number(draft.rawPayloadRetentionDays),
    imageCleanupEnabled: draft.imageCleanupEnabled,
    expectedVersion,
  }
}

export function formatRetentionBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  const scaled = value / 1024 ** index
  const digits = scaled >= 100 || index === 0 ? 0 : scaled >= 10 ? 1 : 2
  return `${scaled.toFixed(digits)} ${units[index]}`
}

export function formatRetentionTimestamp(value: string | null): string {
  if (!value) return '暂无候选'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(parsed)
}
