import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, usage, WorkUsageFields, UsageEvidenceSummary, UsageCorrectionForm, loadDailyReport, loadTeamWorkCase
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  usage = await vite.ssrLoadModule('/src/features/storeResources/workUsage.ts')
  ;({ WorkUsageFields } = await vite.ssrLoadModule('/src/features/storeResources/WorkUsageFields.tsx'))
  ;({ UsageEvidenceSummary, UsageCorrectionForm } = await vite.ssrLoadModule('/src/features/storeResources/WorkUsagePanel.tsx'))
  ;({ loadDailyReport } = await vite.ssrLoadModule('/src/features/dailyReports/api.ts'))
  ;({ loadTeamWorkCase } = await vite.ssrLoadModule('/src/api/resources.ts'))
})
after(async () => { await vite?.close() })
const item = { id: 'soap', name: '清洁液', category: 'CLEANING_SUPPLY', unit: '毫升', issueUnit: '升', issueFactor: 1000, rowVersion: 2 }
const catalogue = { hotelOrgUnitId: 'hotel-a', enabled: true, rowVersion: 0, reviewerAvailable: true, reviewerName: '王店助', reviewerPositionName: '店助', items: [item] }
const input = { schemaVersion: 1, noUsage: false, lines: [{ itemId: 'soap', quantity: '0.15', unit: '升', expectedItemVersion: 2 }] }

test('daily report accepts typed use while issuing stock remains a separate non-consumption task', () => {
  assert.equal(usage.supportsWorkUsage({ itemCode: 'HKA_USAGE_RETURN' }), true)
  assert.equal(usage.supportsWorkUsage({ itemCode: 'NSA_DAILY_HANDOVER' }), true)
  assert.equal(usage.supportsWorkUsage({ itemCode: 'HKA_ITEM_ISSUE' }), false)
  assert.equal(usage.supportsWorkUsage({ itemCode: 'HKA_DAILY_REPORT' }), true)
  assert.equal(usage.supportsWorkUsage({ submissionPolicy: { inventoryUsage: true } }), true)
})

test('daily report references an existing use record without sending duplicate quantities', () => {
  const referenceUsage = { workRecordId: 'usage-source', status: 'APPROVED', noUsage: false, lines: [{ itemName: '浴巾', inputQuantity: 3, inputUnit: '条', baseQuantity: 3, baseUnit: '条', category: 'LINEN' }] }
  const linked = { ...catalogue, referenceUsage }
  assert.deepEqual(usage.usagePayload(input, linked), { schemaVersion: 1, referenceRecordId: 'usage-source' })
  assert.deepEqual(usage.usagePayload(input, catalogue), input)
  assert.equal(usage.usageValidation(undefined, linked), undefined)
  const html = renderToStaticMarkup(React.createElement(WorkUsageFields, { catalogue: linked, value: input, onChange() {}, onRefresh() {} }))
  assert.match(html, /已关联当日物品使用登记/); assert.match(html, /不重复扣库存/)
  assert.match(html, /浴巾 3 条/); assert.doesNotMatch(html, /添加使用物品|第1项数量/)
})

test('correction form uses base quantities, current version, mandatory reason and disabled busy state', () => {
  const row = { noUsage: false, lines: [{ itemId: 'soap', itemName: '清洁液', baseQuantity: 150, baseUnit: '毫升', inputQuantity: 0.15, inputUnit: '升', category: 'CLEANING_SUPPLY' }] }
  const props = { catalogue, row, busy: false, onCancel() {}, onSave() {}, onRefresh() {} }
  const html = renderToStaticMarkup(React.createElement(UsageCorrectionForm, props))
  assert.match(html, /value="150"/); assert.match(html, /更正原因/)
  assert.match(html, /disabled="">确认更正并同步/); assert.match(html, /当前库存加权均价/)
  assert.match(renderToStaticMarkup(React.createElement(UsageCorrectionForm, { ...props, busy: true })), /fieldset disabled/)
})
test('preserves structured drafts and validates explicit no-use declarations', () => {
  assert.deepEqual(usage.usageInput(input), input)
  assert.equal(usage.usageInput({ 牙刷: 2 }), undefined)
  assert.equal(usage.usageValidation(input, catalogue), undefined)
  assert.equal(usage.usageValidation({ schemaVersion: 1, noUsage: true, lines: [] }, catalogue), undefined)
  assert.match(usage.usageValidation({ ...input, noUsage: true }, catalogue), /不能同时/)
  assert.match(usage.usageValidation({ ...input, lines: [] }, catalogue), /添加/)
})
test('rejects stale versions, duplicate items, foreign items and lossy conversions', () => {
  for (const patch of [{ expectedItemVersion: 1 }, { itemId: 'foreign' }, { quantity: '-1' }, { quantity: '0' }, { quantity: '0.00001' }, { unit: '箱' }]) {
    assert.ok(usage.usageValidation({ ...input, lines: [{ ...input.lines[0], ...patch }] }, catalogue))
  }
  assert.match(usage.usageValidation({ ...input, lines: [...input.lines, ...input.lines] }, catalogue), /重复/)
  assert.match(usage.usageValidation(input, { ...catalogue, reviewerAvailable: false }), /失效/)
  assert.equal(usage.usageValidation({ 牙刷: 2 }, { ...catalogue, enabled: false }), undefined)
})
test('actual employee form shows configured reviewer, quantities and exact base-unit preview', () => {
  const html = renderToStaticMarkup(React.createElement(WorkUsageFields, { catalogue, value: input, onChange() {}, onRefresh() {} }))
  assert.match(html, /王店助/); assert.match(html, /折合 150 毫升/)
  assert.match(html, /审核通过才扣库/); assert.match(html, /不扣减总库存/)
  assert.match(html, /第1项数量/); assert.match(html, /添加使用物品/)
})
test('form shows missing configuration and busy inputs cannot change', () => {
  const html = renderToStaticMarkup(React.createElement(WorkUsageFields, { catalogue: { ...catalogue, reviewerAvailable: false, items: [] }, value: { 牙刷: 2 }, disabled: true, onChange() {}, onRefresh() {} }))
  assert.match(html, /fieldset disabled/); assert.match(html, /旧草稿/); assert.match(html, /审核人已失效/); assert.match(html, /尚未配置可填报物品/)
})
test('work detail renders human-readable snapshot without ids or database fields', () => {
  const html = renderToStaticMarkup(React.createElement(UsageEvidenceSummary, { usage: { workRecordId: 'internal-record-id', status: 'APPROVED', noUsage: false, lines: [{ itemId: 'internal-item-id', itemName: '浴巾', inputQuantity: 3, inputUnit: '条', baseQuantity: 3, baseUnit: '条', category: 'LINEN' }] } }))
  assert.match(html, /审核通过/); assert.match(html, /浴巾 3 条/); assert.match(html, /布草使用，不扣总库存/)
  assert.doesNotMatch(html, /internal-record-id|internal-item-id|schemaVersion/)
})

