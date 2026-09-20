import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, beforeEach, test } from 'node:test'
import { createServer } from 'vite'

const originalWindow = globalThis.window
const originalFetch = globalThis.fetch
let vite, client
let values
const marker = 'hotel-ai-os-federated-session'
const session = { accessToken: 'isolated-test-token', tokenType: 'Bearer', expiresAt: '2030-01-01T00:00:00Z', accountId: 'fixture-account' }
const storage = {
  getItem: key => values.get(key) ?? null,
  setItem: (key, value) => values.set(key, value),
  removeItem: key => values.delete(key),
}

before(async () => {
  globalThis.window = { location: { search: '' }, localStorage: storage, sessionStorage: storage }
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot',
    root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  client = await vite.ssrLoadModule('/src/api/client.ts')
})
beforeEach(() => {
  values = new Map()
  window.localStorage = window.sessionStorage = storage
  client.clearAccessToken()
})
after(async () => { await vite?.close(); globalThis.window = originalWindow; globalThis.fetch = originalFetch })

test('password login clears stale WeCom hints without storing passwords or bearer tokens', async () => {
  values.set(marker, 'wecom')
  globalThis.fetch = async () => new Response(JSON.stringify(session))
  await client.login('local-user', 'local-password')
  assert.equal(client.hasAccessToken(), true)
  assert.equal(values.size, 0)
})

test('password login still succeeds when WebView storage methods throw', async () => {
  window.localStorage = window.sessionStorage = {
    getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') }, removeItem() { throw new Error('blocked') },
  }
  globalThis.fetch = async () => new Response(JSON.stringify(session))
  assert.equal((await client.login('local-user', 'local-password')).accountId, 'fixture-account')
  assert.equal(client.hasAccessToken(), true)
})

test('password login waits for earlier logout so a late response cannot clear its session cookie', async () => {
  let finishLogout, loginRequests = 0
  globalThis.fetch = async url => {
    if (url.endsWith('/auth/logout')) return new Promise(resolve => { finishLogout = () => resolve(new Response('{}')) })
    loginRequests++
    return new Response(JSON.stringify(session))
  }
  const signingOut = client.logout()
  const signingIn = client.login('local-user', 'local-password')
  await Promise.resolve()
  assert.equal(loginRequests, 0)
  finishLogout()
  await Promise.all([signingOut, signingIn])
  assert.equal(loginRequests, 1)
  assert.equal(client.hasAccessToken(), true)
})

test('hanging password requests time out without establishing an in-memory session', async () => {
  const realTimeout = globalThis.setTimeout
  let requestSignal
  globalThis.setTimeout = (callback, delay, ...args) => realTimeout(callback, delay === 20_000 ? 15 : delay, ...args)
  globalThis.fetch = async (_url, init) => { requestSignal = init.signal; return new Promise(() => {}) }
  try {
    await assert.rejects(client.login('local-user', 'local-password'), /账号登录超时/)
    assert.equal(requestSignal.aborted, true)
    assert.equal(client.hasAccessToken(), false)
  } finally { globalThis.setTimeout = realTimeout }
})
