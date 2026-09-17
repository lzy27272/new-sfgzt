export type JsonRecord = Record<string, unknown>

export type WorkPackageOption = { id: string; name: string; code?: string }

export type EvidenceRequirementDraft = {
  key: string
  checkpointCode: string
  label: string
  captureSource: 'CAMERA' | 'FILE' | 'CAMERA_OR_FILE'
  minimum: string
  recommendedMaximum: string
  requiredInstances: string
  instanceField: string
  instanceLabel: string
  minimumPerInstance: string
  requiredWhenField: string
  requiredWhenEquals: '' | 'true' | 'false'
  mediaTypes: string
  extras: JsonRecord
}

export type ReminderMomentDraft = {
  key: string
  code: string
  kind: 'REMINDER' | 'PROGRESS' | 'OVERDUE' | 'ESCALATION'
  localTime: string
  recipient: 'EXECUTORS' | 'DIRECT_MANAGER'
  extras: JsonRecord
}

export type WorkPackageItemDraft = {
  key: string
  itemCode: string
  name: string
  description: string
  itemType: string
  formVersionId: string
  required: boolean
  enabled: boolean
  periodType: string
  timezoneMode: string
  fixedTimezone: string
  workWindowStart: string
  workWindowEnd: string
  dueLocalTime: string
  shiftAvailableAnchor: 'SHIFT_START' | 'SHIFT_MIDPOINT' | 'SHIFT_END'
  shiftAvailableOffsetMinutes: string
  shiftDueAnchor: 'SHIFT_START' | 'SHIFT_MIDPOINT' | 'SHIFT_END'
  shiftDueOffsetMinutes: string
  graceMinutes: string
  weekdays: number[]
  dayOfMonth: string
  holidayPolicy: string
  holidayDates: string
  workdayOverrides: string
  waiverAllowed: boolean
  targetGranularity: string
  reviewMode: string
  completionStatementRequired: boolean
  exceptionStatementRequired: boolean
  nextActionRequired: boolean
  attachmentRequired: boolean
  attachmentCountUnlimited: boolean
  maxAttachments: string
  maxFileSizeMb: string
  allowedExtensions: string
  evidenceRequirements: EvidenceRequirementDraft[]
  reminderMoments: ReminderMomentDraft[]
  dailyReport: boolean
  reportFactLabel: string
  reportIncludeEvidence: boolean
  reportIncludeExceptions: boolean
  standardVersionId: string
  standards: JsonRecord[]
  responsibilities: JsonRecord[]
  submissionExtras: JsonRecord
  reminderExtras: JsonRecord
  reportExtras: JsonRecord
  applicabilityPolicy: JsonRecord
  executionExtras: JsonRecord
}

const record = (value: unknown): JsonRecord => {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value as JsonRecord
  if (typeof value === 'string') {
    try {
      const parsed: unknown = JSON.parse(value)
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as JsonRecord : {}
    } catch { return {} }
  }
  return {}
}

const list = <T>(value: unknown): T[] => Array.isArray(value) ? value as T[] : []
const value = (source: JsonRecord, ...keys: string[]) => keys.map((key) => source[key]).find((candidate) => candidate !== undefined && candidate !== null)
const text = (source: JsonRecord, ...keys: string[]) => String(value(source, ...keys) ?? '')
const bool = (source: JsonRecord, fallback: boolean, ...keys: string[]) => {
  const candidate = value(source, ...keys)
  return candidate === undefined ? fallback : candidate === true
}
const numericText = (source: JsonRecord, fallback: string, ...keys: string[]) => {
  const candidate = value(source, ...keys)
  return candidate === undefined || candidate === null ? fallback : String(candidate)
}
const newKey = (prefix: string) => `${prefix}-${crypto.randomUUID()}`
const normalizedTime = (candidate: string) => candidate.slice(0, 5)
const splitValues = (candidate: string) => candidate.split(/[，,\s]+/).map((entry) => entry.trim().toLowerCase()).filter(Boolean)
const optionalInteger = (candidate: string) => candidate.trim() === '' ? undefined : Number(candidate)

