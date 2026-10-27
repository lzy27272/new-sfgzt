import assert from 'node:assert/strict'
import { test } from 'node:test'
import { saveFinanceDraft, loadFinanceDraft, removeFinanceDraft, savePrivateFinanceForm, loadPrivateFinanceForm, removePrivateFinanceForm } from '../src/features/finance/encryptedDrafts.ts'

// Minimal asynchronous IndexedDB double; crypto is the real WebCrypto implementation.
function fakeDatabase() {
  const tables = { keys: new Map(), records: new Map() }; const controls = { failWrites: false }
  const db = { close() {}, transaction(name) {
    let pending = 0, aborted = false, completed = false
    const tx = { error: null, abort() { aborted = true; queueMicrotask(() => tx.onabort?.()) }, objectStore() { return store } }
    function complete() { setTimeout(() => { if (!pending && !aborted && !completed) { completed = true; tx.oncomplete?.() } }, 0) }
    function req(fn, write = false) {
      const request = {}; pending++
      setTimeout(() => {
        if (aborted) return
        try {
          if (write && controls.failWrites) throw new DOMException('Quota exceeded', 'QuotaExceededError')
          request.result = fn(); request.onsuccess?.()
        } catch (e) { request.error = e; tx.error = e; request.onerror?.(); aborted = true; tx.onerror?.() }
        pending--; complete()
      }, 0)
      return request
    }
    const store = {
      get(key) { return req(() => tables[name].get(key)) },
      put(value, key) { return req(() => tables[name].set(key ?? value.key, value), true) },
      add(value, key) { return req(() => { if (tables[name].has(key)) throw new DOMException('duplicate', 'ConstraintError'); tables[name].set(key, value) }, true) },
      index() { return { openCursor(scope) {
        const request = {}; const rows = [...tables[name].entries()].filter(([, value]) => value.scope === scope); let index = 0
        const next = () => { pending++; setTimeout(() => { const row = rows[index++]; request.result = row ? { value: row[1], delete() { tables[name].delete(row[0]) }, continue: next } : null; request.onsuccess?.(); pending--; complete() }, 0) }
        next(); return request
      } } },
    }
    return tx
  } }
  return { tables, controls, indexedDB: { open() { const request = { result: db }; setTimeout(() => request.onsuccess?.(), 0); return request } } }
}
async function withDb(run) {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'indexedDB'); const previousRange = Object.getOwnPropertyDescriptor(globalThis, 'IDBKeyRange')
  const fixture = fakeDatabase(); Object.defineProperty(globalThis, 'indexedDB', { configurable: true, value: fixture.indexedDB }); Object.defineProperty(globalThis, 'IDBKeyRange', { configurable: true, value: { only: x => x } })
  try { await run(fixture) } finally {
    if (previous) Object.defineProperty(globalThis, 'indexedDB', previous); else delete globalThis.indexedDB
    if (previousRange) Object.defineProperty(globalThis, 'IDBKeyRange', previousRange); else delete globalThis.IDBKeyRange
  }
}
const draft = () => ({ form: { title: '私密报销内容', expectedVersion: 0, invoices: [] }, dirty: true, files: [{ id: 'file1', kind: 'INVOICE', file: new File(['original-invoice-bytes'], 'receipt.pdf', { type: 'application/pdf' }) }] })
test('financial draft and files are encrypted, recover byte-identically, and isolate accounts', async () => withDb(async ({ tables }) => {
  const scope = 'finance:v1:tenant:employee:assignment'
  await saveFinanceDraft(scope, 'c1', draft())
  assert.equal(tables.keys.get(scope).extractable, false)
  const stored = JSON.stringify([...tables.records.values()])
  assert.ok(!stored.includes('私密报销内容')); assert.ok(!stored.includes('receipt.pdf')); assert.ok(!stored.includes('original-invoice-bytes'))
  const restored = await loadFinanceDraft(scope, 'c1')
  assert.equal(restored.form.title, '私密报销内容'); assert.equal(await restored.files[0].file.text(), 'original-invoice-bytes')
  assert.equal(await loadFinanceDraft('finance:v1:tenant:other:assignment', 'c1'), null)
  await removeFinanceDraft(scope, 'c1'); assert.equal(await loadFinanceDraft(scope, 'c1'), null); assert.equal(tables.records.size, 0)
}))
test('rapid updates serialize so the final input survives; unchanged files are reused', async () => withDb(async ({ tables }) => {
  const scope = 'queue-scope'
  await Promise.all(Array.from({ length: 5 }, (_, i) => saveFinanceDraft(scope, 'c1', { ...draft(), form: { ...draft().form, title: `最新内容${i}` } })))
  assert.equal((await loadFinanceDraft(scope, 'c1')).form.title, '最新内容4')
  assert.equal(tables.records.size, 2)
}))

