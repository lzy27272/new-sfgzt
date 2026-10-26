import type { RouteParams, WorkExpectation, WorkRecordDetail } from '../../domain.ts'
import type { WorkBoard } from './workBoardModel.ts'

export type SpotCheckImpact = {
  businessDate: string; assignmentId: string; expected: number; completed: number; afterCompleted: number; beforeRate: number; afterRate: number
}
export type SpotCheckPreview = { loading?: boolean; error?: string; impact?: SpotCheckImpact }

export function spotCheckPreviewParams(record: WorkRecordDetail, expectation: WorkExpectation): RouteParams {
  const businessDate = record.businessDate
  if (!businessDate || !/^\d{4}-\d{2}-\d{2}$/.test(businessDate) || Number.isNaN(Date.parse(businessDate))
    || new Date(businessDate).toISOString().slice(0, 10) !== businessDate || !record.positionAssignmentId || !record.targetOrgUnitId) {
    throw new Error('记录缺少原工作日期、执行任职或组织，无法预估；请刷新记录或联系管理员核对。')
  }
  if (expectation.businessDate && businessDate !== expectation.businessDate) throw new Error('记录与工作归属日期不一致，无法预估；请刷新核对。')
  return { dateMode: 'day', from: businessDate, to: businessDate, assignmentId: record.positionAssignmentId,
    orgUnitId: expectation.hotelOrgUnitId || record.targetOrgUnitId, queue: 'PERIOD', status: 'ALL' }
}

export function spotCheckImpactFromBoard(data: WorkBoard, params: RouteParams): SpotCheckImpact {
  const { expected, completed } = data.period
  if (data.fromDate !== params.from || data.toDate !== params.to || !params.assignmentId
    || !Number.isSafeInteger(expected) || expected < 1 || !Number.isSafeInteger(completed) || completed < 1 || completed > expected) {
    throw new Error('未取得可核对的原任职原日有效完成统计，无法预估。请刷新后重试；不会显示示例比例。')
  }
  const percentage = (count: number) => Math.round(count / expected * 1000) / 10
  return { businessDate: params.from, assignmentId: params.assignmentId, expected, completed, afterCompleted: completed - 1,
    beforeRate: percentage(completed), afterRate: percentage(completed - 1) }
}
