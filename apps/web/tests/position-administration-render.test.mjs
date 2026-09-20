import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, PositionAdministration
before(async () => {
  vite = await createServer({
    appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot',
    root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true },
  })
  ;({ PositionAdministration } = await vite.ssrLoadModule('/src/features/organization/PositionAdministration.tsx'))
})
after(async () => { await vite?.close() })

const identity = {
  key: 'position-admin-render-test', tenantId: 'test-tenant', actorId: 'test-actor',
  roleCode: 'PLATFORM_ADMIN', orgScopes: [], label: '平台管理员', userName: '测试管理员', orgName: '测试集团', focus: '',
}

// Render the whole tab, not only its leaf editors. On first entry both the
// selected position and its asynchronously loaded configuration are absent.
// Their missing IDs must never be treated as a matching configuration.
for (const [name, overrides] of [
  ['administrator opening the positions tab', {}],
  ['read-only user opening the positions tab', { canManage: false, canPublish: false }],
  ['publisher without management access opening the positions tab', { canManage: false }],
  ['opening a position deep link before data arrives', { positionId: 'requested-position' }],
]) {
  test(`${name} renders the initial loading state without crashing`, () => {
    const markup = renderToStaticMarkup(React.createElement(PositionAdministration, {
      identity, canManage: true, canPublish: true, hotels: [], ...overrides,
    }))
    assert.match(markup, /正在读取岗位与功能方案/u)
    assert.doesNotMatch(markup, /保存草稿|发布当前草稿|提交发布/u)
  })
}
