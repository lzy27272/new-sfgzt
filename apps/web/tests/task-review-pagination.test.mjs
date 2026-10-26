import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, afterEach, test } from 'node:test'
import { createServer } from 'vite'

let vite, loadTasks
const originalFetch = globalThis.fetch
const identity = { tenantId: 'tenant', actorId: 'reviewer', roleCode: 'ASSISTANT_GENERAL_MANAGER', businessActorAssignmentId: 'assignment', orgScopes: [] }
const tasks = count => Array.from({ length: count }, (_, index) => ({ id: 'task-' + index, title: '任务' + index, status: 'AWAITING_REVIEW' }))
const json = payload => new Response(JSON.stringify(payload), { status: 200, headers: { 'content-type': 'application/json' } })

before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  ;({ loadTasks } = await vite.ssrLoadModule('/src/api/resources.ts'))
})
afterEach(() => { globalThis.fetch = originalFetch })
after(async () => { await vite?.close() })

test('reviewer queue fetches more than 100 real tasks, preserves filters and assignment headers', async () => {
  const source = tasks(237), requests = []
  globalThis.fetch = async (url, init) => {
    const query = new URL(url, 'http://localhost').searchParams
    const page = Number(query.get('page'))
    requests.push(page)
    assert.equal(query.get('view'), 'review')
    assert.equal(query.get('status'), 'AWAITING_REVIEW')
    assert.equal(query.get('orgUnitId'), 'store')
    assert.equal(new Headers(init.headers).get('X-Assignment-Id'), 'assignment')
    return json({ content: source.slice(page * 100, (page + 1) * 100) })
  }
  const result = await loadTasks(identity, { view: 'review', status: 'AWAITING_REVIEW', orgUnitId: 'store' })
  assert.deepEqual(requests, [0, 1, 2])
  assert.equal(result.data.length, 237)
  assert.equal(result.data.at(-1).id, 'task-236')
  assert.equal(result.source, 'api')
})

test('a full final review page reads the next empty page instead of silently truncating', async () => {
  const source = tasks(100), requests = []
  globalThis.fetch = async url => { const page = Number(new URL(url, 'http://localhost').searchParams.get('page')); requests.push(page); return json(page ? [] : source) }
  assert.equal((await loadTasks(identity, { view: 'review' })).data.length, 100)
  assert.deepEqual(requests, [0, 1])
})

test('duplicate pages cannot loop forever or return a partial successful review queue', async () => {
  let count = 0
  globalThis.fetch = async () => { count += 1; return json(tasks(100)) }
  await assert.rejects(loadTasks(identity, { view: 'review' }), /发生变化/)
  assert.equal(count, 2)
})

test('failure after the first page is surfaced, not replaced with demo or partial data', async () => {
  let count = 0
  globalThis.fetch = async () => { count += 1; if (count > 1) throw new Error('模拟断网'); return json(tasks(100)) }
  await assert.rejects(loadTasks(identity, { view: 'review' }), /模拟断网/)
})

test('already aborted review requests do not issue a fetch', async () => {
  const abort = new AbortController()
  abort.abort()
  globalThis.fetch = async () => { throw new Error('不应请求') }
  await assert.rejects(loadTasks(identity, { view: 'review' }, abort.signal), error => error.name === 'AbortError')
})

test('aborting between pages stops pagination without returning partial tasks', async () => {
  const abort = new AbortController()
  let count = 0
  globalThis.fetch = async (_url, init) => { count += 1; assert.ok(init.signal); abort.abort(); return json(tasks(100)) }
  await assert.rejects(loadTasks(identity, { view: 'review' }, abort.signal), error => error.name === 'AbortError')
  assert.equal(count, 1)
})

test('other task views keep the existing single-page behavior', async () => {
  const requests = []
  globalThis.fetch = async url => { requests.push(new URL(url, 'http://localhost').searchParams); return json(tasks(100)) }
  assert.equal((await loadTasks(identity, { view: 'mine' })).data.length, 100)
  assert.equal(requests.length, 1)
  assert.equal(requests[0].get('page'), '0')
})
