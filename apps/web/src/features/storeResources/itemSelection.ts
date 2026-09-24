import type { UsageInputLine, UsageItem } from './workUsage'

export const itemCategories = { ALL: '全部', LINEN: '布草', CONSUMABLE: '耗品', CLEANING_SUPPLY: '清洁用品' }
export const itemScopes = { ALL: '全部适用', POSITION: '本岗位专用', DEPARTMENT: '本部门通用', SHARED: '全店通用' }
export type ItemScopeFilter = keyof typeof itemScopes
export const matchesItemScope = (item: UsageItem, scope: ItemScopeFilter) => scope === 'ALL' || item.usageScopeKind === scope
export function filterUsageItems(items: UsageItem[], search: string, category: string) {
  const terms = search.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return items.filter(item => (category === 'ALL' || item.category === category)
    && terms.every(term => `${item.name} ${item.sku ?? ''} ${item.specification}`.toLocaleLowerCase().includes(term)))
}
/** Adding selections never replaces quantities already entered, even across searches/pages. */
export function appendUsageItems(lines: UsageInputLine[], items: UsageItem[]): UsageInputLine[] {
  const seen = new Set(lines.map(line => line.itemId))
  const added = items.filter(item => !seen.has(item.id) && Boolean(seen.add(item.id)))
  return [...lines, ...added.slice(0, Math.max(0, 100 - lines.length)).map(item => ({ itemId: item.id, quantity: '', unit: item.issueUnit, expectedItemVersion: item.rowVersion }))]
}
