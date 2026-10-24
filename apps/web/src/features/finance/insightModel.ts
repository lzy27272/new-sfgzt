import type { FinanceRow } from './unifiedModel'
export function currentFinanceMonth(now = new Date()) { return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}` }
export function insightValue(value: unknown): string { return value === null || value === undefined || value === '' ? '资料不足，暂不可判断' : String(value) }
export function insightEditContent(content: FinanceRow, analysis: string[], recommendations: string[]): FinanceRow {
  const lines = (key: string, texts: string[]) => ((content[key] ?? []) as FinanceRow[]).map((row, index) => ({ ...row, text: texts[index] ?? String(row.text ?? '') }))
  return { analysis: lines('analysis', analysis), recommendations: lines('recommendations', recommendations) }
}
export const migrationKinds = [ ['OPENING', '科目期初'], ['OPEN_ITEM', '未清往来'], ['ASSET', '固定资产期初'], ['INVENTORY', '存货财务期初'], ['PREPAID', '剩余待摊'] ] as const
export const migrationFieldLabels: Record<string, string> = { sourceKey: '来源唯一编号（文本）', accountCode: '控制科目代码 / 资产原值科目', accumulatedAccountCode: '累计折旧科目', physicalAssetId: '中台实物资产 ID（显式映射）', inventoryItemId: '中台实物库存品目 ID（显式映射）', inServiceDate: '原投用日期', originalUsefulMonths: '原预计使用月数', name: '名称 / 摘要', direction: '余额方向 DEBIT/CREDIT', businessDate: '原业务日期', dueDate: '到期日', partyName: '往来单位 / 员工', employeeReference: '员工原系统编号', debit: '期初借方', credit: '期初贷方', originalAmount: '原始金额 / 原值', remainingAmount: '剩余余额 / 净值', accumulatedAmount: '累计折旧', quantity: '存货数量', unit: '计量单位', residualAmount: '资产残值', remainingMonths: '剩余月数', benefitEnd: '受益结束日期' }
export function validateColumnMapping(mapping: Record<string, string>, required: string[], columns: string[]) { return required.every(key => !!mapping[key] && columns.includes(mapping[key])) && Object.values(mapping).filter(Boolean).every(value => columns.includes(value)) }
