export type PlanType = 'WEEKLY' | 'MONTHLY'
export type PlanAction = {
  id?: string; actionId: string; hotelId: string; assigneeAssignmentId: string; title: string; description: string
  dueAt: string; acceptanceCriteria: string; linkedTaskId?: string; linkedExpectationId?: string
  taskId?: string; taskStatus?: string; assigneeName?: string; hotelName?: string
}
export type PlanDraft = {
  summary: string; objective: string; risk: string; hotelSummaries: Record<string, string>; items: PlanAction[]
}
export type WorkPlan = PlanDraft & {
  id: string; type: PlanType; periodStart: string; periodEnd: string; status: string
  revisionId: string; revisionNo: number; draftVersion?: number; ownerAssignmentId: string; ownerName: string
  hotelIds: string[]; hotels?: { id: string; name: string }[]; dueAt: string; submittedAt?: string; late: boolean
}
export type OperationsContext = {
  assignmentId?: string; positionCode: string; positionName: string; roleCode?: string
  hotels: { id: string; name: string }[]; dailyTemplateStatus: string
  canReadPlan: boolean; canSubmitPlan: boolean; canReviewPlan: boolean
  dailyTemplateNotice?: string; portfolioConfigurationNotice?: string
}
export type DailyItem = {
  expectationId: string; itemCode: string; name: string; hotelId: string; hotelName: string
  targetTime?: string; availableAt?: string; dueAt?: string; reminderAt?: string; escalationAt?: string
  boardStatus: string; recordId?: string; formVersionId?: string; assignmentId?: string
}
export type CompletionItem = {
  id: string; title: string; hotelId: string; hotelName: string; source: string; dueAt?: string; submittedAt?: string
  status: string; expectationId?: string; taskId?: string; recordId?: string; qualified: boolean; onTime: boolean
}
export type CompletionReport = {
  month: string; asOf: string; denominator: number; qualifiedCompleted: number; onTimeCompleted: number
  pendingReview: number; lateQualified: number; futurePlanned: number; completionRate: number | null; onTimeRate: number | null
  bySource: { source: string; label: string; due: number; completed: number; onTime: number; pendingReview: number; completionRate: number | null; onTimeRate: number | null }[]
  items: CompletionItem[]; totalItems: number; page: number; size: number
  planCompliance: { id: string; type: PlanType; periodStart: string; periodEnd: string; status: string; revisionNo: number; submittedAt?: string; dueAt: string; late: boolean }[]
  historyCorrectionNote?: string; undatedTasks?: number; snapshot?: { id: string; revisionNo: number; capturedAt: string }
}
export function chinaToday(now = new Date()) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}
export const formatTime = (value?: string) => value && Number.isFinite(Date.parse(value)) ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value)) : '—'
export function planPeriodStart(type: PlanType, date: string) {
  const value = new Date(`${date.slice(0, 10)}T12:00:00Z`)
  if (!Number.isFinite(value.getTime())) throw new Error('请选择有效日期')
  if (type === 'MONTHLY') value.setUTCDate(1)
  else value.setUTCDate(value.getUTCDate() - (value.getUTCDay() + 6) % 7)
  return value.toISOString().slice(0, 10)
}
export function addDays(date: string, count: number) {
  const value = new Date(`${date}T12:00:00Z`)
  value.setUTCDate(value.getUTCDate() + count)
  return value.toISOString().slice(0, 10)
}
export function planDueAt(type: PlanType, start: string) {
  return `${type === 'WEEKLY' ? addDays(start, 7) : `${start.slice(0, 7)}-06`}T00:00:00+08:00`
}
export function planDeadline(value: string) {
  const local = localDateTime(value)
  return local.endsWith('T00:00') ? `${addDays(local.slice(0, 10), -1)} 24:00` : formatTime(value)
}
export function percentage(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) ? '—' : `${value.toLocaleString('zh-CN', { maximumFractionDigits: 1 })}%`
}
export function localDateTime(value?: string) {
  if (!value) return ''
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return value
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return ''
  const parts = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date)
  return parts.replace(' ', 'T')
}
export function serializePlanDraft(draft: PlanDraft): PlanDraft {
  return { ...draft, items: draft.items.map(item => ({ ...item, dueAt: item.dueAt && !/(Z|[+-]\d{2}:\d{2})$/.test(item.dueAt) ? `${item.dueAt}${item.dueAt.length === 16 ? ':00' : ''}+08:00` : item.dueAt })) }
}
export function emptyPlanDraft(hotelIds: string[]): PlanDraft {
  return { summary: '', objective: '', risk: '', hotelSummaries: Object.fromEntries(hotelIds.map(id => [id, ''])), items: [] }
}
export function validatePlanDraft(draft: PlanDraft, hotelIds: string[]) {
  if (!draft.summary.trim() || !draft.objective.trim()) return '请填写总结与工作目标。'
  const seen = new Set<string>()
  for (const [index, item] of draft.items.entries()) {
    if (!item.actionId || seen.has(item.actionId)) return `第${index + 1}项行动标识重复，请删除后重新添加。`
    seen.add(item.actionId)
    if (!hotelIds.includes(item.hotelId)) return `第${index + 1}项的门店已不在当前负责范围，请重新选择。`
    if (!item.title.trim() || !item.assigneeAssignmentId || !item.dueAt || !item.acceptanceCriteria.trim()) return `请补全第${index + 1}项的内容、负责人、截止时间与验收标准。`
    if (item.linkedTaskId && item.linkedExpectationId) return `第${index + 1}项只能关联一种已有工作。`
    if (!Number.isFinite(Date.parse(serializePlanDraft({ ...draft, items: [item] }).items[0].dueAt))) return `第${index + 1}项的截止时间无效。`
  }
  return undefined
}
