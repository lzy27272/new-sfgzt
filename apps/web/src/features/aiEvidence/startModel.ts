import type { NavigationParams, WorkExpectation } from '../../domain.ts'

const groomingCodes = new Set([
  'GM_APPEARANCE_MEETING_V56', 'GM_APPEARANCE_MORNING_MEETING_V2', 'GM_APPEARANCE_MORNING_MEETING',
  'AGM_NS_APPEARANCE_MEETING', 'AGM_WS_APPEARANCE_MEETING',
])

function submittedRecordId(item: WorkExpectation) {
  const submitted = (status?: string) => status === 'SUBMITTED' || status === 'APPROVED'
  const record = item.records?.find(row => row.id && submitted(row.status))
  if (record) return record.id
  return item.recordId && (submitted(item.latestRecordStatus) || (!item.latestRecordStatus && item.latestSubmittedAt))
    ? item.recordId : undefined
}

export function groomingWorkCandidates(items: WorkExpectation[]) {
  return items.filter(item => groomingCodes.has(item.itemCode ?? '') && Boolean(submittedRecordId(item)))
}

export function aiWorkDetailParams(item: WorkExpectation, hotelId?: string): NavigationParams {
  return { hotelId, dateMode: 'day', from: item.businessDate, to: item.businessDate,
    queue: 'PERIOD', status: 'ALL', expectationId: item.id, recordId: submittedRecordId(item) }
}
