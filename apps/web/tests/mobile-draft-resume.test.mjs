import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  buildResumableDraftKey,
  readResumableDraft,
  removeResumableDraft,
  writeResumableDraft,
} from '../src/app/resumableDrafts.ts'

const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
const reports = readFileSync(new URL('../src/features/dailyReports/DailyReportRoutes.tsx', import.meta.url), 'utf8')
const storage = readFileSync(new URL('../src/app/resumableDrafts.ts', import.meta.url), 'utf8')

class MemoryStorage {
  #values = new Map()

  getItem(key) { return this.#values.get(key) ?? null }
  setItem(key, value) { this.#values.set(key, String(value)) }
  removeItem(key) { this.#values.delete(key) }
  clear() { this.#values.clear() }
}

function createFakeIndexedDb({ putDelayMs = 0 } = {}) {
  const records = new Map()
  const controls = { failDeletes: false }

  const createDatabase = () => ({
    objectStoreNames: { contains: () => true },
    createObjectStore: () => undefined,
    close: () => undefined,
    transaction: () => {
      const transaction = {
        error: null,
        onabort: null,
        oncomplete: null,
        onerror: null,
        objectStore: () => ({
          get(key) {
            const request = { error: null, onsuccess: null, onerror: null, result: undefined }
            setTimeout(() => {
              request.result = records.get(key)
              request.onsuccess?.()
              queueMicrotask(() => transaction.oncomplete?.())
            }, 0)
            return request
          },
          put(value) {
            setTimeout(() => {
              records.set(value.key, value)
              transaction.oncomplete?.()
            }, putDelayMs)
          },
          delete(key) {
            setTimeout(() => {
              if (controls.failDeletes) {
                transaction.error = new Error('simulated delete failure')
                transaction.onerror?.()
                return
              }
              records.delete(key)
              transaction.oncomplete?.()
            }, 0)
          },
        }),
      }
      return transaction
    },
  })

  return {
    controls,
    records,
    indexedDB: {
      open() {
        const request = {
          error: null,
          onblocked: null,
          onerror: null,
          onsuccess: null,
          onupgradeneeded: null,
          result: undefined,
        }
        setTimeout(() => {
          request.result = createDatabase()
          request.onsuccess?.()
        }, 0)
        return request
      },
    },
  }
}

async function withDraftStorage(options, run) {
  const indexedDbDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'indexedDB')
  const localStorageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const fake = createFakeIndexedDb(options)
  Object.defineProperty(globalThis, 'indexedDB', { configurable: true, value: fake.indexedDB })
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: new MemoryStorage() })
  try {
    await run(fake)
  } finally {
    if (indexedDbDescriptor) Object.defineProperty(globalThis, 'indexedDB', indexedDbDescriptor)
    else delete globalThis.indexedDB
    if (localStorageDescriptor) Object.defineProperty(globalThis, 'localStorage', localStorageDescriptor)
    else delete globalThis.localStorage
  }
}

function hydrationEffect(source, readMarker) {
  const readIndex = source.indexOf(readMarker)
  assert.notEqual(readIndex, -1, `missing hydration marker: ${readMarker}`)
  const start = source.lastIndexOf('useEffect(() => {', readIndex)
  const end = source.indexOf('return () => { active = false }', readIndex)
  assert.notEqual(start, -1, `missing hydration effect before: ${readMarker}`)
  assert.notEqual(end, -1, `missing hydration cleanup after: ${readMarker}`)
  return source.slice(start, end)
}

function assertHydrationFailureDoesNotEnableAutosave(source, readMarker) {
  const effect = hydrationEffect(source, readMarker)
  const catchIndex = effect.indexOf('.catch(')
  assert.notEqual(catchIndex, -1, `hydration must handle storage failure: ${readMarker}`)
  assert.match(effect.slice(0, catchIndex), /setDraftHydrated\(true\)/)
  assert.doesNotMatch(effect.slice(catchIndex), /setDraftHydrated\(true\)/)
}

function assertLatestSnapshotFlushesOnUnmount(source, refAssignmentMarker) {
  const refIndex = source.indexOf(refAssignmentMarker)
  assert.notEqual(refIndex, -1, `missing synchronous ref update: ${refAssignmentMarker}`)
  const debounceIndex = source.indexOf('window.setTimeout(', refIndex)
  assert.ok(debounceIndex > refIndex, `draft ref must update before the debounce starts: ${refAssignmentMarker}`)

  const pagehideIndex = source.indexOf("window.addEventListener('pagehide', flushDraft)", debounceIndex)
  assert.notEqual(pagehideIndex, -1, `missing pagehide flush after: ${refAssignmentMarker}`)
  const effectStart = source.lastIndexOf('useEffect(() => {', pagehideIndex)
  const effectEnd = source.indexOf('}, [', pagehideIndex)
  const effect = source.slice(effectStart, effectEnd)
  const cleanupIndex = effect.indexOf('return () => {')
  assert.notEqual(cleanupIndex, -1, `missing lifecycle cleanup after: ${refAssignmentMarker}`)
  const cleanup = effect.slice(cleanupIndex)
  assert.match(cleanup, /removeEventListener\('pagehide', flushDraft\)/)
  assert.match(cleanup, /removeEventListener\('visibilitychange', flushWhenHidden\)/)
  assert.match(cleanup, /flushDraft\(\)/)
}

function assertHydrationGateBlocksEditor(source, componentMarker, editorMarker) {
  const componentStart = source.indexOf(componentMarker)
  assert.notEqual(componentStart, -1, `missing component: ${componentMarker}`)
  const settledState = source.indexOf('const [draftHydrationSettled, setDraftHydrationSettled] = useState(false)', componentStart)
  const reset = source.indexOf('setDraftHydrationSettled(false)', settledState)
  const settled = source.indexOf('setDraftHydrationSettled(true)', reset)
  const gate = source.indexOf('!draftHydrationSettled)', settled)
  const editor = source.indexOf(editorMarker, gate)
  assert.ok(settledState >= componentStart, `missing hydration settled state: ${componentMarker}`)
  assert.ok(reset > settledState, `hydration gate must reset before every read: ${componentMarker}`)
  assert.ok(settled > reset, `hydration gate must settle after the read: ${componentMarker}`)
  assert.ok(gate > settled, `missing render gate after hydration: ${componentMarker}`)
  assert.ok(editor > gate, `editable controls must render after the hydration gate: ${componentMarker}`)
  assert.match(source.slice(gate, editor), /恢复完成前暂不开放编辑/)
}

test('draft keys isolate tenant, account, assignment and work item', () => {
  const first = buildResumableDraftKey('work-record-form', ['tenant-a', 'account-a', 'assignment-a', 'work-a', '2026-09-17'])
  const second = buildResumableDraftKey('work-record-form', ['tenant-a', 'account-a', 'assignment-b', 'work-a', '2026-09-17'])
  assert.notEqual(first, second)
  assert.match(first, /^work-record-form:/)
})

test('resumable storage keeps binary files in IndexedDB with expiry and serialized writes', () => {
  assert.match(storage, /indexedDB\.open\(DATABASE_NAME/)
  assert.match(storage, /blob: file/)
  assert.match(storage, /DRAFT_TTL_MS = 7 \* 24 \* 60 \* 60 \* 1000/)
  assert.match(storage, /const writeChains = new Map/)
})

test('a read waits for the queued write of the same draft key', async () => {
  await withDraftStorage({ putDelayMs: 30 }, async () => {
    const key = `queued-read-${Date.now()}`
    const writing = writeResumableDraft(key, { text: 'last keystroke' })
    const restored = await readResumableDraft(key)
    await writing
    assert.deepEqual(restored, { text: 'last keystroke' })
  })
})

test('a removal tombstone prevents a stale IndexedDB record from being restored', async () => {
  await withDraftStorage({}, async ({ controls, records }) => {
    const key = `tombstone-${Date.now()}`
    await writeResumableDraft(key, { text: 'must stay deleted' })
    assert.equal(records.has(key), true)

    controls.failDeletes = true
    await removeResumableDraft(key)
    assert.equal(records.has(key), true, 'the test keeps the stale IndexedDB value to reproduce resurrection')
    assert.equal(await readResumableDraft(key), undefined)

    controls.failDeletes = false
    await writeResumableDraft(key, { text: 'new intentional draft' })
    assert.deepEqual(await readResumableDraft(key), { text: 'new intentional draft' })
  })
})

test('work record restores form, pending files and server draft after a mobile reload', () => {
  assert.match(app, /readResumableDraft<WorkRecordFormDraft>\(formDraftKey\)/)
  assert.match(app, /readResumableDraft<StoredWorkEvidence\[]>\(filesDraftKey\)/)
  assert.match(app, /serverRecordId: activeRecordIdRef\.current/)
  assert.match(app, /window\.addEventListener\('pagehide', flushDraft\)/)
  assert.match(app, /document\.addEventListener\('visibilitychange', flushWhenHidden\)/)
  assert.match(app, /expectationId: item\.id/)
})

test('a locked work record preserves pending local files until explicit export or discard', () => {
  const lockedStart = app.indexOf('if (serverLocked) {')
  const lockedEnd = app.indexOf('} else if (localConflictsWithServer', lockedStart)
  assert.notEqual(lockedStart, -1)
  assert.notEqual(lockedEnd, -1)
  const lockedBranch = app.slice(lockedStart, lockedEnd)
  assert.match(lockedBranch, /removeResumableDraft\(formDraftKey\)/)
  assert.doesNotMatch(lockedBranch, /removeResumableDraft\(filesDraftKey\)/)

  assert.match(app, /const downloadPendingAttachment = \(entry: PendingWorkEvidence\) =>/)
  assert.match(app, /anchor\.download = entry\.file\.name/)
  assert.match(app, /const discardLockedPendingAttachments = async \(\) =>/)
  assert.match(app, /window\.confirm\(`确认丢弃本机保留的 \$\{attachments\.length\} 个未上传附件吗？此操作不可恢复。`\)/)
  assert.match(app, /await removeResumableDraft\(filesDraftKey\)/)
  assert.match(app, /本机还有 \{attachments\.length\} 个未上传附件/)
})

test('task remark and pending evidence survive WebView recycle and deep link reopening', () => {
  assert.match(app, /readResumableDraft<TaskFormDraft>\(taskFormDraftKey\)/)
  assert.match(app, /readResumableDraft<StoredDraftFile\[]>\(taskFilesDraftKey\)/)
  assert.match(app, /taskId: task\.id/)
  assert.match(app, /已恢复上次未完成的处理说明/)
})

test('task result submission is blocked while evidence is still waiting for upload', () => {
  const runStart = app.indexOf('const run = async (command: string) => {')
  const runEnd = app.indexOf('const evaluateResult = async () => {', runStart)
  assert.notEqual(runStart, -1)
  assert.notEqual(runEnd, -1)
  const run = app.slice(runStart, runEnd)
  const guardIndex = run.indexOf("command === 'submit-result'")
  const pendingIndex = run.indexOf('evidenceFiles.length', guardIndex)
  const returnIndex = run.indexOf('return', pendingIndex)
  const commandTaskIndex = run.indexOf('commandTask(', returnIndex)
  const apiCommandIndex = run.indexOf('apiCommand(', returnIndex)
  const mutationIndex = [commandTaskIndex, apiCommandIndex].filter((index) => index >= 0).sort((left, right) => left - right)[0] ?? -1
  assert.notEqual(guardIndex, -1)
  assert.notEqual(pendingIndex, -1)
  assert.notEqual(returnIndex, -1)
  assert.ok(mutationIndex > returnIndex, 'the pending-evidence return must run before the task mutation')
  assert.match(run, /待上传|先上传/)
})

test('draft hydration failures keep autosave disabled instead of overwriting an unread draft', () => {
  assertHydrationFailureDoesNotEnableAutosave(app, 'readResumableDraft<WorkRecordFormDraft>(formDraftKey)')
  assertHydrationFailureDoesNotEnableAutosave(app, 'readResumableDraft<TaskFormDraft>(taskFormDraftKey)')
  assertHydrationFailureDoesNotEnableAutosave(reports, 'readResumableDraft<DailyReportLocalDraft>(localDraftKey)')
})

test('task and daily report editors stay gated until local draft reads settle', () => {
  assertHydrationGateBlocksEditor(app, 'function TaskDetail(', '<textarea rows={3} value={remark}')
  assertHydrationGateBlocksEditor(reports, 'function ReportEditor(', '<textarea rows={')
})

test('latest text snapshots are captured before debounce and flushed when a component unmounts', () => {
  assertLatestSnapshotFlushesOnUnmount(app, 'formDraftRef.current = snapshot')
  assertLatestSnapshotFlushesOnUnmount(app, 'taskFormDraftRef.current = snapshot')
  assertLatestSnapshotFlushesOnUnmount(reports, 'localDraftRef.current = snapshot')
})

test('daily report local draft is restored and current values are saved before submit', () => {
  assert.match(reports, /readResumableDraft<DailyReportLocalDraft>\(localDraftKey\)/)
  assert.match(reports, /已恢复上次未提交的日报内容/)
  assert.match(reports, /saveDailyReportDraft\(identity, report, input, `\$\{key\}-save`\)/)
  assert.match(reports, /submitDailyReport\(identity, saved, `\$\{key\}-submit`\)/)
})

test('daily report drafts stop on row-version conflict until the user chooses a version', () => {
  assert.match(reports, /type DailyReportLocalDraft = \{[\s\S]*baseRowVersion: number[\s\S]*\}/)
  assert.match(reports, /stored\.baseRowVersion !== report\.rowVersion[\s\S]*setConflictingLocalDraft\(stored\)/)
  assert.match(reports, /if \(!editable \|\| !draftHydrated \|\| conflictingLocalDraft\) return/)

  const lifecycleStart = reports.indexOf("window.addEventListener('pagehide', flushDraft)")
  const lifecycleEffectStart = reports.lastIndexOf('useEffect(() => {', lifecycleStart)
  const lifecycleEffect = reports.slice(lifecycleEffectStart, lifecycleStart)
  assert.match(lifecycleEffect, /if \(!editable \|\| !draftHydrated \|\| conflictingLocalDraft\) return/)

  const runStart = reports.indexOf("const run = async (action: 'save' | 'submit'")
  const commandStart = reports.indexOf('await command.run(', runStart)
  const runPrefix = reports.slice(runStart, commandStart)
  assert.match(runPrefix, /if \(conflictingLocalDraft && \(action === 'save' \|\| action === 'submit'\)\)/)
  assert.match(runPrefix, /return/)
  assert.match(reports, /发现两个版本的未提交日报/)
  assert.match(reports, /使用服务器最新草稿/)
  assert.match(reports, /恢复本机未提交内容/)
})

test('daily report required-field count excludes rows explicitly marked as exceptions', () => {
  const countStart = reports.indexOf('const currentMissingRequiredCount =')
  const countEnd = reports.indexOf('const reviewingHistoricalRevision', countStart)
  assert.notEqual(countStart, -1)
  assert.notEqual(countEnd, -1)
  const countExpression = reports.slice(countStart, countEnd)
  assert.match(countExpression, /item\.required/)
  assert.match(countExpression, /!exceptions\.has\(item\.id\)/)
})
