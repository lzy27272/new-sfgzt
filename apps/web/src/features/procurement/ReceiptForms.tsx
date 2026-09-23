import { useEffect, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { procurementRequest, procurementBlob, download, useProcurementCommand } from './api'
import { date, number, remaining, receiptLabels, type Evidence, type OrderDetail, type Receipt } from './model'
import { CommandFeedback } from './OrderEditor'
import s from './procurement.module.css'

export function EvidenceList({ identity, orderId, files }: { identity: ApiIdentity; orderId: string; files: Evidence[] }) {
  const [preview, setPreview] = useState<{ url: string; name: string }>(); const cmd = useProcurementCommand()
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview.url) }, [preview])
  useEffect(() => { if (!preview) return; const close = (e: KeyboardEvent) => { if (e.key === 'Escape') setPreview(undefined) }; window.addEventListener('keydown', close); return () => window.removeEventListener('keydown', close) }, [preview])
  return <><div className={s.evidence}>{files.map(file => <button key={file.id} type="button" disabled={cmd.busy || Boolean(file.purged_at)} onClick={() => void cmd.run(file.id, async () => {
    const path = `/orders/${orderId}/attachments/${file.id}`
    if (file.media_type.startsWith('image/')) { const blob = await procurementBlob(identity, path); setPreview({ url: URL.createObjectURL(blob), name: file.original_name }) }
    else await download(identity, path, file.original_name)
  }, undefined, '')}>{file.media_type.startsWith('image/') ? '查看照片' : '下载附件'} · {file.original_name}{file.purged_at ? '（已清理）' : ''}</button>)}</div><CommandFeedback {...cmd} />
    {preview && <div className={s.previewBackdrop} role="dialog" aria-modal="true" aria-label="收货照片预览" onClick={() => setPreview(undefined)}><button autoFocus type="button" onClick={() => setPreview(undefined)}>关闭图片</button><img src={preview.url} alt={preview.name} onClick={e => e.stopPropagation()} /></div>}</>
}
export function EvidenceUpload({ identity, order, receiptId, onUpdated }: { identity: ApiIdentity; order: OrderDetail; receiptId?: string; onUpdated: (order: OrderDetail, notice: string) => void }) {
  const cmd = useProcurementCommand()
  const upload = (files: FileList | null) => {
    if (!files?.length) return
    const selected = Array.from(files)
    void cmd.run(`files:${Date.now()}`, async () => {
      // Sequential uploads bound mobile bandwidth and keep each saved file independently recoverable.
      for (const file of selected) { const body = new FormData(); body.append('file', file); await procurementRequest(identity, `/orders/${order.id}/attachments${receiptId ? `?receipt=${receiptId}` : ''}`, 'POST', body) }
      return procurementRequest<OrderDetail>(identity, `/orders/${order.id}`)
    }, result => onUpdated(result, '附件已保存；收货仍需提交复核'), '附件上传成功')
  }
  return <div className={s.picker}><p className={s.muted}>{receiptId ? '至少上传一张真实收货照片。保存数量后可拍照或追加附件，再提交复核。' : '可上传采购凭证、订单截图或供应商清单。'} 每份文件不超过20MB。</p>
    <div className={s.fields}><label>拍照<input aria-label="拍摄收货照片" type="file" accept="image/jpeg,image/png" capture="environment" disabled={cmd.busy} onChange={e => { upload(e.target.files); e.target.value = '' }} /></label><label>选择照片或文档<input aria-label="上传采购附件" type="file" multiple accept=".jpg,.jpeg,.png,.pdf,.docx,.xlsx" disabled={cmd.busy} onChange={e => { upload(e.target.files); e.target.value = '' }} /></label></div>
    <CommandFeedback {...cmd} />{cmd.busy && <p role="status">附件上传中，请稍候…</p>}
  </div>
}
export function ReceiptEditor({ identity, order, receipt, onSaved, onUpdated, onBack }: { identity: ApiIdentity; order: OrderDetail; receipt?: Receipt; onSaved: (order: OrderDetail, receiptId: string) => void; onUpdated: (order: OrderDetail, notice: string) => void; onBack: () => void }) {
  const [id] = useState(receipt?.id ?? crypto.randomUUID()); const [note, setNote] = useState(receipt?.note ?? '')
  const [values, setValues] = useState<Record<string, { arrived: string; accepted: string; rejected: string }>>(() => Object.fromEntries(order.lines.map(l => { const r = receipt?.lines.find(x => x.line_id === l.id); return [l.id, { arrived: r ? String(r.arrived) : '', accepted: r ? String(r.accepted) : '', rejected: r ? String(r.rejected) : '0' }] })))
  const [dirty, setDirty] = useState(false); const cmd = useProcurementCommand()
  const files = order.attachments.filter(f => f.receipt_id === id)
  const save = () => {
    const lines = order.lines.filter(l => Number(values[l.id].arrived) > 0).map(l => ({ lineId: l.id, arrived: Number(values[l.id].arrived), accepted: Number(values[l.id].accepted), rejected: Number(values[l.id].rejected) }))
    if (!lines.length) { cmd.setError('至少填写一项实际到货数量'); return }
    if (lines.some(l => Math.abs(l.arrived - l.accepted - l.rejected) > 0.000001)) { cmd.setError('每项到货数量必须等于合格数量加拒收数量'); return }
    const body = { expectedVersion: receipt?.version ?? -1, note, lines }
    void cmd.run(JSON.stringify(body), commandId => procurementRequest<OrderDetail>(identity, `/orders/${order.id}/receipts/${id}`, 'PUT', { ...body, commandId }), result => { setDirty(false); onSaved(result, id) }, '收货数量已保存，库存未增加')
  }
  return <section className={s.card}><div className={s.heading}><h2>门店确认收货</h2><button onClick={onBack}>返回采购详情</button></div><p className={s.warning}>按实际到货填写，单位为后台库存基础单位。只有上级复核通过才入库，保存或提交收货不会增加库存。</p>
    <form onSubmit={e => { e.preventDefault(); save() }}><div className={s.rowList}>{order.lines.map(line => {
      const value = values[line.id]; const update = (field: keyof typeof value, text: string) => { setValues(v => ({ ...v, [line.id]: { ...v[line.id], [field]: text } })); setDirty(true) }
      return <article key={line.id} className={s.receipt}><h3>{line.item_name} <small>（{line.base_unit}）</small></h3><p className={s.muted}>采购 {number(line.quantity)} · 累计入库 {number(line.approved_quantity)} · 其他批次待复核 {number(line.pending_quantity)} · 尚可收货 {number(remaining(line))}</p>
        <div className={s.grid}>{([['arrived', '本批实际到货'], ['accepted', '本批合格'], ['rejected', '本批拒收']] as const).map(([key, label]) => <label key={key}>{label}（{line.base_unit}）<input type="number" min="0" step="0.0001" aria-label={`${line.item_name}${label}`} value={value[key]} disabled={cmd.busy} onChange={e => update(key, e.target.value)} /></label>)}</div>
      </article>
    })}</div><label>差异／拒收说明<textarea maxLength={2000} value={note} disabled={cmd.busy} onChange={e => { setNote(e.target.value); setDirty(true) }} placeholder="缺货、破损、多到等情况请说明" /></label>
      <div className={s.actions}><p className={s.muted}>未到货物品可留空，后续分批登记。</p><button disabled={cmd.busy} type="submit">保存收货数量</button></div></form>
    {receipt && <><EvidenceUpload identity={identity} order={order} receiptId={id} onUpdated={onUpdated} /><EvidenceList identity={identity} orderId={order.id} files={files} /></>}
    <CommandFeedback {...cmd} /><div className={s.sticky}><div className={s.actions}><span className={s.muted}>{dirty ? '数量或说明有改动，请先保存' : receipt ? `已保存照片 ${files.filter(f => f.media_type.startsWith('image/') && !f.purged_at).length} 张` : '先保存数量，再上传照片'}</span>
      <button className={s.primary} disabled={cmd.busy || dirty || !receipt || !files.some(f => f.media_type.startsWith('image/') && !f.purged_at)} onClick={() => { const body = { expectedVersion: receipt?.version }; void cmd.run(`submit:${id}:${receipt?.version}`, commandId => procurementRequest<OrderDetail>(identity, `/orders/${order.id}/receipts/${id}/submit`, 'POST', { ...body, commandId }), result => { onUpdated(result, '收货已提交，等待复核；尚未入库'); onBack() }, '已提交复核') }}>{cmd.busy ? '处理中…' : '提交收货复核（不入库）'}</button></div></div>
  </section>
}
export function ReceiptReview({ identity, order, receipt, onUpdated, onEdit }: { identity: ApiIdentity; order: OrderDetail; receipt: Receipt; onUpdated: (order: OrderDetail, notice: string) => void; onEdit: () => void }) {
  const [note, setNote] = useState(''); const cmd = useProcurementCommand()
  const review = (approved: boolean) => {
    if (!approved && !note.trim()) { cmd.setError('退回必须填写原因'); return }
    const body = { expectedVersion: receipt.version, approved, note }
    void cmd.run(JSON.stringify({ id: receipt.id, ...body }), commandId => procurementRequest<OrderDetail>(identity, `/orders/${order.id}/receipts/${receipt.id}/review`, 'POST', { ...body, commandId }), result => onUpdated(result, approved ? result.status === 'COMPLETED' ? '全部采购数量已复核入库，流程完成' : '本批复核通过并入库；数量未全部对齐，采购保持待跟进' : '已退回原收货人核对，库存未变化'))
  }
  return <article className={s.receipt}><div className={s.heading}><h3>{receipt.receiver_name} · 收货记录</h3><span className={`${s.badge} ${receipt.status === 'APPROVED' ? s.okBadge : s.warnBadge}`}>{receiptLabels[receipt.status]}</span></div><p className={s.muted}>提交 {date(receipt.submitted_at)} · 复核 {date(receipt.reviewed_at)}</p>
    <div className={s.tableWrap} tabIndex={0} role="region" aria-label="本批收货与库存快照"><table className={s.table}><thead><tr><th>物品 / 单位</th><th>到货</th><th>合格</th><th>拒收</th><th>入库前</th><th>入库后</th></tr></thead><tbody>{receipt.lines.map(l => {
      const item = order.lines.find(i => i.id === l.line_id); const approved = receipt.status === 'APPROVED' || receipt.status === 'VOIDED'
      return <tr key={l.line_id}><td>{l.item_name}<small>{l.base_unit}</small></td><td>{number(l.arrived)}</td><td>{number(l.accepted)}</td><td>{number(l.rejected)}</td><td>{approved && l.quantity_before != null ? number(l.quantity_before) : number(item?.current_quantity)}{!approved && <small>当前参考</small>}</td><td>{approved && l.quantity_after != null ? number(l.quantity_after) : approved ? '未入库' : number(Number(item?.current_quantity ?? 0) + Number(l.accepted))}{!approved && <small>预计，以复核时实际库存为准</small>}</td></tr>
    })}</tbody></table></div><p>{receipt.note || '无收货备注'}</p>{receipt.review_note && <p className={s.info}>复核意见：{receipt.review_note}</p>}
    <EvidenceList identity={identity} orderId={order.id} files={order.attachments.filter(f => f.receipt_id === receipt.id)} />
    {order.canReceive && receipt.receiver_id === identity.actorId && ['DRAFT', 'RETURNED'].includes(receipt.status) && <button onClick={onEdit}>继续核对并提交收货</button>}
    {order.canReceive && receipt.receiver_id === identity.actorId && receipt.status === 'DRAFT' && !receipt.submitted_at && <><label>撤销未提交草稿的原因<textarea maxLength={2000} value={note} onChange={e => setNote(e.target.value)} placeholder="仅用于误建草稿；数量、照片和操作记录仍保留" /></label><button className={s.danger} disabled={cmd.busy || !note.trim()} onClick={() => {
      const body = { expectedVersion: receipt.version, note }
      void cmd.run(`void:${receipt.id}:${JSON.stringify(body)}`, commandId => procurementRequest<OrderDetail>(identity, `/orders/${order.id}/receipts/${receipt.id}/void`, 'POST', { ...body, commandId }), result => onUpdated(result, result.status === 'COMPLETED' ? '草稿已撤销并留痕；全部采购数量已复核入库，流程完成' : '草稿已撤销并留痕，库存未变化'))
    }}>确认撤销未提交草稿</button><CommandFeedback {...cmd} /></>}
    {order.canReview && receipt.status === 'SUBMITTED' && <><label>复核意见（退回必填）<textarea value={note} maxLength={2000} onChange={e => setNote(e.target.value)} /></label><CommandFeedback {...cmd} /><div className={s.actions}><button className={s.danger} disabled={cmd.busy} onClick={() => review(false)}>退回门店核对</button><button className={s.primary} disabled={cmd.busy} onClick={() => review(true)}>复核通过并入库</button></div></>}
  </article>
}