const policyWithout = (source: JsonRecord, keys: string[]) => Object.fromEntries(
  Object.entries(source).filter(([key]) => !keys.includes(key)),
)

function evidenceDraft(source: JsonRecord): EvidenceRequirementDraft {
  const requiredWhen = record(source.requiredWhen)
  const equals = requiredWhen.equals
  return {
    key: text(source, 'checkpointCode') || newKey('evidence'),
    checkpointCode: text(source, 'checkpointCode'),
    label: text(source, 'label'),
    captureSource: ['CAMERA', 'FILE', 'CAMERA_OR_FILE'].includes(text(source, 'captureSource'))
      ? text(source, 'captureSource') as EvidenceRequirementDraft['captureSource'] : 'CAMERA_OR_FILE',
    minimum: numericText(source, '', 'minimum'),
    recommendedMaximum: numericText(source, '', 'recommendedMaximum'),
    requiredInstances: numericText(source, '', 'requiredInstances'),
    instanceField: text(source, 'instanceField'),
    instanceLabel: text(source, 'instanceLabel'),
    minimumPerInstance: numericText(source, '', 'minimumPerInstance'),
    requiredWhenField: text(requiredWhen, 'field'),
    requiredWhenEquals: equals === true ? 'true' : equals === false ? 'false' : '',
    mediaTypes: list<string>(source.mediaTypes).join(','),
    extras: policyWithout(source, ['checkpointCode', 'label', 'captureSource', 'minimum', 'recommendedMaximum', 'requiredInstances', 'instanceField', 'instanceLabel', 'minimumPerInstance', 'requiredWhen', 'mediaTypes']),
  }
}

function reminderDraft(source: JsonRecord): ReminderMomentDraft {
  const kind = text(source, 'kind').toUpperCase()
  const recipient = text(source, 'recipient').toUpperCase()
  return {
    key: text(source, 'code') || newKey('reminder'),
    code: text(source, 'code').toUpperCase(),
    kind: ['REMINDER', 'PROGRESS', 'OVERDUE', 'ESCALATION'].includes(kind) ? kind as ReminderMomentDraft['kind'] : 'REMINDER',
    localTime: normalizedTime(text(source, 'localTime')),
    recipient: recipient === 'DIRECT_MANAGER' ? 'DIRECT_MANAGER' : 'EXECUTORS',
    extras: policyWithout(source, ['code', 'kind', 'localTime', 'recipient']),
  }
}

type ShiftAnchor = WorkPackageItemDraft['shiftAvailableAnchor']
type MappedShiftTiming = {
  availableAnchor: ShiftAnchor
  availableOffsetMinutes: string
  dueAnchor: ShiftAnchor
  dueOffsetMinutes: string
}

