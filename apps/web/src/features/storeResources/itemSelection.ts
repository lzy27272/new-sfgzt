import type { UsageInputLine, UsageItem } from './workUsage'

export const itemCategories = { ALL: '全部', LINEN: '布草', CONSUMABLE: '耗品', CLEANING_SUPPLY: '清洁用品' }
export function filterUsageItems(items: UsageItem[], search: string, category: string) {
  const terms = search.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return items.filter(item => (category === 'ALL' || item.category === category)
    && terms.every(term => `${item.name} ${item.sku ?? ''} ${item.specification}`.toLocaleLowerCase().includes(term)))
}
/** Adding selections never replaces quantities already entered, even across searches/pages. */
export function appendUsageItems(lines: UsageInputLine[], items: UsageItem[]): UsageInputLine[] {
  const seen = new Set(lines.map(line => line.itemId))
  const added = items.filter(item => !seen.has(item.id) && Boolean(seen.add(item.id)))
  return [...lines, ...added.map(item => ({ itemId: item.id, quantity: '', unit: item.issueUnit, expectedItemVersion: item.rowVersion }))].slice(0, 100)
}
