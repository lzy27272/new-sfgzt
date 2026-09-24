import { useId, useLayoutEffect, useRef, useState } from 'react'
import type { UsageCatalogue, UsageItem } from './workUsage'
import { filterUsageItems, itemScopes, matchesItemScope, type ItemScopeFilter } from './itemSelection'
import styles from './usageItemNavigator.module.css'

/** Focus is an explicit user action; catalogue refreshes never change input values or move focus. */
export function useUsageQuantityNavigation() {
  const inputs = useRef(new Map<string, HTMLInputElement>())
  const [target, setTarget] = useState<string>()
  useLayoutEffect(() => {
    if (!target) return
    const input = inputs.current.get(target)
    if (input) {
      input.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' })
      input.focus({ preventScroll: true })
      input.select()
    }
    setTarget(undefined)
  }, [target])
  return { locate: setTarget, inputRef: (id: string) => (input: HTMLInputElement | null) => {
    if (input) inputs.current.set(id, input)
    else inputs.current.delete(id)
  } }
}

export function UsageScopeNotice({ catalogue }: { catalogue: UsageCatalogue }) {
  return <div className={styles.notice}>
    <small>仅显示当前任职可用物品。筛选、搜索不会清空已填数量。</small>
    {catalogue.departmentConfigured === false && <p role="status">当前任职未归属具体部门，暂不显示部门限定物品。请管理员在员工任职中补齐所属部门；全店通用且符合岗位限制的物品仍可填写。</p>}
    {!!catalogue.unconfiguredItemCount && <p role="status">门店有 {catalogue.unconfiguredItemCount} 项物品尚未配置使用范围，暂不列入清单。缺少所需物品时，请管理员在“门店物品配置”补齐部门、岗位，不要将缺项当作未使用。</p>}
  </div>
}

export function UsageItemNavigator({ items, selectedIds, scope, onScopeChange, onLocate }: {
  items: UsageItem[]; selectedIds: string[]; scope: ItemScopeFilter; onScopeChange: (scope: ItemScopeFilter) => void; onLocate: (item: UsageItem) => void
}) {
  const [search, setSearch] = useState('')
  const [expanded, setExpanded] = useState(false)
  const id = useId()
  const scoped = items.filter(item => matchesItemScope(item, scope))
  const results = filterUsageItems(scoped, search, 'ALL')
  return <div className={styles.toolbar} role="search" aria-label="物品搜索定位">
    <div className={styles.controls}>
      <label htmlFor={`${id}-scope`}>适用范围<select id={`${id}-scope`} value={scope} onChange={event => onScopeChange(event.target.value as ItemScopeFilter)}>{Object.entries(itemScopes).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      <div className={styles.searchField}><label htmlFor={`${id}-search`}>搜索并填写数量<input id={`${id}-search`} type="search" autoComplete="off" placeholder="名称 / 编码 / 规格" value={search} aria-controls={`${id}-results`} aria-expanded={expanded && !!search.trim()} onFocus={() => setExpanded(true)} onChange={event => { setSearch(event.target.value); setExpanded(true) }} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault(); if (event.key === 'Escape') setExpanded(false) }} /></label>
        {search && <button type="button" className="link-button" aria-label="清除物品搜索" onClick={() => { setSearch(''); setExpanded(false) }}>清除</button>}
      </div>
    </div>
    {expanded && !!search.trim() && <div className={styles.results} id={`${id}-results`}>
      <div className={styles.resultHeader}><small role="status">找到 {results.length} 项，点击后直接填写数量</small><button type="button" className="link-button" onClick={() => setExpanded(false)}>收起结果</button></div>
      {results.map(item => <button type="button" key={item.id} className={styles.result} disabled={!selectedIds.includes(item.id) && selectedIds.length >= 100} onClick={() => { setExpanded(false); onLocate(item) }}>
        <span><strong>{item.name}</strong><small>{item.sku} · {item.specification || '未配置规格'} · {item.issueUnit}</small></span><span>{selectedIds.includes(item.id) ? '定位数量' : selectedIds.length >= 100 ? '已达100项' : '添加并填写'}</span>
      </button>)}
      {!results.length && <p>此范围没有匹配物品，可调整关键词或选择“全部适用”。不会显示其他部门、岗位的专用物品。</p>}
    </div>}
  </div>
}
