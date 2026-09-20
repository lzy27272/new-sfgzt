import { createHash, randomUUID } from 'node:crypto'
import {
  closeSync, existsSync, fsyncSync, linkSync, lstatSync, mkdirSync,
  openSync, readFileSync, renameSync, unlinkSync, writeFileSync,
} from 'node:fs'
import { dirname, join, resolve } from 'node:path'

// Keep every published revision. A size limit must stop publishing, never evict history.
// Expiry and offsite backups are separate, explicitly approved operational workflows.
const MAX_BYTES = 64 * 1024 * 1024
const MAX_RECORDS = 100_000
const fail = (code) => { throw new Error(code) }
const plainObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)
const text = (value) => typeof value === 'string' && value.length > 0 && value.length <= 256
  && value === value.trim() && !/[\u0000-\u001f\u007f]/u.test(value)
const date = (value) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/u.test(value)
  && Number.isFinite(Date.parse(`${value}T00:00:00Z`))
  && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value

function validateRecord(record) {
  if (!plainObject(record) || !text(record.id) || !text(record.sourceHotelId)
      || !text(record.sourceHotelCode) || !text(record.tenantCode)
      || !plainObject(record.period) || !date(record.period.from) || !date(record.period.to)
      || record.period.from > record.period.to || !text(record.collectedAt)
      || !/^\d{4}-\d{2}-\d{2}T.+(?:Z|[+-]\d{2}:\d{2})$/u.test(record.collectedAt)
      || !Number.isFinite(Date.parse(record.collectedAt))) fail('PMS_SUMMARY_RECORD_INVALID')
}

const revisionKey = (record) => JSON.stringify([
  record.tenantCode, record.sourceHotelId, record.period.from, record.period.to,
  new Date(record.collectedAt).toISOString(),
])

export function mergePmsSummaryRecords(existing, incoming) {
  if (!plainObject(existing) || existing.schemaVersion !== 1 || !Array.isArray(existing.records)) {
    fail('PMS_SUMMARY_STORE_INVALID')
  }
  validateRecord(incoming)
  const ids = new Map()
  const revisions = new Map()
  for (const row of existing.records) {
    validateRecord(row)
    if (ids.has(row.id) || revisions.has(revisionKey(row))) fail('PMS_SUMMARY_STORE_CONFLICT')
    ids.set(row.id, row)
    revisions.set(revisionKey(row), row)
  }
  const sameId = ids.get(incoming.id)
  if (sameId) {
    if (JSON.stringify(sameId) !== JSON.stringify(incoming)) fail('PMS_SUMMARY_REVISION_CONFLICT')
    return { ...existing, records: [...existing.records] }
  }
  if (revisions.has(revisionKey(incoming))) fail('PMS_SUMMARY_REVISION_CONFLICT')
  // No slice(-120), no per-hotel eviction, and no implicit purge by collection date.
  return { ...existing, records: [...existing.records, incoming] }
}

function ordinaryFile(path, limit) {
  const stat = lstatSync(path)
  if (!stat.isFile() || stat.isSymbolicLink()) fail('PMS_SUMMARY_PATH_UNSAFE')
  if (stat.size > limit) fail('PMS_SUMMARY_CAPACITY_REACHED')
}

function parseHistory(content) {
  let parsed
  try { parsed = JSON.parse(content) } catch { fail('PMS_SUMMARY_STORE_INVALID') }
  // JSON.parse alone silently discards duplicate keys, including escaped spellings.
  // Syntax has already been checked; this token walk only guards that lossy case.
  const stack = []
  for (const [token] of content.matchAll(/"(?:[^"\\]|\\[\s\S])*"|[{}\[\],]/gu)) {
    if (token === '{') stack.push({ keys: new Set(), expectsKey: true })
    else if (token === '[') stack.push({})
    else if (token === '}' || token === ']') stack.pop()
    else if (token === ',') {
      if (stack.at(-1)?.keys) stack.at(-1).expectsKey = true
    } else if (stack.at(-1)?.expectsKey) {
      const frame = stack.at(-1)
      const key = JSON.parse(token)
      if (frame.keys.has(key)) fail('PMS_SUMMARY_STORE_CONFLICT')
      frame.keys.add(key)
      frame.expectsKey = false
    }
  }
  return parsed
}

