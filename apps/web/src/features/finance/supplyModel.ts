export type SupplyRow = Record<string, unknown>
export function supplyRows(value: unknown): SupplyRow[] { return Array.isArray(value) ? value.filter((r): r is SupplyRow => !!r && typeof r === 'object' && !Array.isArray(r)) : [] }
export function supplyObject(value: unknown): SupplyRow { return value && typeof value === 'object' && !Array.isArray(value) ? value as SupplyRow : {} }
export function supplyText(row: SupplyRow, key: string, fallback = ''): string { return row[key] === null || row[key] === undefined ? fallback : String(row[key]) }
export function supplyLineAmount(quantity: string, price: string): string {
 const parse = (v: string) => { if (!/^\d{1,14}(\.\d{1,4})?$/.test(v)) throw new Error('数量和单价须为最多四位小数的非负十进制文本'); const [a, b = ''] = v.split('.'); return BigInt(a) * 10000n + BigInt(b.padEnd(4, '0')) }
 const product = parse(quantity) * parse(price), cents = (product + 500000n) / 1000000n
 return `${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`
}
export function supplyMoneyDifference(left: string, right: string): string {
 const parse = (v: string) => { if (!/^-?\d+(\.\d{1,2})?$/.test(v)) throw new Error('金额应为精确两位十进制文本'); const negative = v.startsWith('-'); const [a, b = ''] = v.replace('-', '').split('.'); return (BigInt(a) * 100n + BigInt(b.padEnd(2, '0'))) * (negative ? -1n : 1n) }
 const cents = parse(left) - parse(right), abs = cents < 0n ? -cents : cents
 return `${cents < 0n ? '-' : ''}${abs / 100n}.${String(abs % 100n).padStart(2, '0')}`
}
export const supplyEventLabels = { CAPITALIZE: '在建转固', DEPRECIATE: '月度折旧', TRANSFER: '同法人调拨', LOSS: '盘亏核销', DISPOSE: '完整资产处置' }