function mappedShiftTiming(timing: JsonRecord): MappedShiftTiming {
  const configuredAvailableAnchor = text(timing, 'availableAnchor')
  const configuredDueAnchor = text(timing, 'dueAnchor')
  const anchors: ShiftAnchor[] = ['SHIFT_START', 'SHIFT_MIDPOINT', 'SHIFT_END']
  if (configuredAvailableAnchor || configuredDueAnchor
    || value(timing, 'availableOffsetMinutes') !== undefined
    || value(timing, 'dueOffsetMinutes') !== undefined) {
    return {
      availableAnchor: anchors.includes(configuredAvailableAnchor as ShiftAnchor)
        ? configuredAvailableAnchor as ShiftAnchor : 'SHIFT_START',
      availableOffsetMinutes: numericText(timing, '0', 'availableOffsetMinutes'),
      dueAnchor: anchors.includes(configuredDueAnchor as ShiftAnchor)
        ? configuredDueAnchor as ShiftAnchor : 'SHIFT_END',
      dueOffsetMinutes: numericText(timing, '0', 'dueOffsetMinutes'),
    }
  }

  const aroundMidpoint = Number(value(timing, 'aroundShiftMidpointMinutes') ?? 0)
  const available = value(timing, 'startOffsetMinutes') !== undefined
    ? { anchor: 'SHIFT_START' as ShiftAnchor, offset: Number(value(timing, 'startOffsetMinutes')) }
    : value(timing, 'startBeforeShiftEndMinutes') !== undefined
      ? { anchor: 'SHIFT_END' as ShiftAnchor, offset: -Number(value(timing, 'startBeforeShiftEndMinutes')) }
      : value(timing, 'aroundShiftMidpointMinutes') !== undefined
        ? { anchor: 'SHIFT_MIDPOINT' as ShiftAnchor, offset: -aroundMidpoint }
        : { anchor: 'SHIFT_START' as ShiftAnchor, offset: 0 }
  const due = value(timing, 'endOffsetMinutes') !== undefined
    ? { anchor: 'SHIFT_START' as ShiftAnchor, offset: Number(value(timing, 'endOffsetMinutes')) }
    : value(timing, 'endBeforeShiftEndMinutes') !== undefined
      ? { anchor: 'SHIFT_END' as ShiftAnchor, offset: -Number(value(timing, 'endBeforeShiftEndMinutes')) }
      : value(timing, 'endAfterShiftEndMinutes') !== undefined
        ? { anchor: 'SHIFT_END' as ShiftAnchor, offset: Number(value(timing, 'endAfterShiftEndMinutes')) }
        : value(timing, 'aroundShiftMidpointMinutes') !== undefined
          ? { anchor: 'SHIFT_MIDPOINT' as ShiftAnchor, offset: aroundMidpoint }
          : { anchor: 'SHIFT_END' as ShiftAnchor, offset: 0 }
  return {
    availableAnchor: available.anchor,
    availableOffsetMinutes: String(available.offset),
    dueAnchor: due.anchor,
    dueOffsetMinutes: String(due.offset),
  }
}

export function createWorkPackageItemDraft(sequence: number, formVersionId = ''): WorkPackageItemDraft {
  const code = `DAILY-${String(sequence).padStart(2, '0')}`
  return {
    key: newKey('item'), itemCode: code, name: `新工作项 ${sequence}`, description: '', itemType: 'SCHEDULED_RECORD',
    formVersionId, required: true, enabled: true, periodType: 'DAY', timezoneMode: 'HOTEL', fixedTimezone: '',
    workWindowStart: '', workWindowEnd: '', dueLocalTime: '18:00',
    shiftAvailableAnchor: 'SHIFT_START', shiftAvailableOffsetMinutes: '0', shiftDueAnchor: 'SHIFT_END', shiftDueOffsetMinutes: '0',
    graceMinutes: '0', weekdays: [1, 2, 3, 4, 5, 6, 7],
    dayOfMonth: '', holidayPolicy: 'INCLUDE', holidayDates: '', workdayOverrides: '', waiverAllowed: false, targetGranularity: 'ASSIGNMENT_ORG', reviewMode: 'MANUAL',
    completionStatementRequired: true, exceptionStatementRequired: false, nextActionRequired: false,
    attachmentRequired: false, attachmentCountUnlimited: false, maxAttachments: '10', maxFileSizeMb: '20',
    allowedExtensions: 'jpg,jpeg,png,pdf,docx,xlsx', evidenceRequirements: [], reminderMoments: [],
    dailyReport: true, reportFactLabel: `新工作项 ${sequence}`, reportIncludeEvidence: true, reportIncludeExceptions: true,
    standardVersionId: '', standards: [], responsibilities: [], submissionExtras: {}, reminderExtras: {}, reportExtras: {},
    applicabilityPolicy: {}, executionExtras: {},
  }
}