function privateDirectory(path) {
  mkdirSync(path, { recursive: true, mode: 0o700 })
  const stat = lstatSync(path)
  if (!stat.isDirectory() || stat.isSymbolicLink()) fail('PMS_SUMMARY_PATH_UNSAFE')
}

function writeDurableFile(path, content) {
  const fd = openSync(path, 'wx', 0o600)
  try {
    writeFileSync(fd, content, 'utf8')
    fsyncSync(fd)
  } finally { closeSync(fd) }
}

function archiveRecord(root, row) {
  const content = `${JSON.stringify(row)}\n`
  const digest = createHash('sha256').update(content).digest('hex')
  const monthDirectory = join(root, row.period.from.slice(0, 7))
  privateDirectory(monthDirectory)
  const destination = join(monthDirectory, `${digest}.json`)
  if (existsSync(destination)) {
    ordinaryFile(destination, MAX_BYTES)
    if (readFileSync(destination, 'utf8') !== content) fail('PMS_SUMMARY_ARCHIVE_CONFLICT')
    return
  }
  const temporary = join(monthDirectory, `.${randomUUID()}.tmp`)
  try {
    writeDurableFile(temporary, content)
    // A hard link publishes the complete file exclusively; never overwrite an archive revision.
    linkSync(temporary, destination)
  } finally {
    if (existsSync(temporary)) unlinkSync(temporary)
  }
}

/** Persists only already-sanitized aggregates, without changing their business verification state. */
export function persistPmsSummary({ outputPath, record, maximumBytes = MAX_BYTES }) {
  if (typeof outputPath !== 'string' || !outputPath.trim()
      || !Number.isSafeInteger(maximumBytes) || maximumBytes < 1 || maximumBytes > MAX_BYTES) {
    fail('PMS_SUMMARY_STORE_ARGUMENT_INVALID')
  }
  const destination = resolve(outputPath)
  const lockPath = `${destination}.lock`
  const archiveRoot = `${destination}.history`
  const temporary = `${destination}.${randomUUID()}.tmp`
  let lock
  try {
    mkdirSync(dirname(destination), { recursive: true, mode: 0o700 })
    try { lock = openSync(lockPath, 'wx', 0o600) }
    catch (error) {
      if (error.code === 'EEXIST') fail('PMS_SUMMARY_STORE_BUSY')
      throw error
    }
    writeFileSync(lock, JSON.stringify({ pid: process.pid, startedAt: new Date().toISOString() }))
    let existing = { schemaVersion: 1, records: [] }
    if (existsSync(destination)) {
      ordinaryFile(destination, maximumBytes)
      existing = parseHistory(readFileSync(destination, 'utf8'))
    }
    const merged = mergePmsSummaryRecords(existing, record)
    const content = `${JSON.stringify(merged, null, 2)}\n`
    if (merged.records.length > MAX_RECORDS || Buffer.byteLength(content) > maximumBytes) {
      fail('PMS_SUMMARY_CAPACITY_REACHED')
    }
    privateDirectory(archiveRoot)
    // Archive legacy records as well before the first replacement of an existing store.
    for (const row of merged.records) archiveRecord(archiveRoot, row)
    writeDurableFile(temporary, content)
    renameSync(temporary, destination)
    return { recordCount: merged.records.length, archivedRecordCount: merged.records.length }
  } catch (error) {
    if (String(error.message).startsWith('PMS_SUMMARY_')) throw error
    // The collector prints error messages: never leak paths, credentials or record contents.
    fail('PMS_SUMMARY_STORE_IO_FAILED')
  } finally {
    if (existsSync(temporary)) unlinkSync(temporary)
    if (lock !== undefined) {
      closeSync(lock)
      unlinkSync(lockPath)
    }
  }
}
