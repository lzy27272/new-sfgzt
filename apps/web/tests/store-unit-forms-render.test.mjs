import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite
let ItemForm
let AdjustmentForm
before(async () => {
  vite = await createServer({
    appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot',
    root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true },
  })
  const forms = await vite.ssrLoadModule('/src/features/storeResources/StoreResourceRoutes.tsx')
  ItemForm = forms.ItemForm
  AdjustmentForm = forms.AdjustmentForm
})
after(async () => { await vite?.close() })

const item = {
  id: 'test-item', sku: 'BRUSH', name: '牙刷', category: 'CONSUMABLE', unit: '支',
  unitCost: 0.5, minimumQuantity: 10, purchaseUnit: '箱', purchaseFactor: 100,
  issueUnit: '支', issueFactor: 1, specification: '100支/箱', rowVersion: 1, baseUnitLocked: true,
}
const props = { busy: false, onCancel() {}, async onSubmit() {} }

test('item editor renders real unit settings and locks the historical base unit', () => {
  const html = renderToStaticMarkup(React.createElement(ItemForm, { ...props, initial: item }))
  assert.match(html, /库存基础单位<input[^>]*readOnly=""[^>]*value="支"/)
  assert.match(html, /基础单位已锁定/)
  assert.match(html, /采购单位/)
  assert.match(html, /领用\/耗用单位/)
  assert.match(html, /value="100支\/箱"/)
  assert.match(html, /value="100"/)
  assert.match(html, /不会重算已有数量或成本/)
  assert.match(html, /保存物品/)
})

test('stock entry defaults to purchase packaging and explains input-price units', () => {
  const html = renderToStaticMarkup(React.createElement(AdjustmentForm, { ...props, items: [item], balances: [] }))
  assert.match(html, /<option value="箱" selected="">箱（1箱＝100支）/)
  assert.match(html, /本次单价（元\/箱，选填）/)
  assert.match(html, /库存以支记账；1 箱＝100 支/)
  assert.match(html, /disabled="">确认并留痕/)
})

test('same-name case aliases render an actual selected option rather than a blank select', () => {
  const html = renderToStaticMarkup(React.createElement(AdjustmentForm, {
    ...props, items: [{ ...item, unit: 'ML', purchaseUnit: 'ml', purchaseFactor: 1, issueUnit: 'ML' }], balances: [],
  }))
  assert.match(html, /<option value="ML" selected="">/)
  assert.doesNotMatch(html, /<option value="ml"/)
})

test('saving locks inputs and an empty item list cannot submit inventory', () => {
  const saving = renderToStaticMarkup(React.createElement(ItemForm, { ...props, initial: item, busy: true }))
  assert.match(saving, /<fieldset disabled=""/)
  assert.match(saving, /disabled="">保存中…/)
  const empty = renderToStaticMarkup(React.createElement(AdjustmentForm, { ...props, items: [], balances: [] }))
  assert.match(empty, /disabled="">确认并留痕/)
})
