import type { RouteParams, NavigationParams, WorkExpectation } from '../../domain.ts'

export function selectTeamRecordId(expectation: WorkExpectation, selectedId?: string) {
  const records = expectation.records ?? []
  if (selectedId) {
    if (!records.some((record) => record.id === selectedId)) throw new Error('该提交记录不属于当前工作，请刷新后重试。')
    return selectedId
  }
  // Each repeatable submission needs its own review, even if the newest is approved.
  const pending = expectation.executionPolicy?.repeatableDailyRecords
    ? records.find((record) => record.status === 'SUBMITTED') : undefined
  return pending?.id ?? expectation.recordId ?? records[0]?.id
}

export type BoardStatus = 'ALL' | 'PENDING' | 'OVERDUE' | 'REVIEW' | 'COMPLETED'
export type BoardQueue = 'PERIOD' | 'REVIEW' | 'CARRYOVER'
export type DateMode = 'day' | 'week' | 'custom' | 'month'
export type BoardMetric = {
  expected: number; pending: number; overdue: number; review: number; completed: number
  submitted: number; on_time_completed: number; on_time_submitted: number; late: number; optional_records: number; optional_entries: number; pending_review_records: number
  item_count: number; completed_rate: number | null; submitted_rate: number | null; on_time_completed_rate: number | null; on_time_submitted_rate: number | null
}
export type BoardGroup = BoardMetric & { dimension: 'hotel' | 'department' | 'employee'; group_id: string | null; group_name: string | null }
export type WorkBoard = {
  businessDate: string; fromDate: string; toDate: string; page: number; size: number
  period: BoardMetric; today: BoardMetric; month: BoardMetric; reviewQueue: BoardMetric
  carryover: BoardMetric; filtered: BoardMetric; groups: BoardGroup[]; items: WorkExpectation[]; recentSubmissions: WorkExpectation[]; upcomingItems: WorkExpectation[]
}
export const emptyMetric: BoardMetric = {
  expected: 0, pending: 0, overdue: 0, review: 0, completed: 0, submitted: 0,
  on_time_completed: 0, on_time_submitted: 0, late: 0, optional_records: 0, optional_entries: 0, pending_review_records: 0, item_count: 0,
  completed_rate: null, submitted_rate: null, on_time_completed_rate: null, on_time_submitted_rate: null,
}
export const emptyBoard: WorkBoard = {
  businessDate: '', fromDate: '', toDate: '', page: 0, size: 50,
  period: emptyMetric, today: emptyMetric, month: emptyMetric, reviewQueue: emptyMetric,
  carryover: emptyMetric, filtered: emptyMetric, groups: [], items: [], recentSubmissions: [], upcomingItems: [],
}
export function localBusinessDate(now = new Date()) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(now)
}
export function validDate(value?: string): value is string {
  return Boolean(value && /^\d{4}-\d{2}-\d{2}$/.test(value)
    && !Number.isNaN(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value)
}
function shiftDay(date: string, days: number) {
  const value = new Date(date + 'T00:00:00Z')
  value.setUTCDate(value.getUTCDate() + days)
  return value.toISOString().slice(0, 10)
}
export function dateRange(mode: DateMode, anchor: string, end?: string) {
  if (mode === 'week') {
    const weekday = (new Date(anchor + 'T00:00:00Z').getUTCDay() + 6) % 7
    const from = shiftDay(anchor, -weekday)
    return { from, to: shiftDay(from, 6) }
  }
  if (mode === 'month') return { from: anchor.slice(0, 8) + '01', to: anchor }
  return { from: anchor, to: mode === 'custom' && validDate(end) ? end : anchor }
}
export function boardQuery(params: RouteParams, today = localBusinessDate()) {
  const mode = (['day', 'week', 'custom', 'month'].includes(params.dateMode) ? params.dateMode : 'day') as DateMode
  const anchor = validDate(params.from) ? params.from : today
  const range = dateRange(mode, mode === 'month' && validDate(params.to) ? params.to : anchor, params.to)
  const status = (['ALL', 'PENDING', 'OVERDUE', 'REVIEW', 'COMPLETED'].includes(params.status)
    ? params.status : params.status === 'SUBMITTED' ? 'REVIEW' : params.status === 'LATE_SUBMITTED' ? 'ALL' : 'ALL') as BoardStatus
  const queue = (['PERIOD', 'REVIEW', 'CARRYOVER'].includes(params.queue) ? params.queue : 'PERIOD') as BoardQueue
  return { ...range, mode, status, queue, orgUnitId: params.hotelId || params.orgUnitId || undefined,
    departmentId: params.departmentId || undefined, assignmentId: params.assignmentId || undefined,
    lateOnly: params.lateOnly === 'true' || params.status === 'LATE_SUBMITTED',
    page: /^\d+$/.test(params.page ?? '') ? Math.min(Number(params.page), 1_000_000) : 0 }
}
export function withBoardParams(params: RouteParams, patch: NavigationParams): NavigationParams {
  return { ...params, expectationId: undefined, page: undefined, ...patch }
}
export function rate(value: number | null | undefined) { return value == null ? '—' : value + '%' }
export function isLateSubmitted(item: WorkExpectation) {
  if (typeof item.boardLate === 'boolean') return item.boardLate
  const first = item.firstSubmittedAt ?? item.latestSubmittedAt ?? item.records?.at(-1)?.submittedAt
  return Boolean(first && item.dueAt && Date.parse(first) > Date.parse(item.dueAt))
}
export const boardLabels: Record<BoardStatus, string> = {
  ALL: '全部', PENDING: '待提交', OVERDUE: '逾期未提交', REVIEW: '待审核', COMPLETED: '已完成',
}
