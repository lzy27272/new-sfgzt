import { useCallback, useRef, useState } from 'react'
import type { RoleContext } from '../../domain'
import { createIdempotencyKey } from '../../api/client'
import { AsyncState } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { formatLocalDateTime } from '../shared/FeatureUI'
import { correctLaundry, dispatchLaundry, loadLaundryCatalog, loadLaundryOrder, loadLaundryOrders, receiveLaundry, saveLaundryRate } from './laundryApi'
import { dispatchValidation, laundryDecimal, laundryFeePreview, laundryStatus, localLaundryDate, receiptStatus, receiveValidation } from './laundry'
import type { LaundryDetail, LaundryDispatch, LaundryDispatchLine, LaundryItem, LaundryPage, LaundryReceive, LaundryReceiveLine } from './laundry'
import styles from './storeResources.module.css'

const money = (value: number) => `¥${new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(value)}`
type Command = (fingerprint: unknown, run: (key: string) => Promise<unknown>, message: string) => Promise<boolean>

export function LaundryPanel({ identity, hotelId, canConfigure, canManage, onStockChanged }: {
  identity: RoleContext; hotelId: string; canConfigure: boolean; canManage: boolean; onStockChanged: () => Promise<void>
}) {
  const [filter, setFilter] = useState('ALL'), [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string>(), [showDispatch, setShowDispatch] = useState(false)
  const [busy, setBusy] = useState(false), [message, setMessage] = useState<string>(), [error, setError] = useState<string>()
  const inFlight = useRef(false), retry = useRef<{ fingerprint: string; key: string } | undefined>(undefined)
  const focusDetail = useCallback((node: HTMLElement | null) => { node?.scrollIntoView({ block: 'start', behavior: 'smooth' }) }, [])
  const catalog = useScopedResource(`${identity.key}:${hotelId}:laundry-catalog`, (signal) => loadLaundryCatalog(identity, hotelId, signal), [])
  const orders = useScopedResource<LaundryPage>(`${identity.key}:${hotelId}:laundry:${page}:${filter}`, (signal) => loadLaundryOrders(identity, hotelId, page, filter, signal), { items: [], page: 0, hasMore: false })
  const detail = useScopedResource<LaundryDetail | undefined>(`${identity.key}:${hotelId}:laundry:${selectedId}`, (signal) => selectedId ? loadLaundryOrder(identity, hotelId, selectedId, signal) : Promise.resolve(undefined), undefined)
  const refresh = async () => { await Promise.all([catalog.reload(), orders.reload(), detail.reload(), onStockChanged()]) }
  const command: Command = async (value, run, notice) => {
    if (inFlight.current) return false
    inFlight.current = true; setBusy(true); setMessage(undefined); setError(undefined)
    const fingerprint = JSON.stringify(value)
    // Keep the key on network errors; edited requests receive a new key. Never send two mutations on double-click.
    if (retry.current?.fingerprint !== fingerprint) retry.current = { fingerprint, key: createIdempotencyKey('laundry') }
    try {
      await run(retry.current.key); retry.current = undefined; setMessage(notice)
      try { await refresh() } catch { setError('操作已成功，但刷新失败。请点击刷新核对，不要重复登记。') }
      return true
    } catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败，请核对后重试。'); return false }
    finally { inFlight.current = false; setBusy(false) }
  }
  return <section className={`${styles.panel} ${styles.laundryPanel}`}>
    <header><div><h2>布草洗涤台账</h2><p>送洗 → 分批回收验收 → 洗涤费用；与布草使用量和采购成本分开记账。</p></div><div className={styles.actions}><button type="button" className="secondary" disabled={busy} onClick={() => void refresh()}>刷新洗涤台账</button>{canManage && <button type="button" className="primary" disabled={busy || catalog.loading || !!catalog.error} onClick={() => { setShowDispatch(!showDispatch); setSelectedId(undefined) }}>{showDispatch ? '收起送洗登记' : '登记送洗'}</button>}</div></header>
    <p className={styles.unitNote}>合格回收才计费；返洗继续保留在洗数量，不重复收费。报损需明确确认并记库存损失。单价调整只影响之后新建的送洗单。</p>
    <div className={styles.laundryNotice}>{message && <div role="status" className={styles.success}>{message}</div>}{error && <div role="alert" className={styles.error}>{error}</div>}</div>
    <AsyncState loading={catalog.loading} error={catalog.error} onRetry={catalog.reload} />
    <details className={styles.laundryRates}><summary>布草单价与在洗数量（{catalog.data.length}种）</summary>{!catalog.loading && !catalog.error && !catalog.data.length && <p>请先在“物品配置”添加布草，并录入期初库存；洗涤单价与采购单价分别配置。</p>}<div className={styles.resourceList}>{catalog.data.map((item) => <LaundryRateForm key={`${item.itemId}:${item.rateVersion}:${item.itemVersion}`} item={item} busy={busy} canConfigure={canConfigure} onSave={async (price) => { await command(['rate', item.itemId, item.rateVersion, price], () => saveLaundryRate(identity, hotelId, item, price), '洗涤单价已保存，历史洗涤单金额不变。') }} />)}</div></details>
    {showDispatch && canManage && <LaundryDispatchForm catalog={catalog.data} busy={busy || catalog.loading || !!catalog.error} onSubmit={async (input) => {
      let result: LaundryDetail | undefined
      if (await command(['dispatch', input], async (key) => { result = await dispatchLaundry(identity, hotelId, input, key) }, '送洗已登记，总库存未扣减。')) { setShowDispatch(false); setSelectedId(result?.id) }
    }} />}
    <div className={styles.laundryToolbar}><label>洗涤状态<select aria-label="洗涤状态" value={filter} disabled={busy} onChange={(event) => { setFilter(event.target.value); setPage(0) }}><option value="ALL">全部记录</option><option value="OPEN">尚有在洗</option><option value="COMPLETED">已全部核对</option><option value="CANCELLED">已取消</option></select></label><span>第 {page + 1} 页 · 每页最多25单</span><div className={styles.actions}><button type="button" className="secondary" disabled={!page || busy || orders.loading} onClick={() => setPage(page - 1)}>上一页</button><button type="button" className="secondary" disabled={!orders.data.hasMore || busy || orders.loading} onClick={() => setPage(page + 1)}>下一页</button></div></div>
    <AsyncState loading={orders.loading} error={orders.error} empty={!orders.data.items.length} onRetry={orders.reload} emptyTitle="暂无该状态的洗涤单" />
    <div className={styles.resourceList}>{orders.data.items.map((order) => <article key={order.id}><h3>{order.supplier} · {order.dispatchedOn}</h3><strong>{laundryStatus(order.status)}</strong><p>{order.lineCount}种布草 · 有效洗涤费 {money(order.fee)}</p><small>登记人：{order.operatorName}</small><button type="button" className="secondary" disabled={busy} onClick={() => { setSelectedId(order.id); setShowDispatch(false) }}>查看明细 / 回收验收</button></article>)}</div>
    {selectedId && <section key={selectedId} ref={focusDetail} className={styles.laundryDetail}><header><h3>送洗与回收明细</h3><button type="button" className="secondary" disabled={busy} onClick={() => setSelectedId(undefined)}>收起明细</button></header><AsyncState loading={detail.loading} error={detail.error} onRetry={detail.reload} />{detail.data && detail.data.id === selectedId && <LaundryOrderDetail key={`${detail.data.id}:${detail.data.rowVersion}`} order={detail.data} canManage={canManage && !detail.loading && !detail.error} busy={busy} onReceive={(input) => command(['receive', detail.data!.id, input], (key) => receiveLaundry(identity, hotelId, detail.data!.id, input, key), '回收验收已记账；仅合格数量计洗涤费，确认报损已另记库存损失。')} onCorrect={(receiptId, reason) => command(['correct', detail.data!.id, receiptId, reason, detail.data!.rowVersion], () => correctLaundry(identity, hotelId, detail.data!.id, receiptId, reason, detail.data!.rowVersion), receiptId ? '本次回收已冲销，原记录保留；费用与报损原成本已回退。' : '送洗单已取消，在洗占用已释放，历史记录保留。')} />}</section>}
  </section>
}

export function LaundryRateForm({ item, busy, canConfigure, onSave }: { item: LaundryItem; busy: boolean; canConfigure: boolean; onSave: (price: string) => Promise<void> }) {
  const [price, setPrice] = useState(item.unitPrice === null ? '' : String(item.unitPrice))
  return <article><h3>{item.itemName}</h3><p>总库存 {item.ownedQuantity} {item.baseUnit} · 尚在洗 {item.pendingQuantity} {item.baseUnit} · 可送洗 {item.availableQuantity} {item.baseUnit}</p>{canConfigure ? <form className={styles.form} onSubmit={(event) => { event.preventDefault(); if (!busy && laundryDecimal(price) !== undefined) void onSave(price) }}><label>洗涤单价（元/{item.baseUnit}）<input required type="number" min="0" step="0.0001" value={price} disabled={busy} onChange={(event) => setPrice(event.target.value)} /></label><button type="submit" className="secondary" disabled={busy || laundryDecimal(price) === undefined}>保存洗涤单价</button></form> : <p>洗涤单价：{item.unitPrice === null ? '尚未配置' : `${money(item.unitPrice)}/${item.baseUnit}`}</p>}</article>
}

export function LaundryDispatchForm({ catalog, busy, onSubmit }: { catalog: LaundryItem[]; busy: boolean; onSubmit: (input: LaundryDispatch) => Promise<void> }) {
  const [supplier, setSupplier] = useState(''), [date, setDate] = useState(localLaundryDate), [note, setNote] = useState('')
  const [lines, setLines] = useState<Array<LaundryDispatchLine & { key: number }>>([]), next = useRef(0)
  const input: LaundryDispatch = { supplier, dispatchedOn: date, note, lines: lines.map(({ key: _key, ...line }) => line) }
  const error = dispatchValidation(input, catalog)
  const update = (index: number, patch: Partial<LaundryDispatchLine>) => setLines(lines.map((line, i) => i === index ? { ...line, ...patch } : line))
  return <form className={styles.form} onSubmit={(event) => { event.preventDefault(); if (!busy && !error) void onSubmit(input) }}><h3>登记送洗</h3><fieldset disabled={busy}><div className={styles.formGrid}><label>洗涤供应商<input required maxLength={160} value={supplier} onChange={(event) => setSupplier(event.target.value)} /></label><label>实际送洗日期<input required type="date" max={localLaundryDate()} value={date} onChange={(event) => setDate(event.target.value)} /></label></div>
    {lines.map((line, index) => { const item = catalog.find((candidate) => candidate.itemId === line.itemId); return <div className={styles.laundryEntry} key={line.key}><label>布草<select required value={line.itemId} onChange={(event) => { const chosen = catalog.find((candidate) => candidate.itemId === event.target.value); update(index, { itemId: event.target.value, expectedRateVersion: chosen?.rateVersion ?? -1, expectedItemVersion: chosen?.itemVersion ?? -1 }) }}><option value="">请选择已配置单价的布草</option>{catalog.filter((candidate) => candidate.unitPrice !== null && (candidate.itemId === line.itemId || !lines.some((row) => row.itemId === candidate.itemId))).map((candidate) => <option key={candidate.itemId} value={candidate.itemId}>{candidate.itemName} · {candidate.baseUnit}</option>)}</select></label><label>送洗数量（{item?.baseUnit ?? '基础单位'}）<input required type="number" min="0.0001" step="0.0001" value={line.quantity} onChange={(event) => update(index, { quantity: event.target.value })} /></label><button type="button" className="secondary" onClick={() => setLines(lines.filter((_, i) => i !== index))}>移除</button>{item && <small>可送洗 {item.availableQuantity} {item.baseUnit} · 洗涤单价 {money(item.unitPrice ?? 0)}/{item.baseUnit}；本次只登记在洗数量，不计费。</small>}</div> })}
    <button type="button" className="secondary" disabled={lines.length >= 100 || !catalog.some((item) => item.unitPrice !== null && !lines.some((line) => line.itemId === item.itemId))} onClick={() => setLines([...lines, { key: next.current++, itemId: '', quantity: '', expectedRateVersion: -1, expectedItemVersion: -1 }])}>添加送洗布草</button><label>备注<textarea maxLength={1000} value={note} onChange={(event) => setNote(event.target.value)} /></label></fieldset>{error && <p>{error}</p>}<button type="submit" className="primary" disabled={busy || !!error}>{busy ? '提交中…' : '确认送洗并冻结单价'}</button></form>
}

export function LaundryReceiveForm({ order, busy, onSubmit }: { order: LaundryDetail; busy: boolean; onSubmit: (input: LaundryReceive) => Promise<boolean> }) {
  const [date, setDate] = useState(localLaundryDate), [note, setNote] = useState(''), [confirmWriteOff, setConfirmWriteOff] = useState(false)
  const [lines, setLines] = useState<LaundryReceiveLine[]>(order.lines.filter((line) => line.pendingQuantity > 0).map((line) => ({ lineId: line.id, acceptedQuantity: '0', rewashQuantity: '0', writtenOffQuantity: '0', differenceReason: '' })))
  const input: LaundryReceive = { receivedOn: date, note, confirmWriteOff, expectedVersion: order.rowVersion, lines: lines.filter((line) => [line.acceptedQuantity, line.rewashQuantity, line.writtenOffQuantity].some((value) => laundryDecimal(value) !== 0n)) }
  const error = receiveValidation(input, order)
  const hasLoss = lines.some((line) => Number(line.writtenOffQuantity) > 0)
  const update = (index: number, patch: Partial<LaundryReceiveLine>) => setLines(lines.map((line, i) => i === index ? { ...line, ...patch } : line))
  return <form className={styles.form} onSubmit={(event) => { event.preventDefault(); if (!busy && !error) void onSubmit(input) }}><h3>本次分批回收 / 差异确认</h3><fieldset disabled={busy}><label>实际回收日期<input required type="date" min={order.dispatchedOn} max={localLaundryDate()} value={date} onChange={(event) => setDate(event.target.value)} /></label>
    {lines.map((line, index) => { const original = order.lines.find((row) => row.id === line.lineId)!; return <div className={styles.laundryReceiptEntry} key={line.lineId}><h4>{original.itemName} · 尚在洗 {original.pendingQuantity} {original.baseUnit}</h4><div className={styles.formGrid}>{([['acceptedQuantity', '合格回收'], ['rewashQuantity', '不合格返洗（仍在洗）'], ['writtenOffQuantity', '确认丢失 / 损坏报损']] as const).map(([field, label]) => <label key={field}>{label}（{original.baseUnit}）<input required type="number" min="0" step="0.0001" value={line[field]} onChange={(event) => update(index, { [field]: event.target.value })} /></label>)}<label>差异原因<input maxLength={1000} value={line.differenceReason} onChange={(event) => update(index, { differenceReason: event.target.value })} /></label></div><p>冻结洗涤单价 {money(original.unitPrice)}/{original.baseUnit} · 本次洗涤费预估 ¥{laundryFeePreview(line.acceptedQuantity, original.unitPrice)}</p></div> })}
    <label>本次回收备注<textarea maxLength={1000} value={note} onChange={(event) => setNote(event.target.value)} /></label>{hasLoss && <label className={styles.checkbox}><input type="checkbox" checked={confirmWriteOff} onChange={(event) => setConfirmWriteOff(event.target.checked)} />我已核实报损数量及原因，确认扣减总库存并登记库存损失（不计为洗涤费）。</label>}</fieldset><p>返洗数量继续保留在洗；再次验收合格时才计费。实际费用以服务端最终记账为准。</p>{error && <p role="status">{error}</p>}<button type="submit" className="primary" disabled={busy || !!error}>{busy ? '记账中…' : '确认验收并记账'}</button></form>
}

export function LaundryOrderDetail({ order, busy, canManage, onReceive, onCorrect }: {
  order: LaundryDetail; busy: boolean; canManage: boolean; onReceive: (input: LaundryReceive) => Promise<boolean>; onCorrect: (receiptId: string | undefined, reason: string) => Promise<boolean>
}) {
  const [correction, setCorrection] = useState<{ receiptId?: string }>(), [reason, setReason] = useState('')
  const activeReceipts = order.receipts.filter((receipt) => receipt.status === 'POSTED')
  return <><h3>{order.supplier} · {order.dispatchedOn}</h3><p>{laundryStatus(order.status)} · 有效洗涤费 {money(order.fee)} · 登记人 {order.operatorName}</p>{order.note && <p>送洗备注：{order.note}</p>}{order.cancellationReason && <p>取消原因：{order.cancellationReason}</p>}
    <div className={styles.resourceList}>{order.lines.map((line) => <article key={line.id}><h3>{line.itemName} · {line.baseUnit}</h3><p>送洗 {line.quantity} · 合格回收 {line.acceptedQuantity} · 确认报损 {line.writtenOffQuantity} · 尚在洗 {order.status === 'CANCELLED' ? 0 : line.pendingQuantity}</p><p>冻结单价 {money(line.unitPrice)}/{line.baseUnit} · 有效洗涤费 {money(line.fee)}</p></article>)}</div>
    {canManage && ['SENT', 'PARTIAL'].includes(order.status) && <LaundryReceiveForm order={order} busy={busy} onSubmit={onReceive} />}
    <h3>回收验收历史</h3>{!order.receipts.length && <p>尚无回收记录。</p>}<div className={styles.laundryReceipts}>{order.receipts.map((receipt) => <article key={receipt.id}><header><strong>{receipt.receivedOn} · {receiptStatus(receipt.status)}</strong>{canManage && receipt.status === 'POSTED' && <button type="button" className="secondary" disabled={busy} onClick={() => { setCorrection({ receiptId: receipt.id }); setReason('') }}>冲销本次回收</button>}</header><small>操作人：{receipt.operatorName} · {formatLocalDateTime(receipt.createdAt)}</small>{receipt.lines.map((line) => <p key={line.orderLineId}>{line.itemName}：合格 {line.acceptedQuantity} {line.baseUnit}，返洗 {line.rewashQuantity} {line.baseUnit}，报损 {line.writtenOffQuantity} {line.baseUnit}；原洗涤费 {money(line.fee)}{line.differenceReason && `；差异原因：${line.differenceReason}`}</p>)}{receipt.note && <p>备注：{receipt.note}</p>}{receipt.reversalReason && <p>冲销：{receipt.reversedByName} · {formatLocalDateTime(receipt.reversedAt)} · {receipt.reversalReason}</p>}</article>)}</div>
    {canManage && order.status !== 'CANCELLED' && !activeReceipts.length && <button type="button" className="secondary" disabled={busy} onClick={() => { setCorrection({}); setReason('') }}>取消送洗单</button>}
    {correction && <form className={`${styles.form} ${styles.confirmation}`} onSubmit={(event) => { event.preventDefault(); if (!busy && reason.trim()) void onCorrect(correction.receiptId, reason.trim()) }}><h3>{correction.receiptId ? '确认冲销本次回收' : '确认取消送洗单'}</h3><p>{correction.receiptId ? '本次费用回退；如有报损则恢复原数量和原成本。合格回收数量将重新列为在洗，原记录不会删除；如已再次送洗或出库导致数量不足，冲销会被拒绝。' : '仅适用于没有有效回收记录的送洗单。释放在洗占用，历史单据仍保留。'}</p><label>操作原因<textarea required maxLength={1000} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} /></label><div className={styles.actions}><button type="button" className="secondary" disabled={busy} onClick={() => setCorrection(undefined)}>返回</button><button type="submit" className="primary" disabled={busy || !reason.trim()}>确认并留痕</button></div></form>}
  </>
}