test('saving removes only this draft files no longer referenced and reuses retained ciphertext', async () => withDb(async ({ tables }) => {
  const scope = 'cleanup-scope'; const original = draft()
  const extra = { ...original.files[0], id: 'file2' }
  await saveFinanceDraft(scope, 'c1', { ...original, files: [...original.files, extra] })
  await saveFinanceDraft(scope, 'c10', original)
  await saveFinanceDraft('cleanup-other-account', 'c1', original)
  await savePrivateFinanceForm(scope, 'travel:c1', { baseVersion: '0', values: { destination: '保留的差旅资料' } })
  const retained = tables.records.get(`${scope}/c1/file/file2`)
  await saveFinanceDraft(scope, 'c1', { ...original, files: [extra] })
  assert.equal(tables.records.has(`${scope}/c1/file/file1`), false)
  assert.equal(tables.records.get(`${scope}/c1/file/file2`), retained)
  assert.deepEqual((await loadFinanceDraft(scope, 'c1')).files.map(file => file.id), ['file2'])
  assert.equal(await (await loadFinanceDraft(scope, 'c10')).files[0].file.text(), 'original-invoice-bytes')
  assert.equal((await loadFinanceDraft('cleanup-other-account', 'c1')).files.length, 1)
  assert.equal((await loadPrivateFinanceForm(scope, 'travel:c1')).values.destination, '保留的差旅资料')
}))

test('repeated photo replacement or completed uploads do not accumulate abandoned encrypted files', async () => withDb(async ({ tables }) => {
  const scope = 'upload-cleanup'; const original = draft()
  for (let index = 0; index < 4; index++) {
    await saveFinanceDraft(scope, 'c1', { ...original, files: [{ ...original.files[0], id: `photo-${index}` }] })
    assert.equal(tables.records.size, 2)
  }
  await saveFinanceDraft(scope, 'c1', { ...original, files: [] })
  assert.equal(tables.records.size, 1)
  assert.equal((await loadFinanceDraft(scope, 'c1')).files.length, 0)
}))
test('quota failures are explicit and leave previous encrypted draft intact', async () => withDb(async ({ controls, tables }) => {
  await saveFinanceDraft('quota', 'c1', draft()); controls.failWrites = true
  await assert.rejects(saveFinanceDraft('quota', 'c1', { ...draft(), files: [], form: { ...draft().form, title: '未保存变更' } }), /Quota/)
  controls.failWrites = false
  assert.equal((await loadFinanceDraft('quota', 'c1')).form.title, '私密报销内容')
  assert.equal(await (await loadFinanceDraft('quota', 'c1')).files[0].file.text(), 'original-invoice-bytes')
  assert.equal(tables.records.size, 2)
}))
test('tampered ciphertext or missing encrypted files is never treated as an empty draft', async () => withDb(async ({ tables }) => {
  await saveFinanceDraft('integrity', 'c1', draft())
  const record = tables.records.get('integrity/c1'); new Uint8Array(record.ciphertext)[0] ^= 1
  await assert.rejects(loadFinanceDraft('integrity', 'c1'))
  await saveFinanceDraft('missing', 'c2', draft()); tables.records.delete('missing/c2/file/file1')
  await assert.rejects(loadFinanceDraft('missing', 'c2'), /部分本机凭证缺失/)
}))
test('unread changes by a different tab cannot be overwritten', async () => withDb(async ({ tables }) => {
  await saveFinanceDraft('tabs', 'c1', draft())
  const record = tables.records.get('tabs/c1'); record.writer = 'another-page'; record.etag = 'new-revision'
  await assert.rejects(saveFinanceDraft('tabs', 'c1', { ...draft(), files: [] }), /另一页面/)
  assert.ok(tables.records.has('tabs/c1/file/file1'), 'unread changes must abort before pruning another tab files')
}))

test('travel subform survives page destruction encrypted with its server version and assignment boundary', async () => withDb(async ({ tables }) => {
  const scope = 'tenant:employee:assignment'
  await savePrivateFinanceForm(scope, 'travel:claim1', { baseVersion: '7', values: { destination: '私人差旅目的地', lodgingAmount: '128.01', travelerIds: 'actual-account' } })
  assert.ok(!JSON.stringify([...tables.records.values()]).includes('私人差旅目的地'))
  assert.equal(tables.keys.get(scope).extractable, false)
  const restored = await loadPrivateFinanceForm(scope, 'travel:claim1')
  assert.equal(restored.baseVersion, '7'); assert.equal(restored.values.lodgingAmount, '128.01')
  assert.equal(await loadPrivateFinanceForm('tenant:employee:another-assignment', 'travel:claim1'), null)
  // Retaining the old version allows the UI to request explicit reconciliation, not silently overwrite version 8.
  assert.notEqual(restored.baseVersion, '8')
  await removePrivateFinanceForm(scope, 'travel:claim1'); assert.equal(await loadPrivateFinanceForm(scope, 'travel:claim1'), null)
}))

test('private subform serializes edits and rejects concurrent unread writes and tampering', async () => withDb(async ({ tables }) => {
  await Promise.all(['1', '2', '3'].map(amount => savePrivateFinanceForm('form-race', 'travel:1', { baseVersion: '0', values: { amount } })))
  assert.equal((await loadPrivateFinanceForm('form-race', 'travel:1')).values.amount, '3')
  const record = tables.records.get('form-race/form:travel:1'); record.writer = 'other-page'; record.etag = 'unread-version'
  await assert.rejects(savePrivateFinanceForm('form-race', 'travel:1', { baseVersion: '0', values: { amount: '4' } }), /另一页面/)
  new Uint8Array(record.ciphertext)[0] ^= 1
  await assert.rejects(loadPrivateFinanceForm('form-race', 'travel:1'))
}))