export function mapWorkPackageItem(source: JsonRecord, _index: number): WorkPackageItemDraft {
  const submission = record(value(source, 'submission_policy', 'submissionPolicy'))
  const reminder = record(value(source, 'reminder_policy', 'reminderPolicy'))
  const report = record(value(source, 'report_policy', 'reportPolicy'))
  const applicability = record(value(source, 'applicability_policy', 'applicabilityPolicy'))
  const execution = record(value(source, 'execution_policy', 'executionPolicy'))
  const shiftTiming = record(execution.shiftTiming)
  const mappedShift = mappedShiftTiming(shiftTiming)
  const standards = list<JsonRecord>(source.standards)
  return {
    key: text(source, 'id') || newKey('item'),
    itemCode: text(source, 'item_code', 'itemCode'), name: text(source, 'name'), description: text(source, 'description'),
    itemType: text(source, 'item_type', 'itemType') || 'SCHEDULED_RECORD', formVersionId: text(source, 'form_version_id', 'formVersionId'),
    required: bool(source, true, 'required'), enabled: execution.enabled !== false,
    periodType: text(source, 'period_type', 'periodType') || 'DAY', timezoneMode: text(source, 'timezone_mode', 'timezoneMode') || 'HOTEL',
    fixedTimezone: text(source, 'fixed_timezone', 'fixedTimezone'), workWindowStart: normalizedTime(text(source, 'work_window_start', 'workWindowStart')),
    workWindowEnd: normalizedTime(text(source, 'work_window_end', 'workWindowEnd')), dueLocalTime: normalizedTime(text(source, 'due_local_time', 'dueLocalTime')),
    shiftAvailableAnchor: mappedShift.availableAnchor,
    shiftAvailableOffsetMinutes: mappedShift.availableOffsetMinutes,
    shiftDueAnchor: mappedShift.dueAnchor,
    shiftDueOffsetMinutes: mappedShift.dueOffsetMinutes,
    graceMinutes: numericText(source, '0', 'grace_minutes', 'graceMinutes'), weekdays: list<number>(value(source, 'weekdays')).map(Number),
    dayOfMonth: numericText(source, '', 'day_of_month', 'dayOfMonth'), holidayPolicy: text(source, 'holiday_policy', 'holidayPolicy') || 'INCLUDE',
    holidayDates: list<string>(applicability.holidayDates).join(','), workdayOverrides: list<string>(applicability.workdayOverrides).join(','),
    waiverAllowed: bool(source, false, 'waiver_allowed', 'waiverAllowed'), targetGranularity: text(source, 'target_granularity', 'targetGranularity') || 'ASSIGNMENT_ORG',
    reviewMode: text(source, 'review_mode', 'reviewMode') || 'MANUAL',
    completionStatementRequired: bool(submission, true, 'completionStatementRequired'), exceptionStatementRequired: bool(submission, false, 'exceptionStatementRequired'),
    nextActionRequired: bool(submission, false, 'nextActionRequired'), attachmentRequired: bool(submission, false, 'attachmentRequired'),
    attachmentCountUnlimited: bool(submission, false, 'attachmentCountUnlimited'), maxAttachments: numericText(submission, '10', 'maxAttachments'),
    maxFileSizeMb: String(Math.max(1, Math.round(Number(value(submission, 'maxFileSizeBytes') ?? 20 * 1024 * 1024) / 1024 / 1024))),
    allowedExtensions: list<string>(submission.allowedExtensions).join(',') || 'jpg,jpeg,png,pdf,docx,xlsx',
    evidenceRequirements: list<JsonRecord>(submission.evidenceRequirements).map(evidenceDraft),
    reminderMoments: list<JsonRecord>(reminder.moments).map(reminderDraft),
    dailyReport: bool(report, true, 'dailyReport'), reportFactLabel: text(report, 'factLabel') || text(source, 'name'),
    reportIncludeEvidence: bool(report, true, 'includeEvidence'), reportIncludeExceptions: bool(report, true, 'includeExceptions'),
    standardVersionId: text(standards[0] ?? {}, 'standard_version_id', 'standardVersionId'), standards,
    responsibilities: list<JsonRecord>(source.responsibilities),
    submissionExtras: policyWithout(submission, ['completionStatementRequired', 'exceptionStatementRequired', 'nextActionRequired', 'attachmentRequired', 'attachmentCountUnlimited', 'maxAttachments', 'maxFileSizeBytes', 'allowedExtensions', 'evidenceRequirements']),
    reminderExtras: policyWithout(reminder, ['moments']), reportExtras: policyWithout(report, ['dailyReport', 'factLabel', 'includeEvidence', 'includeExceptions']),
    applicabilityPolicy: policyWithout(applicability, ['holidayDates', 'workdayOverrides']), executionExtras: policyWithout(execution, ['enabled', 'shiftTiming']),
  }
}

