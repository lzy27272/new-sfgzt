import { useRef, useState } from 'react'
import type { UsageCatalogue, UsageInputLine, UsageItem } from './workUsage'
import { usageInput } from './workUsage'
import { filterUsageItems } from './itemSelection'
import { issueRanges, matchesIssueRange, issueDepartmentLabel, setIssueQuantity, type IssueRange } from './issueSelection'
import styles from './crossDepartment.module.css'

export function IssueUsageFields({ catalogue, value, disabled, onChange, onRefresh }: {
  catalogue: UsageCatalogue; value: unknown; disabled?: boolean; onChange: (value: Record<string, unknown>) => void; onRefresh: () => void
}) {
  const [range, setRange] = useState<IssueRange>('MINE')
  const [department, setDepartment] = useState('')
  const [search, setSearch] = useState('')
  const searchInput = useRef<HTMLInputElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const input = usageInput(value) ?? { schemaVersion: 1 as const, noUsage: false, lines: [] }
  const invalid = value != null && !usageInput(value)
  const scoped = catalogue.items.filter(item => matchesIssueRange(item, range, department))
  const results = filterUsageItems(scoped, search, 'ALL')
  const itemById = new Map(catalogue.items.map(item => [item.id, item]))
  const linesById = new Map(input.lines.map(line => [line.itemId, line]))
  const update = (lines: UsageInputLine[]) => onChange({ ...input, noUsage: false, lines })
  const changeQuantity = (item: UsageItem, quantity: string) => update(setIssueQuantity(input.lines, item, quantity))
  const resetScroll = () => { if (list.current) list.current.scrollTop = 0 }
  const continueSearch = () => { searchInput.current?.focus({ preventScroll: true }); searchInput.current?.select() }
  return <section className={styles.issue} aria-label="物品领用登记">
    {invalid && <p role="alert">原草稿格式无法识别，已保留原内容，请联系管理员核对。</p>}
    <fieldset disabled={disabled || invalid}>
      <legend className={styles.srOnly}>选择物品并填写领用数量</legend>
      <div className={styles.sticky} role="search" aria-label="领用物品搜索定位">
        <nav className={styles.ranges} aria-label="领用范围">{Object.entries(issueRanges).map(([key, label]) => <button type="button" key={key} aria-pressed={range === key} onClick={() => { setRange(key as IssueRange); resetScroll() }}>{label}</button>)}</nav>
        {range === 'DEPARTMENT' && <label className={styles.department}>领用部门<select value={department} onChange={event => { setDepartment(event.target.value); resetScroll() }}><option value="">请选择本店部门</option>{catalogue.departments?.map(d => <option value={d.id} key={d.id}>{d.name}</option>)}</select></label>}
        <div className={styles.searchRow}>
          <label><span className={styles.srOnly}>搜索并填写数量</span><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10" cy="10" r="6" /><path d="m15 15 5 5" /></svg><input ref={searchInput} type="search" placeholder="搜索名称 / 编码 / 规格" value={search} onChange={event => { setSearch(event.target.value); resetScroll() }} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault() }} /></label>
          {search && <button type="button" aria-label="清空搜索" onClick={() => { setSearch(''); resetScroll(); continueSearch() }}>×</button>}
        </div>
        <div className={styles.searchHint}><small role="status">{search.trim() ? `找到 ${results.length} 项，可直接填写数量` : `共 ${scoped.length} 项，直接填写领用数量`}</small><button type="button" className="link-button" onClick={continueSearch}>继续搜索</button></div>
      </div>
      <div className={styles.list} ref={list} tabIndex={0} aria-label="可领用物品清单">
        {!results.length && <p className={styles.empty}>{range === 'DEPARTMENT' && !department ? '选择本店部门后查看该部门物品。' : search.trim() ? '当前范围未找到，可修改搜索词或切换范围。' : '当前范围暂无可领用物品。'}</p>}
        {results.map(item => {
          const line = linesById.get(item.id)
          return <div className={styles.item} key={item.id}>
            <div className={styles.itemName}><strong>{item.name}</strong><small>{item.sku}{item.specification ? ` · ${item.specification}` : ''}</small><span className={item.usageScopeMode === 'ALL_DEPARTMENTS' ? styles.commonBadge : styles.badge}>{issueDepartmentLabel(item, catalogue.departments)}</span>{line && line.expectedItemVersion !== item.rowVersion && <p role="alert">配置已变化，请在已选清单移除此项后重新填写。</p>}</div>
            <label><span>{item.issueUnit}</span><input aria-label={`${item.name}领用数量`} type="number" inputMode="decimal" min="0.0001" step="0.0001" placeholder="数量" value={line?.quantity ?? ''} disabled={!line && input.lines.length >= 100} onChange={event => changeQuantity(item, event.target.value)} /></label>
          </div>
        })}
        {!!results.length && <p className={styles.listEnd}>已展示当前范围全部 {results.length} 项物品</p>}
        {input.lines.length >= 100 && <p role="status">本次已选满100项，可移除后继续选择。</p>}
      </div>
      <div className={styles.selectionFooter}>
        <div className={styles.options}><label><input type="checkbox" checked={input.noUsage} disabled={input.lines.length > 0} onChange={event => onChange({ schemaVersion: 1, noUsage: event.target.checked, lines: [] })} />本次没有领用</label><button type="button" className="link-button" onClick={onRefresh}>刷新清单</button></div>
        <details className={styles.summary} aria-label="全部已选领用物品"><summary>已选 {input.lines.length} 项 · 数量已保留<span>查看 / 移除</span></summary><div className={styles.selectedList}>{input.lines.map(line => <div key={line.itemId}><span>{itemById.get(line.itemId)?.name ?? '原物品已不可用'} · {line.quantity || '待填数量'} {line.unit}</span><button type="button" className="link-button" onClick={() => update(input.lines.filter(row => row.itemId !== line.itemId))}>移除</button></div>)}{!input.lines.length && <small>尚未填写领用数量。填写数量会自动取消“本次没有领用”。</small>}</div></details>
        {!!catalogue.unconfiguredItemCount && <p role="alert" className={styles.catalogueWarning}>还有 {catalogue.unconfiguredItemCount} 项待同步使用范围，请刷新清单；仍未同步请联系管理员检查数据升级。</p>}
      </div>
    </fieldset>
  </section>
}
