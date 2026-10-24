import { useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import type { AppNavigate } from '../../app/routeConfig'
import type { RouteParams } from '../../domain'
import { FinanceFacts, FinanceMutation, FinanceTable } from './FinanceData'
import { useFinanceResource } from './useFinanceResource'
import { errorMessage } from './api'
import { rowText, type FinanceRow } from './unifiedModel'
import { approvedLoanRows, listRows, loanClearCandidateRows, loanClearRequest, loanExitStatus } from './controlModel'
import s from './finance.module.css'

type Props = { identity: ApiIdentity; permissions: string[]; params: RouteParams; go: AppNavigate; context?: FinanceRow }
type Paged = { items: FinanceRow[]; total: number; pageSize: number }
type Candidates = { loan: FinanceRow; borrowerId: string; items: FinanceRow[]; hasMore: boolean }
export default function FinanceLoans(props: Props) {
  return <LoanContent key={`${props.identity.tenantId}:${props.identity.actorId}:${props.identity.businessActorAssignmentId ?? ''}`} {...props} />
}
function LoanContent({ identity, permissions, params, go, context }: Props) {
  const has = (permission: string) => permissions.includes('*') || permissions.includes(permission)
  const canReview = has('finance.review'); const canRead = has('finance.read')
  const allowed = canReview || canRead || has('finance.pay') || has('finance.configure')
  const ownContext = useFinanceResource<FinanceRow>(identity, allowed && !context ? '/control/context' : undefined)
  const [hotelId, setHotel] = useState(''); const [page, setPage] = useState(0); const [claimId, setClaim] = useState('')
  const [exitRequested, setExitRequested] = useState(false)
  const loans = useFinanceResource<Paged>(identity, allowed ? `/operations/documents?type=LOAN&page=${page}${hotelId ? `&hotelId=${encodeURIComponent(hotelId)}` : ''}` : undefined)
  const selectedId = params.loanId
  const detail = useFinanceResource<FinanceRow>(identity, allowed && selectedId ? `/operations/documents/${encodeURIComponent(selectedId)}` : undefined)
  const actualLoan = detail.data?.type === 'LOAN' ? detail.data : undefined
  const candidates = useFinanceResource<Candidates>(identity, canReview && selectedId && actualLoan?.status === 'APPROVED' ? `/control/loans/${encodeURIComponent(selectedId)}/clear-candidates` : undefined)
  const loan = candidates.data?.loan?.id === actualLoan?.id ? candidates.data?.loan : actualLoan
  const details = loan?.details && typeof loan.details === 'object' && !Array.isArray(loan.details) ? loan.details as FinanceRow : {}
  const borrower = candidates.data?.borrowerId ?? (typeof details.borrowerAccountId === 'string' ? details.borrowerAccountId : '')
  const actualHotel = typeof loan?.hotelId === 'string' ? loan.hotelId : ''
  const exit = useFinanceResource<FinanceRow>(identity, canRead && exitRequested && borrower && actualHotel ? `/control/loans/exit-check?hotelId=${encodeURIComponent(actualHotel)}&accountId=${encodeURIComponent(borrower)}` : undefined)
  const claims = loanClearCandidateRows(candidates.data?.items ?? []); const selectedClaim = claims.find(row => row.id === claimId)
  const hotels = listRows((context ?? ownContext.data)?.hotels)
  const names = new Map(listRows((context ?? ownContext.data)?.accounts).map(row => [rowText(row, 'id'), rowText(row, 'displayName')]))
  const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
  const reload = () => { loans.reload(); detail.reload(); candidates.reload(); exit.reload() }
  const open = (id?: string) => { setClaim(''); setExitRequested(false); go('finance', { tab: 'control', screen: 'loans', loanId: id }) }
  if (!allowed) return <p role="alert" className={s.error}>当前任职没有借款核对或财务审核权限；本页不会读取其他员工借款。</p>
  return <section className={s.form}><header className={s.heading}><div><h3>借款核销与离职未清核对</h3><p className={s.muted}>实际发放、归还、费用抵扣和资金差错分别留痕；批准但未发放的额度不是员工欠款。</p></div><button type="button" onClick={reload}>刷新实际净余额</button></header>
    <p className={s.warning}>费用报销冲借款是非现金清偿，不会再次付钱或发起银行转账。只能由后台配置的独立财务审核人员，用同借款人、同组织、同法人且已审批的当期费用报销办理。</p>
    {[ownContext.error, loans.error, detail.error, candidates.error, exit.error].filter(Boolean).map((error, index) => <p key={index} role="alert" className={s.error}>{errorMessage(error)}</p>)}
    {!selectedId ? <><div className={s.filters}><label>实际归属组织<select value={hotelId} onChange={event => { setHotel(event.target.value); setPage(0) }}><option value="">权限范围内全部组织</option>{hotels.map(row => <option key={rowText(row, 'id')} value={rowText(row, 'id')}>{rowText(row, 'name')}</option>)}</select></label><button type="button" onClick={() => go('finance', { tab: 'operations', type: 'LOAN' })}>进入借款原单与发放流程</button></div>
      {loans.loading ? <p role="status">正在读取实际借款及净清偿余额…</p> : <><FinanceTable rows={loans.data?.items ?? []} label="真实借款来源（当前页）" columns={[{ key: 'title', label: '借款事由' }, { key: 'hotelName', label: '归属组织' }, { key: 'status', label: '实际审批状态' }, { key: 'amount', label: '申请批准额度（不是实欠）' }, { key: 'paidTotal', label: '有效实际发放' }, { key: 'refundTotal', label: '有效发放退款' }, { key: 'repaidTotal', label: '有效现金归还' }, { key: 'writtenOffTotal', label: '已确认费用核销' }, { key: 'outstandingAmount', label: '实际净未清' }, { key: 'dueDate', label: '归还期限' }]} onOpen={row => open(rowText(row, 'id'))} />
        <div className={s.actions}><button type="button" disabled={page === 0} onClick={() => setPage(value => Math.max(0, value - 1))}>上一页</button><span>第 {page + 1} 页 · 服务端可见共 {loans.data?.total ?? '未读取'} 条，本页可核销已批借款 {approvedLoanRows(loans.data?.items ?? []).length} 条</span><button type="button" disabled={!loans.data || (page + 1) * loans.data.pageSize >= loans.data.total} onClick={() => setPage(value => value + 1)}>下一页</button></div></>}
    </> : <section className={s.panel}><button type="button" onClick={() => open()}>返回真实借款列表</button>{detail.loading || candidates.loading ? <p role="status">正在核对原单版本、真实借款人及同源费用…</p> : null}
      {detail.data && !actualLoan ? <p role="alert" className={s.error}>该来源不是借款单，不能在借款核销入口操作。</p> : null}
      {loan ? <><FinanceFacts data={{ ...loan, borrowerName: names.get(borrower) ?? (borrower ? `实际借款账号 ${borrower}` : '未读取实际借款人，不可核销') }} labels={{ title: '原借款事由', borrowerName: '实际借款员工（不以申请人代替）', hotelName: '归属组织', companyName: '冻结法人主体', status: '审批状态', version: '实际原单版本', amount: '原申请批准额度（非未清余额）', paidTotal: '有效已发放', refundTotal: '有效发放退款', repaidTotal: '有效实际归还', writtenOffTotal: '已确认费用核销', outstandingAmount: '实际净未清余额', dueDate: '归还期限', balanceBasis: '服务端未清口径' }} />
        <div className={s.actions}><button type="button" onClick={() => go('finance', { tab: 'operations', type: 'LOAN', documentId: rowText(loan, 'id') })}>查看原单、原件和审批链</button>{canRead ? <button type="button" disabled={!borrower || !actualHotel || exit.loading} onClick={() => { setExitRequested(true); exit.reload() }}>核对该员工在本组织的离职未清借款</button> : <p className={s.muted}>完整员工未清检查需独立的财务查询权限；当前账号不能借此扩大查询范围。</p>}</div>
        {exitRequested && exit.data ? <section className={s.voucher}><h3>本组织借款未清核对（只读）</h3><p role="status" className={exit.data.hasOutstanding === false ? s.info : s.warning}>{loanExitStatus(exit.data)}</p><p className={s.muted}>{rowText(exit.data, 'note')} 本查询只覆盖所选组织，员工停用或离职最终检查仍会核对其全部实际借款；本页不办理离职、不伪造还款。</p><FinanceTable rows={listRows(exit.data.items)} label="该员工本组织实际借款" columns={[{ key: 'title', label: '借款原单' }, { key: 'amount', label: '原批准额度' }, { key: 'dueDate', label: '期限' }, { key: 'outstanding', label: '有效实际未清余额' }]} /></section> : null}
        <h3>实际资金与非现金清偿历史</h3><FinanceTable rows={listRows(loan.settlements)} label="原始动作及有效净额" columns={[{ key: 'action', label: '真实动作', render: row => ({ PAYMENT: '实际发放', REFUND: '发放退款', REPAYMENT: '实际现金归还', WRITEOFF: '费用核销（非现金）' } as Record<string, string>)[rowText(row, 'action')] ?? rowText(row, 'action') }, { key: 'amount', label: '原事实金额（保留不改）' }, { key: 'effectiveAmount', label: '差错更正后有效金额' }, { key: 'correctedAmount', label: '已确认差错更正' }, { key: 'occurredOn', label: '原业务日期' }, { key: 'reference', label: '原始依据' }, { key: 'note', label: '核对说明' }]} />
        {loan.status !== 'APPROVED' ? <p className={s.info}>此借款尚未完成审批，不能以费用核销；请进入原单完成真实审批及发放。</p> : !canReview ? <p className={s.info}>当前为只读核对。办理非现金核销还需财务审核权限和当前后台独立审核人配置。</p> : candidates.data && !candidates.error ? <section className={s.voucher}><h3>选择该借款人已批费用，部分或全额冲借款</h3><p className={s.muted}>候选项由服务端核对同员工、同法人、同组织及当期费用性质；仅显示尚未被付款或核销清偿的金额。未清余额、任职权限及原单版本会在提交时再次校验。</p>
          {candidates.data.hasMore ? <p className={s.warning}>候选扫描超过200条，本页不是全部可用费用；请先处理可见真实费用后刷新核对，不会把未展示项当作零。</p> : null}
          {claims.length ? <label>真实已批费用报销<select value={claimId} onChange={event => setClaim(event.target.value)}><option value="">请选择服务端核对后的费用报销</option>{claims.map(row => <option key={rowText(row, 'id')} value={rowText(row, 'id')}>{rowText(row, 'title')} · 批准 {rowText(row, 'approvedAmount')} · 待清偿 {rowText(row, 'unsettledAmount')} · V{rowText(row, 'version')}</option>)}</select></label> : <p className={s.info}>当前未读取到可核销的已批费用。不用其他员工、其他法人或资产库存事项代替，也不把空结果当作已结清。</p>}
          {selectedClaim ? <FinanceMutation key={`${loan.id}:${loan.version}:${selectedClaim.id}:${selectedClaim.version}:${selectedClaim.unsettledAmount}:${loan.outstandingAmount}`} identity={identity} path={`/control/loans/${encodeURIComponent(rowText(loan, 'id'))}/clear-claims`} label="确认以真实费用冲抵借款（不再付款）" fields={[{ key: 'amount', label: '本次非现金核销金额（可部分核销，精确到分）', required: true }, { key: 'occurredOn', label: '真实费用核销日期', type: 'date', required: true }, { key: 'note', label: '费用与借款的实际关联、用途及核对依据', type: 'textarea', required: true }]} build={(values, requestId) => loanClearRequest(loan, selectedClaim, values, requestId, today)} onSuccess={() => { setClaim(''); reload() }} confirm="确认由当前独立财务审核人，以该员工已批费用冲抵实际借款？这是非现金清偿，不会再次付款；原借款、原报销和核销事实均保留。"><FinanceFacts data={selectedClaim} labels={{ title: '实际被清偿的费用报销', approvedAmount: '已批准费用金额', unsettledAmount: '当前待清偿（扣除实际付款及其他核销）', version: '冻结报销版本' }} /></FinanceMutation> : null}
        </section> : null}
      </> : null}
    </section>}
  </section>
}
