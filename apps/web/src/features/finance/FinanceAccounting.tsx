import { useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import type { AppNavigate } from '../../app/routeConfig'
import type { RouteParams } from '../../domain'
import { errorMessage, financeDownload } from './api'
import { FinanceFacts, FinanceMutation, FinanceTable, exportFinanceCsv, type FinanceField, type FinanceColumn } from './FinanceData'
import { exactDecimalTotal, financeState, rowText, voucherBalanced, type FinanceList, type FinanceRow } from './unifiedModel'
import { useFinanceResource } from './useFinanceResource'
import { accountingChecks, accountingExportPath, accountingRecord, accountingReportLabels, accountingReportRows, accountingReportTotals, accountingRows, accountingSourceTarget, cashFlowLabels, shortVoucherId, validAccountingLine, type AccountingReportType } from './accountingModel'
import s from './finance.module.css'
import FinanceClosePanel from './FinanceClosePanel'
import FinanceAuxiliary, { FinanceAuxiliaryFields } from './FinanceAuxiliary'

const columnLabels: Record<string, string> = { accountCode: '科目编码', accountName: '科目名称', code: '科目编码', name: '名称', category: '科目类别', categoryName: '现金流类别', normalSide: '余额方向', debit: '借方', credit: '贷方', openingDebit: '启用期初借方', openingCredit: '启用期初贷方', beginningNet: '本期期初净额（借－贷）', periodDebit: '本期借方', periodCredit: '本期贷方', endingNet: '期末净额（借－贷）', balance: '余额', amount: '金额', reportLine: '报表行', accountingDate: '记账日期', businessDate: '业务日期', summary: '摘要', voucherSummary: '凭证摘要', voucherId: '凭证引用', kind: '凭证类型', lineNo: '分录行', cashAccount: '现金 / 银行科目', cashFlowCategory: '现金流分类', sourceType: '来源类型', originSourceType: '原始业务类型', sourceId: '来源编号', eventKey: '经济事件', status: '状态', complete: '来源完整', note: '核对说明', expectedCount: '应有条数', actualCount: '已核对条数', checkName: '检查项', result: '核对结果', message: '具体说明', version: '版本', enabled: '启用', debitCode: '借方科目', creditCode: '贷方科目' }
const totalLabels: Record<string, string> = { revenue: '本期收入', expense: '本期成本费用', netProfit: '本期损益（按实际已过账分录）', assets: '资产合计', liabilities: '负债合计', equity: '权益合计', unclosedProfit: '未结转损益', difference: '勾稽差额', beginningCash: '期初现金及银行', endingCash: '期末现金及银行', netMovement: '本期资金净变动' }
const rowsFrom = accountingRows
type Option = { value: string; label: string }
type AccountingContext = { books: FinanceRow[]; accounts: FinanceRow[]; companies: FinanceRow[]; hotels: FinanceRow[] }
function tableColumns(rows: FinanceRow[]): FinanceColumn[] { return [...new Set(rows.flatMap(Object.keys))].filter(key => !['id', 'tenantId', 'bookId', 'accountId'].includes(key)).map(key => ({ key, label: columnLabels[key] ?? key, render: key === 'voucherId' ? row => <span title={rowText(row, key)}>{shortVoucherId(row[key])}</span> : undefined })) }
const cashFlowOptions = Object.entries(cashFlowLabels).filter(([value]) => value !== 'UNCLASSIFIED').map(([value, label]) => ({ value, label }))
export function FinanceAccounting({ identity, permissions, params, go }: { identity: ApiIdentity; permissions: string[]; params: RouteParams; go: AppNavigate }) {
  const has = (code: string) => permissions.includes('*') || permissions.includes(code)
  const context = useFinanceResource<AccountingContext>(identity, '/accounting/context')
  const screen = params.screen ?? 'vouchers'; const bookId = params.bookId ?? ''; const month = params.month ?? new Date().toLocaleDateString('sv-SE').slice(0, 7)
  const bookHotelId = String(context.data?.books.find(row => row.id === bookId)?.hotelId ?? '')
  const q = `bookId=${encodeURIComponent(bookId)}&month=${encodeURIComponent(month)}`
  const resources = useFinanceResource<FinanceRow>(identity, bookId ? screen === 'periods' ? `/accounting/periods/check?${q}` : screen === 'rules' ? `/accounting/rules?${q}` : screen === 'sources' ? bookHotelId ? `/accounting/source-events?hotelId=${encodeURIComponent(bookHotelId)}&month=${encodeURIComponent(month)}` : undefined : screen === 'accounts' ? `/accounting/accounts?${q}` : screen === 'vouchers' ? `/accounting/vouchers?${q}` : screen === 'plans' ? `/accounting/recurring-plans?${q}` : undefined : undefined)
  // Book-specific queries are independent and begin together; never retain a previous book's account choices.
  const accounts = useFinanceResource<FinanceList>(identity, bookId ? `/accounting/accounts?bookId=${encodeURIComponent(bookId)}` : undefined)
  const sourceChecks = useFinanceResource<FinanceList>(identity, bookId && screen === 'periods' ? `/accounting/source-checks?${q}` : undefined)
  const detail = useFinanceResource<FinanceRow>(identity, screen === 'vouchers' && params.voucherId ? `/accounting/vouchers/${params.voucherId}` : undefined)
  const [showCreate, setShowCreate] = useState(false)
  const navigate = (patch: Record<string, string | undefined>) => go('finance', { ...params, tab: 'accounting', ...patch })
  const bookOptions = (context.data?.books ?? []).map(book => ({ value: rowText(book, 'id'), label: rowText(book, 'name') }))
  const accountRows = accounts.data?.items ?? []
  const accountOptions = accountRows.filter(account => account.enabled === true).map(account => ({ value: rowText(account, 'code'), label: `${rowText(account, 'code')} · ${rowText(account, 'name')}` }))
  const list = rowsFrom(resources.data); const selected = detail.data
  const tabs = [['vouchers', '凭证'], ['reports', '账簿与报表'], ['auxiliary', '辅助核算'], ['periods', '期间与月结'], ['sources', '来源待处理'], ['plans', '折旧 · 摊销 · 周期计提'], ...(has('finance.configure') ? [['accounts', '科目'], ['rules', '记账映射'], ['imports', '期初迁移'], ['books', '账套']] : [])]
  return <section className={s.unifiedPage}><div className={s.heading}><div><small>ACCOUNTING · 原始分录与已过账事实</small><h2>核算与结账</h2><p className={s.muted}>只有已过账分录进入正式账簿。审批通过、支付登记和记账是三个独立状态。</p></div></div>
    <nav className={s.tabs} aria-label="核算功能">{tabs.map(([key, label]) => <button key={key} className={screen === key ? s.selected : ''} onClick={() => { setShowCreate(false); navigate({ screen: key, voucherId: undefined }) }}>{label}</button>)}</nav>
    <div className={s.filters}><label>核算账套<select value={bookId} onChange={e => navigate({ bookId: e.target.value, voucherId: undefined })}><option value="">请明确选择账套</option>{bookOptions.map(book => <option key={book.value} value={book.value}>{book.label}</option>)}</select></label><label>会计期间<input type="month" value={month} onChange={e => navigate({ month: e.target.value, voucherId: undefined })} /></label><button onClick={() => { context.reload(); resources.reload(); detail.reload() }}>重新核对</button></div>
    {!!context.error && <p role="alert" className={s.error}>{errorMessage(context.error)}</p>}{!!resources.error && <p role="alert" className={s.error}>{errorMessage(resources.error)}</p>}{!!detail.error && <p role="alert" className={s.error}>{errorMessage(detail.error)}</p>}{!!accounts.error && <p role="alert" className={s.error}>科目未加载：{errorMessage(accounts.error)}</p>}
    {context.loading || resources.loading ? <p role="status" className={s.info}>正在读取真实账套与期间资料…</p> : null}
    {!bookId && screen !== 'books' && <p className={s.warning}>请先选择账套。尚无账套时，由财务配置管理员关联已确认的法人主体；系统不会自动使用第一家门店。</p>}
    {screen === 'books' && has('finance.configure') && <section className={s.panel}><h3>账套与主体</h3><FinanceTable rows={context.data?.books ?? []} columns={[{ key: 'name', label: '账套' }, { key: 'hotelName', label: '组织' }, { key: 'companyName', label: '法人主体' }, { key: 'startMonth', label: '启用期间' }]} label="账套" /><details><summary>关联真实法人并建立账套</summary><FinanceMutation identity={identity} path="/accounting/books" label="建立账套" fields={[{ key: 'hotelId', label: '归属门店 / 集团', type: 'select', options: (context.data?.hotels ?? []).map(row => ({ value: rowText(row, 'id'), label: rowText(row, 'name') })), required: true }, { key: 'companyId', label: '已配置的法人主体', type: 'select', options: (context.data?.companies ?? []).map(row => ({ value: rowText(row, 'id'), label: `${rowText(row, 'name')} · ${rowText(row, 'taxNumber')}` })), required: true, help: '只显示有权限访问的真实主体，请按归属确认；不会自动选择第一家门店或公司。' }, { key: 'name', label: '账套名称', required: true }, { key: 'startMonth', label: '启用月份', type: 'month', required: true }]} build={(v, requestId) => ({ ...v, requestId, startMonth: `${v.startMonth}-01` })} /></details></section>}
    {bookId && screen === 'vouchers' && <section className={s.panel}><div className={s.heading}><h3>记账凭证</h3>{has('finance.accounting.write') && <button className={s.primary} onClick={() => setShowCreate(!showCreate)}>＋ 手工凭证</button>}</div><p className={s.muted}>引用号显示原始 UUID 的前 8 位，完整编号在详情中保留，不冒充正式连续凭证字号。</p><FinanceTable rows={list} columns={[{ key: 'id', label: '凭证引用', render: row => <span title={rowText(row, 'id')}>{shortVoucherId(row.id)}</span> }, { key: 'accountingDate', label: '记账日' }, { key: 'summary', label: '摘要' }, { key: 'debitTotal', label: '借方合计' }, { key: 'creditTotal', label: '贷方合计' }, { key: 'status', label: '状态' }]} label="凭证" onOpen={row => navigate({ voucherId: rowText(row, 'id') })} />
      {showCreate && <VoucherEditor key={bookId} identity={identity} bookId={bookId} accountOptions={accountOptions} onSuccess={() => { setShowCreate(false); resources.reload() }} />}
      {selected && <div key={`${selected.id}:${selected.version}`} className={s.detailPanel}><div className={s.heading}><h3>凭证原始分录</h3><span className={s.pill}>{financeState(selected.status)}</span></div><FinanceFacts data={selected} labels={{ id: '完整凭证引用', summary: '摘要', accountingDate: '记账日', businessDate: '业务日', createdBy: '制单人', reviewedBy: '复核人' }} /><FinanceTable rows={rowsFrom(selected.lines)} columns={[{ key: 'accountCode', label: '科目' }, { key: 'accountName', label: '科目名称' }, { key: 'summary', label: '摘要' }, { key: 'debit', label: '借方' }, { key: 'credit', label: '贷方' }, { key: 'cashFlowCategory', label: '现金流类别', render: row => cashFlowLabels[String(row.cashFlowCategory)] ?? rowText(row, 'cashFlowCategory') }]} label="凭证分录" /><SourceReferences rows={rowsFrom(selected.source)} permissions={permissions} go={go} />
        {selected.status === 'DRAFT' && has('finance.accounting.review') && (selected.createdBy === identity.actorId ? <p className={s.warning}>这是本人制单的凭证，请由另一位有权限的人员独立复核；不能通过切换任职代替独立审核。</p> : <FinanceMutation identity={identity} path={`/accounting/vouchers/${selected.id}/review`} label="独立复核凭证" fields={[]} build={() => ({ expectedVersion: selected.version })} confirm="确认已复核所有分录与来源？制单人不能复核自己的凭证。" />)}
        {selected.status === 'REVIEWED' && has('finance.accounting.post') && <FinanceMutation identity={identity} path={`/accounting/vouchers/${selected.id}/post`} label="正式过账" fields={[]} build={() => ({ expectedVersion: selected.version })} confirm="正式过账后不能原地修改，只能通过调整或红冲保留历史，确定吗？" />}
        {selected.status === 'POSTED' && selected.kind === 'YEAR_CLOSE' && has('finance.accounting.close') && <details><summary>专用年结回退（原年12月须已授权重开）</summary><FinanceMutation identity={identity} path={`/accounting/vouchers/${selected.id}/reverse-year-close`} label="生成独立待审年结回退" fields={[{ key: 'accountingDate', label: '原年12月记账日期', type: 'date', required: true }, { key: 'reason', label: '受控回退依据', type: 'textarea', required: true }]} build={(v, requestId) => ({ ...v, requestId, expectedVersion: selected.version })} onSuccess={row => navigate({ voucherId: rowText(row, 'id'), month: rowText(row, 'accountingDate').slice(0, 7) })} /></details>}
        {selected.status === 'POSTED' && !['YEAR_CLOSE', 'YEAR_CLOSE_REVERSAL'].includes(String(selected.kind)) && has('finance.accounting.post') && <details><summary>红冲 / 结后调整</summary><FinanceMutation identity={identity} path={`/accounting/vouchers/${selected.id}/reverse`} label="生成红冲草稿" fields={[{ key: 'accountingDate', label: '调整记账日', type: 'date', required: true }, { key: 'reason', label: '调整依据', type: 'textarea', required: true }]} build={(v, requestId) => ({ ...v, requestId, expectedVersion: selected.version })} onSuccess={row => navigate({ voucherId: rowText(row, 'id'), month: rowText(row, 'accountingDate').slice(0, 7) })} /></details>}
      </div>}
    </section>}
    {bookId && screen === 'reports' && <AccountingReports key={`${bookId}:${month}`} identity={identity} bookId={bookId} month={month} onVoucher={id => navigate({ screen: 'vouchers', voucherId: id })} />}
    {bookId && screen === 'sources' && <section className={s.panel}><h3>业务来源与记账状态</h3><SourceReferences rows={list} permissions={permissions} go={go} onVoucher={id => navigate({ screen: 'vouchers', voucherId: id })} />{(has('finance.configure') || has('finance.accounting.write')) && list.filter(row => String(row.status).startsWith('PENDING')).map(row => <details key={rowText(row, 'id')}><summary>处理 {rowText(row, 'summary')} · {financeState(row.status)}</summary><FinanceMutation identity={identity} path={`/accounting/source-events/${row.id}/retry`} label="已修复配置，重新生成" fields={[]} build={() => ({})} /></details>)}</section>}
    {bookId && screen === 'plans' && <RecurringPlans key={bookId} identity={identity} permissions={permissions} bookId={bookId} hotelId={bookHotelId} month={month} plans={list} accounts={accountRows} onVoucher={row => navigate({ screen: 'vouchers', voucherId: rowText(row, 'id'), month: rowText(row, 'accountingDate').slice(0, 7) })} />}
    {bookId && screen === 'periods' && <FinanceClosePanel identity={identity} permissions={permissions} bookId={bookId} month={month} onChange={resources.reload} />}
    {bookId && screen === 'periods' && resources.data && <section className={s.panel}><h3>{month} · 月结检查</h3><FinanceFacts data={resources.data} labels={{ status: '期间状态', version: '核对版本' }} /><CheckTable checks={accountingChecks(resources.data)} label="月结检查" /><p className={resources.data.canClose ? s.info : s.warning}>{resources.data.status === 'CLOSED' ? '本期间已关账。关账后出现的迟到来源不会静默改写冻结快照，请保留核对并依法定程序调整。' : resources.data.canClose ? '服务端检查允许结账，仍需有权限的财务明确确认。' : '本期检查未通过，不能通过空数据或仅已付款状态认定可结账。'}</p>
      {has('finance.accounting.close') && <>{(resources.data.canClose === true || resources.data.status === 'CLOSED') && <FinanceMutation key={`${bookId}:${month}:${resources.data.version}`} identity={identity} path={`/accounting/periods/${resources.data.status === 'CLOSED' ? 'reopen' : 'close'}`} label={resources.data.status === 'CLOSED' ? '授权重开期间' : '执行月结并锁期'} fields={[{ key: 'reason', label: '确认及核对说明', type: 'textarea', required: true }]} build={v => ({ ...v, bookId, month, expectedVersion: resources.data?.version })} confirm="确认本期间及来源核对结果？操作将记录审计，不能直接修改历史账。" />}
        {resources.data.status !== 'CLOSED' && <SourceCoverage key={`${bookId}:${month}`} identity={identity} bookId={bookId} month={month} rows={sourceChecks.data?.items ?? []} error={sourceChecks.error} loading={sourceChecks.loading} />}</>}
      {(has('finance.accounting.close') || has('finance.accounting.write')) && <details><summary>年末损益结转</summary><FinanceMutation identity={identity} path="/accounting/periods/year-close" label="生成损益结转草稿" initial={{ year: month.slice(0, 4) }} fields={[{ key: 'year', label: '结转年度', required: true }, { key: 'retainedEarningsAccountCode', label: '财务确认的留存收益科目', type: 'select', options: accountRows.filter(row => row.category === 'EQUITY' && row.enabled === true).map(row => ({ value: rowText(row, 'code'), label: `${rowText(row, 'code')} · ${rowText(row, 'name')}` })), required: true }, { key: 'summary', label: '结转依据', required: true }]} build={(v, requestId) => ({ ...v, requestId, bookId, year: Number(v.year) })} onSuccess={row => navigate({ screen: 'vouchers', voucherId: rowText(row, 'id'), month: rowText(row, 'accountingDate').slice(0, 7) })} /></details>}
    </section>}
    {bookId && screen === 'auxiliary' && <FinanceAuxiliary key={`${bookId}:${month}`} identity={identity} bookId={bookId} month={month} onVoucher={voucherId => navigate({ screen: 'vouchers', voucherId })} />}
    {bookId && screen === 'accounts' && has('finance.configure') && <AccountingConfiguration key={`${bookId}:accounts`} identity={identity} bookId={bookId} mode="accounts" rows={list} accountOptions={accountOptions} />}
    {bookId && screen === 'rules' && has('finance.configure') && <AccountingConfiguration key={`${bookId}:rules`} identity={identity} bookId={bookId} mode="rules" rows={list} accountOptions={accountOptions} />}
    {bookId && screen === 'imports' && has('finance.configure') && <OpeningImports key={bookId} identity={identity} bookId={bookId} accountOptions={accountOptions} />}
  </section>
}

function VoucherEditor({ identity, bookId, accountOptions, onSuccess }: { identity: ApiIdentity; bookId: string; accountOptions: { value: string; label: string }[]; onSuccess: () => void }) {
  const blankLine = () => ({ accountCode: '', debit: '0.00', credit: '0.00', summary: '', cashFlowCategory: '', auxiliary: {} as Record<string, string> })
  const [lines, setLines] = useState([blankLine(), blankLine()])
  const dimensions = useFinanceResource<FinanceRow>(identity, `/accounting/auxiliary/context?bookId=${encodeURIComponent(bookId)}`)
  const fields: FinanceField[] = [{ key: 'businessDate', label: '业务日期', type: 'date', required: true }, { key: 'accountingDate', label: '记账日期', type: 'date', required: true }, { key: 'summary', label: '凭证摘要', required: true }]
  return <details open><summary>新增手工凭证 · 制单后仍需独立审核</summary><FinanceMutation identity={identity} path="/accounting/vouchers" label="保存凭证草稿" fields={fields} build={(v, requestId) => { if (!voucherBalanced(lines) || !lines.every(validAccountingLine)) throw new Error('每行请选择科目，且只能借方或贷方一侧有正金额；合计须平衡且大于0，金额最多两位小数。'); return { ...v, requestId, bookId, lines: lines.map(line => ({ ...line, cashFlowCategory: line.cashFlowCategory || null, auxiliary: Object.fromEntries(Object.entries(line.auxiliary).filter(([, value]) => value !== '')) })) } }} onSuccess={onSuccess}>
    {lines.map((line, index) => <div className={s.invoice} key={index}><div className={s.heading}><strong>分录 {index + 1}</strong><button type="button" disabled={lines.length <= 2} onClick={() => setLines(lines.filter((_, n) => n !== index))}>移除</button></div><div className={s.pair}><label>科目<select required value={line.accountCode} onChange={e => setLines(lines.map((r, n) => n === index ? { ...r, accountCode: e.target.value } : r))}><option value="">请选择科目</option>{accountOptions.map(account => <option key={account.value} value={account.value}>{account.label}</option>)}</select></label>{(['debit', 'credit'] as const).map(key => <label key={key}>{key === 'debit' ? '借方金额' : '贷方金额'}<input inputMode="decimal" value={line[key]} onChange={e => setLines(lines.map((r, n) => n === index ? { ...r, [key]: e.target.value } : r))} /></label>)}<label>现金流类别（仅资金分录）<select value={line.cashFlowCategory} onChange={event => setLines(lines.map((row, n) => n === index ? { ...row, cashFlowCategory: event.target.value } : row))}><option value="">非资金分录不填写</option>{cashFlowOptions.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label></div>{dimensions.data && <FinanceAuxiliaryFields context={dimensions.data} value={line.auxiliary} onChange={auxiliary => setLines(lines.map((row, n) => n === index ? { ...row, auxiliary } : row))} />}</div>)}{!!dimensions.error && <p role="alert" className={s.error}>辅助维度未加载：{errorMessage(dimensions.error)}</p>}<button type="button" disabled={lines.length >= 500} onClick={() => setLines([...lines, blankLine()])}>＋ 增加分录</button><p className={voucherBalanced(lines) && lines.every(validAccountingLine) ? s.info : s.warning}>借方 {exactDecimalTotal(lines.map(l => l.debit)) ?? '金额有误'} / 贷方 {exactDecimalTotal(lines.map(l => l.credit)) ?? '金额有误'} · {voucherBalanced(lines) && lines.every(validAccountingLine) ? '各行有效、借贷平衡' : '分录尚未完整或平衡，不能保存'}</p>
  </FinanceMutation></details>
}

function CheckTable({ checks, label }: { checks: FinanceRow[]; label: string }) {
  return <FinanceTable rows={checks} columns={[
    { key: 'checkName', label: '检查项' },
    { key: 'result', label: '结果', render: row => <span className={row.passed === true ? s.pill : undefined}>{rowText(row, 'result')}</span> },
    { key: 'message', label: '具体说明' },
  ]} label={label} />
}

function SourceReferences({ rows, permissions, go, onVoucher }: { rows: FinanceRow[]; permissions: string[]; go: AppNavigate; onVoucher?: (id: string) => void }) {
  return <section><h4>真实业务来源</h4><FinanceTable rows={rows} columns={[
    { key: 'sourceType', label: '来源类型' }, { key: 'eventKey', label: '经济事件' }, { key: 'businessDate', label: '业务日' },
    { key: 'sourceId', label: '原始来源编号' }, { key: 'amount', label: '金额' }, { key: 'status', label: '记账状态' },
    { key: 'voucherId', label: '凭证', render: row => row.voucherId && onVoucher ? <button type="button" onClick={() => onVoucher(String(row.voucherId))} title={String(row.voucherId)}>打开 {shortVoucherId(row.voucherId)}</button> : '—' },
    { key: 'sourceLink', label: '资料下钻', render: row => {
      const target = accountingSourceTarget(row, permissions)
      return target ? <button type="button" onClick={() => go('finance', target)}>查看原始业务单据 ›</button> : <span className={s.muted}>暂无可授权直达的源单链接</span>
    } },
  ]} label="来源事件" /><p className={s.muted}>只按服务端保存的报销 / 业务单据关系跳转。无此类关系的来源保留编号与依据，不将任意 UUID 当作报销单；访问原件仍须服务端鉴权。</p>
    {rows.filter(row => Object.keys(accountingRecord(row.metadata)).length > 0).map((row, index) => <details key={rowText(row, 'id', String(index))}><summary>来源依据 · {rowText(row, 'sourceType')} · {rowText(row, 'eventKey')}</summary><FinanceFacts data={accountingRecord(row.metadata)} labels={Object.fromEntries(Object.keys(accountingRecord(row.metadata)).map(key => [key, ({ claimId: '报销单引用', lineId: '报销明细', categoryId: '费用类别', treatment: '经济性质', documentId: '业务单据引用', documentType: '业务类型', sourceType: '上游来源类型', sourceId: '上游来源编号', sourceReference: '源资料依据', planId: '周期计划引用', applicantId: '申请人账号' } as Record<string, string>)[key] ?? key]))} /></details>)}
  </section>
}

function AccountingReports({ identity, bookId, month, onVoucher }: { identity: ApiIdentity; bookId: string; month: string; onVoucher: (id: string) => void }) {
  const [mode, setMode] = useState<'live' | 'snapshot'>('live')
  const [type, setType] = useState<AccountingReportType>('trialBalance')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const query = new URLSearchParams({ bookId, month })
  const report = useFinanceResource<FinanceRow>(identity, `/accounting/reports${mode === 'snapshot' ? '/snapshot' : ''}?${query}`)
  const data = report.data
  const reportRows = data ? accountingReportRows(data, type) : []
  const totals = data ? accountingReportTotals(data, type) : {}
  const columns = tableColumns(reportRows).filter(column => type !== 'cashFlowStatement' || column.key !== 'category')
  const exportBinary = async (format: 'xlsx' | 'pdf', all = false) => {
    if (busy || !data) return
    setBusy(true); setError('')
    try {
      const path = mode === 'snapshot' ? `/accounting/reports/snapshot/export?bookId=${encodeURIComponent(bookId)}&month=${month}&format=${format}&reportType=${all ? 'ALL' : type}&expectedReportVersion=${encodeURIComponent(String(data.reportVersion))}` : accountingExportPath(bookId, month, format, all ? 'ALL' : type, data.reportVersion)
      await financeDownload(identity, path, `${mode === 'snapshot' ? '冻结-' : ''}${all ? '完整账簿报表' : accountingReportLabels[type]}-${month}.${format}`)
    } catch (cause) { setError(errorMessage(cause)) } finally { setBusy(false) }
  }
  return <section className={s.panel}><div className={s.heading}><h3>{mode === 'snapshot' ? '关账冻结报表快照' : '同一版本账簿与报表'}</h3>{data && <span className={s.pill}>{({ FINAL: '已关账正式版', PRELIMINARY: '来源齐备 · 未关账', INCOMPLETE: '资料未完整' } as Record<string, string>)[String(data.status)] ?? financeState(data.status)}</span>}</div>
    <div className={s.filters}><button type="button" className={mode === 'live' ? s.selected : ''} onClick={() => { setMode('live'); setError('') }}>实时同源报表</button><button type="button" className={mode === 'snapshot' ? s.selected : ''} onClick={() => { setMode('snapshot'); setError('') }}>读取关账冻结快照</button><button type="button" onClick={report.reload}>刷新当前版本</button></div>
    {report.loading && <p role="status" className={s.info}>正在读取{mode === 'snapshot' ? '冻结快照' : '已过账事实'}…</p>}
    {!!report.error && <p role="alert" className={s.error}>{errorMessage(report.error)}{mode === 'snapshot' && ' 尚未正式关账时没有冻结快照，不能以实时报告冒充。'}</p>}
    {error && <p role="alert" className={s.error}>{error}</p>}
    {data && <><FinanceFacts data={data} labels={{ month: '会计期间', reportVersion: '取数版本', generatedAt: '生成时间', ...(mode === 'snapshot' ? { frozenAt: '关账冻结时间' } : {}), dateBasis: '日期口径', currency: '币种', amountUnit: '单位' }} />
      {data.detailAccess === 'PAYROLL_DETAILS_RESTRICTED_AGGREGATES_ONLY' && <p className={s.info}>当前账号未获个人薪酬明细权限：账簿中的工资来源明细已隐去，科目汇总及三表仍采用真实已过账总额；导出沿用相同权限。</p>}
      <p className={s.warning}>缺失期初、科目映射或来源核对时，只能作为不完整报告核对，不代表已完成决算。未导入历史数据不生成同比。</p>
      <div className={s.filters}><label>账簿 / 报表<select value={type} onChange={event => setType(event.target.value as AccountingReportType)}>{Object.entries(accountingReportLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
        <button type="button" disabled={busy} onClick={() => void exportBinary('xlsx')}>导出当前 XLSX</button><button type="button" disabled={busy} onClick={() => void exportBinary('pdf')}>导出当前 PDF</button><button type="button" disabled={busy} onClick={() => void exportBinary('xlsx', true)}>完整报表 XLSX</button>
        <button type="button" disabled={busy} onClick={() => void exportBinary('pdf', true)}>完整报表 PDF</button>
        <button type="button" onClick={() => exportFinanceCsv([...reportRows, ...Object.entries(totals).map(([name, amount]) => ({ summary: totalLabels[name] ?? name, amount }))].map(row => ({ period: month, reportVersion: data.reportVersion, ...row })), [{ key: 'period', label: '期间' }, { key: 'reportVersion', label: '取数版本' }, ...columns, ...(!columns.some(column => column.key === 'summary') ? [{ key: 'summary', label: '汇总项' }] : []), ...(!columns.some(column => column.key === 'amount') ? [{ key: 'amount', label: '汇总金额' }] : [])], `${accountingReportLabels[type]}-${month}-${mode}.csv`)}>当前屏幕 CSV</button>
      </div>
      {busy && <p role="status" className={s.info}>正在生成同版本真实文件…</p>}
      {mode === 'snapshot' && <p className={s.info}>冻结快照只读。XLSX、PDF和屏幕使用同一冻结版本与当前明细权限，不以实时数据替换关账结果。</p>}
      {Object.keys(totals).length > 0 && <FinanceFacts data={totals} labels={Object.fromEntries(Object.keys(totals).map(key => [key, totalLabels[key] ?? key]))} />}
      <FinanceTable rows={reportRows} columns={columns} label={accountingReportLabels[type]} onOpen={['detailLedger', 'cashJournal'].includes(type) ? row => { if (typeof row.voucherId === 'string') onVoucher(row.voucherId) } : undefined} />
      <details open><summary>来源完整性与月结检查</summary><CheckTable checks={accountingChecks(data.completeness)} label="完整性检查" /></details>
    </>}
  </section>
}

function SourceCoverage({ identity, bookId, month, rows, loading, error }: { identity: ApiIdentity; bookId: string; month: string; rows: FinanceRow[]; loading: boolean; error?: unknown }) {
  const [selectedType, setSelectedType] = useState('')
  const selected = rows.find(row => row.sourceType === selectedType)
  const fields: FinanceField[] = [
    { key: 'sourceType', label: '来源类型（与核对资料一致）', required: true },
    { key: 'expectedCount', label: '应有条数', type: 'number', required: true }, { key: 'actualCount', label: '已核对条数', type: 'number', required: true },
    { key: 'note', label: '来源清单与真实核对依据', type: 'textarea', required: true }, { key: 'complete', label: '确认本来源齐备', type: 'checkbox' },
  ]
  return <details><summary>来源覆盖确认（保留当前版本，可继续补齐）</summary>
    {loading && <p role="status">正在读取来源核对版本…</p>}{!!error && <p role="alert" className={s.error}>{errorMessage(error)}</p>}
    <FinanceTable rows={rows} columns={[{ key: 'sourceType', label: '来源' }, { key: 'expectedCount', label: '应有条数' }, { key: 'actualCount', label: '已核对条数' }, { key: 'complete', label: '齐备', render: row => row.complete === true ? '已确认' : '待补齐' }, { key: 'note', label: '核对依据' }, { key: 'version', label: '版本' }]} label="来源覆盖" onOpen={row => setSelectedType(String(row.sourceType))} />
    <label>编辑已有来源 / 新增<select value={selectedType} onChange={event => setSelectedType(event.target.value)}><option value="">新增来源核对</option>{rows.map(row => <option key={String(row.sourceType)} value={String(row.sourceType)}>{String(row.sourceType)} · 版本 {String(row.version)}</option>)}</select></label>
    {!loading && !error && <FinanceMutation key={`${selectedType}:${selected?.version ?? -1}`} identity={identity} path="/accounting/source-checks" label="保存来源核对" initial={selected ? { sourceType: String(selected.sourceType), expectedCount: String(selected.expectedCount ?? ''), actualCount: String(selected.actualCount ?? ''), note: String(selected.note ?? ''), complete: String(selected.complete === true) } : { complete: 'false' }} fields={fields} build={values => {
      const current = rows.find(row => row.sourceType === values.sourceType)
      if (!/^\d+$/.test(values.expectedCount) || !/^\d+$/.test(values.actualCount)) throw new Error('应有及已核对条数必须是非负整数。')
      return { ...values, bookId, month, expectedCount: Number(values.expectedCount), actualCount: Number(values.actualCount), complete: values.complete === 'true', expectedVersion: current?.version ?? -1 }
    }} />}
  </details>
}

const recurringLabels: Record<string, string> = { DEPRECIATION: '折旧', AMORTIZATION: '摊销', ALLOCATION: '分摊', ACCRUAL: '计提' }
function RecurringPlans({ identity, permissions, bookId, hotelId, month, plans, accounts, onVoucher }: { identity: ApiIdentity; permissions: string[]; bookId: string; hotelId: string; month: string; plans: FinanceRow[]; accounts: FinanceRow[]; onVoucher: (row: FinanceRow) => void }) {
  const has = (permission: string) => permissions.includes('*') || permissions.includes(permission)
  const accountOptions = accounts.filter(row => row.enabled === true && row.cashAccount !== true).map(row => ({ value: rowText(row, 'code'), label: `${rowText(row, 'code')} · ${rowText(row, 'name')}` }))
  const [showNew, setShowNew] = useState(false)
  return <section className={s.panel}><div className={s.heading}><h3>折旧、摊销与周期任务</h3>{has('finance.configure') && <button type="button" className={s.primary} onClick={() => setShowNew(!showNew)}>＋ 建立真实来源计划</button>}</div>
    <p className={s.muted}>计划仅生成本月待审凭证，不会自动复核、过账或付款；须逐月执行，尾月按剩余额度截断，暂停保留既有事实。</p>
    <FinanceTable rows={plans} columns={[{ key: 'kind', label: '性质', render: row => recurringLabels[String(row.kind)] ?? rowText(row, 'kind') }, { key: 'sourceReference', label: '真实源资料' }, { key: 'startMonth', label: '开始月份' }, { key: 'endMonth', label: '结束月份' }, { key: 'monthlyAmount', label: '月度额度' }, { key: 'totalAmount', label: '总额上限' }, { key: 'allocatedAmount', label: '已执行额度' }, { key: 'debitAccountCode', label: '借方科目' }, { key: 'creditAccountCode', label: '贷方科目' }, { key: 'active', label: '状态', render: row => row.active === true ? '启用' : '已暂停' }]} label="周期计划" />
    {showNew && <RecurringPlanEditor identity={identity} bookId={bookId} hotelId={hotelId} accountOptions={accountOptions} permissions={permissions} onSuccess={() => setShowNew(false)} />}
    {plans.map(plan => <details key={`${plan.id}:${plan.version}`}><summary>{recurringLabels[String(plan.kind)] ?? String(plan.kind)} · {rowText(plan, 'sourceReference')} · {rowText(plan, 'summary')}</summary>
      <FinanceFacts data={plan} labels={{ id: '计划编号', sourceType: '来源类型', sourceId: '来源编号', monthlyAmount: '每月确认金额', totalAmount: '计划总额', allocatedAmount: '已执行金额', version: '当前版本' }} />
      {plan.active === true && has('finance.accounting.write') && <FinanceMutation identity={identity} path={`/accounting/recurring-plans/${plan.id}/run`} label="执行本月并生成待审凭证" initial={{ month }} fields={[{ key: 'month', label: '执行月份（须顺月）', type: 'month', required: true }]} build={(values, requestId) => ({ requestId, month: values.month, expectedVersion: plan.version })} onSuccess={onVoucher} confirm="确认本期真实依据和金额？仅生成草稿，仍须独立复核与过账。" />}
      {has('finance.configure') && <FinanceMutation identity={identity} path={`/accounting/recurring-plans/${plan.id}/active`} label={plan.active === true ? '暂停计划' : '恢复计划'} fields={[{ key: 'reason', label: '暂停 / 恢复依据', type: 'textarea', required: true }]} build={values => ({ ...values, active: plan.active !== true, expectedVersion: plan.version })} />}
    </details>)}
  </section>
}

function RecurringPlanEditor({ identity, bookId, hotelId, accountOptions, permissions, onSuccess }: { identity: ApiIdentity; bookId: string; hotelId: string; accountOptions: Option[]; permissions: string[]; onSuccess: () => void }) {
  const [kind, setKind] = useState('DEPRECIATION')
  const [page, setPage] = useState(0)
  const canReadSources = ['*', 'finance.operations.read', 'finance.operations.write', 'finance.operations.review', 'finance.read', 'finance.review', 'finance.pay'].some(permission => permissions.includes(permission))
  const type = kind === 'DEPRECIATION' ? 'ASSET' : 'PREPAYMENT'
  const sources = useFinanceResource<FinanceList>(identity, canReadSources && hotelId ? `/operations/documents?type=${type}&hotelId=${encodeURIComponent(hotelId)}&page=${page}` : undefined)
  const candidates = (sources.data?.items ?? []).filter(row => row.status === 'APPROVED' && row.hotelId === hotelId)
  const fields: FinanceField[] = [
    { key: 'sourceId', label: type === 'ASSET' ? '已审核资产原始单据' : '已审核预付原始单据', type: 'select', required: true, options: candidates.map(row => ({ value: rowText(row, 'id'), label: `${rowText(row, 'title')} · ${rowText(row, 'sourceReference')} · ${rowText(row, 'amount')}` })) },
    { key: 'startMonth', label: '开始月份', type: 'month', required: true }, { key: 'endMonth', label: '结束月份', type: 'month', required: true },
    { key: 'monthlyAmount', label: '财务确认每月金额', type: 'number', required: true }, { key: 'totalAmount', label: '财务确认总额上限', type: 'number', required: true },
    { key: 'debitAccountCode', label: '借方科目（非资金科目）', type: 'select', options: accountOptions, required: true }, { key: 'creditAccountCode', label: '贷方科目（非资金科目）', type: 'select', options: accountOptions, required: true },
    { key: 'summary', label: '核算依据及摘要', type: 'textarea', required: true },
  ]
  return <div className={s.detailPanel}><h4>建立周期计划</h4><p className={s.warning}>仅支持有真实批准来源的资产折旧与预付摊销。下拉金额为原单参考，不等于可计提金额：资产须扣残值及已计提，预付以实际净支付或已记账承接来源为基础；服务端按批准的政策和期间核验。普通合同审批不等于费用已发生，合同计提 / 分摊尚需独立会计基础确认，当前不开放创建；不猜测税率。</p>
    <label>计划性质<select value={kind} onChange={event => { setKind(event.target.value); setPage(0) }}><option value="DEPRECIATION">资产折旧</option><option value="AMORTIZATION">预付摊销</option></select></label>
    {!hotelId && <p className={s.warning}>账套所属组织尚未读取成功，请刷新后重试。</p>}
    {!canReadSources && <p className={s.warning}>当前账号没有读取来源业务单据的权限，请先由后台配置授权，不能手工编造来源编号。</p>}
    {sources.loading && <p role="status">正在读取真实已审来源…</p>}{!!sources.error && <p role="alert" className={s.error}>{errorMessage(sources.error)}</p>}
    {sources.data && <div className={s.filters}><span>本组织该类型源单共 {sources.data.total} 条，当前第 {page + 1} 页；仅已审单据可选。</span><button type="button" disabled={page === 0 || sources.loading} onClick={() => setPage(value => value - 1)}>上一页来源</button><button type="button" disabled={(page + 1) * 50 >= sources.data.total || sources.loading} onClick={() => setPage(value => value + 1)}>下一页来源</button></div>}
    {canReadSources && hotelId && !sources.loading && !sources.error && (candidates.length ? <FinanceMutation key={`${bookId}:${kind}:${page}`} identity={identity} path="/accounting/recurring-plans" label="确认周期计划" fields={fields} build={(values, requestId) => {
      const source = candidates.find(row => row.id === values.sourceId)
      if (!source) throw new Error('来源单据已变化，请重新选择。')
      return { ...values, requestId, bookId, kind, sourceType: `FO_${type}`, sourceReference: rowText(source, 'sourceReference', rowText(source, 'title')) }
    }} onSuccess={onSuccess} /> : <p className={s.empty}>本页暂无可选已审来源。可翻页查找，或先在业务中心建立并审核本组织资产 / 预付单据。系统不会创建虚构源单。</p>)}
  </div>
}

function AccountingConfiguration({ identity, bookId, mode, rows, accountOptions }: { identity: ApiIdentity; bookId: string; mode: 'accounts' | 'rules'; rows: FinanceRow[]; accountOptions: Option[] }) {
  const [selectedId, setSelectedId] = useState('')
  const changes = useFinanceResource<FinanceList>(identity, `/control/ledger-changes?bookId=${encodeURIComponent(bookId)}`)
  const selected = rows.find(row => row.id === selectedId)
  const accountFields: FinanceField[] = [
    { key: 'code', label: '科目编码', required: true }, { key: 'name', label: '科目名称', required: true },
    { key: 'category', label: '类别', type: 'select', options: ['ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'].map((value, index) => ({ value, label: ['资产', '负债', '权益', '收入', '费用'][index] })), required: true },
    { key: 'normalSide', label: '余额方向', type: 'select', options: [{ value: 'DEBIT', label: '借方' }, { value: 'CREDIT', label: '贷方' }], required: true },
    { key: 'reportLine', label: '财务确认的报表行', required: true }, { key: 'requiredDimensionsText', label: '必填辅助维度（逗号分隔：DEPARTMENT、EMPLOYEE、PARTY、PROJECT；留空不强制）' }, { key: 'cashAccount', label: '现金 / 银行科目', type: 'checkbox' }, { key: 'enabled', label: '启用', type: 'checkbox' },
  ]
  const ruleFields: FinanceField[] = [
    { key: 'sourceType', label: '真实来源类型', required: true }, { key: 'eventKey', label: '经济事件键（与来源待处理一致）', required: true },
    { key: 'debitAccountCode', label: '借方科目', type: 'select', options: accountOptions, required: true }, { key: 'creditAccountCode', label: '贷方科目', type: 'select', options: accountOptions, required: true },
    { key: 'cashFlowCategory', label: '现金流分类（涉及现金必须确认）', type: 'select', options: cashFlowOptions }, { key: 'enabled', label: '启用', type: 'checkbox' },
  ]
  const initial: Record<string, string> = selected ? Object.fromEntries((mode === 'accounts' ? accountFields : ruleFields).map(field => [field.key, String((field.key === 'requiredDimensionsText' ? (Array.isArray(selected.requiredDimensions) ? selected.requiredDimensions.join(',') : '') : selected[field.key]) ?? (field.key === 'debitAccountCode' ? selected.debitCode : field.key === 'creditAccountCode' ? selected.creditCode : '') ?? '')])) : { enabled: 'true', cashAccount: 'false' }
  return <section className={s.panel}><h3>{mode === 'accounts' ? '科目与报表映射' : '经济事件记账映射'}</h3>
    <p className={s.muted}>{mode === 'accounts' ? '科目发生记账后，不能修改报表性质、方向或资金属性。使用新科目及调整凭证保留历史。' : '规则只生成真实来源的待审凭证，不自动过账；不同经济事件分别配置，不由金额猜测税额。'}</p>
    <FinanceTable rows={rows} columns={mode === 'accounts' ? [{ key: 'code', label: '科目编码' }, { key: 'name', label: '名称' }, { key: 'category', label: '类别' }, { key: 'normalSide', label: '方向' }, { key: 'reportLine', label: '报表行' }, { key: 'enabled', label: '启用', render: row => row.enabled === true ? '启用' : '停用' }, { key: 'version', label: '版本' }] : tableColumns(rows)} label={mode === 'accounts' ? '科目' : '来源记账规则'} onOpen={row => setSelectedId(String(row.id))} />
    <div className={s.heading}><h4>{selected ? `编辑现有配置 · 版本 ${selected.version}` : '新增配置'}</h4>{selected && <button type="button" onClick={() => setSelectedId('')}>改为新增</button>}</div>
    {!!changes.error && <p role="alert" className={s.error}>{errorMessage(changes.error)}</p>}
    <FinanceTable rows={(changes.data?.items ?? []).filter(r => r.kind === (mode === 'accounts' ? 'ACCOUNT' : 'RULE'))} columns={[{ key: 'reason', label: '修订依据' }, { key: 'payload', label: '待发布配置' }, { key: 'status', label: '状态' }, { key: 'createdBy', label: '提出人' }, { key: 'publish', label: '独立发布', render: r => r.status === 'DRAFT' && r.createdBy !== identity.actorId ? <FinanceMutation identity={identity} path={`/control/ledger-changes/${r.id}/publish`} label="核对后发布" fields={[{ key: 'note', label: '发布核对依据', type: 'textarea', required: true }]} build={v => ({ expectedVersion: r.version, note: v.note })} /> : '—' }]} label="配置修订版本" />
    <FinanceMutation key={`${mode}:${selected?.id ?? 'new'}:${selected?.version ?? -1}`} identity={identity} path={`/control/ledger-changes/${mode}`} label="保存待独立发布草稿" fields={[...(mode === 'accounts' ? accountFields : ruleFields), { key: 'reason', label: '本次配置变更依据', type: 'textarea', required: true }]} initial={initial} build={(values, requestId) => {
      if (selected && (mode === 'accounts' ? values.code !== selected.code : values.sourceType !== selected.sourceType || values.eventKey !== selected.eventKey)) throw new Error('不能修改既有配置的编码 / 事件身份；请使用“改为新增”建立另一条配置。')
      const { reason, requiredDimensionsText, ...fields } = values
      const requiredDimensions = [...new Set((requiredDimensionsText || '').split(/[,，\s]+/).filter(Boolean))]
      if (mode === 'accounts' && requiredDimensions.some(key => !['DEPARTMENT', 'EMPLOYEE', 'PARTY', 'PROJECT'].includes(key))) throw new Error('辅助维度只允许 DEPARTMENT / EMPLOYEE / PARTY / PROJECT，不按自由名称建档。')
      return mode === 'accounts'
        ? { requestId, reason, account: { ...fields, bookId, requiredDimensions, cashAccount: values.cashAccount === 'true', enabled: values.enabled === 'true', expectedVersion: selected?.version ?? -1 } }
        : { requestId, reason, rule: { ...fields, bookId, cashFlowCategory: values.cashFlowCategory || null, enabled: values.enabled === 'true', expectedVersion: selected?.version ?? -1 } }
    }} />
  </section>
}

function OpeningImports({ identity, bookId, accountOptions }: { identity: ApiIdentity; bookId: string; accountOptions: Option[] }) {
  const [balances, setBalances] = useState([{ accountCode: '', debit: '0.00', credit: '0.00' }])
  const [result, setResult] = useState<FinanceRow>()
  const reconciliation = accountingRecord(result?.reconciliation)
  const fields: FinanceField[] = [
    { key: 'sourceReference', label: '真实来源文件 / 迁移批次', required: true },
    { key: 'mode', label: '操作模式', type: 'select', options: [{ value: 'PREVIEW', label: '仅校验预览（不入账）' }, { value: 'COMMIT', label: '正式原子导入' }], required: true },
    { key: 'reference', label: '财务核对依据', type: 'textarea', required: true },
  ]
  return <section className={s.panel}><h3>期初迁移与核对</h3><p className={s.warning}>仅导入已确认的科目期初余额，不把资产、库存或未清往来资料当作子账已迁移。原系统真实数据须逐项核对；一旦过账或已有正式期初批次，不允许覆盖。</p>
    <FinanceMutation identity={identity} path="/accounting/imports" label="执行所选校验 / 导入" fields={fields} initial={{ mode: 'PREVIEW' }} build={(values, requestId) => {
      const codes = new Set<string>()
      for (const line of balances) {
        if (!line.accountCode || codes.has(line.accountCode)) throw new Error('请选择真实科目，同一科目期初不能重复。')
        codes.add(line.accountCode)
        if (!/^\d{1,14}(\.\d{1,2})?$/.test(line.debit) || !/^\d{1,14}(\.\d{1,2})?$/.test(line.credit) || (/[1-9]/.test(line.debit) && /[1-9]/.test(line.credit))) throw new Error('期初每科目仅一侧可有正金额，最多两位小数；零余额可两侧为零。')
      }
      if (values.mode === 'COMMIT' && !window.confirm('确认已完成真实期初核对并正式导入？该批次不能覆盖，之后调整须保留凭证历史。')) throw new Error('已取消正式导入，输入保留。')
      return { requestId, bookId, mode: values.mode, sourceReference: values.sourceReference, balances, reconciliation: { reference: values.reference } }
    }} onSuccess={setResult}>
      {balances.map((line, index) => <div className={s.invoice} key={index}><div className={s.heading}><strong>科目期初 {index + 1}</strong><button type="button" disabled={balances.length <= 1} onClick={() => setBalances(rows => rows.filter((_, n) => n !== index))}>移除期初行</button></div><div className={s.pair}><label>真实科目<select value={line.accountCode} required onChange={event => setBalances(rows => rows.map((row, n) => n === index ? { ...row, accountCode: event.target.value } : row))}><option value="">请选择科目</option>{accountOptions.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>{(['debit', 'credit'] as const).map(key => <label key={key}>{key === 'debit' ? '期初借方' : '期初贷方'}<input inputMode="decimal" required value={line[key]} onChange={event => setBalances(rows => rows.map((row, n) => n === index ? { ...row, [key]: event.target.value } : row))} /></label>)}</div></div>)}
      <button type="button" onClick={() => setBalances(rows => [...rows, { accountCode: '', debit: '0.00', credit: '0.00' }])}>＋ 新增科目余额</button><p className={s.info}>借方 {exactDecimalTotal(balances.map(row => row.debit)) ?? '金额有误'} · 贷方 {exactDecimalTotal(balances.map(row => row.credit)) ?? '金额有误'}；预览不写账，正式导入仍由服务端整批校验。</p>
    </FinanceMutation>
    {result && <div role="status"><h4>{result.committed === true ? '正式期初已由服务端确认' : '校验预览（未入账）'}</h4><FinanceFacts data={result} labels={{ mode: '本次模式', committed: '是否正式写入', batchId: '正式批次引用' }} /><FinanceFacts data={reconciliation} labels={{ debit: '借方合计', credit: '贷方合计', difference: '借贷差额', balanced: '是否平衡', sourceReference: '真实来源' }} /><FinanceTable rows={rowsFrom(reconciliation.rows)} columns={[{ key: 'accountCode', label: '科目' }, { key: 'debit', label: '期初借方' }, { key: 'credit', label: '期初贷方' }]} label="服务端核对余额" /></div>}
  </section>
}
