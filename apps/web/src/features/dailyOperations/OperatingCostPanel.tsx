import { useState } from 'react'
import type { RoleContext } from '../../domain'
import { AsyncState } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { featureApiRequest } from '../shared/featureApi'
import { loadStoreResourceHotels } from '../storeResources/api'
import { costKinds, costMoney } from '../storeResources/costs'
import type { RoomNightWindow } from '../storeResources/roomNightCosts'
import { allRoomUseBasis } from '../storeResources/roomNightCosts'
import { RoomNightEvidenceView } from '../storeResources/RoomNightCostPanel'
import styles from '../storeResources/storeResources.module.css'

export type OperatingWindow = { from: string; to: string; amount: string | null; perRoomUse: string | null; state: string; reason: string; detail: RoomNightWindow }
export type OperatingSummary = { hotelId: string; today: string; yesterdayDate: string; latestClosedDay: string; generatedAt: string; roomUseBasis: string; dateBasis: string; yesterday: OperatingWindow; monthToYesterday: OperatingWindow; selected: OperatingWindow; notices: string[] }
type Selection = { type: 'DAY' | 'MONTH' | 'CUSTOM'; period: string; from: string; to: string }

export function operatingCostQuery(selection: Selection) {
  const p = new URLSearchParams({ periodType: selection.type })
  if (selection.type === 'CUSTOM') { p.set('from', selection.from); p.set('to', selection.to) }
  else if (selection.period) p.set('period', selection.period)
  return p.toString()
}

export function OperatingCostPanel({ identity, initialHotelId }: { identity: RoleContext; initialHotelId?: string }) {
  const hotels = useScopedResource(`${identity.key}:operating-cost-hotels`, (signal) => loadStoreResourceHotels(identity, signal), [])
  const [chosen, setChosen] = useState<string>()
  const hotelId = chosen ?? (hotels.data.some((hotel) => hotel.id === initialHotelId) ? initialHotelId : hotels.data[0]?.id)
  return <section className={`${styles.panel} ${styles.costPanel}`} aria-label="经营成本汇总"><header><div><h2>经营成本汇总</h2><p>耗品、清洁用品、布草洗涤三类已入账成本。数量更正后按原营业日更新，不覆盖已关账财务月结。</p></div></header>
    <AsyncState loading={hotels.loading} error={hotels.error} empty={!hotels.data.length} emptyTitle="当前权限下没有可查看成本的门店" onRetry={hotels.reload} />
    {!hotels.loading && !hotels.error && hotels.data.length > 0 && <><label>成本所属门店<select aria-label="成本所属门店" value={hotelId} onChange={(event) => setChosen(event.target.value)}>{hotels.data.map((hotel) => <option key={hotel.id} value={hotel.id}>{hotel.name}{hotel.code ? ` · ${hotel.code}` : ''}</option>)}</select></label>{hotelId && <OperatingCostRequest key={`${identity.key}:${hotelId}`} identity={identity} hotelId={hotelId} />}</>}
  </section>
}

