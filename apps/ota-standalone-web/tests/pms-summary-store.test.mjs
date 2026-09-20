import assert from 'node:assert/strict'
import test from 'node:test'
import { createHash } from 'node:crypto'
import { closeSync, existsSync, lstatSync, mkdtempSync, openSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { mergePmsSummaryRecords, persistPmsSummary } from '../../../tools/uat/pms-summary-store.mjs'

const record = (id = 'one', overrides = {}) => ({
  id, schemaVersion: 1, tenantCode: 'fixture-tenant', sourceHotelId: 'fixture-hotel', sourceHotelCode: 'TEST',
  collectedAt: '2026-09-01T04:10:00Z', period: { from: '2026-08-01', to: '2026-08-31' },
  metrics: { overnightSoldRoomNights: 100, occupancyRate: 0.5 },
  validation: { accuracyState: 'CANDIDATE_NUMERICALLY_VALIDATED_DEFINITION_PENDING' },
  ...overrides,
})
const empty = () => ({ schemaVersion: 1, records: [] })
const fixture = (t) => {
  const root = mkdtempSync(join(tmpdir(), 'sfg-pms-summary-store-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  return { root, path: join(root, 'summaries.json') }
}
const read = (path) => JSON.parse(readFileSync(path, 'utf8'))
const archiveFiles = (path) => readdirSync(`${path}.history`, { recursive: true })
  .filter((name) => name.endsWith('.json')).map((name) => join(`${path}.history`, name))

test('preserves three years across twenty hotels, more than 120 records and every prior version', () => {
  const records = Array.from({ length: 36 * 20 }, (_, i) => {
    const month = new Date(Date.UTC(2023, 8 + Math.floor(i / 20), 1))
    const end = new Date(Date.UTC(month.getUTCFullYear(), month.getUTCMonth() + 1, 0))
    return record(`old-${i}`, { sourceHotelId: `hotel-${i % 20}`, sourceHotelCode: `T${i % 20}`,
      period: { from: month.toISOString().slice(0, 10), to: end.toISOString().slice(0, 10) } })
  })
  const existing = { schemaVersion: 1, publisherNote: 'preserved', records }
  const merged = mergePmsSummaryRecords(existing, record())
  assert.equal(merged.records.length, 721)
  assert.deepEqual(merged.records.slice(0, 720), records)
  assert.equal(merged.publisherNote, 'preserved')
  assert.equal(existing.records.length, 720)
  assert.equal(merged.records[0].period.from, '2023-09-01')
})

test('persists each revision once and archives existing rows before publishing', (t) => {
  const { path } = fixture(t)
  const old = record('old', { collectedAt: '2026-09-01T03:10:00Z' })
  writeFileSync(path, JSON.stringify({ schemaVersion: 1, records: [old] }))
  assert.equal(persistPmsSummary({ outputPath: path, record: record() }).recordCount, 2)
  const afterFirstWrite = readFileSync(path, 'utf8')
  assert.equal(persistPmsSummary({ outputPath: path, record: record() }).recordCount, 2)
  assert.equal(readFileSync(path, 'utf8'), afterFirstWrite)
  assert.equal(archiveFiles(path).length, 2)
  assert.deepEqual(read(archiveFiles(path).find((file) => read(file).id === 'old')), old)
  assert.equal(existsSync(`${path}.lock`), false)
})

test('revised metrics receive a new revision without overwriting old facts or verification flags', (t) => {
  const { path } = fixture(t)
  const original = record()
  const changed = record('two', { collectedAt: '2026-09-02T04:10:00Z', metrics: { overnightSoldRoomNights: 120 } })
  persistPmsSummary({ outputPath: path, record: original })
  persistPmsSummary({ outputPath: path, record: changed })
  assert.deepEqual(read(path).records, [original, changed])
  for (const archived of archiveFiles(path).map(read)) {
    assert.equal(archived.roomNightDefinition, undefined)
    assert.equal(archived.validation.accuracyState, original.validation.accuracyState)
  }
})

test('conflicting ID or same-time revision never replaces published data', (t) => {
  const { path } = fixture(t)
  persistPmsSummary({ outputPath: path, record: record() })
  const before = readFileSync(path, 'utf8')
  for (const incoming of [record('one', { metrics: { occupancyRate: 1 } }), record('other-id'),
    record('offset-time', { collectedAt: '2026-09-01T12:10:00+08:00' })]) {
    assert.throws(() => persistPmsSummary({ outputPath: path, record: incoming }), /PMS_SUMMARY_REVISION_CONFLICT/u)
    assert.equal(readFileSync(path, 'utf8'), before)
  }
  assert.equal(archiveFiles(path).length, 1)
})

test('tenant and hotel partitioning preserves distinct same-day observations', () => {
  const first = record()
  const second = record('two', { tenantCode: 'different-tenant' })
  const third = record('three', { sourceHotelId: 'different-hotel' })
  const result = [first, second, third].reduce((all, row) => mergePmsSummaryRecords(all, row), empty())
  assert.equal(result.records.length, 3)
})

test('malformed or conflicting legacy store fails closed, never resets to an empty history', (t) => {
  const { path } = fixture(t)
  for (const content of ['{broken', '{"schemaVersion":1,"records":null}',
    JSON.stringify({ schemaVersion: 1, records: [record(), record()] }),
    JSON.stringify({ schemaVersion: 1, records: [record('bad', { collectedAt: '' })] })]) {
    writeFileSync(path, content)
    assert.throws(() => persistPmsSummary({ outputPath: path, record: record('new') }), /PMS_SUMMARY_(STORE_INVALID|STORE_CONFLICT|RECORD_INVALID)/u)
    assert.equal(readFileSync(path, 'utf8'), content)
    assert.equal(existsSync(`${path}.lock`), false)
  }
})

test('capacity rejection keeps old history and does not partially publish or delete', (t) => {
  const { path } = fixture(t)
  persistPmsSummary({ outputPath: path, record: record() })
  const before = readFileSync(path, 'utf8')
  assert.throws(() => persistPmsSummary({ outputPath: path,
    record: record('large', { collectedAt: '2026-09-02T04:10:00Z', extra: 'x'.repeat(800) }),
    maximumBytes: Buffer.byteLength(before) + 20 }), /PMS_SUMMARY_CAPACITY_REACHED/u)
  assert.equal(readFileSync(path, 'utf8'), before)
  assert.equal(archiveFiles(path).length, 1)
})

test('duplicate JSON keys cannot silently erase or modify old records', (t) => {
  const { path } = fixture(t)
  for (const content of [
    '{"schemaVersion":1,"records":[],"records":[]}',
    '{"schemaVersion":1,"records":[],"rec\\u006frds":[]}',
    JSON.stringify({ schemaVersion: 1, records: [record()] }).replace('"occupancyRate":0.5', '"occupancyRate":0.4,"occupancyRate":0.5'),
  ]) {
    writeFileSync(path, content)
    assert.throws(() => persistPmsSummary({ outputPath: path, record: record('new') }), /PMS_SUMMARY_STORE_CONFLICT/u)
    assert.equal(readFileSync(path, 'utf8'), content)
  }
})

test('JSON strings and nested arrays do not trigger false duplicate-key detection', (t) => {
  const { path } = fixture(t)
  const first = record('first', { note: 'a \\"quoted\\" name, { } [ ]',
    sourceSchema: { fields: ['same', 'same'], samples: [{ same: 1 }, { same: 2 }] } })
  persistPmsSummary({ outputPath: path, record: first })
  persistPmsSummary({ outputPath: path, record: record('second', { collectedAt: '2026-09-02T04:10:00Z' }) })
  assert.deepEqual(read(path).records[0], first)
})

test('concurrent writer lock is not removed or automatically stolen', (t) => {
  const { path } = fixture(t)
  const lock = openSync(`${path}.lock`, 'wx')
  writeFileSync(lock, 'active-writer')
  try {
    assert.throws(() => persistPmsSummary({ outputPath: path, record: record() }), /PMS_SUMMARY_STORE_BUSY/u)
    assert.equal(readFileSync(`${path}.lock`, 'utf8'), 'active-writer')
    assert.equal(existsSync(path), false)
  } finally { closeSync(lock) }
})

test('tampered archive is not overwritten and prevents active summary replacement', (t) => {
  const { path } = fixture(t)
  persistPmsSummary({ outputPath: path, record: record() })
  const before = readFileSync(path, 'utf8')
  const archived = archiveFiles(path)[0]
  writeFileSync(archived, 'tampered')
  assert.throws(() => persistPmsSummary({ outputPath: path,
    record: record('next', { collectedAt: '2026-09-02T04:10:00Z' }) }), /PMS_SUMMARY_ARCHIVE_CONFLICT/u)
  assert.equal(readFileSync(path, 'utf8'), before)
  assert.equal(readFileSync(archived, 'utf8'), 'tampered')
})

test('archive failure leaves the last readable publication intact', (t) => {
  const { path } = fixture(t)
  const before = JSON.stringify(empty())
  writeFileSync(path, before)
  writeFileSync(`${path}.history`, 'not-a-directory')
  assert.throws(() => persistPmsSummary({ outputPath: path, record: record() }), /PMS_SUMMARY_(STORE_IO_FAILED|PATH_UNSAFE)/u)
  assert.equal(readFileSync(path, 'utf8'), before)
  assert.equal(existsSync(`${path}.lock`), false)
})

test('invalid record paths, dates and identities never create an archive', (t) => {
  const { path } = fixture(t)
  for (const override of [{ tenantCode: '' }, { period: { from: '../../bad', to: '2026-08-31' } },
    { period: { from: '2026-02-30', to: '2026-03-01' } },
    { period: { from: '2026-09-02', to: '2026-09-01' } }, { collectedAt: 'invalid' }]) {
    assert.throws(() => persistPmsSummary({ outputPath: path, record: record('bad', override) }), /PMS_SUMMARY_RECORD_INVALID/u)
  }
  assert.equal(existsSync(path), false)
  assert.equal(existsSync(`${path}.history`), false)
})

test('archive bytes have content hashes and restrictive Unix permissions', (t) => {
  const { path } = fixture(t)
  persistPmsSummary({ outputPath: path, record: record() })
  const file = archiveFiles(path)[0]
  const digest = createHash('sha256').update(readFileSync(file)).digest('hex')
  assert.equal(file.endsWith(`${digest}.json`), true)
  if (process.platform !== 'win32') {
    assert.equal(lstatSync(path).mode & 0o777, 0o600)
    assert.equal(lstatSync(file).mode & 0o777, 0o600)
    assert.equal(lstatSync(`${path}.history`).mode & 0o777, 0o700)
  }
})

test('multiple processes never lose any successful write', async (t) => {
  const { path } = fixture(t)
  const moduleUrl = new URL('../../../tools/uat/pms-summary-store.mjs', import.meta.url).href
  const run = (row) => new Promise((res, reject) => {
    const child = spawn(process.execPath, ['--input-type=module', '-e',
      `import {persistPmsSummary} from ${JSON.stringify(moduleUrl)};
       try {persistPmsSummary(JSON.parse(process.argv[1]));}
       catch(e) {if(e.message==='PMS_SUMMARY_STORE_BUSY')process.exitCode=9;else throw e;}`,
      JSON.stringify({ outputPath: path, record: row })], { stdio: ['ignore', 'ignore', 'pipe'] })
    let error = ''
    child.stderr.on('data', (chunk) => { error += chunk })
    child.on('error', reject)
    child.on('close', (code) => code === 0 || code === 9 ? res({ row, code }) : reject(new Error(error)))
  })
  const attempts = await Promise.all(Array.from({ length: 8 }, (_, i) => run(record(`process-${i}`, { sourceHotelId: `hotel-${i}` }))))
  const successes = attempts.filter(({ code }) => code === 0).map(({ row }) => row.id).sort()
  assert.ok(successes.length >= 1)
  assert.deepEqual(read(path).records.map((row) => row.id).sort(), successes)
  assert.equal(archiveFiles(path).length, successes.length)
  // The operator can retry BUSY jobs safely; no automatic stale-lock takeover is involved.
  for (const { row, code } of attempts) if (code === 9) persistPmsSummary({ outputPath: path, record: row })
  assert.equal(read(path).records.length, 8)
})

test('monthly collector uses history-preserving persistence and has no 120-record eviction', () => {
  const collector = readFileSync(new URL('../../../tools/uat/monthly-pms-kpi-collector.mjs', import.meta.url), 'utf8')
  assert.match(collector, /persistPmsSummary\(\{ outputPath, record \}\)/u)
  assert.doesNotMatch(collector, /MAX_STORED_RECORDS|slice\(-120\)/u)
})
