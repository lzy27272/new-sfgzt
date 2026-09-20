import { useRef, useState } from 'react'
import type { RoleContext } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { AsyncState } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { formatLocalDateTime } from '../shared/FeatureUI'
import { closeCostMonth, loadCostEntries, loadCostHistory, loadCostSummary } from './costApi'
import { closeBlockReason, costKinds, costMoney } from './costs'
import { RoomNightCostPanel } from './RoomNightCostPanel'
import type { CostCloseInput, CostClosedMonth, CostEntry, CostPage, CostSummary } from './costs'
import styles from './storeResources.module.css'

export function CostPanel({ identity, hotelId, canClose, go }: { identity: RoleContext; hotelId: string; canClose: boolean; go: AppNavigate }) {
  const [month, setMonth] = useState('')
  const requestedMonth = useRef(month)
  requestedMonth.current = month
  const current = useScopedResource<CostSummary | undefined>(`${identity.key}:${hotelId}:cost-month:${month}`, (signal) => loadCostSummary(identity, hotelId, month, signal), undefined)
  const data = current.data && (!month || current.data.month === month) ? current.data : undefined
  return <section className={`${styles.panel} ${styles.costPanel}`}><header><div><h2>成本分析与月结</h2><p>耗品、清洁用品、洗涤和报损分别记账；采购入库及固定资产原值不直接计为使用成本。</p></div></header>
    <div className={styles.laundryToolbar}><label>统计月份<input aria-label="统计月份" type="month" min="2000-01" max={data?.currentMonth} value={month || data?.month || ''} onChange={(event) => { if (event.target.value) setMonth(event.target.value) }} /></label><button type="button" className="secondary" disabled={current.loading} onClick={() => void current.reload()}>刷新成本预览</button></div>
    <AsyncState loading={current.loading} error={current.error} onRetry={current.reload} />
    {data && <CostMonth key={`${identity.key}:${hotelId}:${data.month}`} identity={identity} hotelId={hotelId} summary={data} canClose={canClose} disabled={current.loading || !!current.error} onClosed={(value) => { if (requestedMonth.current === value.month) current.setData(value) }} go={go} />}
    <CostHistory identity={identity} hotelId={hotelId} onSelect={setMonth} refreshKey={`${data?.month}:${data?.closed}`} />
  </section>
}

