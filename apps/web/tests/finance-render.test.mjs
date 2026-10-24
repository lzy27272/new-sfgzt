import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
let vite, ReviewDetail
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  ReviewDetail = (await vite.ssrLoadModule('/src/features/finance/ReviewDetail.tsx')).ReviewDetail
})
after(async () => { await vite?.close() })
const claim = { id: 'claim', version: 2, title: '客房用品', hotel_id: 'hotel', hotel_name: '测试门店', company_name: '测试公司', company_tax_number: 'test', applicant_name: '测试员工', business_date: '2026-09-21', status: 'FINANCE_REVIEW', invoice_amount: '100', transaction_amount: '100', payment_amount: '90', requested_amount: '90', approved_amount: null, category_id: 'cat', mismatch: true, difference_note: '平台补贴10元', invoices: [], attachments: [], reviews: [], payments: [], canReview: true, canPay: false, classificationSuggestion: { source: 'STORE_RULE', categoryName: '客房用品' } }
const render = c => renderToStaticMarkup(React.createElement(ReviewDetail, { claim: c, context: { today: '2026-09-21', categories: [], hotels: [], assignments: [] }, identity: {}, onUpdated() {} }))
test('review shows three evidence types, truthful advisory and amount variance', () => {
  const html = render(claim)
  for (const text of ['发票', '交易记录', '付款凭证', '差异', '平台补贴10元', '初审', '财务总监', 'CEO终审', '出纳登记付款', '未调用AI模型', '确认报销金额']) assert.ok(html.includes(text), text)
  assert.match(html, /disabled="">审核通过/)
  assert.ok(!html.includes('确认已付款'))
})
test('readers do not receive approve actions and only completed claims get voucher export', () => {
  const html = render({ ...claim, canReview: false })
  assert.ok(!html.includes('>审核通过</button>'))
  assert.ok(!html.includes('>导出凭证草稿</button>'))
  const approved = render({ ...claim, canReview: false, status: 'APPROVED', canPay: true, approved_amount: '90' })
  assert.ok(approved.includes('导出凭证草稿'))
  assert.ok(!approved.includes('确认已付款'), 'old direct-payment action must not bypass frozen account review')
  assert.ok(approved.includes('核对账户并建立付款指令'))
  assert.ok(approved.includes('#/finance?tab=control&amp;screen=cash&amp;sourceId=claim'))
  assert.ok(approved.includes('先由独立人员复核账户'))
})
test('review history exposes frozen facts and original download after a returned replacement', () => {
  const html = render({ ...claim, reviews: [{ stage: 'MANAGER_REVIEW', decision: 'RETURN', note: '请补充', created_at: '2026-09-21T01:00:00Z', reviewer_name: '审核员', review_snapshot: { claim: { title: '旧事由', category_name: '旧类别', requested_amount: '90.00', approved_amount: null }, invoices: [{ invoice_code: '', invoice_number: '00123456789012345678', amount: '100.00', buyer_tax_number: 'OLD-TAX' }], evidence: [{ id: 'previous', kind: 'INVOICE', original_name: '旧发票.pdf', sha256: 'hash' }] } }] })
  for (const text of ['旧事由', '旧类别', '00123456789012345678', 'OLD-TAX', '旧发票.pdf', '下载当时原件', '冻结记录']) assert.ok(html.includes(text), text)
})
test('initial self-review is visibly skipped and never shown as approval; director remains in sequence', () => {
  const html = render({ ...claim, workflow_version: 2, approvalRoute: [
    { stage: 'MANAGER_REVIEW', label: '初审', accountName: '沈婧', skipped: true },
    { stage: 'FINANCE_REVIEW', label: '门店财务', accountName: '测试财务', skipped: false },
    { stage: 'DIRECTOR_REVIEW', label: '财务总监', accountName: '测试总监', skipped: false },
    { stage: 'LEADER_REVIEW', label: 'CEO终审', accountName: '刘照阳', skipped: false },
    { stage: 'PAYMENT', label: '出纳登记付款', accountName: '测试出纳', skipped: false },
  ], reviews: [{ stage: 'MANAGER_REVIEW', decision: 'SKIP_SELF', note: '本人初审回避', created_at: '2026-09-22T08:00:00Z', reviewer_name: '沈婧' }] })
  for (const text of ['已跳过自审', '跳过本人初审（非审批通过）', '当前审核：门店财务', '测试总监', '刘照阳', '审批完成后']) assert.ok(html.includes(text), text)
  assert.ok(html.includes('aria-current="step"'))
})
test('legacy claim still clearly identifies its original three-stage workflow', () => {
  const html = render({ ...claim, workflow_version: 1, status: 'LEADER_REVIEW', reviews: [{ stage: 'LEADER_REVIEW', decision: 'APPROVE', note: '历史终审', created_at: '2026-09-21T01:00:00Z', reviewer_name: '原集团领导' }] })
  assert.ok(html.includes('历史流程：门店负责人 → 财务 → 集团领导'))
  assert.ok(html.includes('待集团领导审核'))
  assert.ok(!html.includes('CEO终审'))
})
