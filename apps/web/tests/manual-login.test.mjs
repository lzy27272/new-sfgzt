import assert from 'node:assert/strict'
import test from 'node:test'
import { requestsManualLogin, rememberManualLogin, clearManualLogin } from '../src/app/manualLogin.ts'

function browser(hash = '#/workbench') {
  const storage = new Map()
  return { location: { hash }, sessionStorage: {
    getItem: key => storage.get(key),
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key),
  } }
}

test('manual recovery is explicit, survives a reload and clears only when WeCom is chosen again', () => {
  const tab = browser()
  assert.equal(requestsManualLogin(tab), false)
  rememberManualLogin(tab)
  assert.equal(requestsManualLogin({ ...tab }), true)
  clearManualLogin(tab)
  assert.equal(requestsManualLogin(tab), false)
})

test('manual login hash remains usable when the WebView forbids browser storage', () => {
  const tab = { location: { hash: '#/login' }, sessionStorage: {
    getItem() { throw new Error('storage blocked') },
    setItem() { throw new Error('storage blocked') },
    removeItem() { throw new Error('storage blocked') },
  } }
  rememberManualLogin(tab)
  assert.equal(requestsManualLogin(tab), true)
  clearManualLogin(tab)
  tab.location.hash = '#/workbench'
  assert.equal(requestsManualLogin(tab), false)
})

test('login-method state cannot be mistaken for an authentication token', () => {
  const tab = browser()
  tab.sessionStorage.setItem('hotel-ai-os-login-method', 'arbitrary-token')
  assert.equal(requestsManualLogin(tab), false)
})