function CostMonth({ identity, hotelId, summary, canClose, disabled, onClosed, go }: {
  identity: RoleContext; hotelId: string; summary: CostSummary; canClose: boolean; disabled: boolean; onClosed: (value: CostSummary) => void; go: AppNavigate
}) {
  const [filter, setFilter] = useState({ kind: 'ALL', day: '', page: 0 }), [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string>(), [error, setError] = useState<string>()
  const inFlight = useRef(false)
  const entries = useScopedResource<CostPage<CostEntry>>(`${identity.key}:${hotelId}:cost-entries:${summary.month}:${JSON.stringify(filter)}:${summary.previewToken}`, (signal) => loadCostEntries(identity, hotelId, summary.month, filter.page, filter.kind, filter.day, signal), { items: [], page: 0, hasMore: false })
  const submit = async (input: CostCloseInput) => {
    if (inFlight.current || disabled) return
    inFlight.current = true; setBusy(true); setError(undefined); setMessage(undefined)
    try {
      const result = await closeCostMonth(identity, hotelId, input)
      onClosed(result); setMessage('本月已关账，快照已保留。之后补录或冲销记入当前月份，不会覆盖此月。')
    } catch (reason) { setError(reason instanceof Error ? reason.message : '月结失败，请刷新后重新核对。') }
    finally { inFlight.current = false; setBusy(false) }
  }
  return <><div className={styles.laundryNotice}>{message && <div role="status" className={styles.success}>{message}</div>}{error && <div role="alert" className={styles.error}>{error}</div>}</div>
    <CostSummaryView summary={summary} onKind={(kind) => setFilter({ kind, day: '', page: 0 })} onDay={(day) => setFilter({ kind: 'ALL', day, page: 0 })} />
    <RoomNightCostPanel identity={identity} hotelId={hotelId} month={summary.month} />
    <CostCloseForm key={`${summary.previewToken}:${summary.closed}`} summary={summary} canClose={canClose} busy={busy || disabled} onSubmit={submit} />
    <header><h3>成本来源明细</h3><span>金额及数量保留4位小数；负数为冲回或盘盈。</span></header>
    <div className={styles.laundryToolbar}><label>成本分类<select aria-label="成本分类" value={filter.kind} onChange={(event) => setFilter({ ...filter, kind: event.target.value, page: 0 })}><option value="ALL">全部分类</option>{Object.entries(costKinds).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label><label>入账日期<input type="date" aria-label="入账日期" value={filter.day} min={`${summary.month}-01`} onChange={(event) => { if (!event.target.value || event.target.value.startsWith(summary.month + '-')) setFilter({ ...filter, day: event.target.value, page: 0 }) }} /></label><button type="button" className="secondary" onClick={() => setFilter({ kind: 'ALL', day: '', page: 0 })}>清除筛选</button></div>
    <AsyncState loading={entries.loading} error={entries.error} empty={!entries.data.items.length} onRetry={entries.reload} emptyTitle="本月暂无符合条件的成本明细" />
    {!entries.loading && !entries.error && <CostEntries entries={entries.data.items} />}
    <div className={styles.actions}><button type="button" className="secondary" disabled={!filter.page || entries.loading} onClick={() => setFilter({ ...filter, page: filter.page - 1 })}>上一页</button><span>第 {filter.page + 1} 页 · 每页50条</span><button type="button" className="secondary" disabled={!entries.data.hasMore || entries.loading || !!entries.error} onClick={() => setFilter({ ...filter, page: filter.page + 1 })}>下一页</button></div>
    <div className={styles.actions}><button type="button" className="secondary" onClick={() => go('store-resources', { hotelId, tab: 'usage' })}>处理待审核耗用</button><button type="button" className="secondary" onClick={() => go('store-resources', { hotelId, tab: 'laundry' })}>核对洗涤台账</button><button type="button" className="secondary" onClick={() => go('store-resources', { hotelId, tab: 'inventory' })}>查看库存流水</button></div>
  </>
}

export function CostSummaryView({ summary: s, onKind, onDay }: { summary: CostSummary; onKind: (kind: string) => void; onDay: (day: string) => void }) {
  return <><p className={s.closed ? styles.success : styles.unitNote}>{s.month} · {s.closed ? `已关账 · ${s.closedByName} · ${formatLocalDateTime(s.closedAt)}` : '未关账，随已审核业务自动更新'}{s.closeNote && ` · 核对说明：${s.closeNote}`}</p>
    <div className={styles.metrics}>{[['使用及洗涤', s.usageTotal], ['报损损失', s.lossTotal], ['计入成本合计', s.costTotal], ['盘点调整（不混入成本）', s.adjustmentTotal]].map(([label, amount]) => <article key={label}><span>{label}</span><strong>{costMoney(amount)}</strong></article>)}</div>
    <p className={styles.unitNote}>这里是物品使用、洗涤与报损成本，不是全店经营总成本。下方每间次成本使用已核对的PMS全部用房总间次，同房使用两次计2间次；未接入或缺数据时不虚构分母。</p>
    <div className={styles.costWarnings}><h3>{s.closed ? '关账时的核对提示' : '月结前核对'}</h3><p>待审核耗用 {s.pendingUsageCount} 条 · 截至核对时尚在洗 {s.openLaundryCount} 单（送洗日期早于月末，未回收部分不预估费用）</p><p>零金额记录 {s.zeroAmountCount} 条 · 历史分类待核对 {s.legacyCount} 条 · 跨月补录 / 冲销 {s.priorPeriodCount} 条</p><p>历史手工流水的名称与分类按本功能上线时的配置保留；原金额未重算。零金额不等于没有发生耗用，请先核对。</p></div>
    <h3>成本分类 · 点击查看明细</h3><div className={styles.costCategories}>{s.categories.map((row) => <button type="button" className="secondary" key={row.costKind} onClick={() => onKind(row.costKind)}><span>{costKinds[row.costKind] || '其他成本'}</span><strong>{costMoney(row.amount)}</strong><small>{row.entryCount} 条记录</small></button>)}</div>
    <details className={styles.laundryRates}><summary>每日成本与物品汇总（{s.entryCount}条成本记录）</summary><div className={styles.costCategories}>{s.daily.map((row) => <button key={row.postingDate} type="button" className="secondary" onClick={() => onDay(row.postingDate)}><span>{row.postingDate}</span><strong>{costMoney(row.amount)}</strong><small>盘点调整 {costMoney(row.adjustmentAmount)}</small></button>)}</div><div className={styles.tableWrap}><table><thead><tr><th>物品</th><th>分类</th><th>净数量</th><th>金额</th></tr></thead><tbody>{s.items.map((row) => <tr key={`${row.itemId}:${row.itemName}:${row.baseUnit}:${row.costKind}`}><td>{row.itemName}</td><td>{costKinds[row.costKind] || '其他'}</td><td>{row.quantity} {row.baseUnit}</td><td>{costMoney(row.amount)}</td></tr>)}</tbody></table></div><p>数量按物品和基础单位分别展示；不同单位不相加。盘点调整独立核对，不直接当成日常耗用。</p></details>
  </>
}

export function CostCloseForm({ summary, canClose, busy, onSubmit }: { summary: CostSummary; canClose: boolean; busy: boolean; onSubmit: (input: CostCloseInput) => Promise<void> }) {
  const [note, setNote] = useState(''), [acknowledge, setAcknowledge] = useState(false)
  const blocked = closeBlockReason(summary, canClose)
  if (blocked) return <p className={styles.unitNote}>{blocked}</p>
  return <form className={`${styles.form} ${styles.confirmation}`} onSubmit={(event) => { event.preventDefault(); if (!busy && acknowledge && note.trim()) void onSubmit({ month: summary.month, previewToken: summary.previewToken, note: note.trim(), acknowledgeWarnings: true }) }}><h3>核对并锁定 {summary.month} 月结</h3><p>此操作保留不可覆盖的月结快照。之后补录和冲销进入当前月份，并保留原业务日期；不直接重开或改写历史月份。</p><fieldset disabled={busy}><label>月结核对说明<textarea required maxLength={1000} value={note} onChange={(event) => setNote(event.target.value)} placeholder="请说明零金额、在洗布草及差异核对情况" /></label><label className={styles.checkbox}><input type="checkbox" checked={acknowledge} onChange={(event) => setAcknowledge(event.target.checked)} />已核对成本来源、在洗布草、零金额及历史分类提示，确认未验收洗涤不预估入账。</label></fieldset><button type="submit" className="primary" disabled={busy || !acknowledge || !note.trim()}>{busy ? '月结处理中…' : '确认关账并留存'}</button></form>
}

export function CostEntries({ entries }: { entries: CostEntry[] }) {
  return <div className={styles.costEntries}>{entries.map((entry) => <article key={entry.id}><header><h4>{entry.itemName} · {costKinds[entry.costKind] || '其他成本'}</h4><strong>{costMoney(entry.amount)}</strong></header><p>业务日期 {entry.businessDate} · 入账日期 {entry.postingDate}{entry.priorPeriod && ' · 跨月调整'}{entry.direction < 0 && ' · 冲销记录'}</p><p>数量 {entry.quantity} {entry.baseUnit} · {entry.sourceKind === 'LAUNDRY' ? '洗涤合格回收' : '库存变动'} · {entry.operatorName}</p><p>{entry.reason}</p>{entry.legacyClassification && <p>历史分类按上线时的物品配置保留，请核对。</p>}<details><summary>查看来源与追溯编号</summary><p>原单据编号：{entry.sourceId}</p>{entry.sourceParentId && <p>关联业务编号：{entry.sourceParentId}</p>}{entry.reversesEntryId && <p>冲回成本记录：{entry.reversesEntryId}</p>}<p>登记时间：{formatLocalDateTime(entry.sourceRecordedAt)}</p></details></article>)}</div>
}

function CostHistory({ identity, hotelId, onSelect, refreshKey }: { identity: RoleContext; hotelId: string; onSelect: (month: string) => void; refreshKey: string }) {
  const [page, setPage] = useState(0)
  const history = useScopedResource<CostPage<CostClosedMonth>>(`${identity.key}:${hotelId}:cost-history:${page}:${refreshKey}`, (signal) => loadCostHistory(identity, hotelId, page, signal), { items: [], page: 0, hasMore: false })
  return <section className={styles.laundryRates}><h3>历史月结</h3><AsyncState loading={history.loading} error={history.error} onRetry={history.reload} empty={!history.data.items.length} emptyTitle="尚无月结快照" />{!history.loading && !history.error && history.data.items.map((item) => <p key={item.id}><button type="button" className="secondary" onClick={() => onSelect(item.month)}>查看 {item.month} 月结</button>　{costMoney(item.costTotal)} · {item.closedByName} · {formatLocalDateTime(item.closedAt)}</p>)}<div className={styles.actions}><button type="button" className="secondary" disabled={!page || history.loading} onClick={() => setPage(page - 1)}>上一页月结</button><button type="button" className="secondary" disabled={!history.data.hasMore || history.loading || !!history.error} onClick={() => setPage(page + 1)}>下一页月结</button></div></section>
}