const serializeEvidence = (draft: EvidenceRequirementDraft): JsonRecord => {
  const result: JsonRecord = {
    ...draft.extras, checkpointCode: draft.checkpointCode.trim(), label: draft.label.trim(), captureSource: draft.captureSource,
    mediaTypes: splitValues(draft.mediaTypes),
  }
  for (const [key, candidate] of [['minimum', draft.minimum], ['recommendedMaximum', draft.recommendedMaximum], ['requiredInstances', draft.requiredInstances], ['minimumPerInstance', draft.minimumPerInstance]] as const) {
    const parsed = optionalInteger(candidate)
    if (parsed !== undefined) result[key] = parsed
  }
  if (draft.instanceField.trim()) result.instanceField = draft.instanceField.trim()
  if (draft.instanceLabel.trim()) result.instanceLabel = draft.instanceLabel.trim()
  if (draft.requiredWhenField.trim() && draft.requiredWhenEquals) result.requiredWhen = { field: draft.requiredWhenField.trim(), equals: draft.requiredWhenEquals === 'true' }
  return result
}

const serializeReminder = (draft: ReminderMomentDraft): JsonRecord => ({
  ...draft.extras, code: draft.code.trim().toUpperCase(), kind: draft.kind, localTime: draft.localTime, recipient: draft.recipient,
})

function normalizedResponsibilities(draft: WorkPackageItemDraft): JsonRecord[] {
  const existing = draft.responsibilities.filter((entry) => text(entry, 'participant_type', 'participantType') !== 'ACCEPTOR' || draft.reviewMode !== 'NONE')
  if (!existing.some((entry) => text(entry, 'participant_type', 'participantType') === 'EXECUTOR')) {
    existing.push({ participantType: 'EXECUTOR', resolverType: 'CURRENT_ASSIGNMENT', scopeStrategy: 'TARGET_ORG', escalationLevel: 0 })
  }
  if (draft.reviewMode !== 'NONE' && !existing.some((entry) => text(entry, 'participant_type', 'participantType') === 'ACCEPTOR')) {
    existing.push({ participantType: 'ACCEPTOR', resolverType: 'DIRECT_MANAGER_ASSIGNMENT', scopeStrategy: 'TARGET_ORG', escalationLevel: 0 })
  }
  return existing.map((entry) => ({
    participantType: text(entry, 'participant_type', 'participantType'), resolverType: text(entry, 'resolver_type', 'resolverType'),
    positionId: text(entry, 'position_id', 'positionId') || null, scopeStrategy: text(entry, 'scope_strategy', 'scopeStrategy') || 'TARGET_ORG',
    escalationLevel: Number(value(entry, 'escalation_level', 'escalationLevel') ?? 0),
  }))
}

