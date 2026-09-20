import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, forms
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  forms = await vite.ssrLoadModule('/src/features/storeResources/CostPanel.tsx')
})
after(async () => { await vite?.close() })
const summary = { month: '2026-08', currentMonth: '2026-09', closed: false, canClosePeriod: true, previewToken: 'a'.repeat(64),
  usageTotal: '10.0000', lossTotal: '2.5000', costTotal: '12.5000', adjustmentTotal: '1.0000', entryCount: 3,
  pendingUsageCount: 0, openLaundryCount: 2, zeroAmountCount: 1, legacyCount: 0, priorPeriodCount: 1,
  categories: [{ costKind: 'CONSUMABLE', amount: '10.0000', entryCount: 1 }], daily: [], items: [] }
const render = (name, props) => renderToStaticMarkup(React.createElement(forms[name], props))
test('cost summary separates loss and adjustment and does not invent PMS unit cost', () => {
  const html = render('CostSummaryView', { summary, onKind() {}, onDay() {} })
  for (const text of ['耗品使用', '报损损失', '盘点调整', '零金额记录 1 条', '尚在洗 2 单', '未接入或缺数据时不虚构分母', '¥12.5000']) assert.ok(html.includes(text), text)
})
test('close requires explanation and explicit confirmation, cannot double submit or let readers close', () => {
  const props = { summary, canClose: true, busy: false, async onSubmit() {} }
  const html = render('CostCloseForm', props)
  assert.match(html, /月结核对说明/)
  assert.match(html, /type="checkbox"/)
  assert.match(html, /disabled="">确认关账并留存/)
  assert.match(render('CostCloseForm', { ...props, busy: true }), /<fieldset disabled=""/)
  assert.doesNotMatch(render('CostCloseForm', { ...props, canClose: false }), /<form|确认关账并留存/)
  assert.doesNotMatch(render('CostCloseForm', { ...props, summary: { ...summary, closed: true } }), /<form|确认关账并留存/)
})
test('closed month shows operator, time and explanation instead of new close controls', () => {
  const html = render('CostSummaryView', { summary: { ...summary, closed: true, closedByName: '核对员', closedAt: '2026-09-01T10:00:00+08:00', closeNote: '在洗数量已确认' }, onKind() {}, onDay() {} })
  for (const text of ['已关账', '核对员', '在洗数量已确认', '关账时的核对提示']) assert.ok(html.includes(text), text)
})
test('entry evidence labels corrections and retains source references under details', () => {
  const html = render('CostEntries', { entries: [{ id: 'entry', itemName: '浴巾', baseUnit: '条', costKind: 'LAUNDRY', quantity: '-6.0000', amount: '-7.5000', businessDate: '2026-08-11', postingDate: '2026-09-18', sourceKind: 'LAUNDRY', sourceId: 'receipt-id', sourceParentId: 'order-id', direction: -1, reversesEntryId: 'prior-entry', reason: '数量误录', priorPeriod: true, operatorName: '核对员', sourceRecordedAt: '2026-09-18T10:00:00+08:00' }] })
  for (const text of ['跨月调整', '冲销记录', '数量误录', '查看来源与追溯编号', '冲回成本记录：prior-entry', '-¥7.5000']) assert.ok(html.includes(text), text)
})
