import { useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { errorMessage, financeDownload } from './api'
import { approvalStageLabel, evidenceLabels, money, type ReviewRecord } from './model'
import s from './finance.module.css'

export function ReviewHistory({ records, identity, claimId }: { records: ReviewRecord[]; identity: ApiIdentity; claimId: string }) {
  const [error, setError] = useState('')
  return <div className={s.history}>
    {error && <p role="alert" className={s.error}>{error}</p>}
    {records.map((r, index) => <section key={index}>
      <p><strong>{r.reviewer_name}</strong> · {approvalStageLabel(r.stage, r.review_snapshot?.claim.workflow_version ?? (r.decision === 'SKIP_SELF' || r.stage === 'DIRECTOR_REVIEW' ? 2 : 1))} · {r.decision === 'SKIP_SELF' ? '跳过本人初审（非审批通过）' : r.decision === 'APPROVE' ? '通过' : r.decision === 'RETURN' ? '退回补充' : '拒绝'}<br />{r.note}<br /><small>{new Date(r.created_at).toLocaleString('zh-CN')}</small></p>
      {r.review_snapshot && <details><summary>查看本次审核时的内容与原件</summary>
        <p>{r.review_snapshot.claim.title} · {r.review_snapshot.claim.category_name}<br />申请 {money(r.review_snapshot.claim.requested_amount)}{r.review_snapshot.claim.approved_amount != null && ` · 财务确认 ${money(r.review_snapshot.claim.approved_amount)}`}</p>
        <small>以下为本次审核的冻结记录；退回修改后不会被新内容替换。</small>
        {r.review_snapshot.invoices.map((i, n) => <p key={n} className={s.invoiceSummary}>{i.invoice_code && `${i.invoice_code} / `}{i.invoice_number} · {money(i.amount)}<br />购买方税号：{i.buyer_tax_number}</p>)}
        {r.review_snapshot.evidence.map(e => <div key={e.id} className={s.fileLine}><span>{evidenceLabels[e.kind]} · {e.original_name}</span><button type="button" onClick={() => { setError(''); void financeDownload(identity, `/claims/${claimId}/attachments/${e.id}`, e.original_name).catch(err => setError(errorMessage(err))) }}>下载当时原件</button></div>)}
      </details>}
    </section>)}
  </div>
}
