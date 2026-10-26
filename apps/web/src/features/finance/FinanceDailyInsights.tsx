import { useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { errorMessage, financeDownload } from './api'
import { FinanceMutation, FinanceTable } from './FinanceData'
import { useFinanceResource } from './useFinanceResource'
import { rowText, type FinanceRow } from './unifiedModel'
import { insightValue } from './insightModel'
import s from './finance.module.css'

type Daily = FinanceRow & { dailyOperating: FinanceRow[]; dailyGroup: FinanceRow[]; dailyCash: FinanceRow[]; facts: FinanceRow[] }
const metrics: Record<string, string> = { pmsNetRevenue: '已确认来源营业收入', postedRevenue: '已过账收入', postedExpense: '已过账成本费用', postedNetProfit: '已过账净损益', registeredCashIn: '已登记现金流入', registeredCashOut: '已登记现金流出', registeredCashNet: '已登记现金净流' }
export default function FinanceDailyInsights({ identity }: { identity: ApiIdentity }) {
  const [date, setDate] = useState(() => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()))
  const [open, setOpen] = useState(false); const [hotel, setHotel] = useState(''); const [snapshotId, setSnapshot] = useState(''); const [questionId, setQuestion] = useState(''); const [error, setError] = useState('')
  const context = useFinanceResource<{ hotels: FinanceRow[]; model: FinanceRow }>(identity, open ? '/insights/context' : undefined)
  const live = useFinanceResource<Daily>(identity, open ? `/insights/daily?date=${date}${hotel ? `&hotelId=${hotel}` : ''}` : undefined)
  const snapshots = useFinanceResource<{ items: FinanceRow[] }>(identity, open ? `/insights/daily/snapshots?date=${date}` : undefined)
  const frozen = useFinanceResource<Daily>(identity, open && snapshotId ? `/insights/daily/snapshots/${snapshotId}` : undefined)
  const answer = useFinanceResource<FinanceRow>(identity, open && questionId ? `/insights/questions/${questionId}` : undefined)
  const data = snapshotId ? frozen.data : live.data
  return <details className={s.panel} onToggle={event => setOpen(event.currentTarget.open)}><summary>每日经营、集团管理汇总与收款现金日报</summary>
    <p className={s.warning}>实际按业务日期 / 记账日 / 资金发生日取数，不把月报均摊成日报。PMS收入与已过账收入分别披露、不能相加；预收 / 押金 / 储值不是重复营业收入。来源未导入、没有已登记现金时留空，快报不是正式日结或银行资料完整保证。</p>
    <div className={s.pair}><label>实际业务日期<input type="date" value={date} onChange={e => { setDate(e.target.value); setSnapshot(''); setQuestion('') }} /></label><label>授权组织<select value={hotel} onChange={e => { setHotel(e.target.value); setSnapshot(''); setQuestion('') }}><option value="">授权范围集团管理汇总</option>{context.data?.hotels.map(h => <option key={rowText(h, 'id')} value={rowText(h, 'id')}>{rowText(h, 'name')}</option>)}</select></label></div>
    <p>{snapshotId ? '正在查看冻结日报，后续新资料不会覆盖此版本。' : '正在查看实时日报快报。'} 版本：{rowText(data ?? {}, 'reportVersion', '读取中')}</p>
    <div className={s.actions}><button onClick={live.reload}>刷新当日真实来源</button>{snapshotId && <button onClick={() => setSnapshot('')}>返回实时日报</button>}{(['xlsx', 'pdf'] as const).map(format => <button key={format} disabled={!data} onClick={() => { if (data) void financeDownload(identity, `/insights/daily/export?format=${format}&expectedReportVersion=${rowText(data, 'reportVersion')}${snapshotId ? `&snapshotId=${snapshotId}` : `&date=${date}${hotel ? `&hotelId=${hotel}` : ''}`}`, `经营现金日报-${date}.${format}`).catch(e => setError(errorMessage(e))) }}>导出同版 {format.toUpperCase()}</button>)}</div>
    {!snapshotId && data && <FinanceMutation identity={identity} path="/insights/daily/snapshots" label="冻结当前日报事实" fields={[]} build={(_, requestId) => ({ requestId, date, hotelId: hotel || null, expectedReportVersion: data.reportVersion })} onSuccess={result => { setSnapshot(rowText(result, 'snapshotId')); snapshots.reload() }} />}
    <FinanceTable label="当日单店经营快报" rows={data?.dailyOperating ?? []} columns={[{ key: 'hotelName', label: '门店' }, { key: 'companyId', label: '冻结法人' }, { key: 'date', label: '日期' }, ...Object.entries(metrics).map(([key, label]) => ({ key, label, render: (row: FinanceRow) => insightValue(row[key]) })), { key: 'sourceStatus', label: 'PMS来源状态' }, { key: 'ledgerStatus', label: '当日账务状态' }, { key: 'cashStatus', label: '资金来源完整性' }]} />
    <FinanceTable label="同范围集团当日管理汇总（非正式合并）" rows={data?.dailyGroup ?? []} columns={[{ key: 'metric', label: '指标', render: row => metrics[rowText(row, 'metric')] ?? rowText(row, 'metric') }, { key: 'value', label: '同范围来源合计', render: row => insightValue(row.value) }, { key: 'meaning', label: '范围及缺数说明' }]} />
    <FinanceTable label="当日收款与现金日报（真实来源家族汇总）" rows={data?.dailyCash ?? []} columns={[{ key: 'hotelId', label: '门店' }, { key: 'companyId', label: '法人' }, { key: 'date', label: '实际日期' }, { key: 'sourceKind', label: '资金来源' }, { key: 'direction', label: '收 / 支' }, { key: 'amount', label: '实际登记金额' }, { key: 'sourceCount', label: '真实来源笔数' }, { key: 'sourceVersion', label: '冻结取数版本' }]} />
    <FinanceTable label="已冻结日报历史" rows={snapshots.data?.items ?? []} columns={[{ key: 'periodDate', label: '业务日期' }, { key: 'reportVersion', label: '原版本' }, { key: 'createdAt', label: '冻结时间' }]} onOpen={row => setSnapshot(rowText(row, 'id'))} />
    {snapshotId && data && context.data?.model.ready === true && <FinanceMutation identity={identity} path="/insights/daily/questions" label="引用此冻结日报问数（不执行财务操作）" fields={[{ key: 'question', label: '关于本日报的问题', type: 'textarea', required: true }, { key: 'allowAggregateTransfer', label: '授权将本问题及当前已授权日报汇总发送给已配置模型', type: 'checkbox', required: true }]} build={(value, requestId) => { if (value.allowAggregateTransfer !== 'true') throw new Error('请明确本次汇总数据传输授权'); return { requestId, date: data.date, hotelId: hotel || null, snapshotId, expectedReportVersion: data.reportVersion, question: value.question, allowAggregateTransfer: true } }} onSuccess={result => setQuestion(rowText(result, 'id'))} />}
    {!snapshotId && <p>需先冻结日报后再进行同版问数；模型未配置时不生成模拟答案。</p>}
    {answer.data && <div className={s.detailPanel}><h4>日报问数引用与模型结果</h4><p>{rowText(answer.data, 'sourceReportVersion')} · {rowText(answer.data, 'uncertainty')}</p><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(answer.data.content, null, 2)}</pre></div>}
    {!!(error || live.error || frozen.error || answer.error) && <p className={s.error} role="alert">{error || errorMessage(live.error || frozen.error || answer.error)}</p>}
  </details>
}
