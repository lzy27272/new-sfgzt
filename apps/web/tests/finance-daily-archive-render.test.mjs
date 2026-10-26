import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, Fields, Daily
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  Fields = (await vite.ssrLoadModule('/src/features/finance/FinanceAuxiliary.tsx')).FinanceAuxiliaryFields
  Daily = (await vite.ssrLoadModule('/src/features/finance/FinanceDailyInsights.tsx')).default
})
after(async () => { await vite?.close() })

test('voucher auxiliary choices use actual context references, no manual UUID or invented names', () => {
  const context = { departments: [{ id: 'actual-dept', name: '真实部门' }], employees: [{ id: 'actual-account', displayName: '真实员工', employeeNo: 'EMP-09' }], parties: [{ id: 'actual-party', name: '真实供应商' }], projects: { items: [{ id: 'actual-contract', kind: 'CONTRACT', key: 'CONTRACT:actual-contract', name: '实际已批合同' }], complete: false } }
  const html = renderToStaticMarkup(React.createElement(Fields, { context, value: { departmentId: 'actual-dept', projectKind: 'CONTRACT', projectId: 'actual-contract' }, onChange() {} }))
  assert.equal((html.match(/<select/g) ?? []).length, 4)
  for (const text of ['真实部门', '真实员工', '真实供应商', '实际已批合同', '未分配', '列表不代表全部项目']) assert.ok(html.includes(text), text)
  assert.match(html, /value="actual-dept" selected=""/)
  assert.match(html, /value="CONTRACT:actual-contract" selected=""/)
  assert.ok(!html.includes('<input'), 'auxiliary identities must come from actual authorized options')
})

test('daily entry distinguishes registered sources, date basis, freezing and model-not-connected boundary', () => {
  const html = renderToStaticMarkup(React.createElement(Daily, { identity: { tenantId: 'isolated', actorId: 'isolated', roleCode: 'TEST', orgScopes: [] } }))
  for (const text of ['每日经营', '实际业务日期', '未导入', '留空', '不能相加', '已冻结日报历史', '模型未配置时不生成模拟答案']) assert.ok(html.includes(text), text)
  assert.ok(!html.includes('>0.00<'))
  assert.ok(!html.includes('已自动连接PMS'))
})
