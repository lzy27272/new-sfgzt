import { useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { errorMessage } from './api'
import { FinanceTable } from './FinanceData'
import { useFinanceResource } from './useFinanceResource'
import { rowText, type FinanceRow } from './unifiedModel'
import { insightValue } from './insightModel'
import s from './finance.module.css'

type Context = { departments: FinanceRow[]; employees: FinanceRow[]; parties: FinanceRow[]; projects: { items: FinanceRow[]; complete: boolean; meaning: string }; meaning: string }
type Ledger = { items: FinanceRow[]; total: number; debit: string; credit: string; reportVersion: string; meaning: string }
const actualRows = (value: unknown): FinanceRow[] => Array.isArray(value) ? value.filter((row): row is FinanceRow => !!row && typeof row === 'object' && !Array.isArray(row)) : []
/** Controlled per-line editor; caller owns the values and submits IDs, never candidate labels/snapshots. */
export function FinanceAuxiliaryFields({ context, value, onChange }: { context: FinanceRow; value: Record<string, string>; onChange: (next: Record<string, string>) => void }) {
  const projects = context.projects && typeof context.projects === 'object' && !Array.isArray(context.projects) ? context.projects as FinanceRow : {}
  const choices = actualRows(projects.items)
  const project = value.projectKind === 'NONE' ? 'NONE' : value.projectKind && value.projectId ? `${value.projectKind}:${value.projectId}` : ''
  const set = (key: string, next: string) => onChange({ ...value, [key]: next })
  return <div className={s.pair}>
    <label>实际部门<select value={value.departmentId ?? ''} onChange={event => set('departmentId', event.target.value)}><option value="">未分配</option>{actualRows(context.departments).map(row => <option key={rowText(row, 'id')} value={rowText(row, 'id')}>{rowText(row, 'name')}</option>)}</select></label>
    <label>员工账号<select value={value.employeeAccountId ?? ''} onChange={event => set('employeeAccountId', event.target.value)}><option value="">未分配</option>{actualRows(context.employees).map(row => <option key={rowText(row, 'id')} value={rowText(row, 'id')}>{rowText(row, 'displayName')} · {rowText(row, 'employeeNo', '')}</option>)}</select></label>
    <label>交易对方<select value={value.partyId ?? ''} onChange={event => set('partyId', event.target.value)}><option value="">未分配</option>{actualRows(context.parties).map(row => <option key={rowText(row, 'id')} value={rowText(row, 'id')}>{rowText(row, 'name')}</option>)}</select></label>
    <label>真实项目<select value={project} onChange={event => { const selected = event.target.value; if (!selected || selected === 'NONE') onChange({ ...value, projectKind: selected, projectId: '' }); else { const actual = choices.find(row => rowText(row, 'key') === selected); if (actual) onChange({ ...value, projectKind: rowText(actual, 'kind'), projectId: rowText(actual, 'id') }) } }}><option value="">未分配（不按名称猜配）</option><option value="NONE">明确不分项目</option>{choices.map(row => <option key={rowText(row, 'key')} value={rowText(row, 'key')}>{rowText(row, 'name')} · {rowText(row, 'kind')}</option>)}</select></label>
    {projects.complete === false && <p className={s.warning}>真实项目候选超出窗口，列表不代表全部项目；请先缩小范围，不能提交任意项目编号替代。</p>}
  </div>
}
/** Mount with key={`${bookId}:${month}`} when changing books/periods; all choices come from the authorized book. */
export default function FinanceAuxiliary({ identity, bookId, month, onVoucher }: { identity: ApiIdentity; bookId: string; month: string; onVoucher: (id: string) => void }) {
  const [department, setDepartment] = useState(''); const [employee, setEmployee] = useState(''); const [party, setParty] = useState(''); const [project, setProject] = useState('')
  const context = useFinanceResource<Context>(identity, bookId ? `/accounting/auxiliary/context?bookId=${encodeURIComponent(bookId)}` : undefined)
  const query = new URLSearchParams({ bookId, month }); for (const [key, value] of Object.entries({ departmentId: department, employeeAccountId: employee, partyId: party, projectKey: project })) if (value) query.set(key, value)
  const ledger = useFinanceResource<Ledger>(identity, bookId && month ? `/accounting/auxiliary/ledger?${query.toString()}` : undefined)
  return <section className={s.panel}><h3>真实辅助核算明细</h3><p className={s.warning}>只按已过账分录冻结的部门、员工账号、交易对方及真实项目筛选。历史空维度明确未分配，不能用现在的人员 / 门店 / 项目名称补写历史；工资明细仍须独立授权。</p>
    <div className={s.pair}><label>实际部门<select value={department} onChange={e => setDepartment(e.target.value)}><option value="">全部已授权部门及历史未分配</option>{context.data?.departments.map(row => <option key={rowText(row, 'id')} value={rowText(row, 'id')}>{rowText(row, 'name')}</option>)}</select></label><label>员工账号<select value={employee} onChange={e => setEmployee(e.target.value)}><option value="">全部已授权员工及历史未分配</option>{context.data?.employees.map(row => <option key={rowText(row, 'id')} value={rowText(row, 'id')}>{rowText(row, 'displayName')} · {rowText(row, 'employeeNo', '')}</option>)}</select></label>
      <label>交易对方<select value={party} onChange={e => setParty(e.target.value)}><option value="">全部交易对方及历史未分配</option>{context.data?.parties.map(row => <option key={rowText(row, 'id')} value={rowText(row, 'id')}>{rowText(row, 'name')}</option>)}</select></label><label>真实项目<select value={project} onChange={e => setProject(e.target.value)}><option value="">全部真实项目及历史未分配</option><option value="NONE">明确不分项目（历史空维度不猜配）</option>{context.data?.projects.items.map(row => <option key={rowText(row, 'key')} value={rowText(row, 'key')}>{rowText(row, 'name')} · {rowText(row, 'kind')}</option>)}</select></label></div>
    {context.data?.projects.complete === false && <p className={s.warning}>项目候选超过安全窗口，当前选择器不代表全部项目。请先缩小实际业务范围。</p>}
    <div className={s.actions}><button onClick={() => { setDepartment(''); setEmployee(''); setParty(''); setProject('') }}>清除维度筛选</button><button onClick={ledger.reload}>刷新真实过账明细</button></div>
    {ledger.data && <p>已过账明细 {ledger.data.total} 行 · 借方 {insightValue(ledger.data.debit)} · 贷方 {insightValue(ledger.data.credit)} · 版本 {ledger.data.reportVersion}</p>}
    <FinanceTable label="同账套同期间全量已授权辅助分录" rows={ledger.data?.items ?? []} columns={[{ key: 'accountingDate', label: '记账日' }, { key: 'voucherId', label: '真实凭证引用' }, { key: 'accountCode', label: '真实科目' }, { key: 'accountName', label: '科目名称' }, { key: 'departmentName', label: '冻结部门', render: row => rowText(row, 'departmentName', '历史未分配') }, { key: 'employeeName', label: '冻结员工', render: row => rowText(row, 'employeeName', '历史未分配') }, { key: 'partyName', label: '冻结交易对方', render: row => rowText(row, 'partyName', '历史未分配') }, { key: 'projectName', label: '冻结项目', render: row => rowText(row, 'projectName', '历史未分配') }, { key: 'debit', label: '借方' }, { key: 'credit', label: '贷方' }, { key: 'summary', label: '分录摘要' }]} onOpen={row => onVoucher(rowText(row, 'voucherId'))} />
    <p>{ledger.data?.meaning ?? context.data?.meaning}</p>{!!(context.error || ledger.error) && <p className={s.error} role="alert">{errorMessage(context.error || ledger.error)}</p>}
  </section>
}
