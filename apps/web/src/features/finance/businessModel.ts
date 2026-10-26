import type { FinanceRow } from './unifiedModel'

export const businessScreens = { revenue: '收入与应收', payroll: '逐员工薪酬', intercompany: '内部往来与合并', tax: '票税与申报底稿' } as const
export function businessList(value: unknown): FinanceRow[] { return Array.isArray(value) ? value.filter(item => !!item && typeof item === 'object') as FinanceRow[] : [] }
export function parseBusinessJson(text: string, label: string): unknown {
  let value: unknown
  try { value = JSON.parse(text) } catch { throw new Error(`${label}不是有效 JSON，请先导出规范文件并预览。`) }
  const check = (item: unknown): void => {
    if (typeof item === 'number' && (!Number.isSafeInteger(item) || !Number.isFinite(item))) throw new Error('金额必须使用十进制文本，不能以浮点数传输。')
    if (Array.isArray(item)) item.forEach(check)
    else if (item && typeof item === 'object') Object.values(item).forEach(check)
  }
  check(value); return value
}
export function payrollNet(earnings: Record<string, string>, deductions: Record<string, string>): string {
  const cents = (value: string) => { if (!/^\d+(?:\.\d{1,2})?$/.test(value)) throw new Error('工资组件应精确到分'); const [whole, part = ''] = value.split('.'); return BigInt(whole) * 100n + BigInt(part.padEnd(2, '0')) }
  const net = Object.values(earnings).reduce((sum, value) => sum + cents(value), 0n) - Object.values(deductions).reduce((sum, value) => sum + cents(value), 0n)
  if (net < 0n) throw new Error('代扣不能超过应发')
  return `${net / 100n}.${(net % 100n).toString().padStart(2, '0')}`
}
export function businessStatus(value: unknown): string { return ({ REVIEW: '待独立确认', CANCELLED: '已撤销，保留原始资料', CONFIRMED: '业务已确认，非已过账', SUPERSEDED: '已有重结版本', WAIT_EXTERNAL: '待外部办理，未开票', REGISTERED: '真实外部发票已归档', INCOMPLETE_WORKSHEET: '资料不完整底稿', FINAL_WORKSHEET: '已核对合并底稿', PREPARATION_NOT_EXTERNALLY_FILED: '申报取数，未外部申报' } as Record<string, string>)[String(value)] ?? String(value ?? '—') }
