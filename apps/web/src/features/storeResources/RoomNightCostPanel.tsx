import { useState } from 'react'
import type { RoleContext } from '../../domain'
import { AsyncState } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { formatLocalDateTime } from '../shared/FeatureUI'
import { featureApiRequest } from '../shared/featureApi'
import { costKinds, costMoney } from './costs'
import { allRoomUseBasis, analysisMatches, costChangeText, unitCostText, windowLabel } from './roomNightCosts'
import type { RoomNightAnalysis, RoomNightPeriod, RoomNightWindow } from './roomNightCosts'
import styles from './storeResources.module.css'

export function RoomNightCostPanel({ identity, hotelId, month }: { identity: RoleContext; hotelId: string; month: string }) {
  const [selection, setSelection] = useState<{ type: RoomNightPeriod; period: string }>({ type: 'MONTH', period: month })
  return <section className={styles.roomNightPanel} aria-label="PMS用房间次与成本"><header><h3>PMS用房间次与成本</h3><p>过夜、免费、自用和钟点房全部统计。同房当天使用两次计2间次，不按房号去重，钟点不折算。已核对的总间次即可计算，无需强制提供四类明细。</p></header>
    <div className={styles.laundryToolbar}><label>每间次成本统计方式<select aria-label="每间次成本统计方式" value={selection.type} onChange={(event) => setSelection({ type: event.target.value as RoomNightPeriod, period: event.target.value === 'MONTH' ? month : '' })}><option value="MONTH">按月 / 本月同期</option><option value="DAY">按营业日</option></select></label>
      <label>{selection.type === 'MONTH' ? '成本比较月份' : '成本比较营业日'}<input aria-label="成本比较期间" type={selection.type === 'MONTH' ? 'month' : 'date'} min={selection.type === 'MONTH' ? '2000-01' : '2000-01-01'} value={selection.period} onChange={(event) => setSelection({ ...selection, period: event.target.value })} /></label>
      {!selection.period && <span>{selection.type === 'DAY' ? '日期留空时使用服务端最近已日结营业日。' : '月份留空时统计本月截至最近已日结营业日。'}</span>}
    </div>
    <RoomNightRequest key={`${identity.key}:${hotelId}:${selection.type}:${selection.period}`} identity={identity} hotelId={hotelId} type={selection.type} period={selection.period} />
  </section>
}

function RoomNightRequest({ identity, hotelId, type, period }: { identity: RoleContext; hotelId: string; type: RoomNightPeriod; period: string }) {
  const resource = useScopedResource<RoomNightAnalysis | undefined>(`${identity.key}:${hotelId}:room-use:${type}:${period}`, (signal) => featureApiRequest<RoomNightAnalysis>(`/store-resources/hotels/${encodeURIComponent(hotelId)}/costs/per-room-use?periodType=${type}${period ? `&period=${encodeURIComponent(period)}` : ''}`, identity, { signal }), undefined)
  return <><button type="button" className="secondary" disabled={resource.loading} onClick={() => void resource.reload()}>刷新用房与成本</button><AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />
    {!resource.loading && !resource.error && resource.data && resource.data.roomUseBasis !== allRoomUseBasis && <RoomNightBasisNotice />}
    {!resource.loading && !resource.error && analysisMatches(resource.data, type, period) && <RoomNightCostView analysis={resource.data} />}
  </>
}

