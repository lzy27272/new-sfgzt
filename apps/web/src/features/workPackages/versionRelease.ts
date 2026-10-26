import { publicationStart } from './publicationTiming'

export type ReleaseAllocation = { id: string; assignmentId: string; targetOrgUnitId: string; status: string; validFrom: string; validTo?: string; versionId?: string }
export type AllocationReplacementTarget = { versionId: string; assignmentId: string; targetOrgUnitId: string; validFrom: string; validTo: string; effectiveFrom: string; timeZone: string }
const validDate = (date: string) => /^\d{4}-\d{2}-\d{2}$/.test(date) && Number.isFinite(Date.parse(date)) && new Date(date).toISOString().slice(0, 10) === date

export function versionLocalDate(value: string, timeZone: string) {
  if (!value || !timeZone || !Number.isFinite(Date.parse(value))) return ''
  try { return new Intl.DateTimeFormat('sv-SE', { timeZone }).format(new Date(value)) }
  catch { return '' }
}

export function versionPublicationStart(date: string, timeZone: string, prospective: boolean, now = new Date()) {
  const effectiveFrom = publicationStart(date, timeZone)
  if (prospective && date <= new Intl.DateTimeFormat('sv-SE', { timeZone }).format(now)) {
    throw new Error('三岗位优化须选择未来本地日期00:00生效，不得追溯替换已有工作。')
  }
  return effectiveFrom
}

export function allocationDateIssue(from: string, to: string, effectiveFrom: string, timeZone: string) {
  if (!validDate(from)) return '请选择有效的下发生效日期。'
  if (to && (!validDate(to) || to < from)) return '下发结束日期不能早于开始日期。'
  const versionStart = versionLocalDate(effectiveFrom, timeZone)
  if (!versionStart) return '无法确认所选版本生效日期或服务端时区，请刷新核对后再下发。'
  if (from < versionStart) return `所选版本从 ${versionStart} 生效，下发日期不能提前。`
  return undefined
}

export function conflictingAllocations(rows: ReleaseAllocation[], assignmentId: string, targetOrgUnitId: string, from: string, to = '') {
  if (!assignmentId || !targetOrgUnitId || !validDate(from)) return []
  return rows.filter(row => row.status === 'ACTIVE' && row.assignmentId === assignmentId && row.targetOrgUnitId === targetOrgUnitId
    && row.validFrom <= (to || '9999-12-31') && (!row.validTo || row.validTo >= from))
}

export function allocationReplacementPlan(rows: ReleaseAllocation[], target: AllocationReplacementTarget, now = new Date()) {
  if (!target.versionId || allocationDateIssue(target.validFrom, target.validTo, target.effectiveFrom, target.timeZone)) return undefined
  const today = versionLocalDate(now.toISOString(), target.timeZone)
  if (!today || target.validFrom <= today) return undefined
  const conflicts = conflictingAllocations(rows, target.assignmentId, target.targetOrgUnitId, target.validFrom, target.validTo)
  const previous = conflicts.length === 1 ? conflicts[0] : undefined
  if (!previous?.id || !previous.versionId || previous.versionId === target.versionId
    || !validDate(previous.validFrom) || previous.validFrom >= target.validFrom) return undefined
  const before = new Date(`${target.validFrom}T00:00:00Z`)
  before.setUTCDate(before.getUTCDate() - 1)
  return { previous, closeOn: before.toISOString().slice(0, 10) }
}

export async function confirmAllocationReplacement<T>(
  expectedOldId: string, target: AllocationReplacementTarget,
  load: () => Promise<ReleaseAllocation[]>, confirm: (message: string) => boolean,
  replace: (oldId: string) => Promise<T>, now = new Date(),
) {
  const plan = allocationReplacementPlan(await load(), target, now)
  if (!plan || plan.previous.id !== expectedOldId) throw new Error('已有下发或生效日期已变化，请刷新核对后再切换。')
  if (!confirm(`确认从 ${target.validFrom} 切换到所选工作包版本？\n旧分配截止至 ${plan.closeOn}，新分配从 ${target.validFrom} 开始${target.validTo ? `，截止至 ${target.validTo}` : ''}。\n不会删除或改写历史工作、提交和完成率；仅调整未来分配。`)) return undefined
  return { plan, result: await replace(plan.previous.id) }
}
