import { useRef, useState } from 'react'
import { apiRequest, apiBlob, type ApiIdentity } from '../../api/client'

export function procurementRequest<T>(identity: ApiIdentity, path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  return apiRequest<T>(`/procurement${path}`, identity, { method, body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body), signal: signal ?? AbortSignal.timeout(body instanceof FormData ? 120000 : 30000) })
}
export const message = (error: unknown) => error instanceof Error ? error.message : '操作未完成，请重试'
export async function procurementBlob(identity: ApiIdentity, path: string) { return apiBlob(`/procurement${path}`, identity, { signal: AbortSignal.timeout(60000) }) }
export async function download(identity: ApiIdentity, path: string, name: string) {
  const blob = await procurementBlob(identity, path); const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = name; a.click()
  setTimeout(() => URL.revokeObjectURL(url), 30000)
}
/** Same payload retry keeps the same UUID. Editing the payload starts a new command. */
export function useProcurementCommand() {
  const pending = useRef<{ signature: string; id: string } | undefined>(undefined); const guard = useRef(false)
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  async function run<T>(signature: string, action: (id: string) => Promise<T>, success?: (result: T) => void, text = '已保存') {
    if (guard.current) return
    guard.current = true; setBusy(true); setError(''); setNotice('')
    if (pending.current?.signature !== signature) pending.current = { signature, id: crypto.randomUUID() }
    try { const result = await action(pending.current.id); pending.current = undefined; setNotice(text); success?.(result); return result }
    catch (e) { setError(message(e)) }
    finally { guard.current = false; setBusy(false) }
  }
  return { busy, error, notice, run, setError, setNotice }
}
