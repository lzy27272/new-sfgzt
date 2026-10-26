import { useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { procurementRequest, useProcurementCommand } from './api'
import { filterCatalog, number, type CatalogItem, type HotelContext, type ProcurementContext } from './model'
import { CommandFeedback } from './OrderEditor'
import s from './procurement.module.css'

const roles = [['managerId', 'manager_id', '店长审核人'], ['buyerId', 'buyer_id', '采购经办人（人事行政主管）'], ['reviewerId', 'reviewer_id', '入库复核人（人事行政主管）'], ['financeId', 'finance_id', '门店财务负责人'], ['financeDirectorId', 'finance_director_id', '财务总监'], ['deputyId', 'deputy_id', '副总经理'], ['generalId', 'general_id', '总经理']] as const
export function ProcurementConfiguration({ identity, context, hotelId, hotel, onUpdated }: { identity: ApiIdentity; context: ProcurementContext; hotelId: string; hotel: HotelContext; onUpdated: () => void }) {
  const [actors, setActors] = useState<Record<string, string>>(() => Object.fromEntries(roles.map(([key, field]) => [key, hotel.config[field] ?? ''])))
  const [receivers, setReceivers] = useState<string[]>(hotel.config.receiver_ids ?? []); const [enabled, setEnabled] = useState(hotel.config.enabled ?? false)
  const [requireFinancialDimensions, setRequireFinancialDimensions] = useState(hotel.config.financial_dimensions_required ?? false)
  const [query, setQuery] = useState(''); const [page, setPage] = useState(0); const cmd = useProcurementCommand()
  const filtered = filterCatalog(hotel.items, query); const pages = Math.max(1, Math.ceil(filtered.length / 15)); const current = Math.min(page, pages - 1)
  return <><form className={s.card} onSubmit={e => { e.preventDefault(); const body = { expectedVersion: hotel.config.version ?? -1, enabled, ...actors, receiverIds: receivers, ...(hotel.canConfigureFinance && hotel.financialTransitionAvailable ? { requireFinancialDimensions } : {}) }; void cmd.run(JSON.stringify(body), () => procurementRequest(identity, `/hotels/${hotelId}/config`, 'PUT', body), onUpdated, '门店采购配置已保存') }}>
    <h2>门店采购责任人</h2><p className={s.info}>配置责任人不等于授予权限。请在岗位权限中配置采购读取／发起权限和本门店范围；绑定企微后才能收到企微推送。未绑定时保留站内通知并显示原因。</p>
    <div className={s.fields}>{roles.map(([key, , label]) => <label key={key}>{label}<select required value={actors[key]} onChange={e => setActors(a => ({ ...a, [key]: e.target.value }))}><option value="">请选择启用账号</option>{context.accounts.map(a => <option key={a.id} value={a.id}>{a.display_name}</option>)}</select></label>)}</div>
    <h3>门店收货人（店长也可收货）</h3><div className={s.catalog}>{context.accounts.map(a => <label className={s.check} key={a.id}><input type="checkbox" checked={receivers.includes(a.id)} onChange={e => setReceivers(v => e.target.checked ? [...v, a.id] : v.filter(id => id !== a.id))} />{a.display_name}</label>)}</div>
    <label className={s.check}><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} />启用本门店采购流程</label>
    <h3>采购财务切换</h3>
    <label className={s.check}><input type="checkbox" checked={requireFinancialDimensions} disabled={cmd.busy || !hotel.canConfigureFinance || !hotel.financialTransitionAvailable || (!requireFinancialDimensions && !hotel.financialCategories?.length)} onChange={e => setRequireFinancialDimensions(e.target.checked)} aria-describedby="procurement-finance-mode-help" />启用新版采购财务前置校验</label>
    <p id="procurement-finance-mode-help" className={s.muted}>关闭时，未归集清单可继续提交、采购及主管复核入库，并标记待财务归集。开启后，新提交单据须明确已发布财务类别和归集部门；已提交单据沿用冻结规则。财务确认、预算核对、核算和过账始终独立校验，不因关闭此项放开。</p>
    {!hotel.financialTransitionAvailable ? <p className={s.warning}>采购切换功能尚未安装，请联系管理员完成服务端升级。</p> : !hotel.canConfigureFinance ? <p className={s.info}>您可以维护采购责任人；切换财务校验还需财务配置权限。保存责任人不会改变此开关。</p> : !hotel.financialCategories?.length ? <p className={s.warning}>请先由财务独立发布本门店实际分类，再启用前置校验；未配置期间可保持兼容采购。</p> : null}
    <CommandFeedback {...cmd} /><div className={s.actions}><p className={s.muted}>差异首次确认后30天未闭环，通知四类管理人员；部分入库、退回不会重置计时。</p><button className={s.primary} disabled={cmd.busy}>保存责任人配置</button></div>
  </form><section className={s.card}><h2>低库存自动补货规则</h2><p className={s.muted}>库存 ≤ 物品最低库存时触发。建议数量 = 目标库存 − 当前库存 − 在途未入库数量；已存在采购的物品不会重复生成。自动检查须由部署环境开启。</p>
    <label>搜索物品<input type="search" value={query} onChange={e => { setQuery(e.target.value); setPage(0) }} placeholder="名称、编码、规格" /></label>
    <div className={s.rowList}>{filtered.slice(current * 15, current * 15 + 15).map(item => <ReplenishmentRule key={`${item.id}:${item.rule_version}`} identity={identity} hotelId={hotelId} item={item} onUpdated={onUpdated} />)}</div>
    <div className={s.actions}><button disabled={current === 0} onClick={() => setPage(current - 1)}>上一页</button><span>{current + 1} / {pages}</span><button disabled={current + 1 >= pages} onClick={() => setPage(current + 1)}>下一页</button></div>
  </section></>
}
function ReplenishmentRule({ identity, hotelId, item, onUpdated }: { identity: ApiIdentity; hotelId: string; item: CatalogItem; onUpdated: () => void }) {
  const [enabled, setEnabled] = useState(item.replenishment_enabled ?? false); const [target, setTarget] = useState(item.target_quantity ?? '')
  const cmd = useProcurementCommand()
  return <form className={s.receipt} onSubmit={e => { e.preventDefault(); const body = { enabled, targetQuantity: Number(target), expectedVersion: item.rule_version }; void cmd.run(JSON.stringify(body), () => procurementRequest(identity, `/hotels/${hotelId}/rules/${item.id}`, 'PUT', body), onUpdated, '补货规则已保存') }}>
    <div className={s.heading}><div><strong>{item.name}</strong><p className={s.muted}>当前 {number(item.current_quantity)} {item.unit} · 最低 {number(item.minimum_quantity)} · 在途 {number(item.outstanding_quantity)}</p></div><label className={s.check}><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} />自动补货</label></div>
    <div className={s.fields}><label>补足目标（{item.unit}，高于最低库存）<input type="number" required min={Number(item.minimum_quantity) + 0.0001} step="0.0001" value={target} onChange={e => setTarget(e.target.value)} /></label><div className={s.actions}><button disabled={cmd.busy}>保存补货规则</button></div></div><CommandFeedback {...cmd} />
  </form>
}