export function serializeWorkPackageItems(drafts: WorkPackageItemDraft[]): JsonRecord[] {
  return drafts.map((draft, index) => {
    const standard = draft.standardVersionId
      ? [{ standardVersionId: draft.standardVersionId, usageType: 'EXECUTION', weight: 1 }]
      : draft.standards.map((entry) => ({
        standardVersionId: text(entry, 'standard_version_id', 'standardVersionId'), usageType: text(entry, 'usage_type', 'usageType') || 'EXECUTION',
        weight: Number(value(entry, 'weight') ?? 1),
      })).filter((entry) => entry.standardVersionId)
    return {
      itemCode: draft.itemCode.trim().toUpperCase(), name: draft.name.trim(), description: draft.description.trim() || null,
      itemType: draft.itemType, formVersionId: draft.formVersionId, sortOrder: index + 1, required: draft.required,
      periodType: draft.periodType, timezoneMode: draft.timezoneMode, fixedTimezone: draft.fixedTimezone.trim() || null,
      workWindowStart: draft.workWindowStart || null, workWindowEnd: draft.workWindowEnd || null, dueLocalTime: draft.dueLocalTime || null,
      graceMinutes: Number(draft.graceMinutes || 0), weekdays: draft.weekdays, dayOfMonth: optionalInteger(draft.dayOfMonth) ?? null,
      holidayPolicy: draft.holidayPolicy, waiverAllowed: draft.waiverAllowed, targetGranularity: draft.targetGranularity, reviewMode: draft.reviewMode,
      submissionPolicy: {
        ...draft.submissionExtras, completionStatementRequired: draft.completionStatementRequired,
        exceptionStatementRequired: draft.exceptionStatementRequired, nextActionRequired: draft.nextActionRequired,
        attachmentRequired: draft.attachmentRequired, attachmentCountUnlimited: draft.attachmentCountUnlimited,
        maxAttachments: Number(draft.maxAttachments || 0), maxFileSizeBytes: Number(draft.maxFileSizeMb || 20) * 1024 * 1024,
        allowedExtensions: splitValues(draft.allowedExtensions), evidenceRequirements: draft.evidenceRequirements.map(serializeEvidence),
      },
      reminderPolicy: { ...draft.reminderExtras, moments: draft.reminderMoments.map(serializeReminder) },
      reportPolicy: {
        ...draft.reportExtras, dailyReport: draft.dailyReport, factLabel: draft.reportFactLabel.trim() || draft.name.trim(),
        includeEvidence: draft.reportIncludeEvidence, includeExceptions: draft.reportIncludeExceptions,
      },
      applicabilityPolicy: {
        ...draft.applicabilityPolicy, holidayDates: splitValues(draft.holidayDates), workdayOverrides: splitValues(draft.workdayOverrides),
      },
      executionPolicy: {
        ...draft.executionExtras,
        enabled: draft.enabled,
        ...(draft.periodType === 'SHIFT' ? { shiftTiming: {
          availableAnchor: draft.shiftAvailableAnchor,
          availableOffsetMinutes: Number(draft.shiftAvailableOffsetMinutes || 0),
          dueAnchor: draft.shiftDueAnchor,
          dueOffsetMinutes: Number(draft.shiftDueOffsetMinutes || 0),
        } } : {}),
      },
      standards: standard, responsibilities: normalizedResponsibilities(draft),
    }
  })
}

