import { useRef, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { errorMessage, financeDownload, financeRequest } from './api'
import { EvidencePreview } from './EvidencePreview'
import { ReviewHistory } from './ReviewHistory'
import { evidenceLabels, money, statusLabels, treatmentLabels, type Claim, type EvidenceKind, type FinanceContext } from './model'
import s from './finance.module.css'

export function ReviewDetail({ claim, identity, context, onUpdated }: { claim: Claim; identity: ApiIdentity; context: FinanceContext; onUpdated: (claim: Claim) => void }) {
  const [categoryId, setCategory] = useState(claim.category_id ?? '')
  const [amount, setAmount] = useState(claim.approved_amount ?? claim.requested_amount)
  const [note, setNote] = useState(''); const [ack, setAck] = useState(false); const [busy, setBusy] = useState(false); const lock = useRef(false)
  const [error, setError] = useState(''); const [success, setSuccess] = useState('')
  const [reference, setReference] = useState(''); const [paidOn, setPaidOn] = useState(context.today)
  const request = useRef({ signature: '', id: '' })
  const run = async (action: 'review' | 'payment', input: Record<string, unknown>) => {
    if (lock.current) return
    if (!navigator.onLine) { setError('审核和付款登记必须联网。'); return }
    lock.current = true; setBusy(true); setError(''); setSuccess('')
    const signature = JSON.stringify({ action, claimId: claim.id, version: claim.version, input })
    if (request.current.signature !== signature) request.current = { signature, id: crypto.randomUUID() }
    try {
      const updated = await financeRequest<Claim>(identity, `/claims/${claim.id}/${action}`, 'POST', { ...input, requestId: request.current.id, expectedVersion: claim.version })
      onUpdated(updated); setSuccess(action === 'payment' ? '付款已登记，不会触发银行转账。' : `审核操作已记录：${statusLabels[updated.status]}`)
    } catch (e) { setError(errorMessage(e)) } finally { lock.current = false; setBusy(false) }
  }
  const review = (decision: string) => void run('review', { decision, note, categoryId: categoryId || null, approvedAmount: amount || null, acknowledgeDifference: ack })
  return <>
    <section className={s.reviewContent}>
      <div className={s.heading}><h2>{claim.title}</h2><span className={s.pill}>{statusLabels[claim.status]}</span></div>
      <p>{claim.hotel_name} / {claim.company_name}</p><p className={s.muted}>申请人：{claim.applicant_name} · {claim.business_date} · {claim.id.slice(0, 8)}</p>
      {claim.mismatch && <div className={s.warning}><strong>金额或抬头存在差异，需财务核实</strong><p>发票 {money(claim.invoice_amount)} · 实付 {money(claim.payment_amount)} · 申请 {money(claim.requested_amount)}</p><span>申请人说明：{claim.difference_note || '未填写'}</span></div>}
      <h3>凭证对照</h3><div className={s.documentColumns}>{(Object.keys(evidenceLabels) as EvidenceKind[]).map(kind => <section key={kind}><h4>{evidenceLabels[kind]}</h4>{claim.attachments.filter(a => a.kind === kind).map(a => <EvidencePreview key={a.id} evidence={a} identity={identity} claimId={claim.id} />)}{!claim.attachments.some(a => a.kind === kind) && <p className={s.muted}>未上传</p>}</section>)}</div>
      <h3>核对信息</h3><dl className={s.facts}><dt>发票合计</dt><dd>{money(claim.invoice_amount)}</dd><dt>交易记录</dt><dd>{money(claim.transaction_amount)}</dd><dt>实际支付</dt><dd>{money(claim.payment_amount)}</dd><dt>拟申请报销</dt><dd>{money(claim.requested_amount)}</dd><dt>购买方应为</dt><dd>{claim.company_name}<br />{claim.company_tax_number}</dd><dt>差异说明</dt><dd>{claim.difference_note || '无'}</dd></dl>
      <details><summary>发票号码与原始明细（{claim.invoices.length}）</summary>{claim.invoices.map(i => <p className={s.invoiceSummary} key={i.id}>{i.invoice_code && `${i.invoice_code} / `}{i.invoice_number}<br />{money(i.amount)} · 购买方 {i.buyer_tax_number}</p>)}</details>
      <details open={claim.reviews.length > 0}><summary>审核与付款记录</summary><ReviewHistory records={claim.reviews} identity={identity} claimId={claim.id} />{claim.payments.map((p, i) => <p key={i}>付款 {money(p.amount)} · {p.paid_on}<br />流水参考：{p.reference}</p>)}</details>
    </section>
    <aside className={s.reviewActions}>
      <h2>审核处理</h2>
      <div className={s.checkSummary}><span>发票排重</span><strong>{['DRAFT', 'RETURNED'].includes(claim.status) ? '提交时重新核验' : '提交时已通过'}</strong></div>
      <div className={s.checkSummary}><span>金额校验</span><strong className={claim.mismatch ? s.amber : ''}>{claim.mismatch ? '待人工核实' : '申报金额一致'}</strong></div>
      <p className={s.muted}>门店负责人 → 财务 → 集团领导。审批通过后生成凭证草稿，仍需财务复核后记账。</p>
      {error && <p className={s.error} role="alert">{error}</p>}{success && <p className={s.info} role="status">{success}</p>}
      {claim.canReview ? <fieldset disabled={busy} className={s.form}>
        {claim.status === 'FINANCE_REVIEW' && <><label>费用类别<select value={categoryId} onChange={e => setCategory(e.target.value)}><option value="">请选择</option>{context.categories.filter(c => c.hotel_id === claim.hotel_id && c.enabled).map(c => <option key={c.id} value={c.id}>{c.name} · {treatmentLabels[c.treatment]}</option>)}</select></label>
          <label>确认报销金额<input inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} /></label>
          <label className={s.check}><input type="checkbox" checked={ack} onChange={e => setAck(e.target.checked)} />已核实金额 / 抬头差异及本次调整</label></>}
        <label>审核备注<textarea rows={4} value={note} onChange={e => setNote(e.target.value)} maxLength={2000} placeholder="填写核实依据或补充要求" /></label>
        <div className={s.info}><strong>分类辅助建议</strong><p>{claim.classificationSuggestion.categoryName ? `建议：${claim.classificationSuggestion.categoryName}（${claim.classificationSuggestion.source === 'STORE_RULE' ? '门店规则' : '已审核历史'}）` : '暂无可靠规则匹配，请财务选择合适类别。'}</p><small>规则建议需人工确认；未调用AI模型，也不自动记账。</small></div>
        <div className={s.actions}><button type="button" disabled={!note.trim()} onClick={() => review('RETURN')}>退回补充</button><button type="button" className={s.primary} disabled={!note.trim()} onClick={() => review('APPROVE')}>审核通过</button></div><button type="button" className={s.danger} disabled={!note.trim()} onClick={() => { if (window.confirm('拒绝后此报销单结束，并释放发票占用，确定吗？')) review('REJECT') }}>拒绝报销</button>
      </fieldset> : <p className={s.info}>当前账号不是本阶段审核人，或单据已结束；仅显示权限范围内的信息。</p>}
      {claim.status === 'APPROVED' && <div className={s.voucher}><h3>凭证草稿</h3><p>已生成借贷平衡的草稿及原件索引。尚未记账，不代替税务发票。</p><button type="button" onClick={() => void financeDownload(identity, `/claims/${claim.id}/voucher/export`, '记账凭证草稿.csv').catch(e => setError(errorMessage(e)))}>导出凭证草稿</button></div>}
      {claim.canPay && claim.payments.length === 0 && <fieldset disabled={busy} className={s.form}><h3>登记实际付款</h3><p>审批金额：{money(claim.approved_amount)}</p><label>付款日期<input type="date" value={paidOn} max={context.today} min={claim.business_date} onChange={e => setPaidOn(e.target.value)} /></label><label>实际付款流水 / 凭证参考<input value={reference} maxLength={160} onChange={e => setReference(e.target.value)} /></label><button type="button" className={s.primary} disabled={!reference.trim()} onClick={() => { if (window.confirm(`确认已实际支付${money(claim.approved_amount)}？此操作仅登记，不转账。`)) void run('payment', { amount: claim.approved_amount, reference, paidOn }) }}>确认已付款</button></fieldset>}
    </aside>
  </>
}
