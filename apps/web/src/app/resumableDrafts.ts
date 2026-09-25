const DATABASE_NAME = 'hotel-ai-resumable-drafts'
const DATABASE_VERSION = 1
const STORE_NAME = 'drafts'
const STORAGE_PREFIX = 'hotel-ai-resumable-draft:'
const TOMBSTONE_PREFIX = 'hotel-ai-resumable-draft-removed:'
const DRAFT_TTL_MS = 7 * 24 * 60 * 60 * 1000
const writeChains = new Map<string, Promise<void>>()

type DraftEnvelope<T> = {
  key: string
  version: 1
  updatedAt: string
  expiresAt: number
  value: T
}

export type StoredDraftFile = {
  id: string
  blob: Blob
  name: string
  type: string
  lastModified: number
}

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('设备草稿读取失败'))
  })
}

function transactionComplete(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(transaction.error ?? new Error('设备草稿保存失败'))
    transaction.onabort = () => reject(transaction.error ?? new Error('设备草稿保存被中断'))
  })
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) {
      reject(new Error('当前浏览器不支持离线草稿'))
      return
    }
    const request = globalThis.indexedDB.open(DATABASE_NAME, DATABASE_VERSION)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME, { keyPath: 'key' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('设备草稿数据库打开失败'))
    request.onblocked = () => reject(new Error('设备草稿数据库正被旧页面占用'))
  })
}

function fallbackKey(key: string) {
  return `${STORAGE_PREFIX}${key}`
}

function tombstoneKey(key: string) {
  return `${TOMBSTONE_PREFIX}${key}`
}

function hasRemovalTombstone(key: string): boolean {
  try {
    const raw = globalThis.localStorage?.getItem(tombstoneKey(key))
    if (!raw) return false
    const expiresAt = Number(raw)
    if (Number.isFinite(expiresAt) && expiresAt > Date.now()) return true
    globalThis.localStorage?.removeItem(tombstoneKey(key))
  } catch { /* IndexedDB remains authoritative when fallback storage is unavailable */ }
  return false
}

function markRemovalTombstone(key: string): boolean {
  try {
    if (!globalThis.localStorage) return false
    globalThis.localStorage.setItem(tombstoneKey(key), String(Date.now() + DRAFT_TTL_MS))
    return true
  } catch {
    return false
  }
}

function clearRemovalTombstone(key: string) {
  try { globalThis.localStorage?.removeItem(tombstoneKey(key)) } catch { /* ignore unavailable fallback storage */ }
}

function containsBinary(value: unknown, seen = new Set<unknown>()): boolean {
  if (typeof Blob !== 'undefined' && value instanceof Blob) return true
  if (!value || typeof value !== 'object' || seen.has(value)) return false
  seen.add(value)
  if (Array.isArray(value)) return value.some((entry) => containsBinary(entry, seen))
  return Object.values(value as Record<string, unknown>).some((entry) => containsBinary(entry, seen))
}

function readFallback<T>(key: string): T | undefined {
  try {
    const raw = globalThis.localStorage?.getItem(fallbackKey(key))
    if (!raw) return undefined
    const envelope = JSON.parse(raw) as DraftEnvelope<T>
    if (envelope.version !== 1 || envelope.expiresAt <= Date.now()) {
      globalThis.localStorage?.removeItem(fallbackKey(key))
      return undefined
    }
    return envelope.value
  } catch {
    return undefined
  }
}

function writeFallback<T>(envelope: DraftEnvelope<T>): void {
  if (containsBinary(envelope.value)) {
    throw new Error('设备暂存空间不可用，照片尚未安全保存；请保持页面打开并尽快保存草稿。')
  }
  try {
    if (!globalThis.localStorage) throw new Error('local storage unavailable')
    globalThis.localStorage.setItem(fallbackKey(envelope.key), JSON.stringify(envelope))
  } catch {
    throw new Error('设备暂存空间不足，请先保存或提交当前草稿。')
  }
}

