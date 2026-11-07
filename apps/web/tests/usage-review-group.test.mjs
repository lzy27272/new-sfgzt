import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

let vite, usage, loadTeamWorkCase
const identity = { key: 'isolated-reviewer', tenantId: 'qa-tenant', actorId: 'qa-actor' }
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  usage = await vite.ssrLoadModule('/src/features/storeResources/workUsage.ts')
  ;({ loadTeamWorkCase } = await vite.ssrLoadModule('/src/api/resources.ts'))
})
after(async () => { await vite?.close() })

test('review groups normalize server members while keeping legacy single-reviewer responses usable', async () => {
  const previousFetch = globalThis.fetch
  try {
    globalThis.fetch = async () => new Response(JSON.stringify({ hotel_org_unit_id: 'qa-hotel', enabled: true, row_version: 4, reviewer_available: true,
      reviewer_assignment_id: 'old-primary', reviewer_assignments: [
        { assignment_id: 'manager', reviewer_name: '模拟店长', reviewer_position_name: '店长', available: false },
        { assignment_id: 'assistant', reviewer_name: '模拟店助', reviewer_position_name: '店助', available: true },
      ] }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    const config = await usage.loadUsageConfig(identity, 'qa-hotel')
    assert.equal(config.reviewerAvailable, true)
    assert.deepEqual(usage.configuredUsageReviewers(config).map(row => row.assignmentId), ['manager', 'assistant'])
    assert.match(usage.usageReviewerSummary(config), /模拟店长.*已失效.*模拟店助/)
    assert.equal(usage.configuredUsageReviewers({ ...config, reviewerAssignments: undefined }).length, 1)
    assert.deepEqual(usage.configuredUsageReviewers({ ...config, reviewerAssignments: [] }), [])
  } finally { globalThis.fetch = previousFetch }
})

test('saving two reviewers sends explicit assignment ids and the exact optimistic version', async () => {
  const previousFetch = globalThis.fetch, requests = []
  try {
    globalThis.fetch = async (url, init) => {
      requests.push({ url: String(url), body: JSON.parse(init.body), method: init.method })
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    await usage.saveUsageConfig(identity, 'qa-hotel', ['manager', 'assistant'], 7)
    assert.equal(requests[0].method, 'PUT')
    assert.match(requests[0].url, /qa-hotel\/usage-review-config$/)
    assert.deepEqual(requests[0].body, { reviewerAssignmentIds: ['manager', 'assistant'], expectedVersion: 7 })
    await usage.saveUsageConfig(identity, 'qa-hotel', 'manager', 8)
    assert.deepEqual(requests[1].body, { reviewerAssignmentId: 'manager', expectedVersion: 8 })
  } finally { globalThis.fetch = previousFetch }
})

test('failed review eligibility reads stay denied and distinguish business rejection from a server failure', async () => {
  const previousFetch = globalThis.fetch
  try {
    for (const status of [403, 503]) {
      globalThis.fetch = async url => {
        if (String(url).endsWith('/review-eligibility')) return new Response(JSON.stringify({ detail: '该门店当前未指定此任职审核' }), { status, headers: { 'Content-Type': 'application/json' } })
        if (String(url).includes('/work-expectations/')) return new Response(JSON.stringify({ id: 'expectation', recordId: 'record', records: [{ id: 'record', status: 'SUBMITTED' }] }), { status: 200, headers: { 'Content-Type': 'application/json' } })
        return new Response(JSON.stringify(String(url).endsWith('/attachments') ? [] : { id: 'record', workExpectationId: 'expectation', status: 'SUBMITTED' }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      const result = await loadTeamWorkCase(identity, { id: 'expectation' }, 'record')
      assert.equal(result.data.reviewEligibility.canReview, false)
      assert.match(result.data.reviewEligibility.reason, status === 403 ? /未指定此任职/ : /503/)
    }
  } finally { globalThis.fetch = previousFetch }
})
