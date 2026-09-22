import type { LocalDraft, PendingEvidence } from './model'

const DB = 'hotel-finance-private-v1'
const TTL = 7 * 24 * 60 * 60 * 1000
type CipherRecord = { key: string; scope: string; savedAt: number; iv: Uint8Array<ArrayBuffer>; ciphertext: ArrayBuffer; etag: string; writer: string }
const queues = new Map<string, Promise<unknown>>()
const seenVersions = new Map<string, string>()
let writerId: string | undefined
function request<T>(req: IDBRequest<T>): Promise<T> { return new Promise((resolve, reject) => { req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error) }) }
function done(tx: IDBTransaction): Promise<void> { return new Promise((resolve, reject) => { tx.oncomplete = () => resolve(); tx.onabort = tx.onerror = () => reject(tx.error ?? new Error('本机存储失败')) }) }
async function open(): Promise<IDBDatabase> {
  if (!globalThis.crypto?.subtle || !globalThis.indexedDB) throw new Error('此浏览器不支持安全本机草稿，请保持联网并保存到云端')
  const req = indexedDB.open(DB, 1)
  req.onupgradeneeded = () => { req.result.createObjectStore('keys'); const s = req.result.createObjectStore('records', { keyPath: 'key' }); s.createIndex('scope', 'scope') }
  return request(req)
}
async function cryptoKey(db: IDBDatabase, scope: string): Promise<CryptoKey> {
  const existing = await request<CryptoKey | undefined>(db.transaction('keys').objectStore('keys').get(scope))
  if (existing) return existing
  const candidate = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  const tx = db.transaction('keys', 'readwrite'); const completion = done(tx)
  tx.objectStore('keys').add(candidate, scope)
  try { await completion; return candidate } catch {
    // Another tab may have created the same account/assignment key. Never overwrite it.
    const winner = await request<CryptoKey | undefined>(db.transaction('keys').objectStore('keys').get(scope))
    if (winner) return winner
    throw new Error('无法创建安全本机草稿，请保持页面打开并保存到云端')
  }
}
const aad = (key: string) => new TextEncoder().encode(key)
async function encrypt(key: CryptoKey, scope: string, id: string, bytes: ArrayBuffer): Promise<CipherRecord> {
  const iv = crypto.getRandomValues(new Uint8Array(12))
  return { key: id, scope, savedAt: Date.now(), iv, etag: crypto.randomUUID(), writer: writerId ??= crypto.randomUUID(), ciphertext: await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: aad(id) }, key, bytes) }
}
async function decrypt(key: CryptoKey, record: CipherRecord): Promise<ArrayBuffer> {
  return crypto.subtle.decrypt({ name: 'AES-GCM', iv: record.iv, additionalData: aad(record.key) }, key, record.ciphertext)
}
export function saveFinanceDraft(scope: string, id: string, draft: LocalDraft): Promise<number> {
  const queueKey = `${scope}/${id}`
  const previous = queues.get(queueKey) ?? Promise.resolve()
  const next = previous.catch(() => undefined).then(async () => {
    if (draft.files.reduce((sum, f) => sum + f.file.size, 0) > 100 * 1024 * 1024) throw new Error('本机待上传凭证超过100MB，请先联网保存部分凭证')
    const db = await open()
    try {
      const key = await cryptoKey(db, scope); const additions: CipherRecord[] = []
      for (const f of draft.files) {
        const fileKey = `${queueKey}/file/${f.id}`
        const stored = await request<CipherRecord | undefined>(db.transaction('records').objectStore('records').get(fileKey))
        if (!stored) additions.push(await encrypt(key, scope, fileKey, await f.file.arrayBuffer()))
      }
      const value = { form: draft.form, dirty: draft.dirty, files: draft.files.map(f => ({ id: f.id, kind: f.kind, name: f.file.name, type: f.file.type, lastModified: f.file.lastModified })) }
      const record = await encrypt(key, scope, queueKey, new TextEncoder().encode(JSON.stringify(value)).buffer)
      const tx = db.transaction('records', 'readwrite'); const completion = done(tx)
      let stale = false
      const check = tx.objectStore('records').get(queueKey)
      check.onsuccess = () => {
        const previous = check.result as CipherRecord | undefined
        if (previous && previous.etag !== seenVersions.get(queueKey) && previous.writer !== writerId) { stale = true; tx.abort(); return }
        for (const file of additions) tx.objectStore('records').put(file)
        tx.objectStore('records').put(record)
      }
      try { await completion } catch (error) { if (stale) throw new Error('另一页面已更新本机草稿，本页未覆盖。请保留当前内容并核对另一个页面。'); throw error }
      seenVersions.set(queueKey, record.etag)
      return record.savedAt
    } finally { db.close() }
  })
  queues.set(queueKey, next)
  void next.finally(() => { if (queues.get(queueKey) === next) queues.delete(queueKey) }).catch(() => undefined)
  return next
}
export async function loadFinanceDraft(scope: string, id: string): Promise<(LocalDraft & { savedAt: number }) | null> {
  const db = await open(); const queueKey = `${scope}/${id}`
  try {
    const record = await request<CipherRecord | undefined>(db.transaction('records').objectStore('records').get(queueKey))
    if (!record || record.scope !== scope) return null
    if (record.savedAt < Date.now() - TTL) { await erase(db, scope, id); return null }
    const key = await cryptoKey(db, scope)
    const data = JSON.parse(new TextDecoder().decode(await decrypt(key, record))) as { form: LocalDraft['form']; dirty: boolean; files: { id: string; kind: PendingEvidence['kind']; name: string; type: string; lastModified: number }[] }
    const files: PendingEvidence[] = []
    for (const f of data.files) {
      const encrypted = await request<CipherRecord | undefined>(db.transaction('records').objectStore('records').get(`${queueKey}/file/${f.id}`))
      if (!encrypted) throw new Error('部分本机凭证缺失，请核对云端附件后重新添加；不会静默丢弃附件')
      files.push({ id: f.id, kind: f.kind, file: new File([await decrypt(key, encrypted)], f.name, { type: f.type, lastModified: f.lastModified }) })
    }
    seenVersions.set(queueKey, record.etag)
    return { form: data.form, dirty: data.dirty, files, savedAt: record.savedAt }
  } finally { db.close() }
}
async function erase(db: IDBDatabase, scope: string, id: string) {
  const tx = db.transaction('records', 'readwrite'); const completion = done(tx)
  const req = tx.objectStore('records').index('scope').openCursor(IDBKeyRange.only(scope))
  const prefix = `${scope}/${id}`
  req.onsuccess = () => { const c = req.result; if (c) { if (c.value.key === prefix || c.value.key.startsWith(`${prefix}/file/`)) c.delete(); c.continue() } }
  await completion
  seenVersions.delete(`${scope}/${id}`)
}
export async function removeFinanceDraft(scope: string, id: string) {
  await queues.get(`${scope}/${id}`)?.catch(() => undefined)
  const db = await open(); try { await erase(db, scope, id) } finally { db.close() }
}