export function buildResumableDraftKey(kind: 'work-record-form' | 'work-record-files' | 'task-form' | 'task-files' | 'daily-report-form' | 'daily-activity-photos' | 'lost-property-action', parts: Array<string | undefined>) {
  return [kind, ...parts.map((part) => encodeURIComponent(part || 'none'))].join(':')
}

export function toStoredDraftFile(file: File, id?: string): StoredDraftFile {
  return {
    id: id ?? globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    blob: file,
    name: file.name,
    type: file.type,
    lastModified: file.lastModified,
  }
}

export function fromStoredDraftFile(stored: StoredDraftFile): File {
  return new File([stored.blob], stored.name, {
    type: stored.type || stored.blob.type,
    lastModified: stored.lastModified,
  })
}

export function isStoredDraftFile(value: unknown): value is StoredDraftFile {
  if (!value || typeof value !== 'object') return false
  const file = value as Partial<StoredDraftFile>
  return typeof file.id === 'string'
    && typeof file.name === 'string'
    && typeof file.lastModified === 'number'
    && typeof Blob !== 'undefined'
    && file.blob instanceof Blob
}

export async function readResumableDraft<T>(key: string): Promise<T | undefined> {
  const pendingWrite = writeChains.get(key)
  if (pendingWrite) await pendingWrite
  if (hasRemovalTombstone(key)) return undefined
  let database: IDBDatabase | undefined
  try {
    database = await openDatabase()
    const transaction = database.transaction(STORE_NAME, 'readonly')
    const completed = transactionComplete(transaction)
    const envelope = await requestResult(transaction.objectStore(STORE_NAME).get(key)) as DraftEnvelope<T> | undefined
    await completed
    if (!envelope) return readFallback<T>(key)
    if (envelope.version !== 1 || envelope.expiresAt <= Date.now()) {
      await removeResumableDraft(key)
      return undefined
    }
    return envelope.value
  } catch (error) {
    const fallback = readFallback<T>(key)
    if (fallback !== undefined || !globalThis.indexedDB) return fallback
    throw error
  } finally {
    database?.close()
  }
}

async function writeDraftNow<T>(key: string, value: T): Promise<void> {
  const envelope: DraftEnvelope<T> = {
    key,
    version: 1,
    updatedAt: new Date().toISOString(),
    expiresAt: Date.now() + DRAFT_TTL_MS,
    value,
  }
  let database: IDBDatabase | undefined
  try {
    database = await openDatabase()
    const transaction = database.transaction(STORE_NAME, 'readwrite')
    const completed = transactionComplete(transaction)
    transaction.objectStore(STORE_NAME).put(envelope)
    await completed
    try { globalThis.localStorage?.removeItem(fallbackKey(key)) } catch { /* IndexedDB copy is authoritative */ }
    clearRemovalTombstone(key)
  } catch {
    writeFallback(envelope)
    clearRemovalTombstone(key)
  } finally {
    database?.close()
  }
}

function enqueueDraftWrite(key: string, operation: () => Promise<void>): Promise<void> {
  const previous = writeChains.get(key) ?? Promise.resolve()
  const next = previous.catch(() => undefined).then(operation)
  writeChains.set(key, next)
  void next.finally(() => {
    if (writeChains.get(key) === next) writeChains.delete(key)
  }).catch(() => undefined)
  return next
}

export function writeResumableDraft<T>(key: string, value: T): Promise<void> {
  return enqueueDraftWrite(key, () => writeDraftNow(key, value))
}

async function removeDraftNow(key: string): Promise<void> {
  try { globalThis.localStorage?.removeItem(fallbackKey(key)) } catch { /* ignore unavailable fallback storage */ }
  const tombstoned = markRemovalTombstone(key)
  let database: IDBDatabase | undefined
  try {
    database = await openDatabase()
    const transaction = database.transaction(STORE_NAME, 'readwrite')
    const completed = transactionComplete(transaction)
    transaction.objectStore(STORE_NAME).delete(key)
    await completed
  } catch (error) {
    if (!tombstoned && globalThis.indexedDB) throw error
  } finally {
    database?.close()
  }
}

export function removeResumableDraft(key: string): Promise<void> {
  return enqueueDraftWrite(key, () => removeDraftNow(key))
}
