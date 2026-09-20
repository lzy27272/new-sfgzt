import type { StoreResourceItem } from './types.ts'

export type EntryUnit = { unit: string; factor: number }

export function entryUnits(item: Pick<StoreResourceItem, 'unit' | 'purchaseUnit' | 'purchaseFactor' | 'issueUnit' | 'issueFactor'>): EntryUnit[] {
  const result: EntryUnit[] = []
  for (const option of [
    { unit: item.unit, factor: 1 },
    { unit: item.purchaseUnit || item.unit, factor: item.purchaseFactor ?? 1 },
    { unit: item.issueUnit || item.unit, factor: item.issueFactor ?? 1 },
  ]) {
    if (!result.some((existing) => existing.unit.toLocaleLowerCase() === option.unit.toLocaleLowerCase())) result.push(option)
  }
  return result
}

// Exact decimal preview. Posting always resolves and validates the factor again on the server.
function scaledDecimal(value: string, scale: number): bigint | undefined {
  const match = /^([+-]?)(\d+)(?:\.(\d*))?$/.exec(value.trim())
  if (!match || (match[3]?.length ?? 0) > scale) return undefined
  const integer = BigInt(match[2]) * 10n ** BigInt(scale)
  const fraction = BigInt((match[3] ?? '').padEnd(scale, '0') || '0')
  return (match[1] === '-' ? -1n : 1n) * (integer + fraction)
}

export function baseQuantityPreview(quantity: string, factor: number): { quantity?: string; error?: string } {
  if (!quantity.trim()) return {}
  const input = scaledDecimal(quantity, 4)
  if (input === undefined || input === 0n) return { error: '数量须为非零数字，最多4位小数' }
  if (!Number.isFinite(factor) || factor <= 0 || factor >= 1e12) return { error: '单位换算比例无效，请刷新物品配置' }
  const multiplier = scaledDecimal(factor.toFixed(6), 6)
  if (multiplier === undefined || multiplier <= 0n) return { error: '单位换算比例无效' }
  const product = input * multiplier
  if (product % 1_000_000n !== 0n) return { error: '换算后超过4位小数，请调整数量；系统不会自动舍入' }
  const base = product / 1_000_000n
  const magnitude = base < 0n ? -base : base
  if (magnitude >= 10n ** 18n) return { error: '换算后的库存数量过大' }
  const fraction = (magnitude % 10_000n).toString().padStart(4, '0').replace(/0+$/, '')
  return { quantity: `${base < 0n ? '-' : ''}${magnitude / 10_000n}${fraction ? `.${fraction}` : ''}` }
}

export function unitConfigurationError(base: string, purchase: string, purchaseFactor: string, issue: string, issueFactor: string): string | undefined {
  const baseName = base.trim().toLocaleLowerCase()
  if (!baseName) return '请填写基础单位'
  const choices = [
    { name: purchase.trim().toLocaleLowerCase() || baseName, factor: purchaseFactor.trim() },
    { name: issue.trim().toLocaleLowerCase() || baseName, factor: issueFactor.trim() },
  ]
  const seen = new Map([[baseName, 1_000_000n]])
  for (const choice of choices) {
    const factor = scaledDecimal(choice.factor || (choice.name === baseName ? '1' : ''), 6)
    if (factor === undefined || factor <= 0n || factor >= 10n ** 18n) return '换算比例须大于0，最多12位整数和6位小数'
    const existing = seen.get(choice.name)
    if (existing !== undefined && existing !== factor) return '同名单位必须使用相同比例，基础单位比例必须为1'
    seen.set(choice.name, factor)
  }
  return undefined
}
