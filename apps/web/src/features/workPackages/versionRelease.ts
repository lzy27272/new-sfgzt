import { publicationStart } from './publicationTiming'

export type ReleaseAllocation = { id: string; assignmentId: string; targetOrgUnitId: string; status: string; validFrom: string; validTo?: string }
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
