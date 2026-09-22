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
  for (const text of ['发票', '交易记录', '付款凭证', '差异', '平台补贴10元', '门店负责人', '集团领导', '未调用AI模型', '确认报销金额']) assert.ok(html.includes(text), text)
  assert.match(html, /disabled="">审核通过/)
  assert.ok(!html.includes('确认已付款'))
})
test('readers do not receive approve actions and only completed claims get voucher export', () => {
  const html = render({ ...claim, canReview: false })
  assert.ok(!html.includes('>审核通过</button>'))
  assert.ok(!html.includes('>导出凭证草稿</button>'))
  const approved = render({ ...claim, canReview: false, status: 'APPROVED', canPay: true, approved_amount: '90' })
  assert.ok(approved.includes('导出凭证草稿'))
  assert.ok(approved.includes('确认已付款'))
  assert.ok(approved.includes('实际付款流水'))
})
test('review history exposes frozen facts and original download after a returned replacement', () => {
  const html = render({ ...claim, reviews: [{ stage: 'MANAGER_REVIEW', decision: 'RETURN', note: '请补充', created_at: '2026-09-21T01:00:00Z', reviewer_name: '审核员', review_snapshot: { claim: { title: '旧事由', category_name: '旧类别', requested_amount: '90.00', approved_amount: null }, invoices: [{ invoice_code: '', invoice_number: '00123456789012345678', amount: '100.00', buyer_tax_number: 'OLD-TAX' }], evidence: [{ id: 'previous', kind: 'INVOICE', original_name: '旧发票.pdf', sha256: 'hash' }] } }] })
  for (const text of ['旧事由', '旧类别', '00123456789012345678', 'OLD-TAX', '旧发票.pdf', '下载当时原件', '冻结记录']) assert.ok(html.includes(text), text)
})