export function RoomNightCostView({ analysis: a }: { analysis: RoomNightAnalysis }) {
  if (a.roomUseBasis !== allRoomUseBasis) return <RoomNightBasisNotice />
  const priorLabel = a.periodType === 'DAY' ? '较前一日' : '较上月同期'
  return <><p><strong>{windowLabel(a.current)}</strong> · {a.current.costMonthClosed ? '成本月份已关账' : '成本尚未关账，随已入账业务更新'} · 最近已日结 {a.latestClosedDay}</p>
    <RoomNightEvidenceView window={a.current} />
    <div className={styles.roomNightCards}>{a.current.costs.map((metric) => {
      const compare = a.comparisons.find((item) => item.kind === metric.kind)
      return <article key={metric.kind}><h4>{costKinds[metric.kind] || '其他'}</h4><strong>{unitCostText(metric)}</strong><p>{metric.entryCount ? `已入账 ${costMoney(metric.amount)} · ${metric.entryCount} 条记录` : '暂无本类成本记录，不代表实际成本为0'}</p><p>{metric.reason}</p>
        <dl><div><dt>{priorLabel}</dt><dd>{costChangeText(compare?.previous)}</dd></div><div><dt>较去年同期</dt><dd>{costChangeText(compare?.yearAgo)}</dd></div></dl>
        {compare && <details><summary>为什么可比 / 不可比</summary><p>{priorLabel}：{compare.previous.reason}</p><p>同比：{compare.yearAgo.reason}</p></details>}
      </article>
    })}</div>
    {(a.current.pendingUsageCount > 0 || a.current.shiftedEntryCount > 0) && <div className={styles.costWarnings}><p>待审核耗用 {a.current.pendingUsageCount} 条 · 非原业务日期入账 / 冲销 {a.current.shiftedEntryCount} 条。冲销可能产生负数，请结合原单据判断，不直接据此评价员工。</p></div>}
    <details className={styles.laundryRates}><summary>查看对比期成本与PMS来源</summary>{[[priorLabel, a.previous], ['去年同期', a.yearAgo]].map(([label, raw]) => {
      const window = raw as RoomNightWindow
      return <section key={label as string}><h4>{label as string} · {windowLabel(window)}</h4><RoomNightEvidenceView window={window} /><ul>{window.costs.map((m) => <li key={m.kind}>{costKinds[m.kind]}：{m.entryCount ? costMoney(m.amount) : '无入账记录'} · {unitCostText(m)}{m.state !== 'READY' && ` · ${m.reason}`}</li>)}</ul></section>
    })}</details>
    <details className={styles.laundryRates}><summary>统计口径与使用边界</summary>{a.notices.map((notice) => <p key={notice}>{notice}</p>)}<p>计算时间：{formatLocalDateTime(a.generatedAt)} · 成本时区 {a.costTimezone} · PMS营业日时区 {a.businessTimezone}</p></details>
  </>
}

export function RoomNightEvidenceView({ window }: { window: RoomNightWindow }) {
  const s = window.source
  const confirmed = s.state === 'READY' || s.state === 'ZERO_USES'
  return <div className={s.state === 'READY' ? styles.unitNote : styles.costWarnings} role="status"><p>{s.reason}</p>
    {confirmed && s.roomUses != null && <p>PMS全部用房：<strong>{s.roomUses}</strong> 间次 · 已覆盖 {s.coveredDays}/{s.expectedDays} 天</p>}
    {confirmed && s.breakdown && <><dl className={styles.roomNightBreakdown} aria-label="全部用房分类明细">
      <div><dt>收费过夜房</dt><dd>{s.breakdown.paidOvernightRoomUses} 间次</dd></div>
      <div><dt>免费房（含免费钟点）</dt><dd>{s.breakdown.complimentaryRoomUses} 间次</dd></div>
      <div><dt>自用房（含自用钟点）</dt><dd>{s.breakdown.houseUseRoomUses} 间次</dd></div>
      <div><dt>收费钟点房</dt><dd>{s.breakdown.paidHourlyRoomUses} 间次</dd></div>
    </dl><p>同一次使用只归一类，不重复计入分项；同房不同次使用分别计数，钟点不折算。这是用房数量明细，不是各类型独立成本。</p></>}
    {confirmed && !s.breakdown && <p>已核对全部用房总间次；未提供完整分项，分项不按0补齐，不影响总成本分摊。</p>}
    {s.candidateRoomUses != null && <p>待核验数量：{s.candidateRoomUses} 间次（尚未作为计算分母）</p>}
    {s.missingDates.length > 0 && <p>缺少日期：{s.missingDates.join('、')}</p>}
    {s.definitionLabel && <p>{s.definitionLabel}</p>}
    {s.revision && <details><summary>查看PMS采集与来源指纹</summary><p>采集时间：{formatLocalDateTime(s.collectedAt)} · {s.mode === 'DAILY' ? '完整逐日汇总' : '该期间汇总'}</p><p>来源指纹：{s.revision}</p><p>此指纹用于核对本次数据版本，不是图片附件，也不是已经锁定的KPI快照。</p></details>}
  </div>
}

function RoomNightBasisNotice() {
  return <p role="alert" className={styles.costWarnings}>当前数据不是“用房间次”口径，请刷新或联系管理员更新服务；不会把旧房晚或折合房晚当作间次计算。</p>
}
