import type { RoleContext } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { useScopedResource } from '../../shared/useScopedResource'
import { procurementRequest } from './api'
import type { Order } from './model'

export function ProcurementTodo({ identity, go }: { identity: RoleContext; go: AppNavigate }) {
  const orders = useScopedResource(`${identity.key}:procurement-todos`, signal => procurementRequest<Order[]>(identity, '/todos', 'GET', undefined, signal), [], 60000)
  return <article className="wb-panel"><header><div><h2>采购与收货待办</h2><p>低库存审核、采购、收货与入库复核，按当前责任人显示</p></div><button className="text-action" onClick={() => go('procurement')}>进入采购与收货 ›</button></header>
    {orders.error ? <p className="inline-error" role="alert">采购待办读取失败<button onClick={() => void orders.reload()}>重试</button></p> : orders.loading ? <p className="wb-empty">正在读取采购待办…</p> : !orders.data.length ? <p className="wb-empty">暂无待办；可进入采购与收货发起需求或配置规则。</p> : orders.data.slice(0, 5).map(o => <button className="wb-compact-row" key={o.id} onClick={() => go('procurement', { orderId: o.id, hotelId: o.hotel_id })}><span><strong>{o.title}</strong><small>{o.hotel_name}</small></span><b>{o.todo_label} ›</b></button>)}
    {orders.data.length > 5 && <button className="text-action" onClick={() => go('procurement')}>查看更多采购待办 ›</button>}
  </article>
}
