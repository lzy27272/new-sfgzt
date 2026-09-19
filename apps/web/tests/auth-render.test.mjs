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

  assert.match(markup, /账号登录/u)
  assert.doesNotMatch(markup, /class="shell"/)
})

test('a federated session hides the cached workbench while identity is resolving', () => {
  sessionStorage.setItem(FEDERATED_SESSION_STORAGE_KEY, 'wecom')

  const markup = renderToStaticMarkup(React.createElement(App))

  assert.match(markup, /正在验证登录状态/u)
  assert.doesNotMatch(markup, /前台员工工作台/u)
  assert.doesNotMatch(markup, /class="shell"/)
  assert.match(markup, /使用账号密码登录/u)
})

test('explicit manual recovery bypasses stale WeCom markers and renders usable credential fields', () => {
  window.location.hash = '#/login'
  sessionStorage.setItem(FEDERATED_SESSION_STORAGE_KEY, 'wecom')
  const markup = renderToStaticMarkup(React.createElement(App))
  assert.match(markup, /name="username"/)
  assert.match(markup, /name="password"/)
  assert.doesNotMatch(markup, /正在验证登录状态/u)
})
