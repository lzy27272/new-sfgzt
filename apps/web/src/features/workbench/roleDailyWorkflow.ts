import type { WorkExpectation } from '../../domain.ts'
import type { WorkBoard } from './workBoardModel.ts'

export type DailyWorkflowRole = 'HOUSEKEEPING_ATTENDANT' | 'ASSISTANT_GENERAL_MANAGER' | 'NIGHT_SERVICE_ATTENDANT'
export type DailyWorkflowGroup = 'start' | 'during' | 'close'

export function dailyWorkflowRole(roleCode?: string): DailyWorkflowRole | undefined {
  const code = roleCode?.trim().toUpperCase()
  if (code === 'HOUSEKEEPING_ATTENDANT' || code === 'ASSISTANT_GENERAL_MANAGER' || code === 'NIGHT_SERVICE_ATTENDANT') return code
}

export const dailyWorkflowGroups: Record<DailyWorkflowRole, ReadonlyArray<{ id: DailyWorkflowGroup; label: string; description: string }>> = {
  HOUSEKEEPING_ATTENDANT: [
    { id: 'start', label: '开工准备与按需领用', description: '先准备工作车；物品按需领用，不要求每天重复领用。' },
    { id: 'during', label: '做房与即时记录', description: '服务、客诉和遗留物发现后及时记录，不等日报收尾。' },
    { id: 'close', label: '日报与收尾', description: '核对实际耗用、差异和工作车归位；日报引用当天已有记录。' },
  ],
  ASSISTANT_GENERAL_MANAGER: [
    { id: 'start', label: '早间综合检查', description: '各项检查按自己的时间完成，早餐仅显示本店实际生成的工作。' },
    { id: 'during', label: '当班检查与跟进', description: '逐项保存查房、对客服务与工程等记录，保留独立检查和考核。' },
    { id: 'close', label: '晚间巡查与日报', description: '晚间巡查后汇总异常、未完成事项及次日安排，不补造上午记录。' },
  ],
  NIGHT_SERVICE_ATTENDANT: [
    { id: 'start', label: '接班准备', description: '确认接班待办与工作车准备，按需领用物品。' },
    { id: 'during', label: '班中巡查', description: '保留每轮设备检查及公共区域巡查，安全异常立即上报。' },
    { id: 'close', label: '收尾交班与日报', description: '跨午夜仍归属原营业日；日报提交不代替接收人交班确认。' },
  ],
}

const housekeepingStart = new Set(['HKA_CART_PREPARATION', 'HKA_ITEM_ISSUE'])
const housekeepingClose = new Set(['HKA_USAGE_RETURN', 'HKA_DAILY_REPORT'])
const nightStart = new Set(['NSA_CART_PREPARATION', 'NSA_ITEM_ISSUE'])
const nightClose = new Set(['NSA_FINAL_PUBLIC_RECHECK', 'NSA_DAILY_HANDOVER'])
const assistantStart = new Set(['BREAKFAST_INSPECTION', 'BREAKFAST_FEEDBACK', 'BREAKFAST_WORKFLOW', 'APPEARANCE_MEETING', 'OTA_CHECK', 'PUBLIC_AREA', 'KITCHEN_INSPECTION'])
const assistantClose = new Set(['EVENING_LIGHT_BAR', 'EVENING_PUBLIC_AREA', 'DAILY_REPORT', 'DAILY_SUMMARY'])

export function dailyWorkflowGroup(role: DailyWorkflowRole, item: Pick<WorkExpectation, 'itemCode' | 'executionPolicy'>): DailyWorkflowGroup {
  const configured = (item.executionPolicy as Record<string, unknown> | undefined)?.workflowGroup
  if (configured === 'START') return 'start'
  if (configured === 'DURING') return 'during'
  if (configured === 'CLOSE') return 'close'
  const code = item.itemCode ?? ''
  if (role === 'HOUSEKEEPING_ATTENDANT') return housekeepingStart.has(code) ? 'start' : housekeepingClose.has(code) ? 'close' : 'during'
  if (role === 'NIGHT_SERVICE_ATTENDANT') return nightStart.has(code) ? 'start' : nightClose.has(code) ? 'close' : 'during'
  const suffix = code.replace(/^AGM_(NS|WS)_/, '')
  return assistantStart.has(suffix) ? 'start' : assistantClose.has(suffix) ? 'close' : 'during'
}

export function breakfastWorkflowSchedule(item: WorkExpectation) {
  const raw = (item.executionPolicy as Record<string, unknown> | undefined)?.breakfastStages
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
  const schedule = raw as Record<string, unknown>
  const configuredTime = (value: unknown) => typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value) ? value : '待配置'
  const stages = ([['opening', '开餐巡查'], ['peak', '高峰巡查与意见'], ['closing', '收尾汇总']] as const).map(([key, label]) => {
    const value = schedule[key]
    const stage = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
    return { key, label, start: configuredTime(stage.start), end: configuredTime(stage.end) }
  })
  return { confirmed: schedule.scheduleConfirmed === true, stages,
    recheck: schedule.recheckOnlyWhenUnresolved === true ? configuredTime(schedule.unresolvedRecheck) : undefined }
}

export function isImmediateWorkflowItem(item: WorkExpectation) {
  return item.executionPolicy?.registrationMode === 'WHEN_OCCURRED'
    || ['HKA_GUEST_SERVICE_EVENT', 'HKA_COMPLAINT_EVENT', 'HKA_LOST_PROPERTY', 'NSA_LOST_PROPERTY'].includes(item.itemCode ?? '')
}

export function workflowTime(value?: string) {
  if (!value || Number.isNaN(Date.parse(value))) return '待配置'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

export function workflowTimeOrder(first: WorkExpectation, second: WorkExpectation) {
  const timestamp = (item: WorkExpectation) => {
    const value = Date.parse(item.availableAt ?? item.dueAt ?? '')
    return Number.isNaN(value) ? Number.MAX_SAFE_INTEGER : value
  }
  return timestamp(first) - timestamp(second) || first.title.localeCompare(second.title, 'zh-CN')
}

// The board's metrics cover all rows; its first list page does not.
export async function loadDailyWorkflowItems(loadPage: (page: number) => Promise<{ data: WorkBoard; source: string }>) {
  const items: WorkExpectation[] = []
  const ids = new Set<string>()
  for (let page = 0; page < 400; page += 1) {
    const result = await loadPage(page)
    if (result.source !== 'api') throw new Error('未读取到实际工作数据，请刷新重试。')
    const { data } = result
    for (const item of data.items) {
      if (!item.id || ids.has(item.id)) throw new Error('工作清单在读取时发生变化，请刷新重试。')
      ids.add(item.id)
      items.push(item)
    }
    if (items.length >= data.filtered.item_count) return { data: items, source: 'api' as const }
    if (!data.items.length) throw new Error('工作清单尚未完整读取，请刷新重试。')
  }
  throw new Error('工作清单较大，请缩小日期范围后重试。')
}
