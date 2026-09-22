import { useEffect, useRef, useState } from 'react'
import type { UsageItem } from './workUsage'
import { filterUsageItems, itemCategories } from './itemSelection'
import styles from './storeResources.module.css'

export function StoreItemPicker({ items, selectedIds, onConfirm, onClose }: {
  items: UsageItem[]; selectedIds: string[]; onConfirm: (items: UsageItem[]) => void; onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [search, setSearch] = useState('')
  const [category, setCategory] = useState('ALL')
  const [page, setPage] = useState(1)
  const [chosen, setChosen] = useState<Set<string>>(new Set())
  const existing = new Set(selectedIds)
  const filtered = filterUsageItems(items, search, category)
  const pages = Math.max(1, Math.ceil(filtered.length / 8))
  const currentPage = Math.min(page, pages)
  const additions = items.filter(item => chosen.has(item.id) && !existing.has(item.id))
  useEffect(() => {
    const element = dialog.current!
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    element.showModal()
    return () => { element.close(); document.body.style.overflow = previousOverflow }
  }, [])
  return <dialog ref={dialog} className={styles.itemPicker} onCancel={event => { event.preventDefault(); onClose() }} aria-labelledby="item-picker-title">
    <header><div><h2 id="item-picker-title">添加物品</h2><p>当前门店 · 当前岗位可用物品</p><small>单位来自后台配置，不可修改。</small></div><button type="button" className="secondary" aria-label="关闭物品选择" onClick={onClose}>关闭</button></header>
    <div className={styles.pickerBody}>
      <label className={styles.pickerSearch}>搜索物品<input autoFocus type="search" placeholder="搜索物品名称、编码、规格" value={search} onChange={event => { setSearch(event.target.value); setPage(1) }} /></label>
      <nav className={styles.pickerCategories} aria-label="物品分类">{Object.entries(itemCategories).map(([key, label]) => <button type="button" key={key} aria-pressed={key === category} onClick={() => { setCategory(key); setPage(1) }}>{label}</button>)}</nav>
      <div className={styles.pickerList}>{filtered.slice((currentPage - 1) * 8, currentPage * 8).map(item => <label className={styles.pickerItem} key={item.id}>
        <input type="checkbox" checked={existing.has(item.id) || chosen.has(item.id)} disabled={existing.has(item.id) || (!chosen.has(item.id) && additions.length + existing.size >= 100)} onChange={event => setChosen(previous => { const next = new Set(previous); event.target.checked ? next.add(item.id) : next.delete(item.id); return next })} />
        <span><strong>{item.name}</strong><small>{item.specification || '未配置规格'}{item.sku ? ` · ${item.sku}` : ''}</small></span>
        <span>{item.issueUnit}<small>后台单位</small></span><span>{existing.has(item.id) ? '已添加' : ''}</span>
      </label>)}</div>
      {!filtered.length && <p role="status">没有找到符合条件的物品，请调整搜索或分类；不能添加其他门店或已停用的物品。</p>}
      <nav className={styles.pickerPagination} aria-label="物品分页"><span>共 {filtered.length} 项</span><button type="button" className="secondary" disabled={currentPage <= 1} onClick={() => setPage(currentPage - 1)}>上一页</button><span>{currentPage} / {pages}</span><button type="button" className="secondary" disabled={currentPage >= pages} onClick={() => setPage(currentPage + 1)}>下一页</button></nav>
    </div>
    <footer><span aria-live="polite">已选 {additions.length} 项</span><button type="button" className="secondary" onClick={onClose}>取消</button><button type="button" className="primary" disabled={!additions.length} onClick={() => onConfirm(additions)}>确认添加（{additions.length}）</button></footer>
  </dialog>
}
