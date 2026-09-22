import type { Claim } from './model'
import s from './finance.module.css'

export function ApprovalTimeline({ claim }: { claim: Claim }) {
  const route = claim.approvalRoute ?? []
  if (!route.length) return null
  const currentReviews = claim.reviews.filter(r => !claim.submitted_at || new Date(r.created_at).getTime() >= new Date(claim.submitted_at).getTime())
  const skipped = route.some(step => step.skipped)
  return <section className={s.approvalTimeline} aria-label="审批流转进度">
    <div className={s.heading}><h3>审批流转</h3><small>已提交单据使用提交时的审批快照</small></div>
    {claim.workflow_version === 1 && <p className={s.muted}>历史三段审核单据，保留原审核链。</p>}
    <ol className={s.flowSteps}>{route.map(step => {
      const review = [...currentReviews].reverse().find(r => r.stage === step.stage)
      const payment = step.stage === 'PAYMENT'
      const current = payment ? claim.status === 'APPROVED' && !claim.payments.length : claim.status === step.stage
      const complete = payment ? claim.payments.length > 0 : review?.decision === 'APPROVE' || claim.status === 'APPROVED'
      const state = step.skipped ? '已跳过自审' : current ? (payment ? '待登记付款' : '当前审核') : review?.decision === 'RETURN' ? '已退回' : review?.decision === 'REJECT' ? '已拒绝' : complete ? '已完成' : payment ? '审批完成后' : '待审核'
      return <li key={step.stage} className={step.skipped ? s.skippedStep : current ? s.currentStep : complete ? s.completedStep : ''} aria-current={current ? 'step' : undefined}><strong>{step.label}</strong><span>{step.accountName}</span><small>{state}</small></li>
    })}</ol>
    {skipped && <p className={s.warning}>初审人与申请人相同，已跳过本人初审并留痕；跳过不代表审核通过。</p>}
  </section>
}
