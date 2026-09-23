import { usageInput, usagePayload, usageValidation, type UsageCatalogue, type UsageInputLine, type UsageItem } from './workUsage'
import { baseQuantityPreview } from './unitConversion'

export type DailyUsageInput = { schemaVersion: 2; remainingUnusedConfirmed: boolean; commonItemIds: string[]; lines: UsageInputLine[] }
export const usageGroups = [['LINEN', '布草'], ['CONSUMABLE', '客用品'], ['CLEANING_SUPPLY', '清洁用品']] as const
export const emptyUsageLine = (item: UsageItem): UsageInputLine => ({ itemId: item.id, quantity: '', unit: item.issueUnit, expectedItemVersion: item.rowVersion })

export function readDailyUsage(value: unknown): DailyUsageInput | undefined {
  if (!value || typeof value !== 'object') return undefined
  const input = value as Partial<DailyUsageInput>
  if (input.schemaVersion !== 2 || typeof input.remainingUnusedConfirmed !== 'boolean' || !Array.isArray(input.commonItemIds)
    || !input.commonItemIds.every(id => typeof id === 'string') || !Array.isArray(input.lines)) return undefined
  return usageInput({ schemaVersion: 1, noUsage: false, lines: input.lines }) ? input as DailyUsageInput : undefined
}

/** Derive additions without effects: refreshing catalogue never overwrites quantities or stale item versions. */
export function dailyUsageValue(value: unknown, catalogue: UsageCatalogue): DailyUsageInput {
  const currentIds = catalogue.quickList?.itemIds ?? catalogue.items.slice(0, 100).map(item => item.id)
  // A disabled configured shortcut is excluded for new drafts, but remains visible in an existing draft.
  const commonIds = currentIds.filter(id => catalogue.items.some(item => item.id === id))
  const quick = readDailyUsage(value), legacy = usageInput(value)
  const previous = quick?.lines ?? legacy?.lines ?? []
  const added = commonIds.filter(id => !previous.some(line => line.itemId === id)).map(id => emptyUsageLine(catalogue.items.find(item => item.id === id)!))
  const sameList = quick && [...quick.commonItemIds].sort().join('|') === [...commonIds].sort().join('|')
  return {
    schemaVersion: 2, commonItemIds: commonIds,
    remainingUnusedConfirmed: quick ? Boolean(sameList && quick.remainingUnusedConfirmed) : Boolean(legacy?.noUsage && !legacy.lines.length),
    lines: [...previous, ...added],
  }
}

export function dailyUsageValidation(value: unknown, catalogue: UsageCatalogue): string | undefined {
  if (catalogue.referenceUsage) return undefined
  if (!catalogue.enabled || !catalogue.reviewerAvailable) return '门店耗用审核人尚未配置或已失效，请联系管理员配置后再提交。'
  if (value != null && !readDailyUsage(value) && !usageInput(value)) return '旧草稿中的物品数据无法识别，请保留原内容并重新核对。'
  const input = dailyUsageValue(value, catalogue)
  if (input.lines.length > 100) return '本次物品超过100项，请减少非常用品或联系管理员调整常用品清单。'
  const seen = new Set<string>(), positive: UsageInputLine[] = []
  for (const line of input.lines) {
    const item = catalogue.items.find(item => item.id === line.itemId)
    if (!item || item.rowVersion !== line.expectedItemVersion) return '清单中有物品已停用或配置变化，请核对标记的物品和数量。'
    if (seen.has(item.id)) return '同一种物品不能重复填写。'
    seen.add(item.id)
    if (![item.unit, item.issueUnit].includes(line.unit)) return `${item.name}的单位已变化，请重新核对。`
    if (!line.quantity.trim()) {
      if (!input.remainingUnusedConfirmed) return '仍有未填物品，请填写数量或确认其余未填物品本日未使用。'
      continue
    }
    const preview = baseQuantityPreview(line.quantity, line.unit === item.unit ? 1 : item.issueFactor)
    // Zero is an explicit no-use declaration, but negative, invalid and overprecision values are not.
    if (!/^\d+(\.\d{1,4})?$/.test(line.quantity.trim()) || Number(line.quantity) < 0) return `${item.name}：请输入非负数量，最多4位小数。`
    if (Number(line.quantity) > 0) {
      if (preview.error) return `${item.name}：${preview.error}`
      positive.push(line)
    }
  }
  if (!input.lines.length && !input.remainingUnusedConfirmed) return '请添加使用物品，或确认本日未使用。'
  return usageValidation({ schemaVersion: 1, noUsage: !positive.length, lines: positive }, catalogue)
}

export function dailyUsagePayload(value: unknown, catalogue: UsageCatalogue) {
  if (catalogue.referenceUsage) return usagePayload(value, catalogue)
  if (value != null && !readDailyUsage(value) && !usageInput(value)) return value
  return dailyUsageValue(value, catalogue)
}