function OperatingCostRequest({ identity, hotelId }: { identity: RoleContext; hotelId: string }) {
  const [draft, setDraft] = useState<Selection>({ type: 'MONTH', period: '', from: '', to: '' })
  const [selected, setSelected] = useState(draft)
  const query = operatingCostQuery(selected)
  const resource = useScopedResource<OperatingSummary | undefined>(`${identity.key}:${hotelId}:operating-cost:${query}`, (signal) => featureApiRequest<OperatingSummary>(`/store-resources/hotels/${encodeURIComponent(hotelId)}/costs/operating-summary?${query}`, identity, { signal }), undefined, 30_000)
  const customInvalid = draft.type === 'CUSTOM' && (!draft.from || !draft.to || draft.from > draft.to)
  return <><form className={styles.laundryToolbar} onSubmit={(event) => { event.preventDefault(); if (!customInvalid) { if (operatingCostQuery(draft) === query) void resource.reload(); else setSelected(draft) } }}>
    <label>查询方式<select aria-label="经营成本查询方式" value={draft.type} onChange={(event) => setDraft({ type: event.target.value as Selection['type'], period: '', from: '', to: '' })}><option value="DAY">日</option><option value="MONTH">月</option><option value="CUSTOM">自定义周期</option></select></label>
    {draft.type === 'CUSTOM' ? <><label>开始日期<input aria-label="成本开始日期" required type="date" min="2000-01-01" max={resource.data?.yesterdayDate} value={draft.from} onChange={(event) => setDraft({ ...draft, from: event.target.value })} /></label><label>结束日期<input aria-label="成本结束日期" required type="date" min={draft.from || '2000-01-01'} max={resource.data?.yesterdayDate} value={draft.to} onChange={(event) => setDraft({ ...draft, to: event.target.value })} /></label></> : <label>{draft.type === 'DAY' ? '营业日期（留空为昨日）' : '月份（留空为当月截至昨日）'}<input aria-label="经营成本查询期间" type={draft.type === 'DAY' ? 'date' : 'month'} value={draft.period} min={draft.type === 'DAY' ? '2000-01-01' : '2000-01'} max={draft.type === 'DAY' ? resource.data?.yesterdayDate : resource.data?.today.slice(0, 7)} onChange={(event) => setDraft({ ...draft, period: event.target.value })} /></label>}
    <button type="submit" className="primary" disabled={resource.loading || customInvalid}>查询成本</button><button type="button" className="secondary" disabled={resource.loading} onClick={() => void resource.reload()}>刷新联动数据</button>
  </form><AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />
    {!resource.loading && !resource.error && resource.data?.hotelId === hotelId && <OperatingCostView summary={resource.data} />}
  </>
}

const totalText = (window: OperatingWindow) => window.amount == null ? '暂无已入账数据' : costMoney(window.amount)
const perUseText = (window: OperatingWindow) => window.perRoomUse == null ? '暂不可计算' : `${costMoney(window.perRoomUse)}/间次`
export function OperatingCostView({ summary: a }: { summary: OperatingSummary }) {
  if (a.roomUseBasis !== allRoomUseBasis || a.dateBasis !== 'BUSINESS_DATE_LIVE') return <p role="alert">数据口径不一致，请刷新或联系管理员核对；不显示旧口径金额。</p>
  return <><div className={styles.metrics} aria-label="经营成本四项指标">
    <article><span>昨日总成本（已入账三类）</span><strong>{totalText(a.yesterday)}</strong><small>{a.yesterdayDate}</small></article>
    <article><span>昨日单房开房成本</span><strong>{perUseText(a.yesterday)}</strong><small>同房使用2次计2间次</small></article>
    <article><span>月度累计成本（截至昨日）</span><strong>{totalText(a.monthToYesterday)}</strong><small>{a.monthToYesterday.from} 至 {a.monthToYesterday.to}</small></article>
    <article><span>截止昨日当月单房开房成本</span><strong>{perUseText(a.monthToYesterday)}</strong><small>累计成本 ÷ 累计用房间次</small></article>
  </div><div className={styles.costWarnings}><p>昨日：{a.yesterday.reason}</p><p>当月：{a.monthToYesterday.reason}</p><p>布草使用不扣总库存；洗涤费用在合格回收验收后入账，不按布草采购价重复计算。</p></div>
    <h3>查询结果 · {a.selected.from} 至 {a.selected.to}</h3><p>已入账成本：<strong>{totalText(a.selected)}</strong> · 单房开房成本：<strong>{perUseText(a.selected)}</strong></p><p>{a.selected.reason}</p>
    <div className={styles.roomNightCards}>{a.selected.detail.costs.map((metric) => <article key={metric.kind}><h4>{costKinds[metric.kind]}</h4><strong>{metric.entryCount ? costMoney(metric.amount) : '暂无本类记录'}</strong><p>{metric.entryCount} 条入账/冲销流水</p></article>)}</div>
    <p>待审核耗用 {a.selected.detail.pendingUsageCount} 条；跨日期入账/冲销 {a.selected.detail.shiftedEntryCount} 条。</p><RoomNightEvidenceView window={a.selected.detail} />
    <details className={styles.laundryRates}><summary>统计口径与数据更新时间</summary>{a.notices.map((notice) => <p key={notice}>{notice}</p>)}<p>最近已日结：{a.latestClosedDay} · 更新：{new Date(a.generatedAt).toLocaleString('zh-CN')}</p></details>
  </>
}