test('linked work detail identifies corrected quantities without overwriting original evidence', () => {
  const html = renderToStaticMarkup(React.createElement(UsageEvidenceSummary, { usage: { workRecordId: 'internal-record-id', status: 'APPROVED', noUsage: true, lines: [], referenced: true, correctionVersion: 2, corrections: [{ id: 'internal-revision-id', revisionNo: 2, reason: '复核发现重复登记' }] } }))
  assert.match(html, /不重复扣库存或计费/)
  assert.match(html, /已更正 2 次/)
  assert.match(html, /最新核定数量/)
  assert.match(html, /原提交及更正记录保留/)
  assert.match(html, /复核发现重复登记/)
  assert.doesNotMatch(html, /internal-record-id|internal-revision-id|schemaVersion/)
})

test('daily report shows readable usage summaries without raw ids and pending work is not completed', async () => {
  const originalFetch = globalThis.fetch
  const value = { workRecordId: 'hidden-id', completion: '已核对', formData: {}, inventoryUsage: { status: 'PENDING', statusLabel: '待审核，未扣库', summary: '清洁液 0.15 升；浴巾 3 条（布草使用，不扣总库存）' } }
  try {
    for (const stored of [value, JSON.stringify(value)]) {
      globalThis.fetch = async () => new Response(JSON.stringify({ id: 'report', current_revision_id: 'revision', revisions: [{ id: 'revision', revision_status: 'DRAFT', payload_snapshot: {} }], item_results: [{ id: 'result', template_item_id: 'item', revision_id: 'revision', result_status: 'PENDING', system_prefilled: true, value: stored }] }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      const report = await loadDailyReport({ key: 'test', tenantId: 'tenant', actorId: 'employee' }, 'report')
      const fact = report.currentRevision.sections.flatMap(section => section.items)[0]
      assert.match(fact.systemValue, /待审核，未扣库.*清洁液 0.15 升/)
      assert.doesNotMatch(fact.systemValue, /hidden-id|workRecordId|inventoryUsage/)
      assert.equal(report.completionRate, 0)
    }
  } finally { globalThis.fetch = originalFetch }
})

test('usage evidence link loads the selected historical record, not the latest corrected attempt', async () => {
  const originalFetch = globalThis.fetch, paths = []
  try {
    globalThis.fetch = async path => {
      paths.push(String(path))
      const body = String(path).includes('/work-expectations/') ? { id: 'expectation', recordId: 'latest', records: [
        { id: 'latest', status: 'APPROVED' }, { id: 'old-attempt', status: 'REJECTED' },
      ] }
        : String(path).endsWith('/attachments') ? [] : { id: 'old-attempt', workExpectationId: 'expectation', status: 'REJECTED' }
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    const result = await loadTeamWorkCase({ key: 'test', tenantId: 'tenant', actorId: 'manager' }, { id: 'expectation' }, 'old-attempt')
    assert.equal(result.data.record.id, 'old-attempt')
    assert.ok(paths.some(path => path.endsWith('/records/old-attempt')))
    assert.ok(!paths.some(path => path.endsWith('/records/latest')))
  } finally { globalThis.fetch = originalFetch }
})

test('usage evidence rejects a record whose returned work does not match the selected work', async () => {
  const originalFetch = globalThis.fetch
  try {
    globalThis.fetch = async path => {
      const body = String(path).includes('/work-expectations/')
        ? { id: 'expectation', records: [{ id: 'old-attempt', status: 'REJECTED' }] }
        : { id: 'old-attempt', workExpectationId: 'another-work', status: 'REJECTED' }
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    await assert.rejects(loadTeamWorkCase({ key: 'test', tenantId: 'tenant', actorId: 'manager' }, { id: 'expectation' }, 'old-attempt'), /不属于此工作/)
  } finally { globalThis.fetch = originalFetch }
})
