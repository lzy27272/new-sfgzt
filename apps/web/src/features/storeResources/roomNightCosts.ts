import { costMoney } from './costs'

export type RoomNightPeriod = 'DAY' | 'MONTH'
export const allRoomUseBasis = 'ALL_ROOM_USES_V1'
export type RoomNightBreakdown = {
  paidOvernightRoomUses: string; complimentaryRoomUses: string
  houseUseRoomUses: string; paidHourlyRoomUses: string
}
export type RoomNightEvidence = {
  state: string; reason: string; roomUses?: string; candidateRoomUses?: string
  coveredDays: number; expectedDays: number; missingDates: string[]; mode?: string
  definitionLabel?: string; collectedAt?: string; revision?: string; breakdown?: RoomNightBreakdown
}
export type RoomNightMetric = {
  kind: string; amount: string; entryCount: number; zeroAmountCount: number; legacyCount: number
  state: string; reason: string; perRoomUse?: string
}
export type RoomNightWindow = {
  from: string; to: string; costMonthClosed: boolean; pendingUsageCount: number; shiftedEntryCount: number
  source: RoomNightEvidence; costs: RoomNightMetric[]
}
export type RoomNightChange = { percent?: string; state: string; reason: string }
export type RoomNightAnalysis = {
  roomUseBasis: typeof allRoomUseBasis
  periodType: RoomNightPeriod; selection: string; currentMonth: string; latestClosedDay: string
  businessTimezone: string; costTimezone: string; generatedAt: string
  current: RoomNightWindow; previous: RoomNightWindow; yearAgo: RoomNightWindow
  comparisons: Array<{ kind: string; previous: RoomNightChange; yearAgo: RoomNightChange }>
  notices: string[]
}

export function unitCostText(metric: RoomNightMetric): string {
  return metric.state === 'READY' && metric.perRoomUse != null ? `${costMoney(metric.perRoomUse)} / 间次` : '暂不计算'
}
export function costChangeText(change?: RoomNightChange): string {
  if (change?.state !== 'READY' || change.percent == null || !/^-?\d+(\.\d{1,2})?$/.test(change.percent)) return '不可比'
  return `${change.percent.startsWith('-') || /^0(\.0+)?$/.test(change.percent) ? '' : '+'}${change.percent}%`
}
export function windowLabel(window: Pick<RoomNightWindow, 'from' | 'to'>): string {
  if (window.to < window.from) return '本期暂无已日结日期'
  return window.from === window.to ? window.from : `${window.from} 至 ${window.to}`
}
export function analysisMatches(analysis: RoomNightAnalysis | undefined, type: RoomNightPeriod, period: string): analysis is RoomNightAnalysis {
  return !!analysis && analysis.roomUseBasis === allRoomUseBasis && analysis.periodType === type && (!period || analysis.selection === period)
}
