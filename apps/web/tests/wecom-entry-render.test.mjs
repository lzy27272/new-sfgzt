import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, EntryPage, resources
before(async () => {
  vite = await createServer({
    appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot',
    root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true },
  })
  ;({ WecomTaskEntryPage: EntryPage } = await vite.ssrLoadModule('/src/features/wecom/WecomTaskEntry.tsx'))
  resources = await vite.ssrLoadModule('/src/api/resources.ts')
})
after(async () => { await vite?.close() })

test('失败入口提供新的企微验证链接，且不再显示无限加载状态', () => {
  const markup = renderToStaticMarkup(React.createElement(EntryPage, {
    entry: { securityError: '当前企微尚未绑定有效账号' }, onAuthenticated() {}, onCancel() {},
  }))
  assert.match(markup, /当前企微尚未绑定有效账号/)
  assert.match(markup, /href="\/api\/v1\/integrations\/wecom\/oauth\/start\?returnTo=%23%2Fworkbench"/)
  assert.match(markup, /重新通过企业微信验证/)
  assert.doesNotMatch(markup, /class="spinner"/)
})

test('统计接口保留总部与门店各自的员工明细，并兼容没有新字段的旧服务', async () => {
  const originalFetch = globalThis.fetch
  const metric = { expected: 1, completed: 0, pending: 1 }
  const organization = (id, name) => ({ id, name, today: metric, monthToDate: metric,
    departments: [{ id: 'department', name: '管理', today: metric, monthToDate: metric,
      employees: [{ id: `${id}-assignment`, assignmentId: `${id}-assignment`, employeeId: `${id}-employee`, name,
        positionName: '管理岗位', today: metric, monthToDate: metric }] }] })
  try {
    for (const includeHeadquarters of [true, false]) {
      globalThis.fetch = async () => new Response(JSON.stringify({
        asOfDate: '2026-09-19', monthStart: '2026-09-01', today: metric, monthToDate: metric,
        hotels: [organization('hotel', '正式门店')],
        ...(includeHeadquarters ? { organizations: [organization('headquarters', '总部员工')] } : {}),
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      const { data } = await resources.loadWorkbenchSummary({ tenantId: 'tenant', actorId: 'leader', roleCode: 'CEO', orgScopes: [] }, '2026-09-19')
      assert.equal(data.hotels.length, 1)
      assert.equal(data.organizations.length, includeHeadquarters ? 1 : 0)
      if (includeHeadquarters) assert.equal(data.organizations[0].departments[0].employees[0].name, '总部员工')
    }
  } finally { globalThis.fetch = originalFetch }
})