export function validateWorkPackageItems(drafts: WorkPackageItemDraft[]): string[] {
  const issues: string[] = []
  if (!drafts.length) return ['至少保留一个工作项。']
  if (!drafts.some((item) => item.enabled)) issues.push('至少启用一个工作项。')
  const codes = new Set<string>()
  drafts.forEach((item, index) => {
    const label = `第 ${index + 1} 项`
    const code = item.itemCode.trim().toUpperCase()
    if (!code) issues.push(`${label}缺少工作项编码。`)
    else if (!/^[A-Z][A-Z0-9_-]{1,63}$/.test(code)) issues.push(`${label}工作项编码格式无效。`)
    else if (codes.has(code)) issues.push(`工作项编码“${code}”重复。`)
    else codes.add(code)
    if (!item.name.trim()) issues.push(`${label}缺少工作项名称。`)
    if (!item.formVersionId) issues.push(`${label}未选择已发布表单。`)
    const maxAttachments = Number(item.maxAttachments)
    if (!Number.isInteger(maxAttachments) || maxAttachments < 0 || maxAttachments > 200) issues.push(`${label}附件上限必须为 0 到 200。`)
    const maxFileSizeMb = Number(item.maxFileSizeMb)
    if (!Number.isInteger(maxFileSizeMb) || maxFileSizeMb < 1 || maxFileSizeMb > 20) issues.push(`${label}单文件上限必须为 1 到 20MB。`)
    if (item.periodType === 'SHIFT') {
      for (const [name, candidate] of [['可开始偏移', item.shiftAvailableOffsetMinutes], ['截止偏移', item.shiftDueOffsetMinutes]] as const) {
        const minutes = Number(candidate)
        if (!Number.isInteger(minutes) || Math.abs(minutes) > 2880) issues.push(`${label}${name}必须是 -2880 到 2880 的整数分钟。`)
      }
      if (item.shiftAvailableAnchor === item.shiftDueAnchor
        && Number.isInteger(Number(item.shiftAvailableOffsetMinutes))
        && Number.isInteger(Number(item.shiftDueOffsetMinutes))
        && Number(item.shiftDueOffsetMinutes) <= Number(item.shiftAvailableOffsetMinutes)) {
        issues.push(`${label}同一班次基准下，截止偏移必须晚于可开始偏移。`)
      }
    }
    for (const candidate of [...splitValues(item.holidayDates), ...splitValues(item.workdayOverrides)]) {
      const parsed = new Date(`${candidate}T00:00:00Z`)
      if (!/^\d{4}-\d{2}-\d{2}$/.test(candidate) || Number.isNaN(parsed.valueOf()) || parsed.toISOString().slice(0, 10) !== candidate) {
        issues.push(`${label}节假日或调休日期“${candidate}”格式无效。`)
      }
    }
    const reminderCodes = new Set<string>()
    item.reminderMoments.forEach((moment) => {
      if (!/^[A-Z][A-Z0-9_]{0,23}$/.test(moment.code.trim().toUpperCase()) || !/^([01]\d|2[0-3]):[0-5]\d$/.test(moment.localTime)) issues.push(`${label}存在无效的提醒编码或时间。`)
      if (reminderCodes.has(moment.code.trim().toUpperCase())) issues.push(`${label}提醒编码“${moment.code}”重复。`)
      reminderCodes.add(moment.code.trim().toUpperCase())
    })
    const evidenceCodes = new Set<string>()
    item.evidenceRequirements.forEach((evidence) => {
      const evidenceCode = evidence.checkpointCode.trim()
      if (!evidenceCode || !evidence.label.trim()) issues.push(`${label}存在未填写完整的证据点。`)
      if (evidenceCodes.has(evidenceCode)) issues.push(`${label}证据点编码“${evidenceCode}”重复。`)
      evidenceCodes.add(evidenceCode)
      for (const candidate of [evidence.minimum, evidence.recommendedMaximum, evidence.requiredInstances, evidence.minimumPerInstance].filter(Boolean)) {
        if (!Number.isInteger(Number(candidate)) || Number(candidate) < 0 || Number(candidate) > 200) issues.push(`${label}证据数量必须为 0 到 200 的整数。`)
      }
      if (evidence.requiredInstances && !evidence.instanceField.trim()) issues.push(`${label}按实例上传证据时必须填写实例字段。`)
    })
  })
  return [...new Set(issues)]
}

export function duplicateWorkPackageItem(items: WorkPackageItemDraft[], index: number): WorkPackageItemDraft[] {
  const source = items[index]
  if (!source) return items
  const clone = structuredClone(source)
  clone.key = newKey('item')
  clone.itemCode = `${source.itemCode}_COPY`
  clone.name = `${source.name}（副本）`
  clone.evidenceRequirements = clone.evidenceRequirements.map((item) => ({ ...item, key: newKey('evidence') }))
  clone.reminderMoments = clone.reminderMoments.map((item) => ({ ...item, key: newKey('reminder'), code: `${item.code}_COPY`.slice(0, 24) }))
  return [...items.slice(0, index + 1), clone, ...items.slice(index + 1)]
}

export function moveWorkPackageItem(items: WorkPackageItemDraft[], from: number, to: number): WorkPackageItemDraft[] {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) return items
  const next = [...items]
  const [moved] = next.splice(from, 1)
  if (!moved) return items
  next.splice(to, 0, moved)
  return next
}
