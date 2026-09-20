import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, forms
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  forms = await vite.ssrLoadModule('/src/features/storeResources/LaundryPanel.tsx')
})
after(async () => { await vite?.close() })
const props = { busy: false, async onSubmit() { return true }, async onReceive() { return true }, async onCorrect() { return true }, async onSave() {} }
const item = { itemId: 'item', itemName: '浴巾', baseUnit: '条', ownedQuantity: 100, availableQuantity: 80, pendingQuantity: 20, unitPrice: 1.25, rateVersion: 0, itemVersion: 0 }
const order = { id: 'order', supplier: '洗涤厂', dispatchedOn: '2026-09-18', status: 'PARTIAL', rowVersion: 1, fee: 10, operatorName: '库存管理员',
  lines: [{ id: 'line', itemId: 'item', itemName: '浴巾', baseUnit: '条', quantity: 20, acceptedQuantity: 8, writtenOffQuantity: 0, pendingQuantity: 12, unitPrice: 1.25, fee: 10 }],
  receipts: [{ id: 'receipt', receivedOn: '2026-09-18', status: 'POSTED', operatorName: '库存管理员', createdAt: '2026-09-18T10:00:00+08:00', lines: [{ orderLineId: 'line', itemName: '浴巾', baseUnit: '条', acceptedQuantity: 8, rewashQuantity: 2, writtenOffQuantity: 0, fee: 10, differenceReason: '污渍返洗' }] }] }
const render = (name, extra) => renderToStaticMarkup(React.createElement(forms[name], { ...props, ...extra }))
test('read-only price view cannot edit laundry rates', () => {
  const readonly = render('LaundryRateForm', { item, canConfigure: false })
  assert.match(readonly, /总库存 100 条 · 尚在洗 20 条 · 可送洗 80 条/)
  assert.doesNotMatch(readonly, /保存洗涤单价|<input/)
  assert.match(render('LaundryRateForm', { item, canConfigure: true }), /洗涤单价（元\/条）/)
})
test('new dispatch requires supplier, date and lines, and disables duplicate submission', () => {
  const html = render('LaundryDispatchForm', { catalog: [item], busy: true })
  assert.match(html, /洗涤供应商/)
  assert.match(html, /<fieldset disabled=""/)
  assert.match(html, /disabled="">提交中…/)
  assert.match(render('LaundryDispatchForm', { catalog: [] }), /disabled="">确认送洗并冻结单价/)
})
test('real receipt form separates qualified, rewash, write-off, reasons and date', () => {
  const html = render('LaundryReceiveForm', { order })
  for (const text of ['合格回收', '不合格返洗（仍在洗）', '确认丢失 / 损坏报损', '差异原因', '尚在洗 12 条', '冻结洗涤单价', '实际回收日期']) assert.ok(html.includes(text), text)
  assert.match(html, /disabled="">确认验收并记账/)
})
test('history remains Chinese, read-only for readers and exposes corrections only to stock managers', () => {
  const readonly = render('LaundryOrderDetail', { order, canManage: false })
  assert.match(readonly, /回收验收历史/)
  assert.match(readonly, /污渍返洗/)
  assert.doesNotMatch(readonly, /确认验收并记账|冲销本次回收/)
  const manager = render('LaundryOrderDetail', { order, canManage: true })
  assert.match(manager, /冲销本次回收/)
  assert.doesNotMatch(manager, /取消送洗单/)
  const reversed = render('LaundryOrderDetail', { order: { ...order, receipts: [{ ...order.receipts[0], status: 'REVERSED', reversalReason: '误录', reversedByName: '管理员', reversedAt: '2026-09-18T11:00:00+08:00' }] }, canManage: true })
  assert.match(reversed, /已冲销（不再计费）/)
  assert.match(reversed, /误录/)
})
