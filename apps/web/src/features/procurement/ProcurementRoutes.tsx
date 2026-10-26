import { useState } from 'react'
import type { RoleContext, RouteParams } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { useScopedResource } from '../../shared/useScopedResource'
import { procurementRequest, useProcurementCommand, download, message } from './api'
import { date, deliveryLabels, eventLabels, money, number, statusLabels, type HotelContext, type Order, type OrderDetail, type ProcurementContext } from './model'
import { OrderEditor, CommandFeedback } from './OrderEditor'
import { ProcurementConfiguration } from './ProcurementConfiguration'
import { EvidenceList, EvidenceUpload, ReceiptEditor, ReceiptReview } from './ReceiptForms'
import s from './procurement.module.css'

const states = [['ALL', '全部采购'], ['DRAFT', '待审核提交'], ['SUBMITTED', '待采购'], ['ORDERED', '待收货'], ['REVIEW', '待复核入库'], ['DIFFERENCE', '数量差异'], ['COMPLETED', '已完成']]
const emptyContext: ProcurementContext = { hotels: [], accounts: [], canRequest: false, canConfigure: false }
export default function ProcurementRoutes({ identity, params, go }: { identity: RoleContext; permissions: string[]; params: RouteParams; go: AppNavigate }) {
  const context = useScopedResource(`${identity.key}:procurement-context`, signal => procurementRequest<ProcurementContext>(identity, '/context', 'GET', undefined, signal), emptyContext)
  const selected = useScopedResource<OrderDetail | undefined>(`${identity.key}:procurement-order:${params.orderId ?? ''}`, signal => params.orderId ? procurementRequest<OrderDetail>(identity, `/orders/${params.orderId}`, 'GET', undefined, signal) : Promise.resolve(undefined), undefined)
  const requestedHotel = selected.data?.hotel_id || params.hotelId
  const hotelId = requestedHotel ? context.data.hotels.find(h => h.id === requestedHotel)?.id : context.data.hotels[0]?.id
  const hotel = useScopedResource<HotelContext | undefined>(`${identity.key}:procurement-hotel:${hotelId ?? ''}`, signal => hotelId ? procurementRequest<HotelContext>(identity, `/hotels/${hotelId}`, 'GET', undefined, signal) : Promise.resolve(undefined), undefined)
  const state = states.some(([key]) => params.state === key) ? params.state : 'ALL'; const page = Math.max(0, Number.parseInt(params.page || '0', 10) || 0)
  const orders = useScopedResource<Order[]>(`${identity.key}:procurement-orders:${hotelId}:${state}:${page}`, signal => hotelId ? procurementRequest<Order[]>(identity, `/orders?hotel=${hotelId}&state=${state}&page=${page}`, 'GET', undefined, signal) : Promise.resolve([]), [])
  const [notice, setNotice] = useState(''); const [editing, setEditing] = useState(false)
  const order = selected.data; const route = (patch: RouteParams) => { setEditing(false); setNotice(''); go('procurement', { hotelId, ...patch }) }
  const updated = (data: OrderDetail, text: string) => { selected.setData(data); setNotice(text); void orders.reload(); void hotel.reload() }
  const saved = (data: OrderDetail, text: string) => { setEditing(false); setNotice(text); selected.setData(data); go('procurement', { hotelId, orderId: data.id }) }
  const error = context.error || selected.error || hotel.error || (!params.orderId && orders.error)
  const receipt = order?.receipts.find(r => r.id === params.receiptId)
  const receiptAllowed = order?.canReceive && ['ORDERED', 'PARTIAL'].includes(order.status) && (params.receiptId === 'new' || receipt && receipt.receiver_id === identity.actorId && ['DRAFT', 'RETURNED'].includes(receipt.status))
  const refresh = () => { setNotice(''); void Promise.all([context.reload(), hotel.reload(), orders.reload(), selected.reload()]) }
  return <section className={s.page}>
    <header className={s.heading}><div><span className="eyebrow">门店物资闭环</span><h1>采购与收货</h1><p>审核有据，分批收货；复核入库后才增加库存。</p></div><div className={s.actions}><button onClick={() => go('workbench')}>返回工作台</button><button onClick={refresh}>刷新</button></div></header>
    <div className={s.heading}><label>当前门店<select aria-label="采购门店" value={hotelId ?? ''} disabled={context.loading} onChange={e => { setEditing(false); setNotice(''); go('procurement', { hotelId: e.target.value }) }}><option value="" disabled>请选择门店</option>{context.data.hotels.map(h => <option key={h.id} value={h.id}>{h.name}</option>)}</select></label><div className={s.actions}>
      {context.data.canRequest && <button className={s.primary} disabled={!hotelId || !hotel.data?.config.enabled} onClick={() => route({ create: 'true' })}>＋ 有需求采购</button>}
      {context.data.canConfigure && <button disabled={!hotelId} onClick={() => route({ tab: 'config' })}>采购配置</button>}</div></div>
    <CommandFeedback error={error ? message(error) : undefined} notice={notice} />
    {context.loading || hotel.loading || params.orderId && selected.loading ? <p className={s.info} role="status">正在读取采购数据…</p> : null}
    {!context.loading && !context.error && !hotelId && <p className={s.warning}>{requestedHotel ? '该门店不在当前授权范围内，请选择可访问门店。' : '当前任职没有可访问门店，请管理员配置门店范围。'}</p>}
    {hotelId && hotel.data && !error && <>
      {!hotel.data.config.enabled && <p className={s.warning}>当前门店尚未启用采购。需配置责任人与预警接收人后启用，现有库存不会自动创建采购。</p>}
      {params.tab === 'config' && context.data.canConfigure ? <ProcurementConfiguration key={`${hotelId}:${hotel.data.config.version ?? -1}`} identity={identity} context={context.data} hotelId={hotelId} hotel={hotel.data} onUpdated={() => void hotel.reload()} />
        : params.create === 'true' && context.data.canRequest ? <OrderEditor key={`new:${hotelId}`} identity={identity} hotelId={hotelId} hotel={hotel.data} onSaved={saved} onBack={() => route({})} />
        : order && params.orderId ? <>
          {editing && order.canEdit ? <OrderEditor key={`${order.id}:${order.version}`} identity={identity} hotelId={hotelId} hotel={hotel.data} order={order} onSaved={saved} onBack={() => setEditing(false)} />
            : params.receiptId && receiptAllowed ? <ReceiptEditor key={`${order.id}:${params.receiptId}:${receipt?.version ?? -1}`} identity={identity} order={order} receipt={receipt} onSaved={(data, receiptId) => { updated(data, '收货数量已保存，请上传照片'); go('procurement', { hotelId, orderId: order.id, receiptId }) }} onUpdated={updated} onBack={() => go('procurement', { hotelId, orderId: order.id })} />
            : <OrderView key={order.id} identity={identity} order={order} onUpdated={updated} onEdit={() => setEditing(true)} onBack={() => route({})} onReceipt={receiptId => go('procurement', { hotelId, orderId: order.id, receiptId })} />}
        </> : <section className={s.card}><div className={s.tabs} role="navigation" aria-label="采购状态">{states.map(([key, label]) => <button className={state === key ? s.selected : ''} key={key} onClick={() => route({ state: key })}>{label}</button>)}</div>
          {orders.loading ? <p className={s.muted}>正在读取采购清单…</p> : !orders.data.length ? <p className={s.muted}>暂无符合条件的采购单。低库存采购将在开启规则和自动检查后生成，也可以主动发起需求。</p> : <div className={s.tableWrap} tabIndex={0} role="region" aria-label="采购列表"><table className={s.table}><thead><tr><th>采购单</th><th>来源</th><th>状态</th><th>待复核批次</th><th>更新时间</th><th>操作</th></tr></thead><tbody>{orders.data.slice(0, 50).map(o => <tr key={o.id}><td><strong>{o.title}</strong><small>{o.hotel_name} · {o.line_count} 种物品</small></td><td>{o.source === 'LOW_STOCK' ? '低库存补货' : '主动需求'}</td><td><span className={`${s.badge} ${o.status === 'COMPLETED' ? s.okBadge : o.difference_days && o.difference_days >= 30 ? s.warnBadge : ''}`}>{statusLabels[o.status]}</span>{o.difference_days != null && o.status !== 'COMPLETED' && <small>差异跟进第 {o.difference_days} 天</small>}</td><td>{o.pending_receipts || 0}</td><td>{date(o.updated_at)}</td><td><button onClick={() => route({ orderId: o.id })}>查看 / 办理</button></td></tr>)}</tbody></table></div>}
          <div className={s.actions}><button disabled={page === 0 || orders.loading} onClick={() => route({ state, page: String(page - 1) })}>上一页</button><small>第 {page + 1} 页 · 每页50单</small><button disabled={orders.data.length <= 50 || orders.loading} onClick={() => route({ state, page: String(page + 1) })}>下一页</button></div>
        </section>}
    </>}
  </section>
}
function OrderView({ identity, order, onUpdated, onEdit, onBack, onReceipt }: { identity: RoleContext; order: OrderDetail; onUpdated: (order: OrderDetail, text: string) => void; onEdit: () => void; onBack: () => void; onReceipt: (id: string) => void }) {
  const cmd = useProcurementCommand(); const days = order.first_difference_at ? Math.floor((Date.now() - Date.parse(order.first_difference_at)) / 86400000) : 0
  const pending = order.receipts.filter(r => r.status === 'SUBMITTED').length
  return <>
    <section className={s.card}><div className={s.heading}><div><h2>{order.title}</h2><p className={s.muted}>{order.source === 'LOW_STOCK' ? '低库存自动补货' : '主动采购需求'} · {order.expected_on ? `期望到货 ${order.expected_on}` : '尚未填写到货日期'}</p></div><span className={`${s.badge} ${order.status === 'COMPLETED' ? s.okBadge : s.warnBadge}`}>{statusLabels[order.status]}</span></div>
      <div className={s.actions}><button onClick={onBack}>返回采购列表</button><button disabled={cmd.busy} onClick={() => void cmd.run('export', () => download(identity, `/orders/${order.id}/export`, '采购对账及入库快照.xlsx'), undefined, '采购对账表已下载')}>导出采购对账</button>{order.canEdit && <button onClick={onEdit}>修改／增加采购物品</button>}{order.canReceive && ['ORDERED', 'PARTIAL'].includes(order.status) && <button className={s.primary} onClick={() => onReceipt('new')}>登记本次到货</button>}</div>
      {order.note && <p className={s.info}>{order.note}</p>}
      {order.pendingFinancialClassificationCount != null && <p className={order.pendingFinancialClassificationCount > 0 ? s.warning : s.info}>{order.pendingFinancialClassificationCount > 0 ? `本单 ${order.pendingFinancialClassificationCount} 项待财务归集。兼容单可继续实物采购及主管复核入库；财务确认、核算和过账须先补齐真实分类。` : '归集资料已齐，财务核算状态请到财务中心查看。'}{order.financial_dimensions_required === true && ' 本单已冻结财务前置校验规则，不随门店开关关闭而放宽。'}</p>}
      {order.first_difference_at && <p className={order.status === 'COMPLETED' ? s.success : s.warning}>{order.status === 'COMPLETED' ? '数量已全部对齐；历史差异记录保留。' : `采购数量与已复核入库量尚未对齐，流程不结束。首次确认：${date(order.first_difference_at)}；已跟进 ${days} 天。`}{order.status !== 'COMPLETED' && days >= 30 && ' 已达到30天预警条件，下方可查看四类接收人的通知状态。'}</p>}
      <div className={s.grid}><div className={s.metric}>采购物品<strong>{order.lines.length} 种</strong></div><div className={s.metric}>待复核收货<strong>{pending} 批</strong><small>待复核不计入库存</small></div><div className={s.metric}>采购金额<strong>{order.lines.every(l => l.unit_price != null) ? money(order.lines.reduce((sum, l) => sum + Number(l.input_quantity) * Number(l.unit_price), 0)) : '待采购确认'}</strong><small>采购不重复计入耗用成本</small></div></div>
      <div className={s.tableWrap} tabIndex={0} role="region" aria-label="采购数量对账"><table className={s.table}><thead><tr><th>物品</th><th>采购数量</th><th>采购单价</th><th>已复核入库</th><th>待复核</th><th>尚未入库</th></tr></thead><tbody>{order.lines.map(l => <tr key={l.id}><td>{l.item_name}<small>{l.specification || l.sku}</small></td><td>{number(l.input_quantity)} {l.input_unit}<small>折合 {number(l.quantity)} {l.base_unit}</small></td><td>{l.unit_price == null ? '待填写' : `${money(Number(l.unit_price))} / ${l.input_unit}`}</td><td>{number(l.approved_quantity)} {l.base_unit}</td><td>{number(l.pending_quantity)} {l.base_unit}</td><td>{number(Number(l.quantity) - Number(l.approved_quantity))} {l.base_unit}</td></tr>)}</tbody></table></div>
      <CommandFeedback {...cmd} />{order.canEdit && <div className={s.sticky}><div className={s.actions}><p className={s.muted}>确认清单后发送给本门店配置的采购经办人</p><button className={s.primary} disabled={cmd.busy} onClick={() => void cmd.run(`submit:${order.id}:${order.version}`, commandId => procurementRequest<OrderDetail>(identity, `/orders/${order.id}/submit`, 'POST', { commandId, expectedVersion: order.version }), result => onUpdated(result, '已提交采购并生成经办人通知'))}>{cmd.busy ? '提交中…' : '审核确认并提交采购'}</button></div></div>}
    </section>
    {order.canPurchase && order.status === 'SUBMITTED' && <PurchaseForm key={`${order.id}:${order.version}`} identity={identity} order={order} onUpdated={onUpdated} />}
    <div className={s.split}><div className={s.rowList}><section className={s.card}><h2>分批收货与入库复核</h2>{!order.receipts.length && <p className={s.muted}>暂无收货记录；采购办理后由门店登记实际到货。</p>}<div className={s.rowList}>{order.receipts.map(r => <ReceiptReview key={`${r.id}:${r.version}`} identity={identity} order={order} receipt={r} onUpdated={onUpdated} onEdit={() => onReceipt(r.id)} />)}</div></section>
      <section className={s.card}><h2>采购附件</h2>{order.canPurchase && ['SUBMITTED', 'ORDERED', 'PARTIAL'].includes(order.status) && <EvidenceUpload identity={identity} order={order} onUpdated={onUpdated} />}<EvidenceList identity={identity} orderId={order.id} files={order.attachments.filter(f => !f.receipt_id)} /></section></div>
      <aside className={s.rowList}><section className={s.card}><h2>消息与预警送达</h2><p className={s.muted}>差异满30天分别同步门店财务、财务总监、副总、总经理。未发送不显示为已送达。</p><ul className={s.timeline}>{order.notifications.map((n, i) => <li key={i}><strong>{n.recipient_name}</strong> · {n.title}<small>{deliveryLabels[n.delivery_status] || '发送状态待核查'} · {date(n.created_at)}</small></li>)}</ul>{!order.notifications.length && <p className={s.muted}>暂无通知</p>}</section>
        <section className={s.card}><h2>流程留痕</h2><ol className={s.timeline}>{order.events.map((e, i) => <li key={i}>{eventLabels[e.event_type] || '流程操作'}<small>{e.actor_name || '系统自动化'} · {date(e.created_at)}</small>{e.detail.note && <p>{e.detail.note}</p>}</li>)}</ol></section></aside>
    </div>
  </>
}
function PurchaseForm({ identity, order, onUpdated }: { identity: RoleContext; order: OrderDetail; onUpdated: (order: OrderDetail, text: string) => void }) {
  const [supplier, setSupplier] = useState(order.supplier || ''); const [expectedOn, setExpectedOn] = useState(order.expected_on || ''); const [prices, setPrices] = useState<Record<string, string>>(() => Object.fromEntries(order.lines.map(l => [l.id, l.unit_price == null ? '' : String(l.unit_price)])))
  const [note, setNote] = useState(''); const cmd = useProcurementCommand()
  return <form className={s.card} onSubmit={e => { e.preventDefault(); const body = { expectedVersion: order.version, supplier, expectedOn, buyerId: order.config.buyer_id, prices: order.lines.map(l => ({ lineId: l.id, unitPrice: Number(prices[l.id]) })) }; void cmd.run(JSON.stringify(body), commandId => procurementRequest<OrderDetail>(identity, `/orders/${order.id}/purchase`, 'POST', { ...body, commandId }), result => onUpdated(result, '采购已登记，已通知门店收货')) }}>
    <h2>采购经办 · 确认采购</h2><div className={s.fields}><label>供应商<input required maxLength={200} value={supplier} onChange={e => setSupplier(e.target.value)} /></label><label>预计到货日期<input required type="date" value={expectedOn} onChange={e => setExpectedOn(e.target.value)} /></label></div>
    <div className={s.fields}>{order.lines.map(l => <label key={l.id}>{l.item_name} · 单价（元 / {l.input_unit}）<input type="number" min="0" step="0.0001" required value={prices[l.id]} onChange={e => setPrices(p => ({ ...p, [l.id]: e.target.value }))} /></label>)}</div>
    <p className={s.muted}>采购数量按已确认清单办理；需要调整时先退回申请，不可在收货后修改采购数量强行结单。</p><label>退回原因（仅退回时必填）<textarea maxLength={2000} value={note} onChange={e => setNote(e.target.value)} /></label><CommandFeedback {...cmd} />
    <div className={s.actions}><button type="button" className={s.danger} disabled={cmd.busy} onClick={() => { if (!note.trim()) { cmd.setError('请填写退回原因'); return }; void cmd.run(`return:${order.version}:${note}`, commandId => procurementRequest<OrderDetail>(identity, `/orders/${order.id}/return`, 'POST', { commandId, expectedVersion: order.version, note }), result => onUpdated(result, '采购申请已退回，申请人可核对清单')) }}>退回申请人核对</button><button type="submit" className={s.primary} disabled={cmd.busy}>确认已采购，通知门店收货</button></div>
  </form>
}
