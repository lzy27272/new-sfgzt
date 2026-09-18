import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite
let InvitationDialog
before(async () => {
  vite = await createServer({
    appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot',
    root: fileURLToPath(new URL('..', import.meta.url)),
    server: { middlewareMode: true },
  })
  InvitationDialog = (await vite.ssrLoadModule('/src/features/wecom/WecomInvitationDialog.tsx')).WecomInvitationDialog
})
after(async () => { await vite?.close() })

const testUrl = 'https://example.test/#/wecom-onboarding?token=non-production-test-token'
function render(minutes, props = {}) {
  return renderToStaticMarkup(React.createElement(InvitationDialog, {
    invitation: {
      requestId: 'test', status: 'WAITING_PROFILE', enrollmentUrl: testUrl,
      expiresAt: new Date(Date.now() + minutes * 60_000).toISOString(),
    },
    mode: 'onboarding', regenerating: false, onClose() {}, onRegenerate() {}, ...props,
  }))
}

test('shared invitation renders seven-day validity and a regeneration control', () => {
  const html = render(7 * 1440)
  assert.match(html, /员工一键邀请/)
  assert.match(html, /剩余7天/)
  assert.match(html, /北京时间/)
  assert.match(html, /重新生成邀请/)
  assert.match(html, /旧码仍按原时间到期/)
  assert.match(html, /<button class="secondary">复制链接<\/button>/)
  assert.match(html, /<button class="primary">发送给员工<\/button>/)
  assert.doesNotMatch(html, /120 MINUTES/)
})

test('expiring shared invitation renders an actionable reminder', () => {
  const html = render(60)
  assert.match(html, /data-tone="warning"/)
  assert.match(html, /邀请即将到期/)
  assert.match(html, /重新生成邀请/)
})

test('expired invitation hides the link and disables sending, but regeneration stays usable', () => {
  const html = render(-1)
  assert.match(html, /邀请已过期，请重新生成/)
  assert.match(html, /此二维码已停止展示/)
  assert.doesNotMatch(html, /non-production-test-token/)
  assert.match(html, /<button class="secondary" disabled="">复制链接<\/button>/)
  assert.match(html, /<button class="primary" disabled="">发送给员工<\/button>/)
  assert.match(html, /<button class="secondary">重新生成邀请<\/button>/)
})

test('regeneration hides the old code and prevents duplicate actions', () => {
  const html = render(60, { regenerating: true })
  assert.match(html, /正在生成新邀请/)
  assert.doesNotMatch(html, /non-production-test-token/)
  assert.match(html, /disabled="">正在生成…/)
})

test('generation errors stay inside the dialog and allow another attempt', () => {
  const html = render(-1, { error: '测试：网络暂时不可用' })
  assert.match(html, /role="alert">测试：网络暂时不可用/)
  assert.match(html, /<button class="secondary">重新生成邀请<\/button>/)
})

test('personal invitation does not acquire shared regeneration or seven-day labels', () => {
  const html = render(120, { mode: 'binding', onRegenerate: undefined })
  assert.match(html, /员工绑定邀请/)
  assert.doesNotMatch(html, /重新生成邀请|剩余7天/)
})
