export type TemplateLifecycleStatus = 'DRAFT' | 'IN_REVIEW' | 'PUBLISHED' | 'RETIRED'
export type TemplateOwnership = 'HEADQUARTERS' | 'STORE_SUPPLEMENT'

export type DailyReportDeliveryTiming = {
  mode: 'FIXED_LOCAL' | 'SHIFT_END'
  openLocalTime?: string
  dueLocalTime?: string
  openOffsetMinutes?: number
  dueOffsetMinutes?: number
}

export type DailyReportTemplateSummary = {
  id: string
  code: string
  name: string
  ownership: TemplateOwnership
  ownerOrgUnitId?: string
  positionId?: string
  baseTemplateDefinitionId?: string
  ownerOrgName?: string
  positionName?: string
  workPackageName?: string
  currentVersionId?: string
  versionNo?: number
  lifecycleStatus: TemplateLifecycleStatus
  effectiveFrom?: string
  effectiveTo?: string
  updatedAt?: string
}

export type DailyReportTemplateItem = {
  id: string
  code: string
  label: string
  description?: string
  inputType: 'TEXT' | 'NUMBER' | 'BOOLEAN' | 'SELECT' | 'NARRATIVE' | 'EVIDENCE'
  required: boolean
  evidenceRequired: boolean
  dataSource: 'EMPLOYEE' | 'SYSTEM' | 'MIXED'
  standardReference?: string
  displayOrder?: number
  workPackageItemId?: string
  standardVersionId?: string
  metricId?: string
  config?: {
    originalDataSourceType?: string
    originalValueType?: string
    dataSourceConfig?: Record<string, unknown>
    evidencePolicy?: Record<string, unknown>
    validationRules?: Record<string, unknown>
    optionValues?: unknown[]
  }
}

export type DailyReportTemplateSection = {
  id: string
  code: string
  name: string
  description?: string
  conditional: boolean
  conditionDescription?: string
  source: TemplateOwnership
  required?: boolean
  displayOrder?: number
  config?: {
    applicabilityCondition?: Record<string, unknown>
    sectionRole?: string
  }
  items: DailyReportTemplateItem[]
}

export type DailyReportTemplateVersion = {
  id: string
  versionNo: number
  lifecycleStatus: TemplateLifecycleStatus
  title: string
  description?: string
  workPackageVersionId?: string
  effectiveFrom?: string
  effectiveTo?: string
  rowVersion: number
  sections: DailyReportTemplateSection[]
  allowedActions: string[]
  createdAt?: string
  deliveryTiming?: DailyReportDeliveryTiming
  publicationTimeZone?: string
}

export type DailyReportTemplateDetail = DailyReportTemplateSummary & {
  versions: DailyReportTemplateVersion[]
  allowedActions: string[]
}

export type TemplateVersionDraft = {
  title: string
  description?: string
  effectiveFrom?: string
  effectiveTo?: string
  sections: DailyReportTemplateSection[]
  deliveryTiming?: DailyReportDeliveryTiming
}

export type DailyReportDeliveryPolicy = {
  id?: string
  templateAssignmentId?: string
  enabled: boolean
  openLocalTime: string
  dueLocalTime: string
  graceMinutes: number
  preDueReminderMinutes: number[]
  overdueReminderMinutes: number[]
  backfillDays: number
  timeZone?: string
  rowVersion: number
  updatedAt?: string
  configured: boolean
  deliveryTiming?: DailyReportDeliveryTiming
}

export type DailyReportDeliveryPolicyDraft = Pick<
  DailyReportDeliveryPolicy,
  'enabled' | 'openLocalTime' | 'dueLocalTime' | 'graceMinutes' | 'preDueReminderMinutes' | 'overdueReminderMinutes' | 'backfillDays'
>
