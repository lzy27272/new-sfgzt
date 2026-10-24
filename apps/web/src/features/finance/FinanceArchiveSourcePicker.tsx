import { useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { FinanceMutation, FinanceTable } from './FinanceData'
import { errorMessage } from './api'
import { useFinanceResource } from './useFinanceResource'
import { rowText, type FinanceRow } from './unifiedModel'
import s from './finance.module.css'

const labels: Record<string, string> = { SUPPLY_RECOGNITION: '采购匹配 / 验收及实际发票', ASSET_CARD: '真实财务资产卡及原来源', ACCRUAL: '租赁 / 待摊计划及合同原件', ASSET_GAIN: '盘盈实际估值与原件', BOOK_REPORT: '已关账三表（不依赖 AI）' }
export default function FinanceArchiveSourcePicker({ identity, onSelect }: { identity: ApiIdentity; onSelect: (type: string, id: string) => void }) {
  const [kind, setKind] = useState('SUPPLY_RECOGNITION'); const [reportKey, setReport] = useState('')
  const sources = useFinanceResource<{ items: FinanceRow[] }>(identity, `/archive/sources?sourceType=${kind}`)
  const reports = useFinanceResource<{ items: FinanceRow[] }>(identity, kind === 'BOOK_REPORT' ? '/archive/report-sources' : undefined)
  const selected = reports.data?.items.find(row => `${row.bookId}:${row.month}:${row.reportVersion}` === reportKey)
  return <section className={s.panel}><h3>从真实专项来源选择，不用填写来源编号</h3><label>专项来源<select value={kind} onChange={e => { setKind(e.target.value); setReport('') }}>{Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
    {kind === 'BOOK_REPORT' && <><p className={s.warning}>只选择真实已关账、已冻结三表。当前账号按其总账 / 工资权限取得相应版本，不依赖已配置模型或已生成 AI 报告。新来源确认后仍需正式“冻结此来源版本”归档。</p><label>真实账套及关账月份<select value={reportKey} onChange={e => setReport(e.target.value)}><option value="">请选择真实关账报表</option>{reports.data?.items.map(row => { const key = `${row.bookId}:${row.month}:${row.reportVersion}`; return <option key={key} value={key}>{rowText(row, 'name')} · {rowText(row, 'month')}</option> })}</select></label>{selected && <FinanceMutation key={reportKey} identity={identity} path="/archive/report-sources" label="确认此真实三表来源版本" fields={[]} build={(_, requestId) => ({ requestId, bookId: selected.bookId, month: selected.month, expectedReportVersion: selected.reportVersion })} onSuccess={result => { onSelect('BOOK_REPORT', rowText(result, 'sourceId')); sources.reload() }} />}</>}
    <FinanceTable label={`${labels[kind]} · 当前真实可见来源`} rows={sources.data?.items ?? []} columns={[{ key: 'title', label: '真实来源' }, { key: 'hotelId', label: '组织' }, { key: 'companyId', label: '法人' }]} onOpen={row => onSelect(kind, rowText(row, 'id'))} />{!!(sources.error || reports.error) && <p className={s.error} role="alert">{errorMessage(sources.error || reports.error)}</p>}
  </section>
}
