import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, beforeEach, test } from 'node:test'

import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

const FEDERATED_SESSION_STORAGE_KEY = 'hotel-ai-os-federated-session'
const webRoot = fileURLToPath(new URL('..', import.meta.url))
const originalGlobals = new Map(
  ['window', 'document', 'localStorage', 'sessionStorage'].map((key) => [
    key,
    Object.getOwnPropertyDescriptor(globalThis, key),
  ]),
)

function createMemoryStorage() {
  const values = new Map()
  return {
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, String(value)),
  }
}

const localStorage = createMemoryStorage()
const sessionStorage = createMemoryStorage()
let vite
let App

function assertSimplifiedLoginBrand(markup) {
  assert.match(markup, /<h1>贵州四方馆酒店管理有限公司工作台<\/h1>/u)
  assert.match(markup, /<strong>四方馆中台<\/strong>/u)
  assert.doesNotMatch(markup, /内部测试版|内部测试系统|TECH-V0\.2-PILOT\.8|以真实账号进入门店管理闭环|关键操作均记录账号、组织和时间/u)
  assert.match(markup, /黔ICP备2026014300号-1/u)
}

before(async () => {
  const window = {
    addEventListener() {},
    clearInterval,
    history: { replaceState() {} },
    localStorage,
    location: {
      hash: '#/',
      href: 'http://127.0.0.1:4173/#/',
      origin: 'http://127.0.0.1:4173',
      search: '',
    },
    removeEventListener() {},
    sessionStorage,
    setInterval,
  }

  Object.defineProperties(globalThis, {
    document: { configurable: true, value: { hidden: false }, writable: true },
    localStorage: { configurable: true, value: localStorage, writable: true },
    sessionStorage: { configurable: true, value: sessionStorage, writable: true },
    window: { configurable: true, value: window, writable: true },
  })

  vite = await createServer({
    appType: 'custom',
    configFile: './vite.config.ts',
    logLevel: 'silent',
    mode: 'pilot',
    root: webRoot,
    server: { middlewareMode: true },
  })
  App = (await vite.ssrLoadModule('/src/App.tsx')).default
})

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  window.location.hash = '#/'
})

after(async () => {
  await vite?.close()
  for (const [key, descriptor] of originalGlobals) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor)
    else delete globalThis[key]
  }
})

test('an unauthenticated Pilot entry renders account login instead of a cached workbench', () => {
  const markup = renderToStaticMarkup(React.createElement(App))

  assertSimplifiedLoginBrand(markup)
  assert.match(markup, /账号登录/u)
  assert.match(markup, /class="login-auth-screen has-icp-footer"/)
  assert.equal((markup.match(/<footer class="site-icp-footer"/g) ?? []).length, 1)
  assert.match(markup, /<\/section><\/div><footer class="site-icp-footer"/)
  assert.doesNotMatch(markup, /class="shell(?:\s[^"]*)?"/)
})

test('a federated session hides the cached workbench while identity is resolving', () => {
  sessionStorage.setItem(FEDERATED_SESSION_STORAGE_KEY, 'wecom')

  const markup = renderToStaticMarkup(React.createElement(App))

  assertSimplifiedLoginBrand(markup)
  assert.match(markup, /正在验证登录状态/u)
  assert.equal((markup.match(/<footer class="site-icp-footer"/g) ?? []).length, 1)
  assert.match(markup, /<\/section><\/div><footer class="site-icp-footer"/)
  assert.doesNotMatch(markup, /前台员工工作台/u)
  assert.doesNotMatch(markup, /class="shell(?:\s[^"]*)?"/)
  assert.match(markup, /使用账号密码登录/u)
})

test('explicit manual recovery bypasses stale WeCom markers and renders usable credential fields', () => {
  window.location.hash = '#/login'
  sessionStorage.setItem(FEDERATED_SESSION_STORAGE_KEY, 'wecom')
  const markup = renderToStaticMarkup(React.createElement(App))
  assertSimplifiedLoginBrand(markup)
  assert.match(markup, /name="username"/)
  assert.match(markup, /name="password"/)
  assert.doesNotMatch(markup, /正在验证登录状态/u)
})
