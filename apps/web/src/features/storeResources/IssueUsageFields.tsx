import { useState } from 'react'
import type { UsageCatalogue, UsageInputLine, UsageItem } from './workUsage'
import { usageInput } from './workUsage'
import { appendUsageItems, filterUsageItems } from './itemSelection'
import { issueRanges, matchesIssueRange, issueDepartmentLabel, type IssueRange } from './issueSelection'
import { useUsageQuantityNavigation } from './UsageItemNavigator'
import styles from './crossDepartment.module.css'

export function IssueUsageFields({ catalogue, value, disabled, onChange, onRefresh }: {
  catalogue: UsageCatalogue; value: unknown; disabled?: boolean; onChange: (value: Record<string, unknown>) => void; onRefresh: () => void
}) {
  const [range, setRange] = useState<IssueRange>('MINE')
  const [department, setDepartment] = useState('')
  const [search, setSearch] = useState('')
  const [expanded, setExpanded] = useState(false)
  const [limit, setLimit] = useState(50)
  const navigation = useUsageQuantityNavigation()
  const input = usageInput(value) ?? { schemaVersion: 1 as const, noUsage: false, lines: [] }
  const invalid = value != null && !usageInput(value)
  const scoped = catalogue.items.filter(item => matchesIssueRange(item, range, department))
  const results = filterUsageItems(scoped, search, 'ALL')
  const itemById = new Map(catalogue.items.map(item => [item.id, item]))
  const update = (lines: UsageInputLine[]) => onChange({ ...input, noUsage: false, lines })
  const changeQuantity = (item: UsageItem, quantity: string) => {
    const lines = appendUsageItems(input.lines, [item])
    update(lines.map(line => line.itemId === item.id ? { ...line, quantity } : line))
  }
  const locate = (item: UsageItem) => {
    if (input.noUsage) update(input.lines)
    setExpanded(false); setLimit(Math.max(limit, scoped.findIndex(row => row.id === item.id) + 1))
    navigation.locate(item.id)
  }
  return <section className={styles.issue} aria-label="物品领用登记">
    <h3>物品领用登记</h3>
    <p>默认显示本岗位、本部门适用物品；跨部门领用或全店物资均仅限当前门店。领用不等于实际消耗，日报另填实际使用数量。</p>
    {invalid && <p role="alert">原草稿格式无法识别，已保留原内容，请联系管理员核对。</p>}
    <fieldset disabled={disabled || invalid}>
      <div className={styles.sticky} role="search" aria-label="领用物品搜索定位">
        <nav className={styles.ranges} aria-label="领用范围">{Object.entries(issueRanges).map(([key, label]) => <button type="button" key={key} aria-pressed={range === key} onClick={() => { setRange(key as IssueRange); setExpanded(false) }}>{label}</button>)}</nav>
        {range === 'DEPARTMENT' && <label>领用部门<select value={department} onChange={event => { setDepartment(event.target.value); setExpanded(false) }}><option value="">请选择本店部门</option>{catalogue.departments?.map(d => <option value={d.id} key={d.id}>{d.name}</option>)}</select></label>}
        <label>搜索并填写数量<input type="search" placeholder="名称 / 编码 / 规格" value={search} onFocus={() => setExpanded(true)} onChange={event => { setSearch(event.target.value); setExpanded(true) }} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault(); if (event.key === 'Escape') setExpanded(false) }} /></label>
        {expanded && search.trim() && <div className={styles.results}><small role="status">找到 {results.length} 项，点击直接填写数量</small>{results.map(item => <button type="button" key={item.id} onClick={() => locate(item)}>{item.name} · {item.sku} <span>定位数量 →</span></button>)}{!results.length && <p>当前范围未找到，可切换跨部门领用或全店物资。</p>}</div>}
        <small>筛选、搜索不会清空已填数量。</small>
      </div>
      {!scoped.length && <p>{range === 'DEPARTMENT' && !department ? '选择本店部门后查看该部门物品。' : '当前范围暂无可领用物品。'}</p>}
      {!input.noUsage && scoped.slice(0, limit).map(item => {
        const line = input.lines.find(entry => entry.itemId === item.id)
        return <div className={styles.item} key={item.id}><div><strong>{item.name}</strong><small>{item.sku} · {issueDepartmentLabel(item, catalogue.departments)}</small><small>{item.specification || '未配置规格'} · {item.issueUnit}</small>{line && line.expectedItemVersion !== item.rowVersion && <p role="alert">配置已变化，请移除此项后重新填写并核对。</p>}</div><label>领用数量<input ref={navigation.inputRef(item.id)} aria-label={`${item.name}领用数量`} type="number" inputMode="decimal" min="0.0001" step="0.0001" placeholder="数量" value={line?.quantity ?? ''} disabled={!line && input.lines.length >= 100} onChange={event => changeQuantity(item, event.target.value)} /></label></div>
      })}
      {!input.noUsage && scoped.length > limit && <button type="button" className="secondary" onClick={() => setLimit(limit + 50)}>显示更多物品</button>}
      <section className={styles.summary} aria-label="全部已选领用物品"><h4>已选物品 · {input.lines.length} 项</h4><p>包含其他范围已填写的物品，提交前请核对。</p>{input.lines.map(line => <div key={line.itemId}><span>{itemById.get(line.itemId)?.name ?? '原物品已不可用'} · {line.quantity || '待填数量'} {line.unit}</span><button type="button" className="link-button" onClick={() => update(input.lines.filter(row => row.itemId !== line.itemId))}>移除</button></div>)}{!input.lines.length && <small>尚未填写领用数量</small>}</section>
      <label><input type="checkbox" checked={input.noUsage} disabled={input.lines.length > 0} onChange={event => onChange({ schemaVersion: 1, noUsage: event.target.checked, lines: [] })} />本次没有领用</label>
      <button type="button" className="link-button" onClick={onRefresh}>刷新物品清单</button>
      {!!catalogue.unconfiguredItemCount && <p>本店 {catalogue.unconfiguredItemCount} 项物品尚未配置使用部门，请联系管理员补齐。</p>}
    </fieldset>
  </section>
}
