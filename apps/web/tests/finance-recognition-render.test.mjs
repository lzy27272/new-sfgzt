import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, Review, UploadAction, IntakeEditor
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  const module = await vite.ssrLoadModule('/src/features/finance/RecognitionReview.tsx')
  Review = module.RecognitionReview; UploadAction = module.RecognitionUploadAction
  IntakeEditor = (await vite.ssrLoadModule('/src/features/finance/InvoiceIntakeEditor.tsx')).InvoiceIntakeEditor
})
after(async () => { await vite?.close() })
const render = props => renderToStaticMarkup(React.createElement(Review, props))

test('intake offers image recognition retry only for saved JPEG or PNG originals', () => {
  const props = { identity: { tenantId: 'qa', actorId: 'qa', roleCode: 'QA', orgScopes: [] }, intake: { id: 'qa', version: 1, canEdit: true, originalName: 'synthetic' }, onUpdated() {} }
  for (const mediaType of ['image/jpeg', 'image/png']) assert.match(renderToStaticMarkup(React.createElement(IntakeEditor, { ...props, intake: { ...props.intake, mediaType } })), /重新识别已保存原件/)
  for (const mediaType of ['application/pdf', 'application/xml', 'application/ofd']) assert.doesNotMatch(renderToStaticMarkup(React.createElement(IntakeEditor, { ...props, intake: { ...props.intake, mediaType } })), /重新识别已保存原件/)
})

test('pending originals have a clear upload-and-recognize action independent of a nonzero claim amount', () => {
  const props = { pending: 2, busy: false, ready: true, online: true, conflict: false, onUpload() {} }
  const html = renderToStaticMarkup(React.createElement(UploadAction, props))
  assert.match(html, /上传并识别凭证/); assert.match(html, /2 份待上传/); assert.match(html, /不会自动提交审核/)
  assert.doesNotMatch(html, /disabled/)
  assert.equal(renderToStaticMarkup(React.createElement(UploadAction, { ...props, pending: 0 })), '')
  for (const state of [{ busy: true }, { ready: false }, { online: false }, { conflict: true }]) assert.match(renderToStaticMarkup(React.createElement(UploadAction, { ...props, ...state })), /disabled=""/)
})

test('only persisted invoice fields are actionable; buyer name and date are readable references', () => {
  const html = render({ extraction: { source: 'LOCAL_OCR', status: 'CANDIDATES', invoiceNumber: '00000000000000123456', buyerName: '合成公司', invoiceDate: '2026-10-26' }, onUse() {} })
  assert.match(html, /使用发票号码候选 00000000000000123456/)
  assert.match(html, /合成公司/); assert.match(html, /2026-10-26/); assert.match(html, /仅供对照原件/)
  assert.doesNotMatch(html, /使用购买方名称候选|使用开票日期候选/)
  assert.match(html, /待人工核对/); assert.match(html, /数据库排重/)
})
test('payment pane cannot offer a transaction amount or generic invoice amount as payment', () => {
  const html = render({ kind: 'PAYMENT', extraction: { source: 'LOCAL_OCR', status: 'CANDIDATES', amount: '500.00', transactionAmount: '100.00', paymentAmount: '90.00' }, onUse() {} })
  assert.match(html, /使用实际支付金额候选 90.00/)
  assert.doesNotMatch(html, /500\.00|100\.00|价税合计|交易记录金额/)
})
test('unavailable OCR visibly retains original and retry, and similarity is only a review warning', () => {
  const html = render({ extraction: { source: 'MANUAL_REQUIRED', status: 'UNAVAILABLE', notice: '原件已保存，服务暂不可用', warnings: ['低清晰度需要核对'], similarityWarning: '疑似与已有图片相似' }, onRetry() {}, disabled: true })
  for (const text of ['识别服务暂不可用', '原件已保存', '低清晰度需要核对', '疑似与已有图片相似', '不据此自动拒绝', '重新识别已保存原件', '无需重新选图']) assert.ok(html.includes(text), text)
  assert.match(html, /disabled=""/)
  assert.doesNotMatch(html, /已识别候选|已通过税务验真/)
})
