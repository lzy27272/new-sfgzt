import type { FinanceRow } from './unifiedModel'

export function authorizationDate(local: string): string {
 if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(local)) throw new Error('请选择完整的北京时间和分钟')
 const full = `${local}:00+08:00`;const date = new Date(full)
 if (!Number.isFinite(date.getTime())) throw new Error('日期或时间不存在')
 const shanghai = new Date(date.getTime() + 8 * 3600_000).toISOString().slice(0, 16)
 if (shanghai !== local) throw new Error('日期或时间不存在')
 return full
}
export function authorizationWindow(from: string, until: string, maximumDays: number, now = Date.now()) {
 const start = authorizationDate(from);const end = authorizationDate(until)
 if (Date.parse(end) <= Date.parse(start) || Date.parse(end) <= now || Date.parse(end) - Date.parse(start) > maximumDays * 86400_000) throw new Error(`请核对有效窗口：截止须晚于开始及当前时间，最长${maximumDays}天`)
 return { validFrom: start, validUntil: end }
}
export function authorizationCeiling(value: string): string {
 if (!/^\d{1,14}(\.\d{1,2})?$/.test(value) || !/[1-9]/.test(value)) throw new Error('例外上限须为正的精确分值，最多14位整数和2位小数')
 return value
}
export function authorizationDrafts(rows: FinanceRow[], actorId: string): FinanceRow[] { return rows.filter(row => row.applicantId === actorId && ['DRAFT', 'RETURNED'].includes(String(row.status))) }
export function authorizationBudgets(rows: FinanceRow[], hotelId: unknown): FinanceRow[] { return rows.filter(row => row.hotelId === hotelId && row.kind === 'BUDGET' && row.status === 'PUBLISHED') }
export function authorizationState(row: FinanceRow, now = Date.now()): string {
 const status = String(row.status ?? '')
 if (['PUBLISHED', 'APPROVED'].includes(status) && !Number.isFinite(Date.parse(String(row.validUntil)))) return '有效期缺失，需后台核对'
 if (['PUBLISHED', 'APPROVED'].includes(status) && Date.parse(String(row.validUntil)) <= now) return '已到期，不再有效'
 if (status === 'PUBLISHED' && Date.parse(String(row.validFrom)) > now) return '已发布，尚未到生效时间'
 return ({ DRAFT: '草稿，尚未发布', PUBLISHED: '已发布，办理仍校验实时权限', REVOKED: '已撤销', REJECTED: '已拒绝', REVIEW: '待独立总监审核', APPROVED: '已批准，限本人原草稿版本及有效期' } as Record<string, string>)[status] ?? status
}
