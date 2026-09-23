import { useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { procurementRequest, download, useProcurementCommand, message } from './api'
import { filterCatalog, mergeImport, number, type CatalogItem, type DraftLine, type HotelContext, type ImportPreview, type OrderDetail } from './model'
import s from './procurement.module.css'

export function CommandFeedback({ error, notice }: { error?: string; notice?: string }) {
  return <>{error && <p role="alert" className={s.error}>{error}</p>}{notice && <p role="status" className={s.success}>{notice}</p>}</>
}
export function ItemPicker({ items, lines, onChange, disabled }: { items: CatalogItem[]; lines: DraftLine[]; onChange: (rows: DraftLine[]) => void; disabled: boolean }) {
  const [query, setQuery] = useState(''); const [page, setPage] = useState(0)
  const filtered = filterCatalog(items, query); const maxPage = Math.max(0, Math.ceil(filtered.length / 8) - 1); const selectedPage = Math.min(page, maxPage)
  return <>
    <div className={s.picker}><label>搜索并添加门店物品<input type="search" placeholder="物品名称、编码或规格" value={query} onChange={e => { setQuery(e.target.value); setPage(0) }} /></label>
      <div className={s.catalog}>{filtered.slice(selectedPage * 8, selectedPage * 8 + 8).map(item => {
        const selected = lines.some(l => l.itemId === item.id)
        return <button type="button" disabled={disabled || selected || lines.length >= 200} key={item.id} onClick={() => onChange([...lines, { itemId: item.id, quantity: 1, unit: item.purchase_unit || item.unit }])}><span><strong>{item.name}</strong><small>{item.specification || item.sku} · 库存 {number(item.current_quantity)} {item.unit}</small></span><b>{selected ? '已添加' : '＋ 添加'}</b></button>
      })}</div>{!filtered.length && <p className={s.muted}>没有匹配物品。新物品请先由管理员在本门店物品配置中建档。</p>}
      <div className={s.actions}><button type="button" disabled={selectedPage === 0} onClick={() => setPage(selectedPage - 1)}>上一页</button><small>第 {selectedPage + 1} / {maxPage + 1} 页 · {filtered.length} 种</small><button type="button" disabled={selectedPage === maxPage} onClick={() => setPage(selectedPage + 1)}>下一页</button></div>
    </div>
    <div className={s.tableWrap} role="region" aria-label="采购清单，可横向滚动" tabIndex={0}><table className={s.table}><thead><tr><th>物品 / 规格</th><th>当前库存 / 最低值</th><th>数量</th><th>单位</th><th>折合库存数量</th><th>操作</th></tr></thead><tbody>{lines.map((line, index) => {
      const item = items.find(i => i.id === line.itemId); const options = item ? [...new Set([item.unit, item.purchase_unit].filter(Boolean))] : [line.unit]
      const factor = item && line.unit !== item.unit ? item.purchase_factor : 1
      const change = (patch: Partial<DraftLine>) => onChange(lines.map((l, n) => n === index ? { ...l, ...patch } : l))
      return <tr key={line.itemId}><td>{item?.name ?? '物品已停用，请移除'}<small>{item?.specification || item?.sku}</small></td><td>{number(item?.current_quantity)} / {number(item?.minimum_quantity)} {item?.unit}</td>
        <td><input type="number" aria-label={`${item?.name ?? '物品'}采购数量`} min="0.0001" step="0.0001" required value={line.quantity || ''} disabled={disabled} onChange={e => change({ quantity: Number(e.target.value) })} /></td>
        <td><select aria-label={`${item?.name ?? '物品'}采购单位`} value={line.unit} disabled={disabled} onChange={e => change({ unit: e.target.value })}>{options.map(u => <option key={u}>{u}</option>)}</select></td>
        <td>{number(line.quantity * factor)} {item?.unit}</td><td><button type="button" className={s.danger} disabled={disabled} onClick={() => onChange(lines.filter((_, n) => n !== index))}>移除</button></td></tr>
    })}</tbody></table></div>{!lines.length && <p className={s.muted}>请从上方搜索添加物品，或导入采购清单。</p>}
  </>
}
export function OrderEditor({ identity, hotelId, hotel, order, onSaved, onBack }: { identity: ApiIdentity; hotelId: string; hotel: HotelContext; order?: OrderDetail; onSaved: (order: OrderDetail, notice: string) => void; onBack: () => void }) {
  const [title, setTitle] = useState(order?.title ?? '门店采购需求'); const [note, setNote] = useState(order?.note ?? ''); const [expectedOn, setExpectedOn] = useState(order?.expected_on ?? '')
  const [lines, setLines] = useState<DraftLine[]>(order?.lines.map(l => ({ itemId: l.item_id, quantity: Number(l.input_quantity), unit: l.input_unit })) ?? [])
  const [preview, setPreview] = useState<ImportPreview>(); const cmd = useProcurementCommand()
  const save = async (submit: boolean) => {
    const body = { expectedVersion: order?.version ?? -1, title: title.trim(), note, expectedOn: expectedOn || null, lines }
    await cmd.run(JSON.stringify({ save: body, id: order?.id }), id => procurementRequest<OrderDetail>(identity, order ? `/orders/${order.id}` : `/hotels/${hotelId}/orders`, order ? 'PUT' : 'POST', { ...body, commandId: id }), result => onSaved(result, submit ? '清单已保存，请核对后点击提交采购。' : '采购草稿已保存'))
  }
  return <form className={s.card} onSubmit={e => { e.preventDefault(); void save(false) }}>
    <div className={s.heading}><div><h2>{order?.source === 'LOW_STOCK' ? '店长审核 · 低库存补货清单' : '填写采购需求'}</h2><p className={s.muted}>可继续搜索添加其他采购。数量、单位以门店后台配置为准。</p></div><button type="button" onClick={onBack}>返回列表</button></div>
    {!hotel.config.enabled && <p className={s.warning}>门店采购未启用，请管理员先配置责任人与预警接收人。</p>}
    <fieldset disabled={cmd.busy} style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}><div className={s.fields}><label>采购事由<input required maxLength={160} value={title} onChange={e => setTitle(e.target.value)} /></label><label>期望到货日期<input type="date" value={expectedOn} onChange={e => setExpectedOn(e.target.value)} /></label></div>
      <ItemPicker items={hotel.items} lines={lines} onChange={setLines} disabled={cmd.busy} />
      <div className={s.actions}><button type="button" onClick={() => void cmd.run('template', () => download(identity, `/hotels/${hotelId}/imports/template`, '采购导入模板.xlsx'), undefined, '模板已下载')}>下载 Excel 模板</button>
        <label>导入采购清单（xlsx）<input type="file" accept=".xlsx" onChange={e => { const file = e.target.files?.[0]; e.target.value = ''; setPreview(undefined); if (file) { const form = new FormData(); form.append('file', file); void cmd.run(`preview:${file.name}:${file.lastModified}`, () => procurementRequest<ImportPreview>(identity, `/hotels/${hotelId}/imports/preview`, 'POST', form), setPreview, '已解析，请确认预览') } }} /></label></div>
      {preview && <div className={s.info}><strong>导入预览 · {preview.rows.length} 项</strong><ul>{preview.rows.map(r => <li key={r.row}>第 {r.row} 行：{r.error || `${r.name} ${number(r.quantity)} ${r.unit}（折合 ${number(r.baseQuantity)}）`}</li>)}</ul><button type="button" disabled={!preview.valid} onClick={() => { try { setLines(mergeImport(lines, preview.rows.map(r => ({ itemId: r.itemId!, quantity: r.quantity!, unit: r.unit! })))); setPreview(undefined) } catch (e) { cmd.setError(message(e)) } }}>确认添加到清单</button></div>}
      <label>采购备注<textarea maxLength={2000} value={note} onChange={e => setNote(e.target.value)} placeholder="用途、紧急原因或其他要求" /></label>
    </fieldset>
    <CommandFeedback {...cmd} /><div className={s.sticky}><div className={s.actions}><span className={s.muted}>保存不下单、不改变库存。采购后不可改数量强行结单。</span><button type="submit" className={s.primary} disabled={cmd.busy || !lines.length || !hotel.config.enabled}>{cmd.busy ? '保存中…' : '保存清单并进入确认'}</button></div></div>
  </form>
}
